package com.residency.service;

import ai.timefold.solver.core.api.solver.*;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.residency.db.*;
import com.residency.model.*;
import com.residency.solver.*;

import java.util.Collections;

import java.sql.SQLException;
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
                    // Rotation without a PGY rule still eligible with global defaults
                    // Use rotation-level defaults as fallback
                    maxMap.putIfAbsent(rot.getId(), Math.max(1, rot.getMaxBlocksAllowed() / 4));
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
