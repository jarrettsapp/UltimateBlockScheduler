package com.residency.service;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.*;
import com.residency.cpsat.ScheduleConfig;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.residency.db.*;
import com.residency.model.*;
import com.residency.solver.*;

import java.util.Collections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Bridges the application layer with Timefold Solver.
 *
 * Responsibilities:
 *  1. Load all data from DB and build a RotationSchedule (the PlanningSolution)
 *  2. Configure and start the Timefold solver (runs until stop() is called)
 *  3. Stream best-score updates back to the UI via a callback
 *  4. On stop: commit the best solution found to the DB
 *  5. Provide snapshot/restore for undo support
 */
public class TimefoldSchedulerService {

    // ── PRODUCTION Phase-3 defaults (locked 2026-06-26 after the move-set + multi-start benchmarks;
    //    see TIMEFOLD_OPTIMIZATION_HANDOFF.md §6). The benchmark verdict: best-of-N multi-start with the
    //    tiered fragile-dominant objective and the LEAN R0 move set is the most reliable config — it ties
    //    or beats every custom-move variant per seed, rescues weak seeds, and collapses run-to-run
    //    variance. Budget stays long because time-to-best lands late (median ~240s of 300s). ──
    /** Default parallel starts for production multi-start optimize. Override: env {@code TF_STARTS}. */
    public static final int DEFAULT_STARTS = 10;
    /** Default per-start time budget (seconds). Override: env {@code TF_SPENT} or runner arg. */
    public static final int DEFAULT_SPENT_SECONDS = 600;

    private final ResidentDAO residentDAO;
    private final RotationDAO rotationDAO;
    private final BlockDAO blockDAO;
    private final AssignmentDAO assignmentDAO;
    private final RulesDAO rulesDAO;

    private SolverJob<RotationSchedule, Long> solverJob;
    private SolverManager<RotationSchedule, Long> solverManager;
    private volatile RotationSchedule bestSolution;

    public TimefoldSchedulerService() throws SQLException {
        residentDAO  = new ResidentDAO();
        rotationDAO  = new RotationDAO();
        blockDAO     = new BlockDAO();
        assignmentDAO = new AssignmentDAO();
        rulesDAO     = new RulesDAO();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Start solving (async — runs until stop() is called)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @param year             Academic year to schedule
     * @param onBestSolution   Called each time a better solution is found
     * @param onSolveFinished  Called when the solver terminates
     */
    /**
     * @param onBestSolution  Called each time a better solution is found (from solver thread)
     * @param onSolveFinished Called when the solver terminates normally
     * @param onError         Called with (message, exception) if the solver fails
     * @param onDiagnostic    Called with informational messages (data counts, etc.)
     */
    public void startSolving(int year,
                             Consumer<RotationSchedule> onBestSolution,
                             Consumer<RotationSchedule> onSolveFinished,
                             BiConsumer<String, Throwable> onError,
                             Consumer<String> onDiagnostic)
            throws SQLException {

        bestSolution = null; // clear previous run so stale solution is never committed
        RotationSchedule problem = buildSolution(year);

        // Log data counts so the user can see data loaded correctly
        long residentCount = problem.getAssignments().stream()
            .mapToInt(ResidentBlockAssignment::getResidentId).distinct().count();
        long blockCount = problem.getAssignments().stream()
            .mapToInt(ResidentBlockAssignment::getBlockId).distinct().count();
        onDiagnostic.accept(String.format("Loaded: %d planning slots  (%d residents × %d blocks)",
            problem.getAssignments().size(), residentCount, blockCount));

        if (problem.getAssignments().isEmpty()) {
            onError.accept("No planning data found — ensure residents and blocks exist for year " + year, null);
            return;
        }

        SolverConfig config = new SolverConfig()
            .withSolutionClass(RotationSchedule.class)
            .withEntityClasses(ResidentBlockAssignment.class)
            .withConstraintProviderClass(com.residency.solver.RotationConstraintProvider.class)
            .withTerminationConfig(new TerminationConfig()
                // Default to 2 minutes; user can manually stop earlier via UI
                .withSpentLimit(java.time.Duration.ofMinutes(2)));

        solverManager = SolverManager.create(config);

        solverJob = solverManager.solveAndListen(
            1L,
            id -> problem,
            solution -> {
                bestSolution = solution;
                onBestSolution.accept(solution);
            },
            (id, ex) -> {
                if (ex != null) onError.accept("Solver encountered an error", ex);
            }
        );

        // Watch for completion on a background thread
        new Thread(() -> {
            try {
                RotationSchedule finalSol = solverJob.getFinalBestSolution();
                RotationSchedule result = (finalSol != null) ? finalSol : bestSolution;
                if (result != null) onSolveFinished.accept(result);
                else onError.accept("Solver finished but produced no solution", null);
            } catch (Exception e) {
                // Normal termination via terminateEarly also lands here
                if (bestSolution != null) onSolveFinished.accept(bestSolution);
                else onError.accept("Solver terminated without a solution: " + e.getMessage(), e);
            }
        }, "solver-finish-watcher").start();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Synchronous (headless) solve — blocks until termination, returns best
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Solves the given problem synchronously with a hard spent-time limit and returns the best
     * solution found. Unlike {@link #startSolving} (async, UI-driven), this blocks — the path used by
     * the headless warm-start runner. A pre-initialized (warm-start) problem begins Local Search
     * immediately from its assigned cells; pass {@code spentLimitSeconds <= 0} for a no-op-ish
     * trivial budget when you only want to verify ingestion.
     */
    public RotationSchedule solveSync(RotationSchedule problem, int spentLimitSeconds) {
        SolverConfig config = new SolverConfig()
            .withSolutionClass(RotationSchedule.class)
            .withEntityClasses(ResidentBlockAssignment.class)
            .withConstraintProviderClass(RotationConstraintProvider.class)
            .withTerminationConfig(new TerminationConfig()
                .withSpentLimit(java.time.Duration.ofSeconds(Math.max(1, spentLimitSeconds))));
        SolverFactory<RotationSchedule> factory = SolverFactory.create(config);
        Solver<RotationSchedule> solver = factory.buildSolver();
        return solver.solve(problem);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Stop solving and commit best solution
    // ══════════════════════════════════════════════════════════════════════

    public void stopSolving() {
        if (solverManager != null) {
            solverManager.terminateEarly(1L);
        }
    }

    public boolean isSolving() {
        return solverManager != null && solverManager.getSolverStatus(1L) == SolverStatus.SOLVING_ACTIVE;
    }

    /**
     * Commit the best solution found so far to the database.
     * Ensures DB connection is valid before attempting commit.
     */
    public void commitBestSolution(int year) throws SQLException {
        if (bestSolution == null) return;
        
        // Ensure database connection is valid
        DatabaseManager.getInstance();
        
        commitToDB(bestSolution, year);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Snapshot / Restore (undo)
    // ══════════════════════════════════════════════════════════════════════

    public ScheduleSnapshot takeSnapshot(int year, String label) throws SQLException {
        List<Assignment> current = assignmentDAO.getByYear(year);
        List<ScheduleSnapshot.AssignmentRecord> records = current.stream()
            .map(a -> new ScheduleSnapshot.AssignmentRecord(
                a.getResidentId(), a.getRotationId(), a.getBlockId(), a.isOverrideWarning()))
            .collect(Collectors.toList());
        return new ScheduleSnapshot(year, records, label);
    }

    public void restoreSnapshot(ScheduleSnapshot snap) throws SQLException {
        deleteYearAssignments(snap.getYear());
        for (ScheduleSnapshot.AssignmentRecord rec : snap.getRecords()) {
            assignmentDAO.insert(new Assignment(0, rec.residentId, rec.rotationId,
                                                rec.blockId, rec.overrideWarning));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Build the PlanningSolution from DB
    // ══════════════════════════════════════════════════════════════════════

    public RotationSchedule buildSolution(int year) throws SQLException {
        List<Resident>            residents    = residentDAO.getAll();
        List<Rotation>            rotations    = rotationDAO.getAll();
        List<Block>               blocks       = blockDAO.getByYear(year);
        List<RotationRequirement> requirements = rulesDAO.getAllRequirements();
        List<Prerequisite>        prereqs      = rulesDAO.getAllPrerequisites();
        List<RotationSequenceRule> sequenceRules = rulesDAO.getAllSequenceRules();

        SolverProblemFacts facts = new SolverProblemFacts(rotations, requirements, prereqs, blocks);

        // Build per-resident embedded rule maps once
        // These are shared references (same content per PGY level)
        Map<Integer, Map<Integer, Integer>> maxBlocksByPgy    = new HashMap<>(); // pgy -> rotId -> max
        Map<Integer, Map<Integer, Integer>> minBlocksByPgy    = new HashMap<>();
        Map<Integer, Set<Integer>>          requiredByPgy     = new HashMap<>();
        Map<Integer, Set<Integer>>          eligibleByPgy     = new HashMap<>();

        for (Resident r : residents) {
            int pgy = r.getPgyLevel();
            if (!maxBlocksByPgy.containsKey(pgy)) {
                Map<Integer, Integer> maxMap = new HashMap<>();
                Map<Integer, Integer> minMap = new HashMap<>();
                Set<Integer> reqSet = new HashSet<>();
                Set<Integer> eligSet = new HashSet<>();

                for (Rotation rot : rotations) {
                    RotationRequirement req = facts.getRequirement(rot.getId(), pgy);
                    if (req != null) {
                        maxMap.put(rot.getId(), (int)Math.ceil(req.getMaxBlocks()));
                        minMap.put(rot.getId(), (int)Math.ceil(req.getMinBlocks()));
                        if (req.isRequired()) reqSet.add(rot.getId());
                        eligSet.add(rot.getId()); // has a rule = eligible
                    }
                    // Rotation without a PGY rule still eligible with global defaults.
                    // Each planning slot here is one 2-week block, so the cap must be in
                    // slots. maxBlocksAllowed is entered in WEEKS -> convert via ScheduleUnits.
                    // (Previously /4, which capped at half the intended slots. See PROJECT.md Code review, H2.)
                    maxMap.putIfAbsent(rot.getId(), Math.max(1, ScheduleUnits.weeksToSlots(rot.getMaxBlocksAllowed())));
                    eligSet.add(rot.getId()); // all rotations eligible unless restricted
                }
                maxBlocksByPgy.put(pgy, maxMap);
                minBlocksByPgy.put(pgy, minMap);
                requiredByPgy.put(pgy, reqSet);
                eligibleByPgy.put(pgy, eligSet);
            }
        }

        // Capacity map: rotationId -> maxResidentsPerBlock (same for all residents)
        Map<Integer, Integer> capacityMap = new HashMap<>();
        for (Rotation rot : rotations) {
            capacityMap.put(rot.getId(), rot.getMaxResidentsPerBlock());
        }

        // Prerequisite map: rotationId -> Set<prerequisiteRotationIds>
        // Simplified: ignore PGY filter for now (apply all prereqs)
        Map<Integer, Set<Integer>> prereqMap = new HashMap<>();
        for (Prerequisite p : prereqs) {
            prereqMap.computeIfAbsent(p.getRotationId(), k -> new HashSet<>())
                     .add(p.getPrerequisiteRotationId());
        }

        Map<Integer, Map<Integer, Set<Integer>>> mustBeAfterByPgy = new HashMap<>();
        Map<Integer, Map<Integer, Set<Integer>>> noImmediateFollowByPgy = new HashMap<>();
        for (Resident r : residents) {
            int pgy = r.getPgyLevel();
            mustBeAfterByPgy.computeIfAbsent(pgy, k -> buildSequenceMapForPgy(sequenceRules, pgy, RotationSequenceRuleType.MUST_BE_AFTER));
            noImmediateFollowByPgy.computeIfAbsent(pgy, k -> buildSequenceMapForPgy(sequenceRules, pgy, RotationSequenceRuleType.CANNOT_IMMEDIATELY_FOLLOW));
        }

        // Build planning entities
        List<ResidentBlockAssignment> entities = new ArrayList<>();
        for (Resident r : residents) {
            int pgy = r.getPgyLevel();
            for (Block b : blocks) {
                ResidentBlockAssignment slot = new ResidentBlockAssignment(
                    r.getId(), r.getName(), pgy, b.getId(), b.getBlockNumber());

                slot.setMaxBlocksPerRotation(maxBlocksByPgy.get(pgy));
                slot.setMinBlocksRequired(minBlocksByPgy.get(pgy));
                slot.setRequiredRotationIds(requiredByPgy.get(pgy));
                slot.setEligibleRotationIds(eligibleByPgy.get(pgy));
                slot.setMaxResidentsPerBlock(capacityMap);
                slot.setPrerequisiteMap(prereqMap);
                slot.setMustBeAfterMap(mustBeAfterByPgy.get(pgy));
                slot.setDisallowedImmediatePredecessorMap(noImmediateFollowByPgy.get(pgy));

                entities.add(slot);
            }
        }

        // Build ResidentRequirement facts — one per (resident × required rotation).
        // These let the constraint provider detect zero-assignment violations.
        List<ResidentRequirement> residentReqs = new ArrayList<>();
        for (Resident r : residents) {
            int pgy = r.getPgyLevel();
            Set<Integer> reqRotIds = requiredByPgy.getOrDefault(pgy, Collections.emptySet());
            Map<Integer, Integer> minMap = minBlocksByPgy.getOrDefault(pgy, Collections.emptyMap());
            for (int rotId : reqRotIds) {
                int min = minMap.getOrDefault(rotId, 1);
                residentReqs.add(new ResidentRequirement(r.getId(), rotId, min));
            }
        }

        // Value range: all rotation IDs (null handled by nullable=true on @PlanningVariable)
        List<Integer> rotationIds = rotations.stream()
            .map(Rotation::getId)
            .collect(Collectors.toList());

        return new RotationSchedule(entities, facts, residentReqs, rotationIds, year);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  WARM START — build a PlanningSolution initialized from a saved version
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds a {@link RotationSchedule} for {@code year} whose planning variables are PRE-SET from
     * the assignments of a saved schedule version (typically a committed Phase-2 schedule). This is
     * the warm-start path: every {@code ResidentBlockAssignment.rotationId} that has a matching
     * version assignment starts non-null, so Timefold's Construction Heuristic is a no-op and Local
     * Search begins from the given feasible schedule — the role CP-SAT Phase 3 could not fill.
     *
     * <p>Implementation reuses {@link #buildSolution(int)} to construct the exact same entity set +
     * problem facts the cold solve would, then overlays the version's rotationIds. A cell with no
     * matching version row is left null (should not happen for a complete categorical+aux version,
     * but is reported by the caller's round-trip check rather than silently mutated here).
     *
     * @param year      academic year
     * @param versionId id in {@code schedule_versions} to load as the starting solution
     * @return a fully-built warm-start solution (unsolved)
     */
    public RotationSchedule buildSolutionFromVersion(int year, int versionId) throws SQLException {
        RotationSchedule problem = buildSolution(year);

        ScheduleVersionDAO versionDAO = new ScheduleVersionDAO();
        ScheduleVersion v = versionDAO.getVersion(versionId);
        if (v == null) throw new SQLException("Version " + versionId + " not found");
        if (v.getScheduleYear() != year) {
            throw new SQLException("Version " + versionId + " is for year " + v.getScheduleYear()
                + ", not requested year " + year);
        }

        // Map block_number -> blockId for this year so we can key version rows (by block_number) to
        // the entities (keyed internally by blockId, with blockNumber also carried).
        // The entity id is residentId + "_" + blockId, but we match on (residentId, blockNumber)
        // because version assignments are stored by block_number (year-agnostic).
        Map<String, ResidentBlockAssignment> byResAndBlockNum = new HashMap<>();
        for (ResidentBlockAssignment a : problem.getAssignments()) {
            byResAndBlockNum.put(a.getResidentId() + "_" + a.getBlockNumber(), a);
        }

        int set = 0, missingEntity = 0;
        for (ScheduleVersionDAO.VersionAssignment va : versionDAO.getVersionAssignments(versionId)) {
            ResidentBlockAssignment slot =
                byResAndBlockNum.get(va.residentId() + "_" + va.blockNumber());
            if (slot == null) { missingEntity++; continue; }
            slot.setRotationId(va.rotationId());
            set++;
        }

        long stillNull = problem.getAssignments().stream()
            .filter(a -> !a.isAssigned()).count();
        System.out.printf(
            "warm-start: version %d → set %d cells, %d version rows had no matching entity, %d entity cells left unassigned%n",
            versionId, set, missingEntity, stillNull);

        return problem;
    }

    /**
     * Persists a solved (or warm-started) {@link RotationSchedule} as a NEW schedule version via the
     * version tables — the comparable round-trip used by the harvest/solve_runs pipeline (NOT the
     * live {@code assignments} table). Returns the new version id.
     *
     * <p>Only categorical+aux assignments that are non-null are written, mirroring the
     * {@code schedule_version_assignments} convention. Tier scores are recorded from the Timefold
     * hard/medium score (0/0 for a clean feasible result).
     */
    public int commitToVersion(RotationSchedule solution, int year, String name, String notes)
            throws SQLException {
        Connection c = DatabaseManager.getInstance().getValidConnection();
        boolean prevAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            HardMediumSoftScore score = solution.getScore();
            Integer tier1 = score != null ? (int) score.hardScore() : null;
            Integer tier2 = score != null ? (int) score.mediumScore() : null;
            Integer tier3 = score != null ? (int) -score.softScore() : null; // soft cost (≥0)
            boolean feasible = solution.isFeasible();
            String summary = (score != null ? "[timefold] " + solution.scoreSummary() : "[timefold] unscored");

            int versionId;
            String insertVer = """
                INSERT INTO schedule_versions
                  (schedule_year, name, created_at, notes, tier1_score, tier2_score,
                   tier3_score, feasible, summary)
                VALUES (?,?,datetime('now'),?,?,?,?,?,?)
                """;
            try (PreparedStatement ps = c.prepareStatement(insertVer, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, year);
                ps.setString(2, name);
                ps.setString(3, notes);
                if (tier1 == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, tier1);
                if (tier2 == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, tier2);
                if (tier3 == null) ps.setNull(6, java.sql.Types.INTEGER); else ps.setInt(6, tier3);
                ps.setInt(7, feasible ? 1 : 0);
                ps.setString(8, summary);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    versionId = keys.getInt(1);
                }
            }

            String insAssign = "INSERT INTO schedule_version_assignments "
                + "(version_id, resident_id, rotation_id, block_number) VALUES (?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(insAssign)) {
                int rows = 0;
                for (ResidentBlockAssignment slot : solution.getAssignments()) {
                    if (!slot.isAssigned()) continue;
                    ps.setInt(1, versionId);
                    ps.setInt(2, slot.getResidentId());
                    ps.setInt(3, slot.getRotationId());
                    ps.setInt(4, slot.getBlockNumber());
                    ps.addBatch();
                    rows++;
                }
                ps.executeBatch();
                System.out.printf("commitToVersion: wrote version %d with %d categorical rows%n",
                    versionId, rows);
            }

            // Make the version SELF-CONTAINED: also write the deterministic aux coverage
            // (TY→VA, BMC/TY→Y8Pulm, BMC/TY→Y7 Days) into the version so it can be reprinted
            // in full later without re-running the filler. Same rules as AuxFillerService /
            // AuxCoverageCredits — see writeAuxIntoVersion.
            int auxRows = writeAuxIntoVersion(c, versionId, solution);
            System.out.printf("commitToVersion: wrote %d aux rows into version %d%n",
                auxRows, versionId);

            c.commit();
            return versionId;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(prevAuto);
        }
    }

    /**
     * Writes the deterministic auxiliary coverage for a freshly-committed version into
     * {@code schedule_version_assignments} so the version is SELF-CONTAINED (reprintable in
     * full without re-running the filler against the live table). Delegates to
     * {@link com.residency.cpsat.AuxFillerService#fillInto} with a version-backed sink, so the
     * EXACT same Y7/Y8Pulm/VA rules run for versions and for the live solve (no drift).
     */
    private int writeAuxIntoVersion(Connection c, int versionId, RotationSchedule solution)
            throws SQLException {
        // Seed the sink with the version's categorical assignments so the filler sees the
        // current per-block coverage.
        com.residency.cpsat.AuxFillTarget.VersionTarget sink =
            new com.residency.cpsat.AuxFillTarget.VersionTarget(c, versionId);
        for (ResidentBlockAssignment a : solution.getAssignments()) {
            if (a.isAssigned()) sink.seedExisting(a.getResidentId(), a.getRotationId(), a.getBlockNumber());
        }
        new com.residency.cpsat.AuxFillerService().fillInto(sink);
        return sink.flush();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FEASIBILITY MODEL (Item 2) — categorical-only entities + aux facts + ported hard model
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the ported-hard-model problem from a saved version: CATEGORICAL residents become planning
     * entities (rotationId pre-set = warm start); AUX residents become fixed facts whose coverage is
     * pre-counted into {@link TimefoldFacts}. The {@link ScheduleConfig} and rule maps are built
     * identically to {@code CpSatSchedulerEngine} so the Timefold hard model matches CP-SAT's.
     *
     * <p>Aux coverage is derived FROM THE VERSION (not the live assignments table), so coverage credit
     * matches the exact schedule being optimized.
     */
    public RotationSchedule buildFeasibilityProblemFromVersion(int year, int versionId) throws SQLException {
        // ── config + rule maps (mirror CpSatSchedulerEngine) ──
        com.residency.db.ScheduleConfigDAO configDAO = new com.residency.db.ScheduleConfigDAO();
        com.residency.db.RotationLinkRuleDAO linkDAO = new com.residency.db.RotationLinkRuleDAO();
        com.residency.db.AuxFillerRotationDAO fillerDAO = new com.residency.db.AuxFillerRotationDAO();

        ScheduleConfig config = configDAO.loadConfig();
        applyTieredObjectiveOverride(config);
        List<Resident> categoricals = residentDAO.getMainResidents();
        List<Resident> auxResidents = residentDAO.getAuxiliaryResidents();
        List<Rotation>  rotations   = rotationDAO.getAll();
        List<Block>     blocks      = blockDAO.getByYear(year);
        List<RotationRequirement> reqs   = rulesDAO.getAllRequirements();
        List<Prerequisite>        prereqs = rulesDAO.getAllPrerequisites();
        List<RotationSequenceRule> seqRules = rulesDAO.getAllSequenceRules();

        // tier defaults (heavy / sunday-source ids), same as engine
        if (config.getHeavyRotationIds().isEmpty())
            config.setHeavyRotationIds(com.residency.model.WorkloadTiers.heavyIds(rotations));
        if (config.getSundaySourceRotationIds().isEmpty())
            config.setSundaySourceRotationIds(com.residency.model.WorkloadTiers.sundaySourceIds(rotations));

        int totalBlocks = com.residency.model.ScheduleUnits.SLOTS_PER_YEAR;
        config.setTotalBlocks(totalBlocks);
        for (Rotation r : rotations) {
            ScheduleConfig.RotationPolicy policy = configDAO.loadRotationPolicy(r.getId());
            config.getRotationPolicies().put(r.getId(), policy);
        }
        config.setRotationLinkRules(linkDAO.getAll());

        // req / eligibility / prereq / sequence maps (replicate engine's private helpers)
        Map<Integer, Map<Integer, RotationRequirement>> reqMap = new HashMap<>();
        for (RotationRequirement r : reqs)
            reqMap.computeIfAbsent(r.getRotationId(), k -> new HashMap<>()).put(r.getPgyLevel(), r);
        Map<Integer, Set<Integer>> eligibleByRotation = new HashMap<>();
        for (Rotation s : rotations) {
            Set<Integer> pool = new HashSet<>();
            Map<Integer, RotationRequirement> byPgy = reqMap.getOrDefault(s.getId(), Map.of());
            for (Resident r : categoricals)
                if (byPgy.isEmpty() || byPgy.containsKey(r.getPgyLevel())) pool.add(r.getId());
            eligibleByRotation.put(s.getId(), pool);
        }
        Map<Integer, List<Prerequisite>> prereqMap = new HashMap<>();
        for (Prerequisite p : prereqs)
            prereqMap.computeIfAbsent(p.getRotationId(), k -> new ArrayList<>()).add(p);
        Map<Integer, List<RotationSequenceRule>> seqMap = new HashMap<>();
        for (RotationSequenceRule r : seqRules)
            seqMap.computeIfAbsent(r.getRotationId(), k -> new ArrayList<>()).add(r);

        // ── load the version's assignments, split categorical vs aux ──
        ScheduleVersionDAO versionDAO = new ScheduleVersionDAO();
        ScheduleVersion v = versionDAO.getVersion(versionId);
        if (v == null) throw new SQLException("Version " + versionId + " not found");
        Set<Integer> catIds = new HashSet<>();
        Map<Integer, Integer> pgyByResident = new HashMap<>();
        Set<Integer> bmcIds = new HashSet<>();
        for (Resident r : categoricals) {
            catIds.add(r.getId());
            pgyByResident.put(r.getId(), r.getPgyLevel());
            if ("BMC".equals(r.getResidentGroup())) bmcIds.add(r.getId());
        }
        Map<Integer, String> auxGroup = new HashMap<>();
        for (Resident r : auxResidents) { pgyByResident.put(r.getId(), r.getPgyLevel()); auxGroup.put(r.getId(), r.getResidentGroup()); }

        // filler exclusions: aux (resident_rotation) pairs that are regenerated post-solve (excluded from coverage)
        Map<String, Set<Integer>> fillerByGroup = fillerDAO.getAllFillerRotations();
        Set<String> fillerExclusions = new HashSet<>();
        for (Resident r : auxResidents) {
            Set<Integer> fr = fillerByGroup.getOrDefault(r.getResidentGroup(), Set.of());
            for (int rotId : fr) fillerExclusions.add(r.getId() + "_" + rotId);
        }

        List<AuxAssignment> auxAssignments = new ArrayList<>();
        Map<Integer, Map<Integer, Integer>> auxCoverage = new HashMap<>(); // rotId -> slot -> count
        // categorical version rows keyed by (residentId, blockNumber)
        Map<String, Integer> catRotByResBlk = new HashMap<>();
        for (ScheduleVersionDAO.VersionAssignment va : versionDAO.getVersionAssignments(versionId)) {
            if (catIds.contains(va.residentId())) {
                catRotByResBlk.put(va.residentId() + "_" + va.blockNumber(), va.rotationId());
            } else {
                auxAssignments.add(new AuxAssignment(va.residentId(), va.rotationId(), va.blockNumber()));
                if (fillerExclusions.contains(va.residentId() + "_" + va.rotationId())) continue;
                int slot = TimefoldFacts.slotOf(va.blockNumber());
                auxCoverage.computeIfAbsent(va.rotationId(), k -> new HashMap<>())
                           .merge(slot, 1, Integer::sum);
            }
        }

        // Synthetic aux credit — IDENTICAL to CP-SAT (shared AuxCoverageCredits). The filler-
        // excluded VA/Y8Pulm aux rows above are NOT in auxCoverage (so categoricals can move
        // into them), which would leave the hard coverage floor unsatisfiable; crediting here
        // restores it the same way the CP-SAT model does. Registers the Y8Pulm global floor too.
        com.residency.cpsat.AuxCoverageCredits.applyPerBlockCredits(
            auxCoverage, rotations, auxResidents, config, totalBlocks);
        int y8Floor = com.residency.cpsat.AuxCoverageCredits.younker8CategoricalFloor(
            rotations, auxResidents, config, totalBlocks);
        Integer y8Id = com.residency.cpsat.AuxCoverageCredits.younker8RotationId(rotations);
        if (y8Id != null && y8Floor >= 0) config.getCategoricalGlobalFloors().put(y8Id, y8Floor);

        TimefoldFacts tf = new TimefoldFacts(config, totalBlocks, rotations, pgyByResident,
            catIds, bmcIds, auxCoverage, eligibleByRotation, reqMap, prereqMap, seqMap);

        // ── build categorical entities (one per resident × block), rotationId pre-set ──
        List<ResidentBlockAssignment> entities = new ArrayList<>();
        for (Resident r : categoricals) {
            for (Block b : blocks) {
                ResidentBlockAssignment slot = new ResidentBlockAssignment(
                    r.getId(), r.getName(), r.getPgyLevel(), b.getId(), b.getBlockNumber());
                slot.setTfFacts(tf);
                Integer rotId = catRotByResBlk.get(r.getId() + "_" + b.getBlockNumber());
                slot.setRotationId(rotId);   // warm start (null if version lacks this cell)
                entities.add(slot);
            }
        }

        List<Integer> rotationIds = rotations.stream().map(Rotation::getId).collect(Collectors.toList());
        RotationSchedule problem = new RotationSchedule(entities,
            new SolverProblemFacts(rotations, reqs, prereqs, blocks),
            new ArrayList<>(), rotationIds, year);
        problem.setTfFacts(tf);
        problem.setAuxAssignments(auxAssignments);

        long stillNull = entities.stream().filter(a -> !a.isAssigned()).count();
        System.out.printf("feasibility problem: %d categorical entities (%d unassigned), %d aux facts, "
            + "aux coverage rotations=%d%n",
            entities.size(), stillNull, auxAssignments.size(), auxCoverage.size());
        return problem;
    }

    /**
     * Solve (or score-only) the ported-hard-model problem. {@code spentSeconds<=0} ⇒ score the warm
     * start verbatim with no moves (the Item-2 validation path: assert hard==0 on a feasible version).
     */
    public RotationSchedule solveFeasibility(RotationSchedule problem, int spentSeconds) {
        return solveFeasibilityTimed(problem, spentSeconds).solution;
    }

    /** Result of a timed solve: the solution plus when (ms into the solve) the final best score landed. */
    public static class TimedSolve {
        public RotationSchedule solution;
        public long timeToBestMs;   // ms-spent at the LAST best-solution-changed event
        public long totalMs;        // wall-clock of the whole solve
    }

    /**
     * Like {@link #solveFeasibility} but records TIME-TO-BEST via a {@code BestSolutionChangedEvent}
     * listener — so the move-set benchmark can tell whether the budget needs to be 60s or &gt;5min
     * (i.e. is the solver still improving late in the run, or did it plateau early?).
     */
    public TimedSolve solveFeasibilityTimed(RotationSchedule problem, int spentSeconds) {
        SolverConfig config = buildSolverConfig(Math.max(1, spentSeconds));
        SolverFactory<RotationSchedule> factory = SolverFactory.create(config);
        ai.timefold.solver.core.api.solver.Solver<RotationSchedule> solver = factory.buildSolver();
        final long[] lastBestMs = {0L};
        solver.addEventListener(event -> lastBestMs[0] = event.getTimeMillisSpent());
        long t0 = System.currentTimeMillis();
        RotationSchedule solved = solver.solve(problem);
        TimedSolve r = new TimedSolve();
        r.solution = solved;
        r.timeToBestMs = lastBestMs[0];
        r.totalMs = System.currentTimeMillis() - t0;
        return r;
    }

    /** Result of a multi-start solve: best solution of N parallel starts + per-start diagnostics. */
    public static class MultiStartResult {
        public RotationSchedule best;
        public long bestTimeToBestMs;     // time-to-best of the WINNING start
        public long totalWallMs;          // wall-clock of the whole parallel batch
        public int starts;
        public int winningStartIndex;
    }

    /**
     * MULTI-START (Community-legal parallelism; Enterprise {@code moveThreadCount} is unavailable). Runs
     * {@code starts} INDEPENDENT solvers in parallel — each on its own freshly-rebuilt warm start from
     * the same version, each with a DISTINCT random seed so they explore differently — and keeps the
     * best by score. Uses the box's cores via N solver threads (not intra-solver move threads). Mirrors
     * Scheduler 5.0's {@code SolverService.solveMultiStart}.
     */
    public MultiStartResult solveMultiStart(int year, int versionId, int spentSeconds, int starts)
            throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(starts);
        List<java.util.concurrent.Future<TimedSolve>> futures = new ArrayList<>();
        long wall0 = System.currentTimeMillis();
        for (int i = 0; i < starts; i++) {
            final long seed = 1000L + i; // distinct, reproducible per start
            final RotationSchedule problem = buildFeasibilityProblemFromVersion(year, versionId);
            futures.add(pool.submit(() -> {
                SolverConfig config = buildSolverConfig(Math.max(1, spentSeconds), seed);
                SolverFactory<RotationSchedule> factory = SolverFactory.create(config);
                ai.timefold.solver.core.api.solver.Solver<RotationSchedule> solver = factory.buildSolver();
                final long[] lastBestMs = {0L};
                solver.addEventListener(event -> lastBestMs[0] = event.getTimeMillisSpent());
                long t0 = System.currentTimeMillis();
                RotationSchedule solved = solver.solve(problem);
                TimedSolve ts = new TimedSolve();
                ts.solution = solved;
                ts.timeToBestMs = lastBestMs[0];
                ts.totalMs = System.currentTimeMillis() - t0;
                return ts;
            }));
        }
        pool.shutdown();

        MultiStartResult r = new MultiStartResult();
        r.starts = starts;
        for (int i = 0; i < futures.size(); i++) {
            TimedSolve ts = futures.get(i).get();
            // Higher (less negative) score wins; ties broken by earlier time-to-best.
            if (r.best == null
                    || ts.solution.getScore().compareTo(r.best.getScore()) > 0) {
                r.best = ts.solution;
                r.bestTimeToBestMs = ts.timeToBestMs;
                r.winningStartIndex = i;
            }
        }
        r.totalWallMs = System.currentTimeMillis() - wall0;
        return r;
    }

    /**
     * TOPIC-B move-set experiment (see TIMEFOLD_OPTIMIZATION_HANDOFF.md §6). The variant is chosen by
     * the {@code TF_VARIANT} env var; default {@code R0} reproduces the original bare config exactly
     * (only a spent-time limit, Timefold default change+swap), so the production path is UNCHANGED.
     *
     * <ul>
     *   <li><b>R0</b> — bare config (original behavior; default).</li>
     *   <li><b>R1</b> — phased: FIRST_FIT_DECREASING CH → Late Acceptance (size 400) → Step-Counting HC.
     *       Structure only, still default change+swap moves. Tests whether phasing alone helps.</li>
     *   <li><b>R2</b> — R1 plus a {@code ruinRecreateMoveSelector} (weighted up, descending ruin %) in
     *       the LA phase — native LNS escape, no custom Java. The cheapest high-leverage lever.</li>
     * </ul>
     *
     * Custom move factories (R3+ soft-plateau / ejection chains) are not built here yet.
     */
    /**
     * Graded Sunday objective (see TIMEFOLD_OPTIMIZATION_HANDOFF.md §6). PRODUCTION DEFAULT = ON: the
     * tiered FRAGILE≫volunteer penalty + healthy-depth reward (the benchmark-locked objective). Set
     * {@code TF_TIER=0} to fall back to the legacy flat shortfall term. Weights overridable via
     * {@code TF_WV}/{@code TF_WF}/{@code TF_RBASE}/{@code TF_RDEPTH}.
     */
    private static void applyTieredObjectiveOverride(ScheduleConfig config) {
        if ("0".equals(System.getenv("TF_TIER"))) {
            System.out.println("[TF_TIER] tiered objective OFF (legacy flat shortfall)");
            return;
        }
        int wv = envInt("TF_WV", 100);
        config.setWeightVolunteerWeekend(wv);
        config.setWeightFragileWeekend(envInt("TF_WF", 1000));
        config.setWeightHealthyWeekend(envInt("TF_RBASE", 10));
        config.setWeightHealthyDepthReward(envInt("TF_RDEPTH", 3));
        System.out.printf("[TF_TIER] tiered objective ON: Wv=%d Wf=%d Rbase=%d Rdepth=%d%n",
            wv, config.getWeightFragileWeekend(), config.getWeightHealthyWeekend(),
            config.getWeightHealthyDepthReward());
    }

    private static int envInt(String key, int dflt) {
        String v = System.getenv(key);
        try { return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    /** A weighted custom MoveListFactory selector (STEP-cached), for the R3/R4 Sunday move sets. */
    private static ai.timefold.solver.core.config.heuristic.selector.move.factory.MoveListFactoryConfig moveFactory(
            Class<? extends ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveListFactory> factoryClass,
            double weight) {
        var cfg = new ai.timefold.solver.core.config.heuristic.selector.move.factory.MoveListFactoryConfig();
        cfg.setMoveListFactoryClass(factoryClass);
        cfg.setCacheType(ai.timefold.solver.core.config.heuristic.selector.common.SelectionCacheType.STEP);
        cfg.setFixedProbabilityWeight(weight);
        return cfg;
    }

    SolverConfig buildSolverConfig(int spentSeconds) {
        return buildSolverConfig(spentSeconds, null);
    }

    SolverConfig buildSolverConfig(int spentSeconds, Long explicitRandomSeed) {
        String variant = System.getenv().getOrDefault("TF_VARIANT", "R0").trim().toUpperCase();
        TerminationConfig term = new TerminationConfig()
            .withSpentLimit(java.time.Duration.ofSeconds(spentSeconds));

        SolverConfig base = new SolverConfig()
            .withSolutionClass(RotationSchedule.class)
            .withEntityClasses(ResidentBlockAssignment.class)
            .withConstraintProviderClass(RotationFeasibilityConstraintProvider.class);

        // TOPIC-B threading axis: TF_THREADS sets moveThreadCount (e.g. "8" or "AUTO"). Default unset =
        // single-threaded (NONE), so the production path is unchanged. Applies to ALL variants.
        String threads = System.getenv("TF_THREADS");
        if (threads != null && !threads.isBlank()) {
            base.setMoveThreadCount(threads.trim());
            System.out.println("[TF_THREADS] moveThreadCount=" + threads.trim());
        }

        // Multi-START support: distinct random seed per parallel start so the N independent solvers
        // explore differently (otherwise they'd be identical). Set programmatically by the multi-start
        // runner via the overload below; TF_SEED env is the fallback for single-process use.
        if (explicitRandomSeed != null) {
            base.setRandomSeed(explicitRandomSeed);
        } else {
            String seedEnv = System.getenv("TF_SEED");
            if (seedEnv != null && !seedEnv.isBlank()) {
                base.setRandomSeed(Long.parseLong(seedEnv.trim()));
            }
        }

        if ("R0".equals(variant)) {
            System.out.println("[TF_VARIANT] R0 (bare config)");
            return base.withTerminationConfig(term);
        }

        // R2+ all include ruin; R3 adds the Sunday same-block swap move; R4 adds the ejection chain.
        boolean withRuin  = "R2".equals(variant) || "R3".equals(variant) || "R4".equals(variant);
        boolean withSwap  = "R3".equals(variant) || "R4".equals(variant);
        boolean withChain = "R4".equals(variant);
        System.out.println("[TF_VARIANT] " + variant + " (phased CH→LA"
            + (withRuin ? "+ruin" : "") + (withSwap ? "+sundaySwap" : "")
            + (withChain ? "+ejectChain" : "") + "→SCHC)");

        // Phase 0: Construction Heuristic — refills any nulls the LS opens; no-op on a complete warm
        // start. FIRST_FIT (not FIRST_FIT_DECREASING) — the entity has no difficulty comparator, and
        // ruin-recreate supplies its own recreation, so plain FIRST_FIT is sufficient here.
        var ch = new ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig()
            .withConstructionHeuristicType(
                ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType.FIRST_FIT);

        // Phase 1: Late Acceptance — broad plateau traversal of the soft objective.
        var laMoves = new ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig();
        var laChildren = new java.util.ArrayList<
            ai.timefold.solver.core.config.heuristic.selector.move.MoveSelectorConfig>();
        laChildren.add(new ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig()
            .withFixedProbabilityWeight(100.0));
        laChildren.add(new ai.timefold.solver.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig()
            .withFixedProbabilityWeight(100.0));
        if (withRuin) {
            laChildren.add(new ai.timefold.solver.core.config.heuristic.selector.move.generic.RuinRecreateMoveSelectorConfig()
                .withMinimumRuinedPercentage(0.10)
                .withMaximumRuinedPercentage(0.30)
                .withFixedProbabilityWeight(150.0));
        }
        if (withSwap) {
            laChildren.add(moveFactory(com.residency.solver.move.SundaySwapMoveFactory.class, 200.0));
        }
        if (withChain) {
            laChildren.add(moveFactory(com.residency.solver.move.SundayEjectionChainMoveFactory.class, 200.0));
        }
        laMoves.setMoveSelectorList(laChildren);
        // LA gets a phase-level termination so the SCHC phase below is reachable: it hands off after
        // 60s without improvement, OR after ~70% of the total budget, whichever comes first.
        int laSpent = Math.max(20, (int) Math.round(spentSeconds * 0.70));
        var la = new ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig()
            .withMoveSelectorConfig(laMoves)
            .withTerminationConfig(new TerminationConfig()
                .withUnimprovedSpentLimit(java.time.Duration.ofSeconds(60))
                .withSpentLimit(java.time.Duration.ofSeconds(laSpent)))
            .withAcceptorConfig(new ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig()
                .withLateAcceptanceSize(400))
            .withForagerConfig(new ai.timefold.solver.core.config.localsearch.decider.forager.LocalSearchForagerConfig()
                .withAcceptedCountLimit(1));

        // Phase 2: Step-Counting Hill Climbing — exploitation/polish to the time cap.
        var schcMoves = new ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig()
            .withMoveSelectors(
                new ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig(),
                new ai.timefold.solver.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig());
        var schc = new ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig()
            .withMoveSelectorConfig(schcMoves)
            .withAcceptorConfig(new ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig()
                .withStepCountingHillClimbingSize(400))
            .withForagerConfig(new ai.timefold.solver.core.config.localsearch.decider.forager.LocalSearchForagerConfig()
                .withAcceptedCountLimit(1));

        return base
            .withPhases(ch, la, schc)
            .withTerminationConfig(term);
    }

    /**
     * Score the warm start WITHOUT solving — uses Timefold's ScoreManager to explain the score of the
     * unsolved (pre-set) problem. The Item-2 gate: a feasible version must score hard==0/medium==0.
     */
    public ai.timefold.solver.core.api.score.analysis.ScoreAnalysis<HardMediumSoftScore>
            analyzeFeasibility(RotationSchedule problem) {
        SolverConfig config = new SolverConfig()
            .withSolutionClass(RotationSchedule.class)
            .withEntityClasses(ResidentBlockAssignment.class)
            .withConstraintProviderClass(RotationFeasibilityConstraintProvider.class);
        SolutionManager<RotationSchedule, HardMediumSoftScore> sm =
            SolutionManager.create(SolverFactory.create(config));
        return sm.analyze(problem);
    }

    /** Score metrics matching {@code score_grid()} (categorical-only), computed from a solution. */
    public static class CoverageMetrics {
        public int volunteer, fragile, healthy, heavyHeavy;
        public int[] perWeekend; // coverers per weekend (0-based weekend index)
        public int shortfallUnits;  // sum max(0, target - coverers)
    }

    /**
     * Computes the canonical Sunday-coverage metrics (volunteer/fragile/healthy + heavy→heavy) from a
     * solved/warm-started {@link RotationSchedule}, using the same coverer definition as
     * {@code score_grid()} and the Timefold soft objective. The single source of truth for what we
     * record about a Timefold result, so a Timefold version is comparable to a harvest version.
     */
    public CoverageMetrics computeMetrics(RotationSchedule solution) {
        TimefoldFacts tf = solution.getTfFacts();
        int total = tf.totalBlocks();
        int target = tf.config().getSundayCoverageTarget();
        Map<Integer, Integer[]> byRes = new HashMap<>();
        for (ResidentBlockAssignment a : solution.getAssignments()) {
            if (!a.isAssigned() || !tf.isCategorical(a.getResidentId())) continue;
            Integer[] arr = byRes.computeIfAbsent(a.getResidentId(), k -> new Integer[total]);
            int s = TimefoldFacts.slotOf(a.getBlockNumber());
            if (s >= 0 && s < total) arr[s] = a.getRotationId();
        }
        CoverageMetrics m = new CoverageMetrics();
        m.perWeekend = new int[total - 1];
        for (int w = 0; w + 1 < total; w++) {
            int coverers = 0;
            for (Integer[] arr : byRes.values()) {
                Integer here = arr[w], next = arr[w + 1];
                if (here != null && tf.isSundaySource(here) && (next == null || !tf.isHeavy(next))) coverers++;
            }
            m.perWeekend[w] = coverers;
            if (coverers == 0) m.volunteer++;
            else if (coverers == 1) m.fragile++;
            else m.healthy++;
            m.shortfallUnits += Math.max(0, target - coverers);
        }
        // heavy → different-heavy transitions (should be 0 for a feasible schedule)
        for (Integer[] arr : byRes.values()) {
            for (int s = 0; s + 1 < total; s++) {
                Integer h = arr[s], n = arr[s + 1];
                if (h != null && n != null && tf.isHeavy(h) && tf.isHeavy(n) && !h.equals(n)) m.heavyHeavy++;
            }
        }
        return m;
    }

    private Map<Integer, Set<Integer>> buildSequenceMapForPgy(
            List<RotationSequenceRule> rules, int pgy, RotationSequenceRuleType type) {
        Map<Integer, Set<Integer>> map = new HashMap<>();
        for (RotationSequenceRule rule : rules) {
            if (rule.getRuleType() != type) continue;
            if (rule.getPgyLevel() != null && rule.getPgyLevel() != pgy) continue;
            map.computeIfAbsent(rule.getRotationId(), k -> new HashSet<>())
                .add(rule.getRelatedRotationId());
        }
        return map;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Commit solution to DB
    // ══════════════════════════════════════════════════════════════════════

    private void commitToDB(RotationSchedule solution, int year) throws SQLException {
        deleteYearAssignments(year);
        for (ResidentBlockAssignment slot : solution.getAssignments()) {
            if (!slot.isAssigned()) continue;
            Assignment a = new Assignment(0, slot.getResidentId(), slot.getRotationId(),
                                          slot.getBlockId(), false);
            assignmentDAO.insert(a);
        }
    }

    private void deleteYearAssignments(int year) throws SQLException {
        try (var conn = DatabaseManager.getInstance().getConnection();
             var stmt = conn.prepareStatement(
                 "DELETE FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=?)")) {
            stmt.setInt(1, year);
            stmt.executeUpdate();
        }
    }

    public RotationSchedule getBestSolution() { return bestSolution; }
}
