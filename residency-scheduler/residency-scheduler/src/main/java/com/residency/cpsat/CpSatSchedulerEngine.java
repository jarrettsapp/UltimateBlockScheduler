package com.residency.cpsat;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import com.residency.cpsat.ScheduleConfig.RotationLinkRule;
import com.residency.db.*;
import com.residency.model.*;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Four-phase CP-SAT scheduling engine (Phases 0–3).
 *
 * Phase 0 — Seeded feasibility:
 *   Build all Tier-0 hard constraints.
 *   Apply a greedy round-robin seed as warm-start hints (Option B).
 *   Maximize total slot occupancy as a lightweight objective (Option A) so
 *   the solver has a gradient rather than doing pure SAT search.
 *   Use Phase-0-specific solver parameters tuned for fast feasibility (Option C).
 *   If INFEASIBLE: stop and report.
 *   If FEASIBLE: extract variable values as warm-start hints for Phase 1.
 *
 * Phase 1 — Clinical quality (Tier 1):
 *   Rebuild Tier-0 constraints, apply Phase-0 hints, minimize Tier-1
 *   violation count (post-call incompatibilities, inpatient transitions).
 *   After solving, lock Tier-1 violation count ≤ best_found.
 *
 * Phase 2 — Schedule quality (Tier 2 core):
 *   Rebuild Tier-0 constraints, apply Phase-1 hints, enforce Tier-1 lock,
 *   minimize Tier-2 core objective (coverage variance, PGY balance).
 *   After solving, lock Tier-2 cost ≤ best_found.
 *
 * Phase 3 — Pattern optimization (Tier 3):
 *   Rebuild Tier-0 constraints, apply Phase-2 hints, enforce Tier-1 and
 *   Tier-2 locks, minimize 4+2 inpatient/outpatient pattern violations.
 *
 * Each phase uses an independent time budget configured via the UI.
 */
public class CpSatSchedulerEngine {

    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger(CpSatSchedulerEngine.class.getName());

    private static boolean nativeLoaded    = false;
    private static String  nativeLoadError = null;

    public static void ensureNativeLibraries() {
        if (nativeLoaded) return;
        if (nativeLoadError != null) throw new UnsatisfiedLinkError(nativeLoadError);
        try {
            Loader.loadNativeLibraries();
            nativeLoaded = true;
        } catch (Throwable first) {
            try {
                extractAndLoad("ortools-win32-x86-64/jniortools.dll", "jniortools", ".dll");
                nativeLoaded = true;
            } catch (Throwable second) {
                nativeLoadError = second.getMessage() != null
                    ? second.getMessage() : second.getClass().getName();
                throw new UnsatisfiedLinkError(
                    "OR-Tools native library could not be loaded.\n" +
                    "Built-in loader: " + first.getMessage() + "\n" +
                    "Manual search:   " + nativeLoadError);
            }
        }
    }

    private static void extractAndLoad(String resourcePath, String prefix, String suffix)
            throws Exception {
        byte[] bytes = null;
        InputStream res = CpSatSchedulerEngine.class.getResourceAsStream("/" + resourcePath);
        if (res != null) {
            try (res) { bytes = res.readAllBytes(); }
        }
        if (bytes == null) {
            String cp = System.getProperty("java.class.path", "");
            outer:
            for (String entry : cp.split(File.pathSeparator)) {
                if (!entry.toLowerCase().endsWith(".jar")) continue;
                File jarFile = new File(entry);
                if (!jarFile.isFile()) continue;
                try (JarFile jar = new JarFile(jarFile)) {
                    JarEntry je = jar.getJarEntry(resourcePath);
                    if (je != null) {
                        try (InputStream jin = jar.getInputStream(je)) { bytes = jin.readAllBytes(); }
                        break outer;
                    }
                }
            }
        }
        if (bytes == null) {
            String home = System.getProperty("user.home", "");
            String[] candidates = {
                home + "/.m2/repository/com/google/ortools/ortools-win32-x86-64/9.9.3963/ortools-win32-x86-64-9.9.3963.jar",
                home + "\\.m2\\repository\\com\\google\\ortools\\ortools-win32-x86-64\\9.9.3963\\ortools-win32-x86-64-9.9.3963.jar"
            };
            outer2:
            for (String candidate : candidates) {
                File jarFile = new File(candidate);
                if (!jarFile.isFile()) continue;
                try (JarFile jar = new JarFile(jarFile)) {
                    JarEntry je = jar.getJarEntry(resourcePath);
                    if (je != null) {
                        try (InputStream jin = jar.getInputStream(je)) { bytes = jin.readAllBytes(); }
                        break outer2;
                    }
                }
            }
        }
        if (bytes == null) {
            throw new UnsatisfiedLinkError(
                "Could not find " + resourcePath + " in classpath JARs or Maven local repository.");
        }
        File tmp = File.createTempFile(prefix, suffix);
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), bytes);
        System.load(tmp.getAbsolutePath());
    }

    // ── DAOs ──────────────────────────────────────────────────────────────────

    private final ResidentDAO            residentDAO;
    private final RotationDAO            rotationDAO;
    private final BlockDAO               blockDAO;
    private final AssignmentDAO          assignmentDAO;
    private final RulesDAO               rulesDAO;
    private final ScheduleConfigDAO      configDAO;
    private final RotationLinkRuleDAO    linkRuleDAO;
    private final AuxFillerRotationDAO   auxFillerRotationDAO;
    private final Phase0SeedStatsDAO     seedStatsDAO;
    private final Phase0SeedAssignmentDAO seedAssignmentDAO;
    private final Phase0CollectionRunsDAO collectionRunsDAO;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile CpSolver activeSolver;
    private volatile ScheduleSolution bestSolution;
    // Seed ID this run warm-started from (PHASE0_FIX=cache replay), for outcome recording at the
    // end of a full 4-phase run; null when not a cache-replay run. See SEED_POOL_TRACKING_PLAN.md.
    private String runSeedId;

    public CpSatSchedulerEngine() throws SQLException {
        residentDAO          = new ResidentDAO();
        rotationDAO          = new RotationDAO();
        blockDAO             = new BlockDAO();
        assignmentDAO        = new AssignmentDAO();
        rulesDAO             = new RulesDAO();
        configDAO            = new ScheduleConfigDAO();
        linkRuleDAO          = new RotationLinkRuleDAO();
        auxFillerRotationDAO = new AuxFillerRotationDAO();
        seedStatsDAO         = new Phase0SeedStatsDAO();
        seedAssignmentDAO    = new Phase0SeedAssignmentDAO();
        collectionRunsDAO    = new Phase0CollectionRunsDAO();
    }

    /**
     * Populate the authoritative heavy / Sunday-source tier ID lists from {@link WorkloadTiers}
     * (resolved by rotation name) when the DB config leaves them empty. Without this the
     * Sunday-coverage objective ({@link ObjectiveFunctionBuilder#buildSundayCoverageObjective})
     * silently disables itself: there is no UI to set these IDs, so the normal empty-config case
     * meant the objective never ran even with a non-zero weight, while the hard
     * {@code applyZeroVolunteerFloor} already resolved heavy rotations by name. This brings the
     * soft objective (and every other config reader) in line. A non-empty config is left
     * untouched, so an explicit override still wins.
     */
    static void applyTierDefaults(ScheduleConfig config, List<Rotation> rotations) {
        if (config.getHeavyRotationIds().isEmpty())
            config.setHeavyRotationIds(WorkloadTiers.heavyIds(rotations));
        if (config.getSundaySourceRotationIds().isEmpty())
            config.setSundaySourceRotationIds(WorkloadTiers.sundaySourceIds(rotations));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Internal data carriers
    // ══════════════════════════════════════════════════════════════════════

    private record ModelContext(CpModel model, VariableFactory varFactory) {}

    /**
     * One toggleable Tier-0 hard constraint: a human-readable label plus the
     * code that applies it to a ConstraintBuilder.
     *
     * This is the single source of truth for both the order constraints are
     * applied AND the labels shown in the diagnostics. Previously the real model
     * builder, the stepwise diagnosis, and the removal diagnosis each hard-coded
     * their own order and label array, which drifted out of sync and could blame
     * the wrong constraint for an infeasibility (see PROJECT.md Code review, M1).
     *
     * Note: block expansion and cross-rotation no-overlap are applied before any
     * of these steps in every path, so they are not part of this toggleable list.
     */
    private record ConstraintStep(String label, java.util.function.Consumer<ConstraintBuilder> apply) {}

    /**
     * Canonical ordered list of toggleable hard constraints. The maps/lists the
     * individual builders need are captured here so callers can apply any subset
     * by simply iterating (or skipping) entries.
     */
    private List<ConstraintStep> orderedConstraintSteps(
            List<Resident> residents, List<Rotation> rotations,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            Map<Integer, Rotation> rotById) {
        return List.of(
            new ConstraintStep("Coverage min/max per block",      cb -> cb.applyCoverageConstraints(residents, rotations)),
            new ConstraintStep("Categorical-only per-block caps", cb -> cb.applyCategoricalCapConstraints(residents, rotations)),
            new ConstraintStep("Zero-volunteer-weekend floor",    cb -> cb.applyZeroVolunteerFloor(residents, rotations)),
            new ConstraintStep("No heavy→different-heavy",        cb -> cb.applyHeavyToHeavyBan(residents, rotations)),
            new ConstraintStep("PGY cap constraints",             cb -> cb.applyPgyCapConstraints(residents, rotations)),
            new ConstraintStep("Workload caps",                   cb -> cb.applyWorkloadCapConstraints(residents, rotations)),
            new ConstraintStep("Max blocks per resident",         cb -> cb.applyMaxBlocksPerResidentConstraints(residents, rotations, reqMap)),
            new ConstraintStep("Full-year coverage",              cb -> cb.applyFullYearCoverageConstraints(residents, rotations)),
            new ConstraintStep("Prerequisites",                   cb -> cb.applyPrerequisiteConstraints(residents, prereqMap, rotById)),
            new ConstraintStep("Sequence rules",                  cb -> cb.applySequenceRules(residents, seqMap)),
            new ConstraintStep("No-back-to-back half-blocks",     cb -> cb.applyNoBackToBackHalfBlockConstraints(residents, rotations)),
            new ConstraintStep("Mutual non-adjacency",            cb -> cb.applyMutualNonAdjacencyConstraints(residents, rotations)),
            new ConstraintStep("Require break between segments",  cb -> cb.applyRequireBreakBetweenSegmentsConstraints(residents, rotations)),
            new ConstraintStep("Max consecutive blocks",          cb -> cb.applyMaxConsecutiveBlocksConstraints(residents, rotations)),
            new ConstraintStep("Max consec heavy+medium run",     cb -> cb.applyMaxConsecHeavyMedium(residents, rotations)),
            new ConstraintStep("Earliest start block",            cb -> cb.applyEarliestStartConstraints(residents, rotations)),
            new ConstraintStep("Even block start",                cb -> cb.applyEvenBlockStartConstraints(residents, rotations)),
            new ConstraintStep("Rotation link rules",             cb -> cb.applyRotationLinkConstraints(residents, rotations)),
            new ConstraintStep("Requires consecutive",            cb -> cb.applyRequiresConsecutiveConstraints(residents, rotations))
        );
    }

    private record PhaseResult(
        CpSolverStatus status,
        CpSolver       solver,
        VariableFactory varFactory,
        Map<String, Long> hints,
        int tier1Score
    ) {
        boolean feasible() {
            return status == CpSolverStatus.FEASIBLE || status == CpSolverStatus.OPTIMAL;
        }
    }

    /**
     * Result of the Phase-0 decomposition: the final window's full-model solve. The caller
     * feeds {@code mc}/{@code solver}/{@code status} into the same downstream handling as the
     * monolithic Phase-0 path (INFEASIBLE diagnosis, extractHints, Phase-1 handoff). A null
     * DecompResult signals an unrecoverable corner → caller falls back to monolithic.
     */
    private record DecompResult(ModelContext mc, CpSolver solver, CpSolverStatus status) {}

    // ══════════════════════════════════════════════════════════════════════
    //  Coverage-floor prover
    // ══════════════════════════════════════════════════════════════════════

    /** Result of a coverage-floor proof. */
    public record CoverageFloorResult(CpSolverStatus status, int volunteerWeekends,
                                       boolean proven, String log) {}

    /**
     * Builds the SAME hard-constrained model as a real solve (identical capacity, shape,
     * link, and requirement constraints — via {@link #buildBaseModel}) but replaces ALL soft
     * objectives with a single one: MINIMIZE the number of volunteer weekends (weekends with
     * zero eligible categorical Sunday-Y7 coverer). A categorical covers weekend b (the
     * back-end of half-block b) iff it is non-heavy at slot b AND non-heavy at slot b+1
     * (the entering-heavy pre-lock); since every non-heavy rotation is a Sunday source, this
     * is exactly "light/medium at both b and b+1".
     *
     * <p>If this returns OPTIMAL with N volunteer weekends, N is the true mathematical floor:
     * no schedule satisfying the hard constraints can do better, so no amount of additional
     * solving on the full objective can beat it. Used to decide whether a long optimization
     * run is worthwhile before spending the hours.
     */
    public CoverageFloorResult proveCoverageFloor(int year, int timeLimitSec) throws SQLException {
        return proveCoverageFloor(year, timeLimitSec, false);
    }

    public CoverageFloorResult proveCoverageFloor(int year, int timeLimitSec,
                                                  boolean minTransitionsAtZeroVolunteers) throws SQLException {
        ensureNativeLibraries();
        StringBuilder log = new StringBuilder();

        ScheduleConfig            config        = configDAO.loadConfig();
        List<Resident>            residents     = residentDAO.getMainResidents();
        List<Resident>            auxResidents  = residentDAO.getAuxiliaryResidents();
        List<Rotation>            rotations     = rotationDAO.getAll();
        List<Block>               blocks        = blockDAO.getByYear(year);
        List<RotationRequirement> reqs          = rulesDAO.getAllRequirements();
        List<Prerequisite>        prereqs       = rulesDAO.getAllPrerequisites();
        List<RotationSequenceRule> sequenceRules = rulesDAO.getAllSequenceRules();
        if (residents.isEmpty() || rotations.isEmpty() || blocks.isEmpty())
            return new CoverageFloorResult(CpSolverStatus.MODEL_INVALID, -1, false, "No data for year " + year);
        applyTierDefaults(config, rotations);

        int totalBlocks = ScheduleUnits.SLOTS_PER_YEAR;
        config.setTotalBlocks(totalBlocks);
        Map<Integer, int[]> rotationLengths = new HashMap<>();
        for (Rotation r : rotations) {
            ScheduleConfig.RotationPolicy policy = configDAO.loadRotationPolicy(r.getId());
            config.getRotationPolicies().put(r.getId(), policy);
            rotationLengths.put(r.getId(), policy.allowedBlockLengths);
        }
        Map<Integer, Map<Integer, RotationRequirement>> reqMap = buildReqMap(reqs);
        Map<Integer, Set<Integer>>  eligibleByRotation = buildEligibilityMap(residents, rotations, reqMap);
        Map<Integer, List<Prerequisite>> prereqMap     = buildPrereqMap(prereqs);
        Map<Integer, List<RotationSequenceRule>> seqMap = buildSequenceRuleMap(sequenceRules);
        config.setRotationLinkRules(linkRuleDAO.getAll());
        List<Integer> auxIds = auxResidents.stream().map(Resident::getId).collect(Collectors.toList());
        Map<String, Set<Integer>> fillerRotationsByGroup = auxFillerRotationDAO.getAllFillerRotations();
        Set<String> fillerExclusions = buildFillerExclusions(auxResidents, fillerRotationsByGroup);
        Map<Integer, Map<Integer, Integer>> auxCoverage = auxIds.isEmpty()
            ? new HashMap<>()
            : new HashMap<>(assignmentDAO.getAuxiliaryCoverage(auxIds, year, fillerExclusions));
        applyAuxCoverageCredits(auxCoverage, rotations, auxResidents, config, totalBlocks);

        ModelContext mc = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);
        CpModel model = mc.model();
        VariableFactory vf = mc.varFactory();

        // Heavy rotation ids (authoritative tier list, by name).
        Set<Integer> heavyIds = WorkloadTiers.heavyIds(rotations);

        // heavy[r][b] = 1 iff resident r is on any heavy rotation at slot b.
        // coverer[r][b] (weekend b) = 1 iff NOT heavy@b AND NOT heavy@b+1.
        // volunteer[b] = 1 iff sum_r coverer[r][b] == 0.
        List<BoolVar> volunteerVars = new ArrayList<>();
        for (int b = 0; b + 1 < totalBlocks; b++) {
            List<BoolVar> coverers = new ArrayList<>();
            for (Resident r : residents) {
                // heavyAtB, heavyAtB1 as sums of occupancy over heavy rotations.
                List<BoolVar> hB = new ArrayList<>(), hB1 = new ArrayList<>();
                for (int hid : heavyIds) {
                    BoolVar oB = vf.getOccupancyVar(r.getId(), hid, b);
                    if (oB != null) hB.add(oB);
                    BoolVar oB1 = vf.getOccupancyVar(r.getId(), hid, b + 1);
                    if (oB1 != null) hB1.add(oB1);
                }
                // cover = 1 - heavyAtB - heavyAtB1 clamped; model as: cover <= 1-heavyAtB,
                // cover <= 1-heavyAtB1, cover >= 1-heavyAtB-heavyAtB1.
                BoolVar cover = model.newBoolVar(String.format("cov_r%d_b%d", r.getId(), b));
                LinearExprBuilder sumB = LinearExpr.newBuilder();
                hB.forEach(sumB::add);
                LinearExprBuilder sumB1 = LinearExpr.newBuilder();
                hB1.forEach(sumB1::add);
                // cover + heavyAtB <= 1
                model.addLessOrEqual(LinearExpr.newBuilder().add(cover).addSum(hB.toArray(new BoolVar[0])).build(), 1);
                // cover + heavyAtB1 <= 1
                model.addLessOrEqual(LinearExpr.newBuilder().add(cover).addSum(hB1.toArray(new BoolVar[0])).build(), 1);
                // cover >= 1 - heavyAtB - heavyAtB1  ->  cover + heavyAtB + heavyAtB1 >= 1
                model.addGreaterOrEqual(
                    LinearExpr.newBuilder().add(cover)
                        .addSum(hB.toArray(new BoolVar[0]))
                        .addSum(hB1.toArray(new BoolVar[0])).build(), 1);
                coverers.add(cover);
            }
            // volunteer[b] = 1 iff sum(coverers) == 0  ->  sum + M*volunteer >= 1, volunteer <= 1-...
            // Simpler: volunteer is BoolVar with  sum(coverers) >= 1 - volunteer*BIG is awkward;
            // use: anyCover = OR(coverers); volunteer = 1 - anyCover.
            BoolVar volunteer = model.newBoolVar("volunteer_b" + b);
            // volunteer + sum(coverers) >= 1  (if no coverer, volunteer must be 1)
            model.addGreaterOrEqual(
                LinearExpr.newBuilder().add(volunteer).addSum(coverers.toArray(new BoolVar[0])).build(), 1);
            // volunteer <= 1 - cover for each coverer (if any coverer=1, volunteer=0)
            for (BoolVar cov : coverers)
                model.addLessOrEqual(LinearExpr.newBuilder().add(volunteer).add(cov).build(), 1);
            volunteerVars.add(volunteer);
        }

        IntVar volunteerTotal = model.newIntVar(0, volunteerVars.size(), "volunteer_total");
        model.addEquality(volunteerTotal, LinearExpr.sum(volunteerVars.toArray(new BoolVar[0])));

        if (minTransitionsAtZeroVolunteers) {
            // Hard-lock volunteers to 0, then minimize a transition proxy: count of
            // heavy→different-heavy adjacencies plus heavy/medium "run-excess" beyond 3 slots
            // (6 weeks). This shows the BEST transition quality achievable with perfect
            // coverage — the real schedule a volunteers=0 hard floor would produce.
            model.addEquality(volunteerTotal, 0);

            Set<Integer> medNames = rotations.stream()
                .filter(r -> WorkloadTiers.MEDIUM.contains(r.getName()))
                .map(Rotation::getId).collect(Collectors.toSet());
            List<BoolVar> heavyHeavy = new ArrayList<>();
            // Build heavy/medium indicator per resident-slot and the heavy→diff-heavy terms.
            Map<String, BoolVar> hmVar = new HashMap<>();
            for (Resident r : residents) {
                for (int b = 0; b < totalBlocks; b++) {
                    List<BoolVar> occs = new ArrayList<>();
                    for (int id : heavyIds) { BoolVar o = vf.getOccupancyVar(r.getId(), id, b); if (o != null) occs.add(o); }
                    for (int id : medNames) { BoolVar o = vf.getOccupancyVar(r.getId(), id, b); if (o != null) occs.add(o); }
                    BoolVar hm = model.newBoolVar("hm_r" + r.getId() + "_b" + b);
                    // hm = OR(occs)
                    model.addGreaterOrEqual(LinearExpr.newBuilder().add(hm).build(),
                        LinearExpr.newBuilder().addSum(occs.toArray(new BoolVar[0])).build());
                    for (BoolVar o : occs) model.addLessOrEqual(o, hm);
                    hmVar.put(r.getId() + "_" + b, hm);
                }
            }
            // heavy→different-heavy adjacencies
            for (Resident r : residents) {
                for (int b = 0; b + 1 < totalBlocks; b++) {
                    for (int h1 : heavyIds) {
                        BoolVar o1 = vf.getOccupancyVar(r.getId(), h1, b);
                        if (o1 == null) continue;
                        for (int h2 : heavyIds) {
                            if (h1 == h2) continue;
                            BoolVar o2 = vf.getOccupancyVar(r.getId(), h2, b + 1);
                            if (o2 == null) continue;
                            // v = o1 AND o2 : v<=o1, v<=o2, v>=o1+o2-1.
                            BoolVar v = model.newBoolVar("hh_r" + r.getId() + "_b" + b + "_" + h1 + "_" + h2);
                            model.addLessOrEqual(v, o1);
                            model.addLessOrEqual(v, o2);
                            model.addGreaterOrEqual(
                                LinearExpr.newBuilder().add(v).build(),
                                LinearExpr.newBuilder().add(o1).add(o2).add(-1).build());
                            heavyHeavy.add(v);
                        }
                    }
                }
            }
            // run-excess: for each window of 4 consecutive slots (8 weeks), penalize if all 4
            // are heavy/medium (a >6-week run). Sum over windows ≈ count of long-run slots.
            List<BoolVar> longRun = new ArrayList<>();
            for (Resident r : residents) {
                for (int b = 0; b + 3 < totalBlocks; b++) {
                    BoolVar w = model.newBoolVar("long_r" + r.getId() + "_b" + b);
                    // w = 1 iff hm[b]..hm[b+3] all 1
                    List<BoolVar> four = new ArrayList<>();
                    for (int k = 0; k < 4; k++) four.add(hmVar.get(r.getId() + "_" + (b + k)));
                    for (BoolVar h : four) model.addGreaterOrEqual(h, w);
                    model.addLessOrEqual(LinearExpr.newBuilder().add(w).build(),
                        LinearExpr.newBuilder().addSum(four.toArray(new BoolVar[0])).add(-3).build());
                    longRun.add(w);
                }
            }
            LinearExprBuilder obj = LinearExpr.newBuilder();
            for (BoolVar v : heavyHeavy) obj.addTerm(v, 10);  // heavy→heavy weighted heavier
            for (BoolVar v : longRun) obj.addTerm(v, 3);
            IntVar transCost = model.newIntVar(0, 100000, "trans_cost");
            model.addEquality(transCost, obj.build());
            model.minimize(transCost);
        } else {
            model.minimize(volunteerTotal);
        }

        CpSolver solver = configureSolver(config, timeLimitSec);
        long t0 = System.currentTimeMillis();
        CpSolverStatus status = solver.solve(model);
        double secs = (System.currentTimeMillis() - t0) / 1000.0;

        int vol = -1;
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            vol = (int) Math.round(solver.value(volunteerTotal));
            // Independent audit: dump per-resident heavy slot counts so a false floor (one
            // that dodged the heavy requirements) is caught — the totals must match the
            // required load (VA=4, ICU/BMC/Y7D/Y8P=2, Y7N≈2 slots per resident).
            log.append("  AUDIT — per-resident heavy slots (must be ~13–14): ");
            for (Resident r : residents) {
                int h = 0;
                for (int hid : heavyIds)
                    for (int b = 0; b < totalBlocks; b++) {
                        BoolVar o = vf.getOccupancyVar(r.getId(), hid, b);
                        if (o != null && solver.booleanValue(o)) h++;
                    }
                log.append(h).append(" ");
            }
            log.append("\n");
            // Dump the full assignment grid (resident,slot,rotation) so it can be independently
            // re-scored by the external metrics. Marker line for easy parsing.
            log.append("GRID_BEGIN\n");
            for (Resident r : residents) {
                for (int b = 0; b < totalBlocks; b++) {
                    for (Rotation s : rotations) {
                        BoolVar o = vf.getOccupancyVar(r.getId(), s.getId(), b);
                        if (o != null && solver.booleanValue(o)) {
                            log.append(r.getId()).append(',').append(b).append(',')
                               .append(s.getName()).append('\n');
                        }
                    }
                }
            }
            log.append("GRID_END\n");
        }
        boolean proven = status == CpSolverStatus.OPTIMAL;
        log.append(String.format("Coverage-floor proof: status=%s  volunteerWeekends=%d  proven=%s  (%.1fs)%n",
            status, vol, proven, secs));
        if (proven)
            log.append("  => This IS the mathematical floor; no full-objective solve can beat it.\n");
        else
            log.append("  => Not proven optimal within the time limit (lower bound only).\n");
        return new CoverageFloorResult(status, vol, proven, log.toString());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Public entry point
    // ══════════════════════════════════════════════════════════════════════

    /**
     * @param tier0LimitSec Phase 0 (feasibility) time limit in seconds (0 = unlimited)
     * @param tier1LimitSec Phase 1 (clinical)    time limit in seconds (0 = unlimited)
     * @param tier2LimitSec Phase 2 (quality)     time limit in seconds (0 = unlimited)
     * @param tier3LimitSec Phase 3 (pattern)     time limit in seconds (0 = unlimited)
     * @param onProgress    Status messages streamed back to the UI
     */
    public ScheduleSolution solve(int year, Consumer<String> onProgress,
                                  int tier0LimitSec, int tier1LimitSec, int tier2LimitSec,
                                  int tier3LimitSec)
            throws SQLException {
        ensureNativeLibraries();
        stopRequested.set(false);
        runSeedId = null;   // set later iff this run warm-starts from a cached seed
        long startMs = System.currentTimeMillis();

        // ── 1. Load data ───────────────────────────────────────────────────
        onProgress.accept("Loading data from database…");
        ScheduleConfig            config        = configDAO.loadConfig();
        List<Resident>            residents     = residentDAO.getMainResidents();
        List<Resident>            auxResidents  = residentDAO.getAuxiliaryResidents();
        List<Rotation>            rotations     = rotationDAO.getAll();
        List<Block>               blocks        = blockDAO.getByYear(year);
        List<RotationRequirement> reqs          = rulesDAO.getAllRequirements();
        List<Prerequisite>        prereqs       = rulesDAO.getAllPrerequisites();
        List<RotationSequenceRule> sequenceRules = rulesDAO.getAllSequenceRules();

        if (residents.isEmpty() || rotations.isEmpty() || blocks.isEmpty()) {
            ScheduleSolution empty = new ScheduleSolution();
            empty.setStatus(ScheduleSolution.Status.INFEASIBLE);
            empty.setSolverLog("No residents, rotations, or blocks found for year " + year);
            return empty;
        }
        applyTierDefaults(config, rotations);

        int totalBlocks = ScheduleUnits.SLOTS_PER_YEAR; // 26 two-week slots = one academic year
        config.setTotalBlocks(totalBlocks);

        Map<Integer, int[]> rotationLengths = new HashMap<>();
        for (Rotation r : rotations) {
            ScheduleConfig.RotationPolicy policy = configDAO.loadRotationPolicy(r.getId());
            config.getRotationPolicies().put(r.getId(), policy);
            rotationLengths.put(r.getId(), policy.allowedBlockLengths);
        }

        Map<Integer, Map<Integer, RotationRequirement>> reqMap = buildReqMap(reqs);
        Map<Integer, Set<Integer>>  eligibleByRotation = buildEligibilityMap(residents, rotations, reqMap);
        Map<Integer, List<Prerequisite>> prereqMap     = buildPrereqMap(prereqs);
        Map<Integer, List<RotationSequenceRule>> seqMap = buildSequenceRuleMap(sequenceRules);

        config.setRotationLinkRules(linkRuleDAO.getAll());

        // Pre-count auxiliary coverage so the solver adjusts coverage bounds accordingly.
        // Exclude filler-group assignments (e.g. BMC on Younker 7) — those are regenerated post-solve.
        List<Integer> auxIds = auxResidents.stream().map(Resident::getId).collect(Collectors.toList());
        Map<String, Set<Integer>> fillerRotationsByGroup = auxFillerRotationDAO.getAllFillerRotations();
        Set<String> fillerExclusions = buildFillerExclusions(auxResidents, fillerRotationsByGroup);
        Map<Integer, Map<Integer, Integer>> auxCoverage = auxIds.isEmpty()
            ? new HashMap<>()
            : new HashMap<>(assignmentDAO.getAuxiliaryCoverage(auxIds, year, fillerExclusions));
        applyAuxCoverageCredits(auxCoverage, rotations, auxResidents, config, totalBlocks);

        // Younker 7 Days coverage model (real-world rule): EXACTLY 2 bodies per block.
        // This is NOT enforced as a solver coverage floor — doing so is infeasible, because
        // each categorical does exactly one Y7D block (their per-resident requirement) and
        // that supply can't also cover every block. Instead:
        //   • Each categorical does 1 Y7D block (enforced by rotation_requirements).
        //   • The solver caps categoricals at 1/block, except block 13 (2 categoricals).
        //   • The BMC group statically staffs Y7D on every block EXCEPT block 7 and 13;
        //     this is written into the schedule post-solve by AuxFillerService (so BMC
        //     coverage is VISIBLE, not merely inferred) — see runAuxFiller / the Y7D fill.
        //   • Block 7's 2nd body is a TY (external); block 13's is the 2nd categorical.
        // Y7D therefore needs no solver-side per-block min; its total is capped at 2.

        // ── 2. Pre-solve feasibility analysis ─────────────────────────────
        onProgress.accept("Running feasibility analysis…");
        FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer(config, totalBlocks);
        FeasibilityReport feasReport = analyzer.analyze(residents, rotations, reqs, prereqs, eligibleByRotation, auxCoverage);
        if (feasReport.hasIssues())
            onProgress.accept("⚠ Feasibility issues detected — see report. Proceeding anyway…");

        if (stopRequested.get()) return aborted(feasReport);

        // ── 3. Diagnostic log ─────────────────────────────────────────────
        final StringBuilder solverLog = new StringBuilder();
        buildDiagnosticLog(solverLog, residents, rotations, blocks, config, reqMap,
            prereqMap, eligibleByRotation, rotationLengths, seqMap, auxCoverage, totalBlocks,
            feasReport, onProgress);

        if (stopRequested.get()) return aborted(feasReport);

        // ── 4. Four-phase solve ────────────────────────────────────────────

        // ─── Phase 0: Pure feasibility ────────────────────────────────────
        // KNOWN-GOOD baseline: plain feasibility search, no objective/seed/tuning.
        // The "accelerated" path (Options A+B+C below, gated by PHASE0_ACCEL) was a
        // REGRESSION when added together — measured ~480s+ vs the 90–600s baseline —
        // because Option A's maximize-occupancy ran to OPTIMAL and Option C's
        // POLARITY_FALSE fought both the objective and the greedy seed. Reverted to
        // baseline; the accel path is kept OFF for one-option-at-a-time isolation.
        //   A) Lightweight objective: maximize total occupancy (gradient signal).
        //   B) Greedy seed hints: round-robin warm start, repaired via repairHint.
        //   C) Phase-0 solver tuning: probing off + POLARITY_FALSE + stop-after-first.
        // PHASE0_MODE env var selects which acceleration option(s) are active, so each
        // can be measured in isolation WITHOUT recompiling between variants:
        //   baseline (default) — known-good: plain configureSolver, no objective/seed.
        //   B    — Option B only: greedy seed hints on the normal solver.
        //   C    — Option C only: stop-after-first-solution (+ probing off, polarity false).
        //   A    — Option A only: maximize-occupancy objective on the normal solver.
        //   accel — all three together (the committed regression).
        // Mode string may name any subset of options, e.g. "A", "BC", "AC", "accel" (=ABC),
        // or "baseline" (none). Membership is by letter so combos compose freely.
        String p0mode = System.getenv().getOrDefault("PHASE0_MODE", "baseline").trim();
        String p0letters = p0mode.equals("accel") ? "ABC" : p0mode;
        boolean useA = p0letters.contains("A");
        boolean useB = p0letters.contains("B");
        boolean useC = p0letters.contains("C");

        // mc0/solver0/status0 are populated by EITHER the decomposition path (when
        // PHASE0_DECOMP is set and succeeds) or the monolithic path below. All downstream
        // handling (INFEASIBLE diagnosis, extractHints, Phase-1 handoff) is shared.
        ModelContext mc0;
        CpSolver solver0;
        CpSolverStatus status0;

        // ── Phase-0 DECOMPOSITION (staged warm-start; PHASE0_DECOMP=roll3|roll1) ──────
        // Solves the year in time-sliced windows (block 13 anchored first), carrying each
        // window's full assignment forward as HINTS into the next window's full-model solve.
        // Every window solves the FULL model (all yearly per-resident constraints present),
        // so the win is warm-starting, not problem shrinkage. On any window that returns
        // INFEASIBLE/UNKNOWN, returns null → we fall through to the monolithic path below
        // (never regress). Default/unset → skip entirely. See PHASE0_DECOMPOSITION_PLAN.md.
        String p0decomp = System.getenv().getOrDefault("PHASE0_DECOMP", "").trim();
        DecompResult dr = p0decomp.isEmpty() ? null
            : solvePhase0Decomp(p0decomp, residents, rotations, config, reqMap, prereqMap,
                eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage,
                tier0LimitSec, onProgress, solverLog, startMs);

        if (dr != null) {
            // Decomposition produced a final full-model solve; feed it into the shared path.
            mc0     = dr.mc();
            solver0 = dr.solver();
            status0 = dr.status();
        } else {
            if (!p0decomp.isEmpty()) {
                onProgress.accept("Phase-0 decomposition hit an unrecoverable corner — falling back to monolithic solve…");
                solverLog.append(String.format("\nPhase 0 decomp=%s fell back to monolithic.\n", p0decomp));
            }
            onProgress.accept(String.format(
                "Phase 0 ▶ Finding feasible assignment (mode=%s, limit: %ds)…", p0mode, tier0LimitSec));
            mc0 = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
                eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);

            if (useB) {
                // Option B: apply greedy seed as warm-start hints before solving.
                Map<String, Long> greedySeed = buildGreedySeedHints(
                    residents, rotations, mc0.varFactory(), rotationLengths, eligibleByRotation, totalBlocks);
                if (!greedySeed.isEmpty())
                    applyHints(mc0.model(), mc0.varFactory(), residents, rotations, totalBlocks, greedySeed);
            }
            if (useA) {
                // Option A: lightweight objective — maximize total slot occupancy.
                List<BoolVar> allOccVars = new ArrayList<>();
                for (Resident r : residents)
                    for (Rotation s : rotations)
                        allOccVars.addAll(mc0.varFactory().getOccupancyVars(r.getId(), s.getId()).values());
                if (!allOccVars.isEmpty()) {
                    IntVar totalOcc = mc0.model().newIntVar(0, allOccVars.size(), "p0_total_occ");
                    mc0.model().addEquality(totalOcc,
                        LinearExpr.sum(allOccVars.toArray(new BoolVar[0])));
                    mc0.model().maximize(totalOcc);
                }
            }

            // ── Phase-0 FIX options (post-decomp investigation; PHASE0_FIX=…) ──────────
            // These attack the COLD first-feasibility search directly instead of decomposing
            // the problem (which dead-ended — window 1 is the full problem). All env-gated
            // OFF; unset → the plain solve below, untouched. Modes:
            //   cache       — Option 1: replay a DB-cached known-FEASIBLE assignment as hints
            //                 (repairHint). The model is identical every run, so a feasible
            //                 point found once is feasible forever. Highest-probability win.
            //   fjportfolio — Option 2: stack the worker portfolio toward feasibility_jump
            //                 (the only subsolver that ever finds feasibility on this model).
            //   multiseed   — Option 3: short fresh-random-seed attempts back-to-back, stop on
            //                 the first feasible draw (more lottery tickets per wall budget).
            //   lns         — Option 4: hard-fix the bulk of a cached/greedy assignment and let
            //                 CP-SAT repair only the free remainder (single-shot LNS, never the
            //                 cold-window-1 trap).
            //   presolveN   — Option 5: bound presolve (setMaxPresolveIterations / disable) so
            //                 search (where feasibility_jump lives) starts immediately.
            // See PHASE0_DECOMPOSITION_PLAN.md "post-decomp FIX options".
            String p0fix = System.getenv().getOrDefault("PHASE0_FIX", "").trim();
            boolean p0fixDiag = "1".equals(System.getenv("PHASE0_DIAG"));
            // Set when the default path replayed a cached seed: Phase 0 then only needs to validate
            // the warm start, so we stop at the first feasible solution (collapsing P0 to ~instant).
            boolean seededDefaultPath = false;

            // PHASE0_CACHE_COLLECT=1 = pool-SEEDING mode: solve COLD (no replay) so each run
            // explores freshly and the pool accumulates genuine variety, persisting every new
            // distinct find. Used by phase0_seed_pool.sh to build the pool up front; OFF for
            // normal cache runs (which replay a random cached assignment as a warm start).
            boolean cacheCollect = "1".equals(System.getenv("PHASE0_CACHE_COLLECT"));

            if (p0fix.equals("cache")) {
                if (cacheCollect) {
                    onProgress.accept("Phase 0 ▶ cache COLLECT — cold solve (no replay); persisting new finds for pool variety.");
                    solverLog.append("Phase 0 FIX=cache (COLLECT): cold solve, will append if distinct.\n");
                } else {
                    // Integrity guard: if the model changed since the pool was built, revalidate
                    // every entry against the CURRENT model and evict any that no longer solve.
                    ensurePoolFreshOrEvict(year, residents, rotations, config, reqMap, prereqMap,
                        eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage, solverLog);
                    // Option 1: warm-start from a RANDOM cached feasible assignment if present, so
                    // consecutive runs start from different feasible basins (no single-basin bias).
                    Map<String, Long> cached = loadCachedFeasibleHints(year);
                    if (cached != null && !cached.isEmpty()) {
                        runSeedId = seedId(cached);   // remember which seed we started from (outcome recording)
                        applyHints(mc0.model(), mc0.varFactory(), residents, rotations, totalBlocks, cached);
                        onProgress.accept(String.format(
                            "Phase 0 ▶ cache hit — warm-starting from cached seed %s… (%d vars, repairHint).",
                            runSeedId.substring(0, 8), cached.size()));
                        solverLog.append(String.format("Phase 0 FIX=cache: replayed seed %s (%d vars).\n",
                            runSeedId.substring(0, 8), cached.size()));
                    } else {
                        onProgress.accept("Phase 0 ▶ cache MISS — cold solve; will persist the result if feasible.");
                        solverLog.append("Phase 0 FIX=cache: empty pool — cold solve, will cache on success.\n");
                    }
                }
            } else if (p0fix.equals("lns")) {
                // Option 4: hard-fix the bulk of a cached/greedy assignment, leave a free margin
                // for CP-SAT to repair. Single-shot — never a cold staged window.
                applyLnsCore(mc0.model(), mc0.varFactory(), residents, rotations, config,
                    rotationLengths, eligibleByRotation, totalBlocks, year, onProgress, solverLog);
            } else if (p0fix.isEmpty() && !useB && !useA
                    && !"1".equals(System.getenv("PHASE0_NO_SEED"))) {
                // DEFAULT PATH seed warm-start (Phase-0 acceleration, the proven win): when the
                // year's pool is non-empty, replay one cached feasible assignment as Phase-0 hints
                // so a normal real solve starts warm instead of cold-searching for feasibility.
                // Phase 0 then collapses to validation/instant and Phase 1 inherits the warm start
                // via p0.hints() (existing carry-forward at 834–838) — no separate Phase-1 hinting
                // needed. The chosen seed_id is recorded in runSeedId so the run is attributable to
                // its seed (join key for solve_runs ↔ phase0_seed_stats; closes the recordOutcome
                // loop at 1032–1043). Honors PHASE0_SEED_SELECT=roundrobin for fair coverage.
                // Reversible: empty pool ⇒ falls through to today's cold Phase 0 unchanged; opt out
                // entirely with PHASE0_NO_SEED=1. Excludes the explicit Option A/B experimental
                // warm-starts so they remain isolated.
                Map<String, Long> seed = loadCachedFeasibleHints(year);
                if (seed != null && !seed.isEmpty()) {
                    runSeedId = seedId(seed);
                    applyHints(mc0.model(), mc0.varFactory(), residents, rotations, totalBlocks, seed);
                    seededDefaultPath = true; // Phase 0 only needs to validate the seed → stop-first.
                    onProgress.accept(String.format(
                        "Phase 0 ▶ seeded warm-start from seed_id=%s (%d vars) — validating…",
                        runSeedId.substring(0, 8), seed.size()));
                    solverLog.append(String.format(
                        "Phase 0: Phase-1 seeded from seed_id=%s (%d vars, default-path replay).\n",
                        runSeedId.substring(0, 8), seed.size()));
                } else {
                    solverLog.append("Phase 0: seed pool empty — cold solve (no warm start).\n");
                }
            }

            // Build the solver. fjportfolio / multiseed / presolveN tune it; cache & lns reuse
            // the standard configureSolver (Option C tuning still available via PHASE0_MODE=C).
            // A seeded default-path run uses configureSolverPhase0 too: it is the purpose-built
            // "validate a warm-start seed and stop at first feasible" config (probing off, polarity
            // FALSE, stop-after-first). The plain configureSolver + a bare setStopAfterFirstSolution
            // on a hinted/repairHint model trips an OR-Tools 9.9 native assertion
            // (heuristics.fixed_search != nullptr); configureSolverPhase0 avoids it, exactly as the
            // FIX=cache replay path already relies on.
            solver0 = (useC || seededDefaultPath)
                ? configureSolverPhase0(config, tier0LimitSec)
                : configureSolver(config, tier0LimitSec);
            if (p0fix.startsWith("presolve")) {
                applyPresolveBound(solver0, p0fix, solverLog); // Option 5
            }
            // The feasibility_jump portfolio (Option 2) is also a COMPOSABLE knob: either the
            // standalone mode PHASE0_FIX=fjportfolio, OR stacked on any other FIX mode via
            // PHASE0_PORTFOLIO=fj. The latter lets pool-SEEDING (PHASE0_FIX=cache + COLLECT)
            // find each feasible point ~10× faster, and lets cache-replay runs combine the two
            // strongest levers (warm start + feasibility-jump-heavy search).
            boolean wantFjPortfolio = p0fix.equals("fjportfolio")
                || "fj".equalsIgnoreCase(System.getenv().getOrDefault("PHASE0_PORTFOLIO", "").trim());
            if (wantFjPortfolio) {
                applyFeasibilityJumpPortfolio(solver0, solverLog); // Option 2
            }
            // Phase-0 only needs a feasible incumbent: stop at the first solution for all FIX
            // modes (configureSolverPhase0 already does this; configureSolver does not). The seeded
            // default path already got configureSolverPhase0 above, so it has stop-after-first too.
            if (!p0fix.isEmpty() && !useC && !seededDefaultPath)
                solver0.getParameters().setStopAfterFirstSolution(true);

            // Pool-seeding: randomize the seed each collect run so cold solves land in
            // different feasible basins (genuine pool variety, not the same point repeatedly).
            if (p0fix.equals("cache") && cacheCollect)
                solver0.getParameters().setRandomSeed(new Random().nextInt(Integer.MAX_VALUE));

            // PHASE0_DIAG=1 turns on CP-SAT's search-progress log so we can see the per-step
            // presolve timing (probing / BVE / iterations) and find what eats the budget on
            // capped draws. Applies on top of whatever mode is selected; output goes to stdout
            // (the run log). Diagnostic only — leave off for normal runs.
            if (p0fixDiag) {
                solver0.getParameters().setLogSearchProgress(true);
                solver0.getParameters().setLogToStdout(true);
            }
            activeSolver = solver0;

            if (p0fix.equals("multiseed")) {
                // Option 3: short fresh-seed attempts back-to-back; stop on first feasible.
                status0 = solveMultiSeed(solver0, mc0, tier0LimitSec, onProgress, solverLog, startMs);
            } else {
                status0 = solver0.solve(mc0.model());
            }
            activeSolver = null;

            // Option 1: persist the FIRST feasible Phase-0 assignment so later runs warm-start.
            String bankedSeedId = null;
            if (p0fix.equals("cache")
                    && (status0 == CpSolverStatus.FEASIBLE || status0 == CpSolverStatus.OPTIMAL)) {
                Map<String, Long> found =
                    extractHints(solver0, mc0.varFactory(), residents, rotations, totalBlocks);
                bankedSeedId = seedId(found);
                // Stamp the pool with the current model fingerprint so a later model change is
                // detectable (triggers revalidation on the next replay run).
                String fp = computeModelFingerprint(residents, rotations, reqMap,
                    eligibleByRotation, rotationLengths, seqMap, totalBlocks, config);
                try { configDAO.saveRawValue(CACHE_FP_PREFIX + year, fp); }
                catch (SQLException e) { LOG.log(java.util.logging.Level.WARNING, "fingerprint save failed", e); }
                saveCachedFeasibleHints(year, found, solverLog);
            }

            // Durable per-run collection telemetry: record EVERY collection solve (feasible AND
            // capped) so the time-to-feasibility history accumulates instead of being overwritten
            // with the CSV. Capped runs are the right-censored data points the cap analysis needs.
            // new_seed_id is null on a cap or duplicate; non-null when this run banked a NEW seed.
            if (p0fix.equals("cache") && cacheCollect) {
                try {
                    double secs = (System.currentTimeMillis() - startMs) / 1000.0;
                    collectionRunsDAO.record(year, status0.name(), secs, tier0LimitSec > 0 ? tier0LimitSec : null,
                        bankedSeedId, config.getCpSatNumWorkers());
                } catch (SQLException e) {
                    LOG.log(java.util.logging.Level.WARNING, "collection-run telemetry write failed", e);
                }
            }
        }
        solverLog.append(String.format("\nPhase 0 result: %s  (%.1fs)%s\n",
            status0, (System.currentTimeMillis() - startMs) / 1000.0,
            dr != null ? " [decomp=" + p0decomp + "]" : ""));

        if (status0 == CpSolverStatus.INFEASIBLE) {
            onProgress.accept("Phase 0 INFEASIBLE — running stepwise diagnosis to locate the conflict…");
            solverLog.append("\n═══ STEPWISE FEASIBILITY DIAGNOSIS (model proved INFEASIBLE) ═══\n");
            runStepwiseDiagnosis(solverLog, residents, rotations, config, reqMap, prereqMap,
                eligibleByRotation, rotationLengths, seqMap, auxCoverage, totalBlocks);
            ScheduleSolution sol = new ScheduleSolution();
            sol.setStatus(ScheduleSolution.Status.INFEASIBLE);
            sol.setFeasibilityReport(feasReport);
            sol.setSolverLog(solverLog.toString());
            sol.setRuntimeMs(System.currentTimeMillis() - startMs);
            commitToDB(sol, year, blocks, residents, rotations);
            return sol;
        }

        PhaseResult p0 = new PhaseResult(status0, solver0, mc0.varFactory(),
            status0 == CpSolverStatus.UNKNOWN ? Map.of()
                : extractHints(solver0, mc0.varFactory(), residents, rotations, totalBlocks),
            Integer.MAX_VALUE);

        onProgress.accept(String.format("Phase 0 ✓ [%s]  |  Phase 1 ▶ Clinical optimization (limit: %ds)…",
            status0, tier1LimitSec));
        if (stopRequested.get()) return commitAndReturn(p0, feasReport, solverLog, startMs, year, blocks, residents, rotations, config);

        // ─── Phase 1: Minimize Tier-1 clinical violations ─────────────────
        ModelContext mc1 = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);
        if (!p0.hints().isEmpty())
            applyHints(mc1.model(), mc1.varFactory(), residents, rotations, totalBlocks, p0.hints());

        ObjectiveFunctionBuilder obj1 = new ObjectiveFunctionBuilder(
            mc1.model(), mc1.varFactory(), config, totalBlocks, auxCoverage);
        IntVar tier1Var = obj1.buildTier1Counter(residents, rotations);
        mc1.model().minimize(tier1Var);

        CpSolver solver1 = configureSolver(config, tier1LimitSec);
        activeSolver = solver1;
        CpSolverStatus status1 = solver1.solve(mc1.model());
        activeSolver = null;

        int bestTier1 = Integer.MAX_VALUE;
        PhaseResult p1;
        if (p0.feasible() && !p0.hints().isEmpty() && status1 == CpSolverStatus.UNKNOWN) {
            // Phase 1 timed out with no improvement — fall back to Phase 0 result
            p1 = new PhaseResult(status1, solver1, mc1.varFactory(), p0.hints(), Integer.MAX_VALUE);
            onProgress.accept("Phase 1 timeout — no improvement. Using Phase 0 result for Phase 2.");
        } else if (status1 == CpSolverStatus.FEASIBLE || status1 == CpSolverStatus.OPTIMAL) {
            try { bestTier1 = (int) solver1.value(tier1Var); }
            catch (Exception e) { LOG.log(java.util.logging.Level.WARNING, "Could not read Tier-1 objective value", e); }
            Map<String, Long> h1 = extractHints(solver1, mc1.varFactory(), residents, rotations, totalBlocks);
            p1 = new PhaseResult(status1, solver1, mc1.varFactory(), h1, bestTier1);
            onProgress.accept(String.format("Phase 1 ✓ [%s]  Tier-1 score: %d  |  Phase 2 ▶ Quality (limit: %ds)…",
                status1, bestTier1, tier2LimitSec));
        } else {
            // Phase 1 INFEASIBLE — use Phase 0 hints and skip Tier-1 lock
            p1 = new PhaseResult(status1, solver1, mc1.varFactory(), p0.hints(), Integer.MAX_VALUE);
            onProgress.accept("Phase 1 did not find a solution — proceeding to Phase 2 with relaxed Tier-1.");
        }
        solverLog.append(String.format("Phase 1 result: %s  tier1_score=%s  (%.1fs)\n",
            status1, bestTier1 == Integer.MAX_VALUE ? "n/a" : bestTier1,
            (System.currentTimeMillis() - startMs) / 1000.0));

        if (stopRequested.get()) return commitAndReturn(p1.feasible() ? p1 : p0, feasReport, solverLog, startMs, year, blocks, residents, rotations, config);

        // ─── Phase 2: Minimize Tier-2 core with Tier-1 locked ────────────
        ModelContext mc2 = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);
        if (!p1.hints().isEmpty())
            applyHints(mc2.model(), mc2.varFactory(), residents, rotations, totalBlocks, p1.hints());

        ObjectiveFunctionBuilder obj2 = new ObjectiveFunctionBuilder(
            mc2.model(), mc2.varFactory(), config, totalBlocks, auxCoverage);

        if (bestTier1 < Integer.MAX_VALUE) {
            IntVar tier1Lock = obj2.buildTier1Counter(residents, rotations);
            mc2.model().addLessOrEqual(tier1Lock, bestTier1);
        }
        IntVar tier2CostVar = obj2.buildTier2Core(residents, rotations);
        mc2.model().minimize(tier2CostVar);

        CpSolver solver2 = configureSolver(config, tier2LimitSec);
        activeSolver = solver2;
        CpSolverStatus status2 = solver2.solve(mc2.model());
        activeSolver = null;

        long bestTier2 = Long.MAX_VALUE;
        PhaseResult p2;
        if (status2 == CpSolverStatus.FEASIBLE || status2 == CpSolverStatus.OPTIMAL) {
            try { bestTier2 = (long) solver2.value(tier2CostVar); }
            catch (Exception e) { LOG.log(java.util.logging.Level.WARNING, "Could not read Tier-2 objective value", e); }
            Map<String, Long> h2 = extractHints(solver2, mc2.varFactory(), residents, rotations, totalBlocks);
            p2 = new PhaseResult(status2, solver2, mc2.varFactory(), h2, bestTier1);
            onProgress.accept(String.format("Phase 2 ✓ [%s]  Tier-2 cost: %d  |  Phase 3 ▶ Pattern (limit: %ds)…",
                status2, bestTier2, tier3LimitSec));
        } else if (p1.feasible()) {
            p2 = new PhaseResult(status2, solver2, mc2.varFactory(), p1.hints(), bestTier1);
            onProgress.accept("Phase 2 timeout — using Phase 1 hints for Phase 3.");
        } else {
            p2 = new PhaseResult(status2, solver2, mc2.varFactory(), p0.hints(), bestTier1);
            onProgress.accept("Phase 2 did not improve — proceeding to Phase 3 with relaxed Tier-2.");
        }
        solverLog.append(String.format("Phase 2 result: %s  tier2=%s  (%.1fs)\n",
            status2, bestTier2 == Long.MAX_VALUE ? "n/a" : bestTier2,
            (System.currentTimeMillis() - startMs) / 1000.0));

        if (stopRequested.get()) return commitAndReturn(
            status2 == CpSolverStatus.FEASIBLE || status2 == CpSolverStatus.OPTIMAL ? p2
                : p1.feasible() ? p1 : p0,
            feasReport, solverLog, startMs, year, blocks, residents, rotations, config);

        // ─── Phase-2-only default (PHASE3_SKIP, default ON) ─────────────────────────
        // Per the #5025 root-cause finding, no hint/pin lane RELIABLY engages Phase 3: it is a
        // per-seed coin-flip (~⅓ optimize), and a non-engaging run burns the FULL ~900s P3 budget
        // win-or-lose. So a DEFAULT run is honest Phase-2-only: it never attempts Phase 3 and never
        // wastes the budget. The Phase-2 result already scores at/above the v7 benchmark (fragile
        // 7–11, h→h 0). Phase-3 OPTIMIZATION is now an EXPLICIT opt-in for the later targeted depth
        // phase: set PHASE3_SKIP=0 to run it. (Mass-harvest relies on this default.)
        if (!"0".equals(System.getenv("PHASE3_SKIP")) && !"1".equals(System.getenv("SINGLE_PHASE"))) {
            solverLog.append("Phase 3 SKIPPED (PHASE3_SKIP default on) — committing Phase-2 result. "
                + "Set PHASE3_SKIP=0 to opt in to Phase-3 optimization.\n");
            return commitAndReturn(
                status2 == CpSolverStatus.FEASIBLE || status2 == CpSolverStatus.OPTIMAL ? p2
                    : p1.feasible() ? p1 : p0,
                feasReport, solverLog, startMs, year, blocks, residents, rotations, config);
        }

        // ─── Phase 3: Minimize 4+2 pattern with Tier-1 and Tier-2 locked ──
        ModelContext mc3 = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);
        // Carry the prior phase's assignment as Phase 3's warm start. PHASE3_SKIP_HINT=1 omits it,
        // letting Phase 3 run probing-ON without the carried-hint crash trigger — used to measure
        // whether a Tier-locked Phase 3 genuinely searches/optimizes the soft objective (the
        // historical pre-seed-wiring behavior), as opposed to the pinning/UNKNOWN modes.
        boolean p3SkipHint = "1".equals(System.getenv("PHASE3_SKIP_HINT"));
        // PHASE3_HINT_FRACTION (0<f<=1, default 1) thins the carried hint to a fraction of its vars.
        // Paired with PHASE3_FIX_TO_HINT=on this PINS only that fraction: enough to make OR-Tools
        // construct the fixed_search heuristic (so the fs_random worker's assertion holds) WITHOUT
        // pinning the whole schedule — leaving the rest free to optimize. The untested documented
        // lever against bug #5025's fixed_search-null assertion.
        double p3HintFrac = 1.0;
        try { p3HintFrac = Math.max(0.0, Math.min(1.0, Double.parseDouble(envOr("PHASE3_HINT_FRACTION", "1")))); }
        catch (NumberFormatException ignore) { /* keep 1.0 */ }
        Map<String, Long> p3Hints = p2.hints();
        if (p3HintFrac < 1.0 && !p3Hints.isEmpty()) {
            Map<String, Long> thinned = new HashMap<>();
            long keepEvery = Math.max(1, Math.round(1.0 / p3HintFrac));
            long idx = 0;
            for (Map.Entry<String, Long> e : p3Hints.entrySet())
                if (idx++ % keepEvery == 0) thinned.put(e.getKey(), e.getValue());
            p3Hints = thinned;
            solverLog.append(String.format("Phase 3: hint thinned to %.2f (%d vars).\n",
                p3HintFrac, p3Hints.size()));
        }
        if (!p3Hints.isEmpty() && !p3SkipHint)
            applyHints(mc3.model(), mc3.varFactory(), residents, rotations, totalBlocks, p3Hints);
        if (p3SkipHint) solverLog.append("Phase 3: carried hint SKIPPED (PHASE3_SKIP_HINT=1).\n");

        ObjectiveFunctionBuilder obj3 = new ObjectiveFunctionBuilder(
            mc3.model(), mc3.varFactory(), config, totalBlocks, auxCoverage);

        // SINGLE_PHASE: collapse the staged P0→P1→P2→P3 flow into ONE solve straight off the seed.
        // Tier-1/Tier-2 become HARD constraints (== their absolute no-compromise value, 0 — proven
        // always achievable across 99 runs) instead of ≤ best-from-a-prior-phase locks, and the soft
        // Phase-3 objective (4+2 + Sunday-coverage + cat-soft-excess) is minimized in the same solve.
        // This sidesteps the #5025 staged-handoff crash entirely (no partial-hint-into-locked-model)
        // and lets the solver optimize the weekend-coverage metric the staged flow never reached.
        boolean singlePhase = "1".equals(System.getenv("SINGLE_PHASE"));
        if (singlePhase) {
            IntVar tier1Hard = obj3.buildTier1Counter(residents, rotations);
            mc3.model().addEquality(tier1Hard, 0);          // absolute: no clinical violations
            IntVar tier2Hard = obj3.buildTier2Core(residents, rotations);
            mc3.model().addEquality(tier2Hard, 0);          // absolute: coverage levels exact
        } else {
            if (bestTier1 < Integer.MAX_VALUE) {
                IntVar tier1Lock3 = obj3.buildTier1Counter(residents, rotations);
                mc3.model().addLessOrEqual(tier1Lock3, bestTier1);
            }
            IntVar tier2Lock3 = obj3.buildTier2Core(residents, rotations);
            if (bestTier2 < Long.MAX_VALUE) mc3.model().addLessOrEqual(tier2Lock3, bestTier2);
        }

        IntVar patternCost = obj3.buildPatternObjective(residents, rotations);
        // Sunday coverage shortfall: penalises weekends below the coverer target.
        // Disabled (zero weight or missing tier lists) unless configured.
        IntVar sundayShortfall = obj3.buildSundayCoverageObjective(residents);
        // Categorical soft-cap excess: discourages (but allows) categoricals beyond a
        // rotation's preferred level, e.g. a 3rd VA categorical above its soft cap of 2.
        IntVar catSoftExcess = obj3.buildCategoricalSoftCapObjective(residents, rotations);
        // Max-consec heavy+medium soft violations: each over-limit window slot costs
        // weightMaxConsecHeavyMedium. Empty list when disabled or in hard mode.
        ConstraintBuilder cb3 = new ConstraintBuilder(
            mc3.model(), mc3.varFactory(), config, totalBlocks, auxCoverage);
        List<com.google.ortools.sat.BoolVar> hmViolations =
            !config.isMaxConsecHeavyMediumHard()
                ? cb3.applyMaxConsecHeavyMedium(residents, rotations)
                : List.of();
        LinearExprBuilder obj3Expr = LinearExpr.newBuilder()
            .add(patternCost)
            .addTerm(sundayShortfall, config.getWeightSundayCoverage())
            .addTerm(catSoftExcess, config.getWeightCategoricalSoftExcess());
        if (!hmViolations.isEmpty()) {
            int wHM = config.getWeightMaxConsecHeavyMedium();
            for (var v : hmViolations) obj3Expr.addTerm(v, wHM);
        }
        mc3.model().minimize(obj3Expr.build());

        // Phase 3 solver — seed-wiring interaction RESOLVED (see SOLVE_DATA_INFRA_PLAN.md / the
        // fixed_search root-cause comment below). History of the matrix that led here, on real data
        // with the seed pipeline:
        //   • probing ON + repairHint, MULTI-worker  → OR-Tools 9.9 native crash (fixed_search != null),
        //     tripped by a parallel fs_random first-solution worker on the partial carried hint.
        //   • probing OFF + repairHint                → UNKNOWN, no incumbent → falls back to the
        //     UN-optimized Phase-2 result (not real optimization).
        //   • fixVariablesToTheirHintedValue ON       → PINS the full carried assignment, so Phase 3
        //     "proves OPTIMAL" in ~1s WITHOUT searching — a FALSE green. NOT a real fix.
        //   • probing ON + repairHint + SINGLE worker → FIX: repairs the hint into a movable incumbent
        //     and optimizes the soft objective (FEASIBLE, never instant OPTIMAL). This is the default.
        // Env knobs remain for controlled experiments / re-observing the old failure modes.
        // DEFAULTS now reflect the verified post-fix healthy config: probing ON + repairHint ON +
        // fixToHint OFF, run single-worker (below). This makes a normal/UI run actually OPTIMIZE the
        // soft objective (FEASIBLE search), not fall back to the un-optimized Phase-2 result.
        boolean p3Probing = "on".equalsIgnoreCase(envOr("PHASE3_PROBING", "on"));
        boolean p3Repair  = !"off".equalsIgnoreCase(envOr("PHASE3_REPAIR_HINT", "on"));
        boolean p3FixHint = "on".equalsIgnoreCase(envOr("PHASE3_FIX_TO_HINT", "off"));
        boolean p3SolverLog = "1".equals(System.getenv("PHASE3_SOLVER_LOG"));
        int p3Workers = config.getCpSatNumWorkers();
        try { p3Workers = Math.max(1, Integer.parseInt(envOr("PHASE3_WORKERS", String.valueOf(p3Workers)))); }
        catch (NumberFormatException ignore) { /* keep config value */ }
        String trajPath = System.getenv("SOLVE_TRAJECTORY_CSV");

        // ── ROOT-CAUSE FIX for the seed-wiring Phase-3 crash (heuristics.fixed_search != nullptr,
        // OR-Tools 9.9.3963). This is upstream bug google/or-tools#5025: with repair_hint=true AND a
        // multi-worker portfolio, a parallel hint-following subsolver dereferences a fixed_search
        // heuristic that was never constructed → native CHECK abort. In #5025 the worker is shared_tree
        // (spawns at high worker counts); in OUR full CP-SAT log at 10 workers it is fs_random. The
        // common trigger is the hint-repair path on a PARTIAL carried hint (only the 9119 occupancy/
        // start vars of ~24544) that has not yet become a feasible incumbent (log: best:inf). NOT fixed
        // upstream as of 9.15 — an OR-Tools bump does not help. The historical pre-seed-wiring run
        // didn't crash only because its hint came from a cold Phase-0 OPTIMAL solve worker 0 could
        // repair first — a timing accident. Verified non-fixes: useFeasibilityJump=false / numViolationLs=0
        // (don't remove fs_random); ignoreSubsolvers("fs_random") → MODEL_INVALID; fixToHint=ON pins the
        // seed → false instant OPTIMAL. The knobs below let us keep the full 10-worker optimize while
        // suppressing the specific crashing subsolvers; sweep them via env to land a config that keeps
        // the workers busy AND optimizes (FEASIBLE, never instant OPTIMAL).
        int p3SharedTree = (int) parseLongOr("PHASE3_SHARED_TREE", 0);   // 0 = disable shared_tree subsolver
        int p3HintConflict = (int) parseLongOr("PHASE3_HINT_CONFLICT_LIMIT", -1); // <0 = leave default
        // Only suppress the feasibility-jump first-solution workers in the MULTI-worker portfolio (the
        // crash is a multi-worker phenomenon). At 1 worker, removing FJ + violation_ls leaves no valid
        // first-solution strategy → MODEL_INVALID; single worker keeps the full (already crash-free) set.
        boolean p3NoFj = p3Workers > 1 && !"1".equals(System.getenv("PHASE3_KEEP_FJ"));

        CpSolver solver3 = new CpSolver();
        solver3.getParameters().setNumWorkers(p3Workers);
        solver3.getParameters().setRepairHint(p3Repair);
        solver3.getParameters().setFixVariablesToTheirHintedValue(p3FixHint);
        if (!p3Probing) solver3.getParameters().setCpModelProbingLevel(0);
        // shared_tree / lns worker tuning only applies to the multi-worker portfolio. At 1 worker the
        // validator rejects any shared_tree+lns allocation ("Cannot have more shared tree + lns workers
        // than total workers") → MODEL_INVALID, so leave a single worker's defaults untouched.
        if (p3Workers > 1) {
            solver3.getParameters().setSharedTreeNumWorkers(p3SharedTree);
            if (p3SharedTree <= 0) solver3.getParameters().setUseSharedTreeSearch(false);
        }
        if (p3HintConflict >= 0) solver3.getParameters().setHintConflictLimit(p3HintConflict);
        if (p3NoFj) {
            solver3.getParameters().setUseFeasibilityJump(false);
            solver3.getParameters().setNumViolationLs(0);
        }
        // PHASE3_IGNORE_SUBSOLVERS=a,b,c → explicitly drop named subsolvers from the portfolio (e.g.
        // "fs_random", the worker that derefs the unbuilt fixed_search in #5025). Lets us keep MULTI-
        // worker + repair_hint ON (the path that actually repairs the hint into a movable incumbent)
        // while removing only the crashing worker. Comma-separated; blank = leave the portfolio intact.
        String ignoreSubs = System.getenv().getOrDefault("PHASE3_IGNORE_SUBSOLVERS", "").trim();
        if (!ignoreSubs.isEmpty()) {
            for (String s : ignoreSubs.split(",")) {
                String w = s.trim();
                if (!w.isEmpty()) solver3.getParameters().addIgnoreSubsolvers(w);
            }
            solverLog.append("Phase 3: ignoring subsolvers " + ignoreSubs + "\n");
        }
        if (tier3LimitSec > 0) solver3.getParameters().setMaxTimeInSeconds(tier3LimitSec);
        // PHASE3_SOLVER_LOG=1 turns on OR-Tools' own search log + dumps the effective parameter proto
        // (used to root-cause this assertion). Defaults OFF so normal/UI runs are unaffected.
        solver3.getParameters().setLogToStdout(p3SolverLog);
        solver3.getParameters().setLogSearchProgress(p3SolverLog);
        activeSolver = solver3;
        solverLog.append(String.format(
            "Phase 3 solver: workers=%d probing=%s repairHint=%s fixToHint=%s sharedTree=%d hintConflict=%d noFJ=%s\n",
            p3Workers, p3Probing ? "ON" : "OFF", p3Repair ? "ON" : "OFF", p3FixHint ? "ON" : "OFF",
            p3SharedTree, p3HintConflict, p3NoFj));
        if (p3SolverLog) solverLog.append("Phase 3 effective params:\n"
            + solver3.getParameters().toString() + "\n");
        // Optional Phase-3 objective trajectory capture (headless analysis). When the
        // SOLVE_TRAJECTORY_CSV env var is set, record (elapsed_s, objective) on every
        // improved incumbent so we can see where the objective plateaus and size budgets
        // from data instead of guesswork. No-op (and zero overhead) when unset, so the UI
        // is unaffected.
        // SINGLE_PHASE two-stage "hint as incumbent" (SINGLE_PHASE_2STAGE=1): the failure mode we hit
        // is that the parallel first-solution workers (which FIND a feasible start for the tier-locked
        // model) are exactly the ones that crash (#5025), while 1 worker can't find a start at all.
        // Break the deadlock: STAGE A pins the carried hint (fixVariablesToTheirHintedValue + 1 worker,
        // ~few sec) to establish a guaranteed feasible incumbent WITHOUT any first-solution search;
        // STAGE B re-hints from that incumbent, UNPINS, turns repair_hint OFF (the hint is already a
        // real feasible solution, nothing to repair → removes the crash trigger) and optimizes from it.
        boolean twoStage = "1".equals(System.getenv("SINGLE_PHASE_2STAGE"));
        CpSolverStatus status3;
        TrajectoryCallback traj = (trajPath != null && !trajPath.isBlank())
            ? new TrajectoryCallback(trajPath, startMs) : null;
        if (twoStage) {
            // STAGE A — pin hint, 1 worker, short cap → instant feasible incumbent.
            CpSolver solverA = new CpSolver();
            solverA.getParameters().setNumWorkers(1);
            solverA.getParameters().setFixVariablesToTheirHintedValue(true);
            solverA.getParameters().setMaxTimeInSeconds(Math.min(30, Math.max(5, tier3LimitSec)));
            activeSolver = solverA;
            CpSolverStatus stA = solverA.solve(mc3.model());
            solverLog.append(String.format("SINGLE_PHASE 2-stage A (pin hint, 1w): %s\n", stA));
            if (stA == CpSolverStatus.OPTIMAL || stA == CpSolverStatus.FEASIBLE) {
                // Re-hint Stage B from Stage A's concrete solution (complete, feasible).
                Map<String,Long> aSol = extractHints(solverA, mc3.varFactory(), residents, rotations, totalBlocks);
                // rebuild a fresh model so the pin constraints from Stage A don't carry over
                ModelContext mcB = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
                    eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);
                ObjectiveFunctionBuilder objB = new ObjectiveFunctionBuilder(
                    mcB.model(), mcB.varFactory(), config, totalBlocks, auxCoverage);
                IntVar t1B = objB.buildTier1Counter(residents, rotations); mcB.model().addEquality(t1B, 0);
                IntVar t2B = objB.buildTier2Core(residents, rotations);    mcB.model().addEquality(t2B, 0);
                IntVar patB = objB.buildPatternObjective(residents, rotations);
                IntVar sunB = objB.buildSundayCoverageObjective(residents);
                IntVar catB = objB.buildCategoricalSoftCapObjective(residents, rotations);
                mcB.model().minimize(LinearExpr.newBuilder()
                    .add(patB)
                    .addTerm(sunB, config.getWeightSundayCoverage())
                    .addTerm(catB, config.getWeightCategoricalSoftExcess()).build());
                applyHints(mcB.model(), mcB.varFactory(), residents, rotations, totalBlocks, aSol);
                solver3 = new CpSolver();
                solver3.getParameters().setNumWorkers(p3Workers);
                solver3.getParameters().setRepairHint(false);   // hint is already feasible → no repair → no #5025
                if (tier3LimitSec > 0) solver3.getParameters().setMaxTimeInSeconds(tier3LimitSec);
                solver3.getParameters().setLogToStdout(p3SolverLog);
                solver3.getParameters().setLogSearchProgress(p3SolverLog);
                activeSolver = solver3;
                solverLog.append(String.format("SINGLE_PHASE 2-stage B (optimize from incumbent, %dw, repairHint OFF)\n", p3Workers));
                status3 = (traj != null) ? solver3.solve(mcB.model(), traj) : solver3.solve(mcB.model());
                mc3 = mcB;   // extraction below reads mc3.varFactory()
            } else {
                solverLog.append("SINGLE_PHASE 2-stage A failed to pin the hint — falling back.\n");
                status3 = stA;
            }
        } else if (traj != null) {
            status3 = solver3.solve(mc3.model(), traj);
        } else {
            status3 = solver3.solve(mc3.model());
        }
        if (traj != null) traj.close();
        activeSolver = null;
        solverLog.append(String.format("Phase 3 result: %s  (%.1fs)\n",
            status3, (System.currentTimeMillis() - startMs) / 1000.0));

        // ── 5. Extract best available solution (Phase 3 > Phase 2 > Phase 1 > Phase 0) ──
        ScheduleSolution solution;
        String phaseLabel;
        if (status3 == CpSolverStatus.FEASIBLE || status3 == CpSolverStatus.OPTIMAL) {
            solution   = extractSolution(solver3, status3, mc3.varFactory(), residents, rotations, totalBlocks, feasReport, startMs);
            phaseLabel = "Phase 3";
        } else if (status2 == CpSolverStatus.FEASIBLE || status2 == CpSolverStatus.OPTIMAL) {
            solution   = extractSolution(solver2, status2, mc2.varFactory(), residents, rotations, totalBlocks, feasReport, startMs);
            phaseLabel = "Phase 2";
        } else if (p1.feasible()) {
            solution   = extractSolution(p1.solver(), p1.status(), p1.varFactory(), residents, rotations, totalBlocks, feasReport, startMs);
            phaseLabel = "Phase 1";
        } else if (p0.feasible()) {
            solution   = extractSolution(p0.solver(), p0.status(), p0.varFactory(), residents, rotations, totalBlocks, feasReport, startMs);
            phaseLabel = "Phase 0";
        } else {
            solution = new ScheduleSolution();
            solution.setStatus(ScheduleSolution.Status.UNKNOWN);
            solution.setFeasibilityReport(feasReport);
            phaseLabel = "none";
        }

        onProgress.accept(String.format("Solver complete — committing %s result [%s]…",
            phaseLabel, solution.getStatus()));

        // Post-solve validation
        if (solution.isFeasible()) {
            ScheduleValidator validator = new ScheduleValidator(config, totalBlocks);
            ScheduleValidator.ValidationResult vr = validator.validate(solution, residents, rotations);
            solverLog.append(vr.formatReport());
            if (vr.hasFailed()) onProgress.accept("⚠ Post-solve validation failures — see solver log.");

            // Constraint score breakdown
            SolutionScoreReporter reporter = new SolutionScoreReporter(config, totalBlocks, auxCoverage);
            solverLog.append(reporter.buildReport(solution, residents, rotations));
        }

        commitToDB(solution, year, blocks, residents, rotations);
        if (solution.isFeasible()) {
            new AuxFillerService().run(year, blocks, rotations, config, solverLog);
        }
        solution.setSolverLog(solverLog.toString());
        configDAO.saveSolverRun(year, "CP-SAT",
            solution.getStatus().name(), null, null,
            (int) -solution.getObjectiveValue(),
            solution.getRuntimeMs(), solution.isFeasible(),
            solution.statusSummary());

        // Per-seed outcome recording: if this full run warm-started from a cached seed and
        // produced a feasible schedule, record its final Tier scores against that seed (reward
        // data for the deferred exploit/prune policies). Best-effort; never breaks a solve.
        if (runSeedId != null && solution.isFeasible()) {
            try {
                int t1 = bestTier1 < Integer.MAX_VALUE ? bestTier1 : 0;
                int t2 = bestTier2 < Long.MAX_VALUE ? (int) bestTier2 : 0;
                int t3 = (int) Math.max(0, -solution.getObjectiveValue()); // Phase-3 objective proxy
                seedStatsDAO.recordOutcome(runSeedId, t1, t2, t3);
                solverLog.append(String.format(
                    "Seed %s outcome recorded: Tier1=%d Tier2=%d Tier3=%d.\n",
                    runSeedId.substring(0, 8), t1, t2, t3));
            } catch (SQLException e) {
                LOG.log(java.util.logging.Level.WARNING, "seed outcome record failed", e);
            }
        }

        activeSolver = null;
        bestSolution = solution;
        return solution;
    }

    public void requestStop() {
        stopRequested.set(true);
        CpSolver s = activeSolver;
        if (s != null) s.stopSearch();
    }

    public ScheduleSolution getBestSolution() { return bestSolution; }

    // ══════════════════════════════════════════════════════════════════════
    //  Base model builder — Tier-0 hard constraints (called once per phase)
    // ══════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════
    //  Phase-0 decomposition (staged warm-start over time-sliced windows)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Window slot-ranges for each decomposition mode, in SOLVE ORDER. Block 13 (slots 24-25)
     * is anchored FIRST in every mode: it carries the only solver-side per-block special (a
     * hard 2-categorical Y7D floor) and a Y7D-cannot-follow-GI/ID seam, so placing it while
     * the schedule is wide open and carrying it forward as a hint lets the seam self-repair.
     * Each int[] is {startSlotInclusive, endSlotExclusive}. See PHASE0_DECOMPOSITION_PLAN.md.
     */
    private static List<int[]> decompWindows(String mode, int totalBlocks) {
        List<int[]> w = new ArrayList<>();
        switch (mode) {
            case "roll3", "hardroll3", "roll3seed" -> {
                w.add(new int[]{24, 26});                       // b13 anchored first
                w.add(new int[]{0, 6});                         // b1-3
                w.add(new int[]{6, 12});                        // b4-6
                w.add(new int[]{12, 18});                       // b7-9
                w.add(new int[]{18, 24});                       // b10-12
            }
            case "roll1", "hardroll1", "roll1seed" -> {
                w.add(new int[]{24, 26});                       // b13 anchored first
                for (int s = 0; s < 24; s += 2) w.add(new int[]{s, s + 2}); // b1..b12
            }
            default -> { return null; }                        // unknown mode → no decomp
        }
        // Defensive clamp: if the grid isn't the canonical 26 slots, drop out-of-range windows.
        w.removeIf(rng -> rng[0] >= totalBlocks);
        for (int[] rng : w) rng[1] = Math.min(rng[1], totalBlocks);
        return w;
    }

    /**
     * Solves Phase 0 by staged solving over the windows from {@link #decompWindows}. Two
     * families, selected by mode prefix:
     *
     * <ul>
     * <li><b>roll3 / roll1 (hint-only):</b> every window solves the FULL model; prior windows
     *     are carried forward as name-keyed {@code addHint} (NOT hard fixes), so the win is
     *     warm-starting, not shrinkage. SMOKE-FAILED: window 1 is a cold full solve. Kept for
     *     reference.</li>
     * <li><b>hardroll3 / hardroll1 (Approach #1 — TRUE sub-problems):</b> after a window is
     *     solved, its slots join a growing FROZEN set that is hard-{@code addEquality}-fixed to
     *     carried values in every later window's model. Presolve eliminates the fixed vars, so
     *     each step genuinely SHRINKS (only the current + still-unprocessed windows are free)
     *     and {@code stopAfterFirstSolution} just needs one feasible completion of the free
     *     tail — fast because the tail is wide open. On a window returning INFEASIBLE/UNKNOWN we
     *     BACKTRACK: unfreeze the previous window and re-solve the pair together (depth-capped);
     *     if backtrack is exhausted, return {@code null} → monolithic fallback (never regress).
     *     This is the packed-year corner mitigation the plan calls for.</li>
     * </ul>
     *
     * @return the final window's solve as a {@link DecompResult}, or {@code null} on an
     *         unrecoverable corner so the caller can fall back to the monolithic Phase-0 solve
     *         and never regress.
     */
    private DecompResult solvePhase0Decomp(
            String mode, List<Resident> residents, List<Rotation> rotations, ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            int totalBlocks,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int tier0LimitSec,
            java.util.function.Consumer<String> onProgress,
            StringBuilder solverLog, long startMs) {

        List<int[]> windows = decompWindows(mode, totalBlocks);
        if (windows == null || windows.isEmpty()) {
            solverLog.append(String.format("\nPhase 0 decomp: unknown mode '%s' — skipping.\n", mode));
            return null;
        }

        final boolean hardFix = mode.startsWith("hard");
        // "seed" variants (roll3seed/roll1seed): hint-based, but window 1 gets the FULL budget
        // and is warm-started with buildGreedySeedHints — Approach #3's fix for the cold
        // window-1 that smoke-killed plain roll3 (greedy-seeded full-model Phase 0 measured
        // ~91s vs ~248s monolithic). Subsequent windows carry the prior solve forward as hints.
        final boolean seedWindow1 = mode.endsWith("seed");

        // Per-window budget.
        //  • Plain hint modes (roll3/roll1): split the cap evenly (each window solves the FULL
        //    model, so an even split keeps total wall time ~comparable to monolithic).
        //  • Hard-fix modes and seed modes: give EVERY window the FULL cap. The early windows
        //    have the largest free region (window 1 ≈ monolithic-with-stop-after-first) while
        //    later windows are warm-started/shrunk and finish fast, so the TOTAL can still beat
        //    monolithic WITHOUT starving the cold first window — the upside-down-staging failure
        //    of the even split (window 1 capped at cap/N). Each window self-limits via
        //    stopAfterFirstSolution, so the full cap is a ceiling, not a spend. 0/neg → unlimited.
        int perWindowSec = tier0LimitSec <= 0 ? 0
            : (hardFix || seedWindow1) ? tier0LimitSec
            : Math.max(30, tier0LimitSec / windows.size());
        // Backtrack depth cap (hard-fix only): how many already-frozen windows we may unfreeze
        // to escape a corner before giving up to the monolithic fallback.
        final int MAX_BACKTRACK = 3;

        // carried = full assignment of every successfully-solved window so far (var name → 0/1).
        Map<String, Long> carried = new HashMap<>();
        // Snapshot of `carried` taken BEFORE each window was solved, so a backtrack can restore
        // the state to "as if window k had not run" and re-solve it with a larger free region.
        List<Map<String, Long>> carriedBefore = new ArrayList<>();
        // Slots that are currently hard-frozen (union of solved windows, minus any unfrozen
        // by an in-flight backtrack). Only meaningful when hardFix.
        Set<Integer> frozenSlots = new HashSet<>();

        // Global wall budget for the WHOLE decomposition so per-window full caps (+ backtracks)
        // can't run away. Allow up to 2× the Phase-0 cap total: window 1 may spend a full cap
        // (worst case ≈ monolithic), leaving headroom for the cheap shrunk tail + limited
        // backtrack. Once exhausted, the next window's limit shrinks to the remainder and a
        // capped window then triggers the fallback. (P1/P2/P3 are tiny in the isolation harness,
        // so startMs ≈ Phase-0 start; for a full 4-phase run this just caps Phase-0 generously.)
        final long globalBudgetMs = tier0LimitSec > 0 ? 2L * tier0LimitSec * 1000L : Long.MAX_VALUE;

        ModelContext mc = null;
        CpSolver solver = null;
        CpSolverStatus status = CpSolverStatus.UNKNOWN;
        int backtracksUsed = 0;

        int wi = 0;
        while (wi < windows.size()) {
            if (stopRequested.get()) {
                solverLog.append("\nPhase 0 decomp: stop requested — aborting decomposition.\n");
                return null;
            }
            int[] rng = windows.get(wi);
            // Clamp this window's limit to the global remaining budget.
            int windowLimit = perWindowSec;
            if (tier0LimitSec > 0) {
                long remainingMs = globalBudgetMs - (System.currentTimeMillis() - startMs);
                int remainingSec = (int) Math.max(0, remainingMs / 1000);
                if (remainingSec <= 0) {
                    solverLog.append(String.format(
                        "  decomp=%s window %d: global Phase-0 budget exhausted — monolithic fallback.\n",
                        mode, wi + 1));
                    return null;
                }
                windowLimit = Math.min(perWindowSec, remainingSec);
            }
            onProgress.accept(String.format(
                "Phase 0 ▶ decomp=%s window %d/%d (slots %d–%d, %s, limit %ds)…",
                mode, wi + 1, windows.size(), rng[0], rng[1] - 1,
                hardFix ? "hard-fix" : "hint", windowLimit > 0 ? windowLimit : tier0LimitSec));

            mc = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
                eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);

            // Remember the pre-solve state of this window so backtrack can rewind to it.
            if (carriedBefore.size() <= wi) carriedBefore.add(new HashMap<>(carried));
            else carriedBefore.set(wi, new HashMap<>(carried));

            int fixedCount = 0;
            if (hardFix) {
                // TRUE sub-problem: hard-fix every frozen slot to its carried value (presolve
                // eliminates them) and leave the current + still-unprocessed windows free.
                fixedCount = fixFrozenSlots(mc.model(), mc.varFactory(), residents, rotations,
                    totalBlocks, rotationLengths, carried, frozenSlots);
                // Still warm-start the free tail from any carried values it has (cheap, helps
                // the seam). addHint never constrains, so this can't fight the hard fixes.
                if (!carried.isEmpty())
                    applyHints(mc.model(), mc.varFactory(), residents, rotations, totalBlocks, carried);
            } else if (!carried.isEmpty()) {
                // Hint-only mode: warm-start the full model from everything decided so far.
                applyHints(mc.model(), mc.varFactory(), residents, rotations, totalBlocks, carried);
            } else if (seedWindow1 && wi == 0) {
                // Seed variants: window 1 has nothing carried yet (cold). Warm-start it with the
                // greedy round-robin seed so the first full-model solve isn't a blank-slate
                // search — the fix for the cold window-1 that smoke-killed plain roll3.
                Map<String, Long> greedySeed = buildGreedySeedHints(
                    residents, rotations, mc.varFactory(), rotationLengths, eligibleByRotation, totalBlocks);
                if (!greedySeed.isEmpty())
                    applyHints(mc.model(), mc.varFactory(), residents, rotations, totalBlocks, greedySeed);
            }

            // Stop at the first feasible solution: we only need a feasible incumbent to carry
            // forward, not optimality (matches the Phase-0 handoff contract).
            solver = configureSolver(config, windowLimit);
            solver.getParameters().setStopAfterFirstSolution(true);

            activeSolver = solver;
            status = solver.solve(mc.model());
            activeSolver = null;

            solverLog.append(String.format(
                "  decomp=%s window %d/%d → %s  (frozen=%d slots, %d vars fixed, %.1fs total)\n",
                mode, wi + 1, windows.size(), status, frozenSlots.size(), fixedCount,
                (System.currentTimeMillis() - startMs) / 1000.0));

            boolean solved = status == CpSolverStatus.FEASIBLE || status == CpSolverStatus.OPTIMAL;

            if (solved) {
                carried = extractHints(solver, mc.varFactory(), residents, rotations, totalBlocks);
                if (hardFix) for (int b = rng[0]; b < rng[1]; b++) frozenSlots.add(b);
                wi++;
                continue;
            }

            // ── Not solved (INFEASIBLE or UNKNOWN/capped) ────────────────────────────────
            if (!hardFix) {
                // Hint mode keeps its original honest behavior: an INFEASIBLE window means the
                // FULL model is infeasible (hints add no constraints) → report via normal path;
                // a capped window is an unrecoverable corner → monolithic fallback.
                if (status == CpSolverStatus.INFEASIBLE)
                    return new DecompResult(mc, solver, status);
                solverLog.append(String.format(
                    "  decomp=%s window %d capped (%s) — falling back to monolithic.\n",
                    mode, wi + 1, status));
                return null;
            }

            // Hard-fix mode: a corner here may be caused by the FROZEN prefix wedging the free
            // tail (packed-year risk). BACKTRACK — unfreeze the previous window and re-solve it
            // together with this one (its slots become free again). Cap the total backtracks.
            if (wi == 0 || backtracksUsed >= MAX_BACKTRACK) {
                solverLog.append(String.format(
                    "  decomp=%s window %d unrecoverable (%s) after %d backtracks — monolithic fallback.\n",
                    mode, wi + 1, status, backtracksUsed));
                return null;
            }
            backtracksUsed++;
            int prev = wi - 1;
            int[] prevRng = windows.get(prev);
            for (int b = prevRng[0]; b < prevRng[1]; b++) frozenSlots.remove(b);
            carried = new HashMap<>(carriedBefore.get(prev));  // rewind to before prev was solved
            solverLog.append(String.format(
                "  decomp=%s window %d corner (%s) → backtrack #%d: unfreeze window %d (slots %d–%d), re-solve.\n",
                mode, wi + 1, status, backtracksUsed, prev + 1, prevRng[0], prevRng[1] - 1));
            wi = prev;  // re-solve the previous window with the larger free region, then this one
        }

        // Final window's solve IS the Phase-0 feasible assignment handed to Phase 1.
        return new DecompResult(mc, solver, status);
    }

    private ModelContext buildBaseModel(
            List<Resident> residents, List<Rotation> rotations,
            ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            int totalBlocks,
            Map<Integer, Map<Integer, Integer>> auxCoverage) {

        CpModel model = new CpModel();
        VariableFactory varFactory = new VariableFactory(model, totalBlocks, rotationLengths);
        varFactory.createAll(residents, rotations, eligibleByRotation);

        BlockExpansionService bes = new BlockExpansionService(model, varFactory, totalBlocks, rotationLengths);
        bes.applyAll(residents, rotations);
        bes.applyNoOverlapAcrossRotations(residents, rotations);

        Map<Integer, Rotation> rotById = rotations.stream()
            .collect(Collectors.toMap(Rotation::getId, r -> r));

        ConstraintBuilder cb = new ConstraintBuilder(model, varFactory, config, totalBlocks, auxCoverage);
        // Apply every toggleable hard constraint, in the canonical order. The same
        // ordered list drives the stepwise and removal diagnostics, so they can
        // never disagree about which constraint is which (PROJECT.md Code review, M1).
        for (ConstraintStep step : orderedConstraintSteps(residents, rotations, reqMap, prereqMap, seqMap, rotById)) {
            step.apply().accept(cb);
        }

        return new ModelContext(model, varFactory);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Hint extraction / application (warm-starting between phases)
    // ══════════════════════════════════════════════════════════════════════

    private Map<String, Long> extractHints(CpSolver solver, VariableFactory varFactory,
            List<Resident> residents, List<Rotation> rotations, int totalBlocks) {
        Map<String, Long> hints = new HashMap<>();
        // Per-variable reads can legitimately fail (e.g. a var not in this phase's
        // model); those are counted and reported once rather than logged per element.
        int[] failures = {0};
        try {
            for (Resident r : residents) {
                for (Rotation s : rotations) {
                    for (int w = 0; w < totalBlocks; w++) {
                        BoolVar occ = varFactory.getOccupancyVar(r.getId(), s.getId(), w);
                        if (occ != null) {
                            try { hints.put(occ.getName(), solver.booleanValue(occ) ? 1L : 0L); }
                            catch (Exception e) { failures[0]++; }
                        }
                    }
                    varFactory.getStartVars(r.getId(), s.getId()).forEach((week, sv) -> {
                        try { hints.put(sv.getName(), solver.booleanValue(sv) ? 1L : 0L); }
                        catch (Exception e) { failures[0]++; }
                    });
                }
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "Hint extraction aborted early", e);
        }
        if (failures[0] > 0)
            LOG.warning("Hint extraction: " + failures[0] + " variable reads failed (warm-start may be partial).");
        return hints;
    }

    private void applyHints(CpModel model, VariableFactory varFactory,
            List<Resident> residents, List<Rotation> rotations, int totalBlocks,
            Map<String, Long> hints) {
        if (hints.isEmpty()) return;
        for (Resident r : residents) {
            for (Rotation s : rotations) {
                for (int w = 0; w < totalBlocks; w++) {
                    BoolVar occ = varFactory.getOccupancyVar(r.getId(), s.getId(), w);
                    if (occ != null) {
                        Long v = hints.get(occ.getName());
                        if (v != null) model.addHint(occ, v);
                    }
                }
                varFactory.getStartVars(r.getId(), s.getId()).forEach((week, sv) -> {
                    Long v = hints.get(sv.getName());
                    if (v != null) model.addHint(sv, v);
                });
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Phase-0 FIX options (post-decomp investigation; env-gated via PHASE0_FIX)
    //  Each attacks the COLD first-feasibility search directly rather than
    //  decomposing the (globally-coupled, order-free) problem, which dead-ended.
    // ══════════════════════════════════════════════════════════════════════

    // --- Option 1: cache & replay feasible assignment(s) ---------------------
    // The Phase-0 model is byte-identical every run, so any feasible assignment
    // found once stays feasible forever. We persist a POOL of distinct feasible
    // assignments (not just one) under schedule_config and replay a RANDOM one
    // as warm-start hints each run, so Phase 1 is not perpetually biased toward a
    // single starting basin. The pool grows: each cold solve that finds a NEW
    // (not-yet-cached) assignment appends it, up to PHASE0_CACHE_POOL_MAX.

    private static final String CACHE_KEY_PREFIX = "phase0_feasible_pool_";
    private static final String CACHE_REC_SEP = "␞";   // record separator between pooled assignments
    private static final int CACHE_POOL_MAX_DEFAULT = 25;

    private int cachePoolMax() {
        try { return Math.max(1, Integer.parseInt(System.getenv().getOrDefault("PHASE0_CACHE_POOL_MAX", ""))); }
        catch (NumberFormatException e) { return CACHE_POOL_MAX_DEFAULT; }
    }

    /** Loads the cached feasible-assignment REPLAY pool for {@code year} (the small capped blob; may
     *  be empty). On first load it lazily backfills the legacy blob into the new unbounded per-seed
     *  store so existing pools (built before per-seed storage) become loadable by id without a wipe. */
    private List<Map<String, Long>> loadCachedFeasiblePool(int year) {
        try {
            String blob = configDAO.loadRawValue(CACHE_KEY_PREFIX + year);
            if (blob == null || blob.isBlank()) return new ArrayList<>();
            List<Map<String, Long>> pool = new ArrayList<>();
            for (String rec : blob.split(CACHE_REC_SEP)) {
                if (!rec.isBlank()) pool.add(deserializeHints(rec));
            }
            backfillSeedAssignments(year, pool);
            return pool;
        } catch (SQLException e) {
            LOG.log(java.util.logging.Level.WARNING, "Phase-0 cache load failed", e);
            return new ArrayList<>();
        }
    }

    /** One-time migration: if the per-seed assignment store is empty for the year but the legacy
     *  replay blob holds seeds, copy each blob assignment into phase0_seed_assignments so prior
     *  pools are harvestable by id. save() is idempotent, so this is safe to call on every load;
     *  the empty-store guard keeps it a no-op once done. Never wipes anything. */
    private void backfillSeedAssignments(int year, List<Map<String, Long>> pool) {
        if (pool.isEmpty()) return;
        try {
            if (seedAssignmentDAO.count(year) > 0) return;   // already migrated
            for (Map<String, Long> a : pool) {
                seedAssignmentDAO.save(seedId(a), year, serializeHints(a));
            }
            LOG.info("Backfilled " + pool.size() + " legacy pool seed(s) into phase0_seed_assignments for year " + year);
        } catch (SQLException e) {
            LOG.log(java.util.logging.Level.WARNING, "per-seed assignment backfill failed", e);
        }
    }

    /**
     * Returns ONE assignment from the cached pool to warm-start this run. Selection mode via
     * env {@code PHASE0_SEED_SELECT}:
     * <ul>
     *   <li><b>random</b> (default) — uniform random draw; different basin each run.</li>
     *   <li><b>roundrobin</b> — coverage-first: pick the least-used / least-recently-used seed
     *       (per phase0_seed_stats), so every seed is consumed before any repeat. Marks the
     *       chosen seed used. Backfills any untracked pooled seed first so existing pools work.</li>
     * </ul>
     * Null if the pool is empty. Both modes return the SAME assignment objects — only the
     * choice policy differs.
     */
    private Map<String, Long> loadCachedFeasibleHints(int year) {
        List<Map<String, Long>> pool = loadCachedFeasiblePool(year);
        if (pool.isEmpty()) return null;
        // PHASE0_SEED_ID pins ONE specific seed by id (full hash or any unique prefix, e.g. the
        // 8-char id shown in logs/CSV). Used to REPEAT the same seed across runs — e.g. the seed→seed
        // variance / ICC experiment — which roundrobin/random can't do (they deliberately spread).
        // Overrides PHASE0_SEED_SELECT. If the id matches no pooled seed, we log and fall through to
        // the normal selection rather than silently solving the wrong seed.
        String pinId = System.getenv().getOrDefault("PHASE0_SEED_ID", "").trim().toLowerCase();
        if (!pinId.isEmpty()) {
            // First check the small replay pool (covers seeds still in the capped blob), then fall
            // back to the UNBOUNDED per-seed store so ANY inventoried seed is loadable by id — this
            // is what lets harvest pin any of a million seeds, not just the handful in the replay set.
            for (Map<String, Long> a : pool) {
                if (seedId(a).startsWith(pinId)) return a;
            }
            try {
                String stored = seedAssignmentDAO.load(pinId, year);
                if (stored != null && !stored.isBlank()) return deserializeHints(stored);
            } catch (SQLException e) {
                LOG.log(java.util.logging.Level.WARNING, "per-seed assignment load failed for " + pinId, e);
            }
            LOG.warning("PHASE0_SEED_ID=" + pinId + " matched no inventoried seed — using normal selection.");
        }
        String mode = System.getenv().getOrDefault("PHASE0_SEED_SELECT", "random").trim().toLowerCase();
        if (mode.equals("roundrobin")) {
            try {
                // Backfill: ensure every pooled seed is tracked (registers IDs for pools built
                // before tracking existed), then pick the least-used and mark it used.
                Map<String, Map<String, Long>> byId = new HashMap<>();
                for (Map<String, Long> a : pool) {
                    String id = seedId(a);
                    byId.put(id, a);
                    seedStatsDAO.ensureSeed(id, year);
                }
                String pick = seedStatsDAO.pickRoundRobin(year);
                if (pick != null && byId.containsKey(pick)) {
                    seedStatsDAO.markUsed(pick);
                    return byId.get(pick);
                }
            } catch (SQLException e) {
                LOG.log(java.util.logging.Level.WARNING, "round-robin seed select failed — falling back to random", e);
            }
            // fall through to random on any issue
        }
        return pool.get(new Random().nextInt(pool.size()));
    }

    /**
     * Appends {@code found} to the cached pool IFF it is distinct from every assignment
     * already cached (so the pool accumulates genuine variety, not duplicates), capped
     * at {@link #cachePoolMax()}. Keying by occupancy fingerprint keeps the comparison
     * cheap and order-independent.
     */
    private void saveCachedFeasibleHints(int year, Map<String, Long> found, StringBuilder solverLog) {
        if (found == null || found.isEmpty()) return;
        try {
            List<Map<String, Long>> pool = loadCachedFeasiblePool(year);
            String fp = fingerprint(found);
            for (Map<String, Long> existing : pool) {
                if (fingerprint(existing).equals(fp)) {
                    solverLog.append(String.format(
                        "Phase 0 FIX=cache: assignment already in pool (%d cached) — not re-adding.\n", pool.size()));
                    return; // already have this one
                }
            }
            // Saturation monitor: distance from this NEW seed to its closest existing pool member,
            // computed BEFORE adding it (so `pool` here excludes `found`). Exact-dedup re-discovery
            // (delta=0) is a near-useless saturation signal at ~900 placements; a shrinking
            // nearest-neighbor distance over insertion order is the real "packing" signal. Reuses
            // hammingPlacements. -1 = first seed (no neighbor). See SEED_POOL_STATS_IMPLEMENTATION_PLAN.md.
            int nnDist = -1;
            for (Map<String, Long> existing : pool) {
                int d = hammingPlacements(found, existing);
                nnDist = (nnDist < 0) ? d : Math.min(nnDist, d);
            }
            String sid = seedId(found);
            // (1) UNBOUNDED inventory: always store this seed's assignment per-seed and register it
            //     in phase0_seed_stats, regardless of the replay-pool cap. This is what makes "generate
            //     as many seeds as you want" work — the cap below only bounds the warm-start REPLAY
            //     blob, never the inventory. Harvest loads any seed by id from phase0_seed_assignments.
            try { seedAssignmentDAO.save(sid, year, serializeHints(found)); }
            catch (SQLException e) { LOG.log(java.util.logging.Level.WARNING, "seed-assignment store failed", e); }
            try { seedStatsDAO.ensureSeed(sid, year, nnDist); }
            catch (SQLException e) { LOG.log(java.util.logging.Level.WARNING, "seed-stats register failed", e); }

            // (2) BOUNDED replay blob: only append to the legacy warm-start pool while it is under the
            //     cap, so deserializing it on every solve stays cheap. Past the cap the seed is still
            //     fully inventoried above; it just doesn't enter the small replay set.
            if (pool.size() >= cachePoolMax()) {
                solverLog.append(String.format(
                    "Phase 0 FIX=cache: replay pool full (%d/%d) — seed %s inventoried but not added to replay set.\n",
                    pool.size(), cachePoolMax(), sid.substring(0, 8)));
                return;
            }
            pool.add(found);
            StringBuilder blob = new StringBuilder();
            for (int i = 0; i < pool.size(); i++) {
                if (i > 0) blob.append(CACHE_REC_SEP);
                blob.append(serializeHints(pool.get(i)));
            }
            configDAO.saveRawValue(CACHE_KEY_PREFIX + year, blob.toString());
            solverLog.append(String.format(
                "Phase 0 FIX=cache: cached new feasible assignment (replay pool now %d/%d; inventory unbounded).\n",
                pool.size(), cachePoolMax()));
        } catch (SQLException e) {
            LOG.log(java.util.logging.Level.WARNING, "Phase-0 cache save failed", e);
        }
    }

    /** Order-independent fingerprint of the SET vars (occupancy = 1) in an assignment. */
    private static String fingerprint(Map<String, Long> hints) {
        return hints.entrySet().stream()
            .filter(e -> e.getValue() != null && e.getValue() != 0L)
            .map(Map.Entry::getKey).sorted().collect(Collectors.joining(","));
    }

    /**
     * Stable, content-based seed ID = SHA-256(fingerprint). Hex (64 chars), collision-free in
     * practice, scales to thousands+ of seeds with no reuse. Same schedule → same ID (dedup-
     * consistent). Used as the {@code seed_id} key in phase0_seed_stats.
     */
    private static String seedId(Map<String, Long> assignment) {
        String fp = fingerprint(assignment);
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(fp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(fp.hashCode());
        }
    }

    /** Serialize as "name=val;name=val;…". Var names contain no ';' or '='-value chars. */
    private static String serializeHints(Map<String, Long> hints) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : hints.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, Long> deserializeHints(String s) {
        Map<String, Long> m = new HashMap<>();
        for (String tok : s.split(";")) {
            int eq = tok.lastIndexOf('=');
            if (eq > 0) {
                try { m.put(tok.substring(0, eq), Long.parseLong(tok.substring(eq + 1))); }
                catch (NumberFormatException ignore) { /* skip malformed token */ }
            }
        }
        return m;
    }

    // --- Cache integrity: model fingerprint + feasibility re-validation ------
    // The pool is feasible for the model that PRODUCED it. If the model later changes
    // (a rule/cap/requirement edited in the DB), pooled entries may become stale, and
    // because replay uses addHint+repairHint (NOT hard fixes) a stale entry would be
    // silently repaired rather than rejected. We guard against that two ways:
    //   (1) MODEL FINGERPRINT — a hash of every structural input to buildBaseModel,
    //       stored alongside the pool. On mismatch we know the model changed.
    //   (2) RE-VALIDATION — hard-addEquality each pooled assignment into a FRESH model
    //       and solve; FEASIBLE = genuinely valid against the CURRENT model, INFEASIBLE =
    //       stale → evict. This is a proof, not a heuristic (CP-SAT FEASIBLE is a proof).

    private static final String CACHE_FP_PREFIX = "phase0_feasible_pool_fp_";

    /**
     * SHA-256 over every input that determines the Phase-0 model's feasibility:
     * residents (id+pgy), rotations (id), per-resident requirements, eligibility,
     * rotation lengths, sequence rules, totalBlocks, and the feasibility-relevant
     * config fields. Order-stable (sorted) so identical models always hash identically.
     */
    private String computeModelFingerprint(List<Resident> residents, List<Rotation> rotations,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            int totalBlocks, ScheduleConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("blocks=").append(totalBlocks).append('|');
        residents.stream().sorted(Comparator.comparingInt(Resident::getId)).forEach(r ->
            sb.append("R").append(r.getId()).append(':').append(r.getPgyLevel()).append(','));
        rotations.stream().sorted(Comparator.comparingInt(Rotation::getId)).forEach(s ->
            sb.append("S").append(s.getId()).append(','));
        new TreeMap<>(rotationLengths).forEach((rid, len) ->
            sb.append("L").append(rid).append('=').append(Arrays.toString(len)).append(','));
        new TreeMap<>(eligibleByRotation).forEach((sid, set) ->
            sb.append("E").append(sid).append('=').append(new TreeSet<>(set)).append(','));
        // requirements: resident → rotation → (min,max-ish summary via toString)
        new TreeMap<>(reqMap).forEach((rid, m) -> {
            sb.append("Q").append(rid).append('{');
            new TreeMap<>(m).forEach((sid, req) -> sb.append(sid).append(':').append(req).append(','));
            sb.append('}');
        });
        new TreeMap<>(seqMap).forEach((sid, rules) ->
            sb.append("SEQ").append(sid).append('=').append(rules).append(','));
        // Config fields that affect HARD feasibility (weights/soft terms excluded on purpose).
        sb.append("CFG:")
          .append(config.getGlobalMinWorkloadBlocks()).append('/')
          .append(config.getGlobalMaxWorkloadBlocks()).append('/')
          .append(config.isEnforceZeroVolunteerWeekends()).append('/')
          .append(config.isMaxConsecHeavyMediumHard()).append('/')
          .append(config.getMaxConsecutiveHeavyMediumWeeks()).append('/')
          .append(new TreeSet<>(config.getHeavyRotationIds()));
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode()); // fallback
        }
    }

    /**
     * Confirms ONE pooled assignment is feasible against the CURRENT model by hard-fixing
     * it into a fresh model and solving (stop-after-first; a feasible completion = valid).
     * Returns true iff FEASIBLE/OPTIMAL.
     */
    private boolean revalidateAssignment(Map<String, Long> assignment,
            List<Resident> residents, List<Rotation> rotations, ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            int totalBlocks, Map<Integer, Map<Integer, Integer>> auxCoverage) {
        ModelContext mc = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);
        // Hard-fix EVERY slot to the cached value (frozenSlots = all blocks).
        Set<Integer> allSlots = new HashSet<>();
        for (int b = 0; b < totalBlocks; b++) allSlots.add(b);
        fixFrozenSlots(mc.model(), mc.varFactory(), residents, rotations, totalBlocks,
            rotationLengths, assignment, allSlots);
        CpSolver solver = configureSolver(config, 30);
        solver.getParameters().setStopAfterFirstSolution(true);
        CpSolverStatus st = solver.solve(mc.model());
        return st == CpSolverStatus.FEASIBLE || st == CpSolverStatus.OPTIMAL;
    }

    /**
     * Before replaying from the pool: if the stored model fingerprint differs from the
     * current model's (or is missing), revalidate every pooled assignment against the
     * current model and EVICT any that no longer solve, then re-stamp the fingerprint.
     * No-op (fast path) when the fingerprint matches — the common case. This is what makes
     * the cache safe to keep across model edits.
     */
    private void ensurePoolFreshOrEvict(int year,
            List<Resident> residents, List<Rotation> rotations, ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            int totalBlocks, Map<Integer, Map<Integer, Integer>> auxCoverage,
            StringBuilder solverLog) {
        try {
            String current = computeModelFingerprint(residents, rotations, reqMap,
                eligibleByRotation, rotationLengths, seqMap, totalBlocks, config);
            String stored = configDAO.loadRawValue(CACHE_FP_PREFIX + year);
            if (current.equals(stored)) return; // model unchanged — fast path, no revalidation
            List<Map<String, Long>> pool = loadCachedFeasiblePool(year);
            if (pool.isEmpty()) { configDAO.saveRawValue(CACHE_FP_PREFIX + year, current); return; }
            solverLog.append(String.format(
                "Phase 0 FIX=cache: MODEL CHANGED (fingerprint mismatch) — revalidating %d pooled entries…\n", pool.size()));
            List<Map<String, Long>> kept = new ArrayList<>();
            int evicted = 0;
            for (Map<String, Long> a : pool) {
                if (revalidateAssignment(a, residents, rotations, config, reqMap, prereqMap,
                        eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage))
                    kept.add(a);
                else evicted++;
            }
            // Persist the cleaned pool + the new fingerprint.
            StringBuilder blob = new StringBuilder();
            for (int i = 0; i < kept.size(); i++) {
                if (i > 0) blob.append(CACHE_REC_SEP);
                blob.append(serializeHints(kept.get(i)));
            }
            configDAO.saveRawValue(CACHE_KEY_PREFIX + year, blob.toString());
            configDAO.saveRawValue(CACHE_FP_PREFIX + year, current);
            solverLog.append(String.format(
                "Phase 0 FIX=cache: revalidation done — kept %d, evicted %d stale entries.\n",
                kept.size(), evicted));
        } catch (SQLException e) {
            LOG.log(java.util.logging.Level.WARNING, "Pool revalidation failed", e);
        }
    }

    /**
     * Standalone pool audit (called by the PoolAudit tool): (1) re-validates every pooled
     * assignment against the current model and reports feasible/stale counts, optionally
     * evicting stale ones; (2) reports the pairwise Hamming-distance distribution as a
     * read-only DIVERSITY measure (min/median/max placements differing between pool entries).
     * Returns a human-readable report.
     */
    public String auditPool(int year, boolean evictStale) throws SQLException {
        ensureNativeLibraries();
        // Rebuild the same model inputs solve() builds (same DAO calls + map builders).
        ScheduleConfig config = configDAO.loadConfig();
        List<Resident> residents = residentDAO.getMainResidents();
        List<Resident> auxResidents = residentDAO.getAuxiliaryResidents();
        List<Rotation> rotations = rotationDAO.getAll();
        applyTierDefaults(config, rotations);
        int totalBlocks = ScheduleUnits.SLOTS_PER_YEAR;
        config.setTotalBlocks(totalBlocks);

        Map<Integer, int[]> rotationLengths = new HashMap<>();
        for (Rotation r : rotations) {
            ScheduleConfig.RotationPolicy policy = configDAO.loadRotationPolicy(r.getId());
            config.getRotationPolicies().put(r.getId(), policy);
            rotationLengths.put(r.getId(), policy.allowedBlockLengths);
        }
        Map<Integer, Map<Integer, RotationRequirement>> reqMap = buildReqMap(rulesDAO.getAllRequirements());
        Map<Integer, Set<Integer>> eligibleByRotation = buildEligibilityMap(residents, rotations, reqMap);
        Map<Integer, List<Prerequisite>> prereqMap = buildPrereqMap(rulesDAO.getAllPrerequisites());
        Map<Integer, List<RotationSequenceRule>> seqMap = buildSequenceRuleMap(rulesDAO.getAllSequenceRules());
        config.setRotationLinkRules(linkRuleDAO.getAll());

        List<Integer> auxIds = auxResidents.stream().map(Resident::getId).collect(Collectors.toList());
        Map<String, Set<Integer>> fillerRotationsByGroup = auxFillerRotationDAO.getAllFillerRotations();
        Set<String> fillerExclusions = buildFillerExclusions(auxResidents, fillerRotationsByGroup);
        Map<Integer, Map<Integer, Integer>> auxCoverage = auxIds.isEmpty()
            ? new HashMap<>()
            : new HashMap<>(assignmentDAO.getAuxiliaryCoverage(auxIds, year, fillerExclusions));
        applyAuxCoverageCredits(auxCoverage, rotations, auxResidents, config, totalBlocks);

        List<Map<String, Long>> pool = loadCachedFeasiblePool(year);
        StringBuilder rpt = new StringBuilder();
        rpt.append(String.format("=== Phase-0 pool audit (year=%d) ===%n", year));
        rpt.append(String.format("pool size: %d%n", pool.size()));
        if (pool.isEmpty()) return rpt.append("(empty pool — nothing to audit)\n").toString();

        // (1) Feasibility re-validation.
        List<Map<String, Long>> feasible = new ArrayList<>();
        int stale = 0;
        for (int i = 0; i < pool.size(); i++) {
            boolean ok = revalidateAssignment(pool.get(i), residents, rotations, config,
                reqMap, prereqMap, eligibleByRotation, rotationLengths, seqMap,
                totalBlocks, auxCoverage);
            rpt.append(String.format("  entry %2d: %s%n", i, ok ? "FEASIBLE" : "STALE (infeasible now)"));
            if (ok) feasible.add(pool.get(i)); else stale++;
        }
        rpt.append(String.format("feasible: %d   stale: %d%n", feasible.size(), stale));

        // (2) Diversity: pairwise Hamming distance over occupancy fingerprints.
        List<String> fps = new ArrayList<>();
        for (Map<String, Long> a : pool) fps.add(fingerprint(a));
        List<Integer> dists = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++)
            for (int j = i + 1; j < pool.size(); j++)
                dists.add(hammingPlacements(pool.get(i), pool.get(j)));
        if (!dists.isEmpty()) {
            Collections.sort(dists);
            int min = dists.get(0), max = dists.get(dists.size() - 1);
            int med = dists.get(dists.size() / 2);
            long dupPairs = dists.stream().filter(d -> d == 0).count();
            rpt.append(String.format(
                "diversity (pairwise placements differing): min=%d  median=%d  max=%d  (%d identical pairs)%n",
                min, med, max, dupPairs));
        }

        if (evictStale && stale > 0) {
            StringBuilder blob = new StringBuilder();
            for (int i = 0; i < feasible.size(); i++) {
                if (i > 0) blob.append(CACHE_REC_SEP);
                blob.append(serializeHints(feasible.get(i)));
            }
            configDAO.saveRawValue(CACHE_KEY_PREFIX + year, blob.toString());
            configDAO.saveRawValue(CACHE_FP_PREFIX + year,
                computeModelFingerprint(residents, rotations, reqMap, eligibleByRotation,
                    rotationLengths, seqMap, totalBlocks, config));
            rpt.append(String.format("evicted %d stale entries; pool now %d.%n", stale, feasible.size()));
        }
        return rpt.toString();
    }

    /** Count of (resident,rotation,block) placements present in exactly one of two assignments. */
    private static int hammingPlacements(Map<String, Long> a, Map<String, Long> b) {
        Set<String> sa = a.entrySet().stream().filter(e -> e.getValue() != null && e.getValue() != 0L)
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> sb = b.entrySet().stream().filter(e -> e.getValue() != null && e.getValue() != 0L)
            .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> union = new HashSet<>(sa); union.addAll(sb);
        Set<String> inter = new HashSet<>(sa); inter.retainAll(sb);
        return union.size() - inter.size(); // symmetric difference size
    }

    // --- Option 2: feasibility_jump-heavy portfolio --------------------------
    /**
     * Steers the CP-SAT worker portfolio toward {@code feasibility_jump} — the ONLY
     * subsolver observed to reach feasibility on this model (the 7 LP/optimization
     * workers never do). Instead of ~2 feasibility finders out of the default 10, run
     * the workers as feasibility-jump variants, multiplying independent feasibility
     * attempts per run. Param names differ across OR-Tools builds; set defensively and
     * log what took. Reversible (env-gated; default path never calls this).
     */
    private void applyFeasibilityJumpPortfolio(CpSolver solver, StringBuilder solverLog) {
        var p = solver.getParameters();
        // Naming subsolvers explicitly (clear_subsolvers / extra_subsolvers with literal
        // "fj_*" names) is rejected by CP-SAT 9.9 as MODEL_INVALID on this model — the exact
        // internal names aren't stable across builds. So bias toward feasibility WITHOUT
        // naming any subsolver: (a) turn feasibility_jump explicitly on (boolean — always
        // valid), (b) raise the worker count so the default portfolio runs MORE
        // feasibility-jump instances with distinct seeds, (c) feasibility-favoring search
        // params (probing off, polarity false). All plain scalars → never invalidates the model.
        int workers = Math.max(p.getNumWorkers(), 16);
        p.setNumWorkers(workers);
        p.setUseFeasibilityJump(true);
        p.setCpModelProbingLevel(0);
        p.setInitialPolarity(com.google.ortools.sat.SatParameters.Polarity.POLARITY_FALSE);
        solverLog.append(String.format(
            "Phase 0 FIX=fjportfolio: workers=%d, feasibility_jump on, probing off, polarity false.\n", workers));
    }

    // --- Option 3: multi-seed restart sequence -------------------------------
    /**
     * Runs short fresh-random-seed attempts back-to-back, stopping on the FIRST feasible
     * draw. Success on this model is essentially a draw of the portfolio, so K independent
     * attempts inside the same wall budget give K shots instead of one long one. Returns
     * the first FEASIBLE/OPTIMAL status, or the last status if none succeed.
     */
    private CpSolverStatus solveMultiSeed(CpSolver solver, ModelContext mc, int totalLimitSec,
            Consumer<String> onProgress, StringBuilder solverLog, long startMs) {
        int attempts = 4;
        try { attempts = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("PHASE0_MULTISEED_TRIES", ""))); }
        catch (NumberFormatException ignore) { /* default 4 */ }
        int perTry = totalLimitSec > 0 ? Math.max(15, totalLimitSec / attempts) : 0;
        CpSolverStatus status = CpSolverStatus.UNKNOWN;
        for (int k = 0; k < attempts; k++) {
            if (stopRequested.get()) break;
            // Clamp the final attempt to whatever wall budget remains.
            if (totalLimitSec > 0) {
                int elapsed = (int) ((System.currentTimeMillis() - startMs) / 1000);
                int remaining = totalLimitSec - elapsed;
                if (remaining <= 0) break;
                solver.getParameters().setMaxTimeInSeconds(Math.min(perTry, remaining));
            }
            solver.getParameters().setRandomSeed(1 + k * 7919); // distinct, deterministic seeds
            onProgress.accept(String.format("Phase 0 ▶ multiseed attempt %d/%d (seed=%d, ≤%ds)…",
                k + 1, attempts, 1 + k * 7919, perTry));
            status = solver.solve(mc.model());
            solverLog.append(String.format("  multiseed attempt %d/%d → %s  (%.1fs total)\n",
                k + 1, attempts, status, (System.currentTimeMillis() - startMs) / 1000.0));
            if (status == CpSolverStatus.FEASIBLE || status == CpSolverStatus.OPTIMAL
                    || status == CpSolverStatus.INFEASIBLE)
                break; // feasible → done; infeasible → model is infeasible, no seed will help
        }
        return status;
    }

    // --- Option 4: LNS fix-and-relax repair around a core --------------------
    /**
     * Hard-fixes the bulk of a cached (or greedy) assignment and leaves a free margin for
     * CP-SAT to repair in a single shot — a large-neighborhood search, NOT a staged window,
     * so it never hits the cold-window-1 trap that killed decomposition. Frees a random
     * subset of slots (default ~30%) so the repair region is non-trivial but small. If no
     * cached assignment exists, falls back to a greedy seed core.
     */
    private void applyLnsCore(CpModel model, VariableFactory varFactory,
            List<Resident> residents, List<Rotation> rotations, ScheduleConfig config,
            Map<Integer, int[]> rotationLengths, Map<Integer, Set<Integer>> eligibleByRotation,
            int totalBlocks, int year, Consumer<String> onProgress, StringBuilder solverLog) {
        Map<String, Long> core = loadCachedFeasibleHints(year);
        String src = "cache";
        if (core == null || core.isEmpty()) {
            core = buildGreedySeedHints(residents, rotations, varFactory,
                rotationLengths, eligibleByRotation, totalBlocks);
            src = "greedy";
        }
        if (core.isEmpty()) {
            solverLog.append("Phase 0 FIX=lns: no core available — plain solve.\n");
            return;
        }
        // Choose a random set of slots to leave FREE (the repair neighborhood).
        double freeFrac = 0.30;
        try { freeFrac = Double.parseDouble(System.getenv().getOrDefault("PHASE0_LNS_FREE_FRAC", "")); }
        catch (NumberFormatException ignore) { /* default 0.30 */ }
        Random rng = new Random();
        Set<Integer> freeSlots = new HashSet<>();
        for (int b = 0; b < totalBlocks; b++) if (rng.nextDouble() < freeFrac) freeSlots.add(b);
        Set<Integer> frozenSlots = new HashSet<>();
        for (int b = 0; b < totalBlocks; b++) if (!freeSlots.contains(b)) frozenSlots.add(b);

        int fixed = fixFrozenSlots(model, varFactory, residents, rotations, totalBlocks,
            rotationLengths, core, frozenSlots);
        // Warm-start the free region too (hints don't constrain, can't fight the hard fixes).
        applyHints(model, varFactory, residents, rotations, totalBlocks, core);
        onProgress.accept(String.format(
            "Phase 0 ▶ LNS repair (%s core): froze %d slots / %d vars, %d slots free.",
            src, frozenSlots.size(), fixed, freeSlots.size()));
        solverLog.append(String.format(
            "Phase 0 FIX=lns: core=%s, froze %d slots (%d vars), free=%d slots (freeFrac=%.2f).\n",
            src, frozenSlots.size(), fixed, freeSlots.size(), freeFrac));
    }

    // --- Option 5: presolve-bounded / search-first ---------------------------
    /**
     * Bounds or disables CP-SAT presolve so the SEARCH (where feasibility_jump lives) starts
     * immediately. {@code presolve0} disables presolve entirely; {@code presolveN} (N≥1) caps
     * presolve at N iterations. Risk: presolve sometimes SPEEDS search, so this can hurt good
     * draws — measured, not assumed. stopAfterFirstSolution is set by the caller.
     */
    private void applyPresolveBound(CpSolver solver, String mode, StringBuilder solverLog) {
        var p = solver.getParameters();
        String digits = mode.substring("presolve".length());
        int iters = 0;
        try { iters = digits.isEmpty() ? 0 : Integer.parseInt(digits); }
        catch (NumberFormatException ignore) { iters = 0; }
        if (iters <= 0) {
            p.setCpModelPresolve(false);
            solverLog.append("Phase 0 FIX=presolve0: presolve DISABLED (search starts immediately).\n");
        } else {
            p.setMaxPresolveIterations(iters);
            solverLog.append(String.format("Phase 0 FIX=presolve%d: presolve capped at %d iterations.\n", iters, iters));
        }
    }

    /**
     * HARD-FIX a subset of slots (Approach #1 — true sub-problems). For every occupancy var
     * whose block falls in {@code frozenSlots}, {@code addEquality(var, carriedValue)}; this
     * genuinely SHRINKS the search (CP-SAT presolve eliminates fixed vars) rather than merely
     * warm-starting it the way {@link #applyHints} does. Start vars whose ENTIRE rotation
     * placement is determined by frozen blocks are fixed too (a start at block t with the
     * whole [t, t+len) inside the frozen set). Blocks outside {@code frozenSlots} stay free.
     *
     * <p>Only vars present in {@code carried} are fixed; a slot with no carried value is left
     * free (so a partially-seeded freeze never over-constrains). Returns the number of
     * occupancy vars fixed (for diagnostics).
     */
    private int fixFrozenSlots(CpModel model, VariableFactory varFactory,
            List<Resident> residents, List<Rotation> rotations, int totalBlocks,
            Map<Integer, int[]> rotationLengths,
            Map<String, Long> carried, Set<Integer> frozenSlots) {
        if (carried.isEmpty() || frozenSlots.isEmpty()) return 0;
        int fixed = 0;
        for (Resident r : residents) {
            for (Rotation s : rotations) {
                for (int b = 0; b < totalBlocks; b++) {
                    if (!frozenSlots.contains(b)) continue;
                    BoolVar occ = varFactory.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ == null) continue;
                    Long v = carried.get(occ.getName());
                    if (v != null) { model.addEquality(occ, v); fixed++; }
                }
                // Fix start vars only when the whole placement lies inside the frozen region,
                // so a rotation that straddles the seam (start frozen, tail free) is not
                // over-fixed. A start var at block t with carried value 0 can always be fixed
                // (it asserts "no start here"); value 1 only when [t, t+len) ⊆ frozenSlots.
                for (var e : varFactory.getStartVars(r.getId(), s.getId()).entrySet()) {
                    int t = e.getKey();
                    BoolVar sv = e.getValue();
                    Long v = carried.get(sv.getName());
                    if (v == null) continue;
                    if (v == 0L) { model.addEquality(sv, 0L); continue; }
                    int[] lengths = rotationLengths.getOrDefault(s.getId(), new int[]{2});
                    boolean allFrozen = true;
                    int maxLen = lengths == null || lengths.length == 0 ? 1 : lengths[lengths.length - 1];
                    for (int b = t; b < t + maxLen && b < totalBlocks; b++)
                        if (!frozenSlots.contains(b)) { allFrozen = false; break; }
                    if (allFrozen) model.addEquality(sv, 1L);
                }
            }
        }
        return fixed;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Solver configuration
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Phase-0-specific solver configuration (Option C).
     *
     * Pure feasibility search benefits from different tuning than optimization phases:
     * - Probing (pre-solve constraint propagation) is expensive and mostly helps when
     *   the solver needs to prove optimality — not useful for "find any solution fast."
     * - Setting initial polarity to false tells each worker to try the "unassigned"
     *   branch first, which tends to reach the first feasible assignment sooner on
     *   tightly-packed problems like this one.
     * - Everything else (workers, repairHint, time limit) matches the standard config.
     */
    private CpSolver configureSolverPhase0(ScheduleConfig config, int timeLimitSec) {
        CpSolver solver = new CpSolver();
        solver.getParameters().setNumWorkers(config.getCpSatNumWorkers());
        solver.getParameters().setLogToStdout(false);
        solver.getParameters().setRepairHint(true);
        // Disable pre-solve probing: expensive for optimization, not helpful for SAT.
        solver.getParameters().setCpModelProbingLevel(0);
        // Prefer the "false" (unassigned) polarity first — finds feasible solutions
        // faster on problems where most slots should be empty (sparse assignment).
        solver.getParameters().setInitialPolarity(com.google.ortools.sat.SatParameters.Polarity.POLARITY_FALSE);
        // Stop at the FIRST feasible solution. Phase 0 carries a lightweight occupancy
        // objective (Option A) only to steer early search toward feasible territory — we
        // do NOT want CP-SAT to prove that objective optimal, which turns "find any
        // schedule" back into a full optimization (observed: ~480s to OPTIMAL). The first
        // feasible incumbent is all Phase 1 needs as a warm start.
        solver.getParameters().setStopAfterFirstSolution(true);
        if (timeLimitSec > 0) solver.getParameters().setMaxTimeInSeconds(timeLimitSec);
        return solver;
    }

    /**
     * Builds a greedy round-robin seed assignment as warm-start hints for Phase 0 (Option B).
     *
     * Strategy: for each rotation, distribute its minimum required blocks across eligible
     * residents in round-robin order, placing each assignment at the earliest valid start
     * block that doesn't overlap prior assignments for that resident. This satisfies the
     * workload-minimum intent for most rotations without trying to honor every constraint
     * (which would require running the full solver anyway).
     *
     * CP-SAT's repairHint=true will take this seed and repair any constraint violations
     * before searching further, so a partially-wrong seed is still useful — it gives the
     * solver a warm region of the search space to start from rather than a blank slate.
     * A seed that is too wrong is simply ignored and the solver falls back to cold search.
     *
     * Returns a map of variable name → value (0 or 1) ready for applyHints().
     */
    private Map<String, Long> buildGreedySeedHints(
            List<Resident> residents,
            List<Rotation> rotations,
            VariableFactory varFactory,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, Set<Integer>> eligibleByRotation,
            int totalBlocks) {

        Map<String, Long> hints = new HashMap<>();
        // Track the next free block for each resident (greedy left-to-right packing).
        Map<Integer, Integer> nextFreeBlock = new HashMap<>();
        for (Resident r : residents) nextFreeBlock.put(r.getId(), 0);

        for (Rotation rot : rotations) {
            Set<Integer> eligible = eligibleByRotation.getOrDefault(rot.getId(), Set.of());
            if (eligible.isEmpty()) continue;

            int[] lengths = rotationLengths.getOrDefault(rot.getId(), new int[]{2});
            int preferredLen = lengths[0]; // use the shortest allowed length for the seed

            // Minimum blocks this rotation needs filled (in solver slots).
            // minBlocksRequired is stored in weeks; convert to slots (1 slot = 2 weeks).
            int minSlots = Math.max(1, rot.getMinBlocksRequired() / 2);

            List<Resident> eligibleResidents = residents.stream()
                .filter(r -> eligible.contains(r.getId()))
                .collect(Collectors.toList());
            if (eligibleResidents.isEmpty()) continue;

            int placed = 0;
            int resIdx = 0;
            // Attempt to place minSlots worth of assignments for this rotation.
            while (placed < minSlots && resIdx < eligibleResidents.size() * totalBlocks) {
                Resident r = eligibleResidents.get(resIdx % eligibleResidents.size());
                resIdx++;

                int startBlock = nextFreeBlock.getOrDefault(r.getId(), 0);
                // Find the earliest valid start block for this (resident, rotation, length).
                boolean found = false;
                for (int t = startBlock; t + preferredLen <= totalBlocks; t++) {
                    BoolVar sv = varFactory.getStartVar(r.getId(), rot.getId(), t);
                    if (sv == null) continue; // variable wasn't created (ineligible at this block)
                    // Hint start var = 1, all other starts for this resident+rotation = 0.
                    hints.put(sv.getName(), 1L);
                    // Hint occupancy vars = 1 for the covered range.
                    for (int b = t; b < t + preferredLen && b < totalBlocks; b++) {
                        BoolVar occ = varFactory.getOccupancyVar(r.getId(), rot.getId(), b);
                        if (occ != null) hints.put(occ.getName(), 1L);
                    }
                    nextFreeBlock.put(r.getId(), t + preferredLen);
                    placed++;
                    found = true;
                    break;
                }
                if (!found) break; // no room left for this resident; move on
            }
        }
        return hints;
    }

    /** Trimmed env var with a default when unset/blank. */
    private static String envOr(String key, String dflt) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v.trim();
    }

    /** Parses env var {@code key} as a long, returning {@code dflt} if unset or unparseable. */
    private static long parseLongOr(String key, long dflt) {
        try { return Long.parseLong(envOr(key, String.valueOf(dflt))); }
        catch (NumberFormatException e) { return dflt; }
    }

    /** Back-compat default: probing OFF (the safe setting for the seed-carrying early phases). */
    private CpSolver configureSolver(ScheduleConfig config, int timeLimitSec) {
        return configureSolver(config, timeLimitSec, false);
    }

    /**
     * Builds an optimization-phase solver.
     *
     * @param allowProbing when true, CP-SAT probing presolve is left at its default (ON). Probing
     *   tightens the model before search — pure presolve, so it never changes the optimum, only
     *   speed-to-solution within the time budget. It MUST stay OFF for any phase that carries a
     *   FULL warm-start hint with {@code repairHint=true}: that combination trips an OR-Tools 9.9
     *   native assertion ({@code heuristics.fixed_search != nullptr}) and aborts the process. The
     *   seed-carrying early phases (0/1) therefore pass {@code false}; Phase 2/3 may pass
     *   {@code true} to keep probing's search acceleration. The env var
     *   {@code PHASE3_PROBING=on|off} overrides Phase 3 explicitly so probing's effect can be
     *   A/B-measured. See SOLVE_DATA_INFRA_PLAN.md (probing scope experiment).
     */
    private CpSolver configureSolver(ScheduleConfig config, int timeLimitSec, boolean allowProbing) {
        CpSolver solver = new CpSolver();
        solver.getParameters().setNumWorkers(config.getCpSatNumWorkers());
        solver.getParameters().setLogToStdout(false);
        // Adopt + repair the warm-start hint into a feasible incumbent. Without this, a hinted
        // phase (esp. Phase 3, locked to Tier-1/Tier-2 ≤ best) could spend its whole budget
        // without ever turning the prior phase's solution into an incumbent and return UNKNOWN,
        // forcing a fallback to the un-optimized earlier phase. Harmless when no hint is set.
        solver.getParameters().setRepairHint(true);
        if (!allowProbing) solver.getParameters().setCpModelProbingLevel(0);
        if (timeLimitSec > 0) solver.getParameters().setMaxTimeInSeconds(timeLimitSec);
        return solver;
    }

    /**
     * Records the Phase-3 objective trajectory to a CSV — one row per improved incumbent —
     * so the objective-vs-time curve can be analyzed to size solve budgets from data.
     * Columns: elapsed_s,objective,best_bound,wall_time_s (wall_time_s is CP-SAT's own clock).
     * Append mode with a header written once; flushes each row so a killed run still keeps data.
     */
    private static final class TrajectoryCallback extends CpSolverSolutionCallback {
        private final java.io.PrintWriter out;
        private final long startMs;
        TrajectoryCallback(String path, long startMs) {
            this.startMs = startMs;
            java.io.PrintWriter w = null;
            try {
                boolean existed = new java.io.File(path).exists();
                w = new java.io.PrintWriter(new java.io.FileWriter(path, true), true); // autoflush
                if (!existed) w.println("elapsed_s,objective,best_bound,cpsat_wall_s");
            } catch (java.io.IOException e) {
                LOG.log(java.util.logging.Level.WARNING, "Could not open trajectory CSV: " + path, e);
            }
            this.out = w;
        }
        @Override public void onSolutionCallback() {
            if (out == null) return;
            double elapsed = (System.currentTimeMillis() - startMs) / 1000.0;
            out.printf("%.1f,%.1f,%.1f,%.2f%n", elapsed, objectiveValue(), bestObjectiveBound(), wallTime());
        }
        void close() { if (out != null) out.close(); }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Solution extraction
    // ══════════════════════════════════════════════════════════════════════

    private ScheduleSolution extractSolution(
            CpSolver solver, CpSolverStatus status,
            VariableFactory varFactory,
            List<Resident> residents, List<Rotation> rotations,
            int totalBlocks, FeasibilityReport feasReport, long startMs) {

        ScheduleSolution sol = new ScheduleSolution();
        sol.setFeasibilityReport(feasReport);
        sol.setRuntimeMs(System.currentTimeMillis() - startMs);
        sol.setNumBranches((int) solver.numBranches());
        sol.setNumConflicts((int) solver.numConflicts());

        switch (status) {
            case OPTIMAL    -> sol.setStatus(ScheduleSolution.Status.OPTIMAL);
            case FEASIBLE   -> sol.setStatus(ScheduleSolution.Status.FEASIBLE);
            case INFEASIBLE -> { sol.setStatus(ScheduleSolution.Status.INFEASIBLE); return sol; }
            default         -> sol.setStatus(ScheduleSolution.Status.UNKNOWN);
        }

        try { sol.setObjectiveValue(solver.objectiveValue()); }
        catch (Exception e) { LOG.log(java.util.logging.Level.WARNING, "Could not read objective value", e); }

        // Per-variable reads can fail individually; a non-zero count means some
        // assignments were dropped from the committed schedule, so report it.
        int failures = 0;
        try {
            for (Resident r : residents) {
                for (Rotation s : rotations) {
                    for (int w = 0; w < totalBlocks; w++) {
                        BoolVar occ = varFactory.getOccupancyVar(r.getId(), s.getId(), w);
                        try {
                            if (occ != null && solver.booleanValue(occ))
                                sol.recordAssignment(r.getId(), s.getId(), w);
                        } catch (Exception e) { failures++; }
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.SEVERE, "Solution extraction aborted early — schedule may be incomplete", e);
        }
        if (failures > 0)
            LOG.severe("Solution extraction: " + failures + " assignment reads failed — committed schedule may be incomplete.");

        bestSolution = sol;
        return sol;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Early-exit helper
    // ══════════════════════════════════════════════════════════════════════

    private ScheduleSolution commitAndReturn(
            PhaseResult best, FeasibilityReport feasReport,
            StringBuilder solverLog, long startMs,
            int year, List<Block> blocks,
            List<Resident> residents, List<Rotation> rotations,
            ScheduleConfig config) throws SQLException {
        ScheduleSolution sol = best.feasible()
            ? extractSolution(best.solver(), best.status(), best.varFactory(),
                              residents, rotations, ScheduleUnits.SLOTS_PER_YEAR, feasReport, startMs)
            : aborted(feasReport);
        commitToDB(sol, year, blocks, residents, rotations);
        if (sol.getStatus() != ScheduleSolution.Status.INFEASIBLE) {
            new AuxFillerService().run(year, blocks, rotations, config, solverLog);
        }
        sol.setSolverLog(solverLog.toString());
        bestSolution = sol;
        return sol;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  DB commit — map week indices back to half-blocks
    // ══════════════════════════════════════════════════════════════════════

    private void commitToDB(ScheduleSolution solution, int year,
                             List<Block> blocks, List<Resident> residents,
                             List<Rotation> rotations) throws SQLException {
        if (residents.isEmpty()) return;

        // Only delete assignments for main (non-auxiliary) residents — never touch aux schedules.
        String mainIds = residents.stream()
            .map(r -> String.valueOf(r.getId()))
            .collect(Collectors.joining(","));
        String deleteSql = """
            DELETE FROM assignments
            WHERE resident_id IN (%s)
              AND block_id IN (SELECT id FROM blocks WHERE schedule_year=?)
            """.formatted(mainIds);
        try (var conn = DatabaseManager.getInstance().getConnection();
             var stmt = conn.prepareStatement(deleteSql)) {
            stmt.setInt(1, year);
            stmt.executeUpdate();
        }

        // Block index 0–25 maps directly to blockNumber 1–26
        Map<Integer, Integer> blockIndexToId = new HashMap<>();
        for (Block b : blocks) {
            int blockIndex = b.getBlockNumber() - 1; // 0-based
            blockIndexToId.put(blockIndex, b.getId());
        }

        Set<String> inserted = new HashSet<>();
        for (Resident r : residents) {
            for (Rotation s : rotations) {
                List<Integer> assignedBlocks = solution.getAssignedWeeks(r.getId(), s.getId());
                if (assignedBlocks.isEmpty()) continue;

                for (int blockIdx : assignedBlocks) {
                    Integer blockId = blockIndexToId.get(blockIdx);
                    if (blockId == null) continue;
                    String key = r.getId() + "_" + blockId;
                    if (!inserted.contains(key)) {
                        assignmentDAO.insert(new Assignment(0, r.getId(), s.getId(), blockId, false));
                        inserted.add(key);
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    /** Builds the set of "residentId_rotationId" pairs to exclude from aux pre-counting. */
    private Set<String> buildFillerExclusions(List<Resident> auxResidents,
                                               Map<String, Set<Integer>> fillerRotationsByGroup) {
        Set<String> exclusions = new HashSet<>();
        for (Resident r : auxResidents) {
            String group = r.getResidentGroup();
            if (group == null) continue;
            Set<Integer> fillerRotIds = fillerRotationsByGroup.getOrDefault(group, Set.of());
            for (int rotId : fillerRotIds) {
                exclusions.add(r.getId() + "_" + rotId);
            }
        }
        return exclusions;
    }

    /**
     * Applies the shared synthetic aux-coverage credits (VA + Younker-8-Pulm BMC static) and
     * registers the Y8Pulm global categorical floor on {@code config}. Delegates to
     * {@link AuxCoverageCredits} so CP-SAT and Timefold stay identical. See that class for the
     * full rule rationale.
     */
    private void applyAuxCoverageCredits(Map<Integer, Map<Integer, Integer>> auxCoverage,
                                  List<Rotation> rotations, List<Resident> auxResidents,
                                  ScheduleConfig config, int totalBlocks) {
        AuxCoverageCredits.applyPerBlockCredits(auxCoverage, rotations, auxResidents, config, totalBlocks);
        int floor = AuxCoverageCredits.younker8CategoricalFloor(rotations, auxResidents, config, totalBlocks);
        Integer y8Id = AuxCoverageCredits.younker8RotationId(rotations);
        if (y8Id != null && floor >= 0) {
            config.getCategoricalGlobalFloors().put(y8Id, floor);
        }
    }

    private ScheduleSolution aborted(FeasibilityReport report) {
        ScheduleSolution s = new ScheduleSolution();
        s.setStatus(ScheduleSolution.Status.UNKNOWN);
        s.setSolverLog("Aborted by user.");
        s.setFeasibilityReport(report);
        return s;
    }

    private Map<Integer, Map<Integer, RotationRequirement>> buildReqMap(List<RotationRequirement> reqs) {
        Map<Integer, Map<Integer, RotationRequirement>> map = new HashMap<>();
        for (RotationRequirement r : reqs)
            map.computeIfAbsent(r.getRotationId(), k -> new HashMap<>()).put(r.getPgyLevel(), r);
        return map;
    }

    private Map<Integer, Set<Integer>> buildEligibilityMap(
            List<Resident> residents, List<Rotation> rotations,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap) {
        Map<Integer, Set<Integer>> eligible = new HashMap<>();
        for (Rotation s : rotations) {
            Set<Integer> pool = new HashSet<>();
            Map<Integer, RotationRequirement> byPgy = reqMap.getOrDefault(s.getId(), Map.of());
            for (Resident r : residents) {
                if (byPgy.isEmpty() || byPgy.containsKey(r.getPgyLevel())) pool.add(r.getId());
            }
            eligible.put(s.getId(), pool);
        }
        return eligible;
    }

    private Map<Integer, List<Prerequisite>> buildPrereqMap(List<Prerequisite> prereqs) {
        Map<Integer, List<Prerequisite>> map = new HashMap<>();
        for (Prerequisite p : prereqs)
            map.computeIfAbsent(p.getRotationId(), k -> new ArrayList<>()).add(p);
        return map;
    }

    private Map<Integer, List<RotationSequenceRule>> buildSequenceRuleMap(List<RotationSequenceRule> rules) {
        Map<Integer, List<RotationSequenceRule>> map = new HashMap<>();
        for (RotationSequenceRule r : rules)
            map.computeIfAbsent(r.getRotationId(), k -> new ArrayList<>()).add(r);
        return map;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Diagnostic log + stepwise diagnosis (unchanged from original)
    // ══════════════════════════════════════════════════════════════════════

    private void buildDiagnosticLog(
            StringBuilder solverLog,
            List<Resident> residents, List<Rotation> rotations, List<Block> blocks,
            ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int totalBlocks,
            FeasibilityReport feasReport,
            Consumer<String> onProgress) {

        solverLog.append("═══ CONSTRAINT DIAGNOSTICS ═══\n");
        solverLog.append(String.format("Schedule: %d residents, %d rotations, %d weeks\n",
            residents.size(), rotations.size(), totalBlocks));

        int effMin = Math.min(config.getGlobalMinWorkloadBlocks(), totalBlocks);
        int effMax = Math.min(config.getGlobalMaxWorkloadBlocks(), totalBlocks);
        solverLog.append(String.format("Workload per resident: %d–%d weeks\n", effMin, effMax));

        int totalMinDemand = 0;
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy p = config.getPolicyFor(s.getId());
            int demand = p.minPerBlock * totalBlocks;
            totalMinDemand += demand;
            solverLog.append(String.format(
                "  %-30s  minPerBlock=%d  maxPerBlock=%d  blockLengths=%s  minDemand=%d blks\n",
                s.getName(), p.minPerBlock, p.maxPerBlock,
                Arrays.toString(p.allowedBlockLengths), demand));
        }
        int totalSupply = residents.size() * totalBlocks;
        solverLog.append(String.format(
            "Total min demand: %d  |  Total supply: %d (%d residents × %d weeks)\n",
            totalMinDemand, totalSupply, residents.size(), totalBlocks));
        if (totalMinDemand > totalSupply)
            solverLog.append("⚠ HARD INFEASIBILITY: min demand exceeds supply\n");

        solverLog.append("\n═══ PER-RESIDENT CAPACITY CHECK ═══\n");
        solverLog.append(String.format("  Workload required: %d–%d weeks per resident\n", effMin, effMax));
        for (Resident r : residents) {
            int maxSchedulable = 0, minDemanded = 0;
            StringBuilder reqDetail = new StringBuilder();
            for (Rotation s : rotations) {
                Set<Integer> pool = eligibleByRotation.get(s.getId());
                if (pool != null && !pool.contains(r.getId())) continue;
                // All quantities here are in SLOTS (2-week units) so they compare directly
                // against effMin/effMax, which are slot-based workload bounds. maxBlocksAllowed
                // is entered in WEEKS, so convert. (Previously used raw weeks — see PROJECT.md Code review, H2.)
                int maxSlots = ScheduleUnits.weeksToSlots(s.getMaxBlocksAllowed());
                maxSchedulable += maxSlots;
                Map<Integer, RotationRequirement> byPgy = reqMap.getOrDefault(s.getId(), Map.of());
                RotationRequirement req = byPgy.get(r.getPgyLevel());
                if (req != null && req.isRequired()) {
                    int[] lengths = config.getPolicyFor(s.getId()).allowedBlockLengths;
                    int minSlots = ScheduleUnits.blocksToSlots(req.getMinBlocks());
                    minDemanded += minSlots;
                    String flag = (minSlots > maxSlots) ? " ⚠ minSlots>maxSlots CONTRADICTION" : "";
                    reqDetail.append(String.format(
                        "    %-30s  minBlocks=%.1f  lengths=%s  minSlots=%d  maxSlots=%d%s\n",
                        s.getName(), req.getMinBlocks(), Arrays.toString(lengths), minSlots, maxSlots, flag));
                }
            }
            boolean ok = maxSchedulable >= effMin && minDemanded <= effMax;
            String flagStr = !ok
                ? (minDemanded > effMax ? "⚠ MIN DEMAND " + minDemanded + " > " + effMax : "⚠ UNDER " + effMin)
                : "✓";
            solverLog.append(String.format("  PGY-%d %-20s  maxSchedulable=%d  minDemanded=%d  %s\n",
                r.getPgyLevel(), r.getName(), maxSchedulable, minDemanded, flagStr));
            if (reqDetail.length() > 0) solverLog.append(reqDetail);
        }

        if (feasReport.hasIssues()) {
            solverLog.append("\n═══ PRE-SOLVE FEASIBILITY ISSUES ═══\n");
            for (FeasibilityReport.Issue issue : feasReport.getIssues()) {
                solverLog.append(String.format("[%s] %s\n  → %s\n  Fix: %s\n\n",
                    issue.type, issue.rotationName, issue.description, issue.suggestion));
            }
        }

        // The expensive stepwise/removal diagnosis is deferred: it only adds value when the
        // model is INFEASIBLE, and on a tight-but-feasible model it wastes minutes timing out
        // on each step. It now runs only from the Phase-0 INFEASIBLE branch.
        solverLog.append("\n═══ FOUR-PHASE SOLVER LOG ═══\n");
    }

    private void runStepwiseDiagnosis(
            StringBuilder log,
            List<Resident> residents, List<Rotation> rotations,
            ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int totalBlocks) {

        Map<Integer, Rotation> rotById = rotations.stream()
            .collect(Collectors.toMap(Rotation::getId, r -> r));

        // Canonical constraint order — identical to buildBaseModel. Step 0 below is
        // "block expansion + no-overlap only"; step k>=1 adds the first k constraints
        // from this list cumulatively, so the lowest INFEASIBLE step pinpoints the
        // constraint that introduced the contradiction.
        List<ConstraintStep> steps = orderedConstraintSteps(residents, rotations, reqMap, prereqMap, seqMap, rotById);

        String[] labels = new String[steps.size() + 1];
        labels[0] = "1.  Block expansion + no-overlap";
        for (int i = 0; i < steps.size(); i++) {
            labels[i + 1] = String.format("%-2d. + %s", i + 2, steps.get(i).label());
        }

        log.append("  Launching all steps in parallel (1 worker each)…\n");

        int numSteps = labels.length;
        ExecutorService pool = Executors.newFixedThreadPool(numSteps);
        @SuppressWarnings("unchecked")
        Future<CpSolverStatus>[] futures = new Future[numSteps];

        for (int step = 0; step < numSteps; step++) {
            final int s = step;
            futures[s] = pool.submit(() -> {
                CpModel m = new CpModel();
                VariableFactory vf = new VariableFactory(m, totalBlocks, rotationLengths);
                vf.createAll(residents, rotations, eligibleByRotation);
                BlockExpansionService bes = new BlockExpansionService(m, vf, totalBlocks, rotationLengths);
                bes.applyAll(residents, rotations);
                bes.applyNoOverlapAcrossRotations(residents, rotations);
                ConstraintBuilder cb = new ConstraintBuilder(m, vf, config, totalBlocks, auxCoverage);
                // Apply the first s constraints (step 0 applies none beyond expansion).
                for (int i = 0; i < s; i++) steps.get(i).apply().accept(cb);
                CpSolver solver = new CpSolver();
                solver.getParameters().setMaxTimeInSeconds(15);
                solver.getParameters().setNumWorkers(1);
                return solver.solve(m);
            });
        }

        pool.shutdown();

        // Collect results — each solver enforces its own 15s limit; add 5s buffer
        CpSolverStatus[] results = new CpSolverStatus[numSteps];
        for (int step = 0; step < numSteps; step++) {
            try {
                results[step] = futures[step].get(20, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                futures[step].cancel(true);
                results[step] = CpSolverStatus.UNKNOWN;
            } catch (Exception e) {
                results[step] = CpSolverStatus.UNKNOWN;
            }
        }

        // Find the lowest-numbered INFEASIBLE step (it introduced the contradiction)
        int firstInfeasible = -1;
        for (int step = 0; step < numSteps; step++) {
            if (results[step] == CpSolverStatus.INFEASIBLE) { firstInfeasible = step; break; }
        }

        // Log all steps in order
        for (int step = 0; step < numSteps; step++) {
            CpSolverStatus st = results[step];
            String tag;
            if (st == CpSolverStatus.INFEASIBLE) {
                tag = (step == firstInfeasible)
                    ? "INFEASIBLE ← first contradiction here"
                    : "INFEASIBLE (superset of step " + (firstInfeasible + 1) + ")";
            } else {
                tag = switch (st) {
                    case OPTIMAL, FEASIBLE -> "FEASIBLE";
                    default                -> "UNKNOWN (timeout — may still be feasible)";
                };
            }
            log.append(String.format("  Step %s: %s\n", labels[step], tag));
        }

        boolean foundInfeasibleStep = firstInfeasible >= 0;
        if (foundInfeasibleStep) {
            // Key the drill-downs off the constraint that first failed, by label, so
            // they stay correct even if the constraint order changes later. The
            // culprit constraint is steps.get(firstInfeasible - 1) (step 0 = expansion).
            String culprit = firstInfeasible >= 1 ? steps.get(firstInfeasible - 1).label() : "";
            if (culprit.equals("Max blocks per resident")) {
                log.append("\n  Drilling into '" + culprit + "' — adding (resident, rotation) pairs one at a time:\n");
                drillMaxBlocksConstraint(log, residents, rotations, config, reqMap,
                    eligibleByRotation, rotationLengths, auxCoverage, totalBlocks);
            } else if (culprit.equals("Earliest start block")) {
                log.append("\n  Drilling into '" + culprit + "' — earliest start block constraints:\n");
                drillEarliestStartConstraints(log, residents, rotations, config,
                    reqMap, eligibleByRotation, rotationLengths, auxCoverage, totalBlocks);
            }
        } else {
            // Every step timed out or was FEASIBLE — the conflict is multi-constraint.
            // Run a removal pass to identify which constraint(s) are part of the conflict.
            log.append("\n  No single step conclusively INFEASIBLE (timeouts masked the contradiction).\n");
            log.append("  Running removal pass — dropping one constraint at a time from the full set:\n");
            runRemovalDiagnosis(log, residents, rotations, config, reqMap, prereqMap,
                eligibleByRotation, rotationLengths, seqMap, auxCoverage, totalBlocks, rotById);
        }
    }

    /** Applies all toggleable constraints, then drops each one individually to find which removal restores feasibility. */
    private void runRemovalDiagnosis(
            StringBuilder log,
            List<Resident> residents, List<Rotation> rotations,
            ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, List<RotationSequenceRule>> seqMap,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int totalBlocks,
            Map<Integer, Rotation> rotById) {

        // Same canonical order/labels as buildBaseModel and the stepwise diagnosis.
        List<ConstraintStep> steps = orderedConstraintSteps(residents, rotations, reqMap, prereqMap, seqMap, rotById);

        // Use a longer timeout per removal test since we know the full problem is hard
        int removalTimeoutSec = 30;

        for (int drop = 0; drop < steps.size(); drop++) {
            CpModel m = new CpModel();
            VariableFactory vf = new VariableFactory(m, totalBlocks, rotationLengths);
            vf.createAll(residents, rotations, eligibleByRotation);
            BlockExpansionService bes = new BlockExpansionService(m, vf, totalBlocks, rotationLengths);
            bes.applyAll(residents, rotations);
            bes.applyNoOverlapAcrossRotations(residents, rotations);
            ConstraintBuilder cb = new ConstraintBuilder(m, vf, config, totalBlocks, auxCoverage);

            // Apply every constraint except the one being dropped.
            for (int i = 0; i < steps.size(); i++) {
                if (i != drop) steps.get(i).apply().accept(cb);
            }

            CpSolver sv = new CpSolver();
            sv.getParameters().setMaxTimeInSeconds(removalTimeoutSec);
            sv.getParameters().setNumWorkers(config.getCpSatNumWorkers());
            CpSolverStatus st = sv.solve(m);

            String result = switch (st) {
                case OPTIMAL, FEASIBLE -> "FEASIBLE ← removing this unblocks the schedule";
                case INFEASIBLE        -> "still INFEASIBLE";
                default                -> "UNKNOWN (timeout)";
            };
            log.append(String.format("    drop %-35s → %s\n", steps.get(drop).label(), result));

            if (st == CpSolverStatus.FEASIBLE || st == CpSolverStatus.OPTIMAL) {
                log.append(String.format(
                    "\n  ★ Constraint '%s' is part of the conflict. " +
                    "Review its settings — relaxing or removing it should restore feasibility.\n",
                    steps.get(drop).label()));
            }
        }
    }

    private void drillEarliestStartConstraints(
            StringBuilder log,
            List<Resident> residents, List<Rotation> rotations,
            ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int totalBlocks) {

        List<Rotation> active = rotations.stream()
            .filter(s -> config.getPolicyFor(s.getId()).earliestStartBlock > 0)
            .toList();

        if (active.isEmpty()) {
            log.append("    No rotations have earliestStartBlock > 0 — constraint source unclear.\n");
            return;
        }

        Map<Integer, Rotation> rotById = rotations.stream()
            .collect(Collectors.toMap(Rotation::getId, r -> r));
        // Prerequisites are needed for the full stack — load from reqMap keys (empty map if unavailable)
        Map<Integer, List<Prerequisite>> emptyPrereqs = Map.of();

        List<Integer> addedRotIds = new ArrayList<>();

        for (Rotation s : active) {
            int earliest = config.getPolicyFor(s.getId()).earliestStartBlock;
            addedRotIds.add(s.getId());

            // Build the full Steps-1-10 constraint stack so the test matches the stepwise context
            CpModel m = new CpModel();
            VariableFactory vf = new VariableFactory(m, totalBlocks, rotationLengths);
            vf.createAll(residents, rotations, eligibleByRotation);
            BlockExpansionService bes = new BlockExpansionService(m, vf, totalBlocks, rotationLengths);
            bes.applyAll(residents, rotations);
            bes.applyNoOverlapAcrossRotations(residents, rotations);
            ConstraintBuilder cb = new ConstraintBuilder(m, vf, config, totalBlocks, auxCoverage);
            cb.applyWorkloadCapConstraints(residents, rotations);
            cb.applyCoverageConstraints(residents, rotations);
            cb.applyMaxBlocksPerResidentConstraints(residents, rotations, reqMap);
            cb.applyPrerequisiteConstraints(residents, emptyPrereqs, rotById);
            cb.applyNoBackToBackHalfBlockConstraints(residents, rotations);
            cb.applyMutualNonAdjacencyConstraints(residents, rotations);
            cb.applyRequireBreakBetweenSegmentsConstraints(residents, rotations);
            cb.applyMaxConsecutiveBlocksConstraints(residents, rotations);

            // Add earliest-start for the rotations accumulated so far
            for (Rotation t : rotations) {
                if (!addedRotIds.contains(t.getId())) continue;
                int e2 = config.getPolicyFor(t.getId()).earliestStartBlock;
                for (Resident r : residents) {
                    for (int b = 0; b < e2 && b < totalBlocks; b++) {
                        BoolVar occ = vf.getOccupancyVar(r.getId(), t.getId(), b);
                        if (occ != null) m.addEquality(occ, 0);
                        BoolVar sv2 = vf.getStartVar(r.getId(), t.getId(), b);
                        if (sv2 != null) m.addEquality(sv2, 0);
                    }
                }
            }

            CpSolver sv = new CpSolver();
            sv.getParameters().setMaxTimeInSeconds(10);
            sv.getParameters().setNumWorkers(config.getCpSatNumWorkers());
            CpSolverStatus st = sv.solve(m);

            if (st == CpSolverStatus.INFEASIBLE) {
                ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
                Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());
                int usableBlocks = totalBlocks - earliest;
                int effectiveCap = 0;
                for (int b = earliest; b < totalBlocks; b++) {
                    effectiveCap += Math.max(0, policy.maxPerBlock - auxByBlock.getOrDefault(b, 0));
                }
                int totalMinDemand = 0;
                for (Resident r : residents) {
                    RotationRequirement req = reqMap.getOrDefault(s.getId(), Map.of()).get(r.getPgyLevel());
                    if (req != null && req.isRequired()) {
                        totalMinDemand += ScheduleUnits.blocksToSlots(req.getMinBlocks());
                    }
                }

                log.append(String.format(
                    "    ⚠ INFEASIBLE after adding: %-35s [earliestStartBlock=%d]\n",
                    s.getName(), earliest));
                log.append(String.format(
                    "      Usable blocks: %d–%d (%d blocks)  maxPerBlock=%d  effectiveCap=%d  totalMinDemand=%d\n",
                    earliest, totalBlocks - 1, usableBlocks, policy.maxPerBlock, effectiveCap, totalMinDemand));

                if (totalMinDemand > effectiveCap) {
                    log.append(String.format(
                        "      → Demand (%d) exceeds capacity (%d). " +
                        "Lower earliestStartBlock or increase maxPerBlock for %s.\n",
                        totalMinDemand, effectiveCap, s.getName()));
                } else {
                    // Check for direct coverage-vs-earliest-start conflict on blocked blocks
                    ScheduleConfig.RotationPolicy pol = config.getPolicyFor(s.getId());
                    List<Integer> conflictBlocks = new ArrayList<>();
                    for (int b = 0; b < earliest; b++) {
                        int auxCount = auxCoverage.getOrDefault(s.getId(), Map.of()).getOrDefault(b, 0);
                        int effMin = Math.max(0, pol.minPerBlock - auxCount);
                        if (effMin > 0) conflictBlocks.add(b);
                    }
                    if (!conflictBlocks.isEmpty()) {
                        log.append(String.format(
                            "      → DIRECT CONFLICT: blocks %s have effectiveMin > 0 (coverage requires " +
                            "main residents there) but earliestStartBlock=%d forbids them.\n",
                            conflictBlocks, earliest));
                        log.append(String.format(
                            "      Fix options:\n" +
                            "        1. Set minPerBlock=0 for %s (make coverage optional)\n" +
                            "        2. Remove earliestStartBlock restriction (set to 0)\n" +
                            "        3. Pre-assign aux residents to cover blocks %s of %s\n",
                            s.getName(), conflictBlocks, s.getName()));
                    } else {
                        // No direct coverage conflict — run per-resident reachability audit
                        log.append(String.format(
                            "      → Capacity sufficient (%d ≥ %d), no direct coverage conflict. " +
                            "Checking per-resident reachability after all constraints:\n",
                            effectiveCap, totalMinDemand));
                        perResidentReachabilityAudit(log, residents, rotations, s, earliest,
                            config, reqMap, eligibleByRotation, rotationLengths, auxCoverage,
                            totalBlocks, emptyPrereqs, rotById);
                    }
                }
                return;
            } else {
                log.append(String.format("    ✓ %-35s [earliestStartBlock=%d]\n", s.getName(), earliest));
            }
        }

        log.append("    All earliest-start constraints individually feasible — conflict is combinatorial.\n");
    }

    /**
     * For each resident that requires the culprit rotation, tests whether that resident
     * alone can be validly placed in at least one block of the rotation under the full
     * constraint stack. Reports which residents have zero reachable blocks.
     */
    private void perResidentReachabilityAudit(
            StringBuilder log,
            List<Resident> residents, List<Rotation> rotations,
            Rotation culprit, int earliest,
            ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int totalBlocks,
            Map<Integer, List<Prerequisite>> prereqMap,
            Map<Integer, Rotation> rotById) {

        ScheduleConfig.RotationPolicy policy = config.getPolicyFor(culprit.getId());

        for (Resident r : residents) {
            RotationRequirement req = reqMap.getOrDefault(culprit.getId(), Map.of()).get(r.getPgyLevel());
            if (req == null || !req.isRequired()) continue;

            int reachable = 0;
            // Test each individual block: can this resident be placed at block b?
            for (int b = earliest; b < totalBlocks; b++) {
                // Quick check: is this block open (not aux-filled to max)?
                int auxCount = auxCoverage.getOrDefault(culprit.getId(), Map.of()).getOrDefault(b, 0);
                if (auxCount >= policy.maxPerBlock) continue;

                // Build a minimal model: just this resident, force them into block b of culprit
                CpModel m = new CpModel();
                VariableFactory vf = new VariableFactory(m, totalBlocks, rotationLengths);
                vf.createAll(List.of(r), rotations, eligibleByRotation);
                BlockExpansionService bes = new BlockExpansionService(m, vf, totalBlocks, rotationLengths);
                bes.applyAll(List.of(r), rotations);
                bes.applyNoOverlapAcrossRotations(List.of(r), rotations);
                ConstraintBuilder cb = new ConstraintBuilder(m, vf, config, totalBlocks, auxCoverage);
                cb.applyWorkloadCapConstraints(List.of(r), rotations);
                cb.applyNoBackToBackHalfBlockConstraints(List.of(r), rotations);
                cb.applyMutualNonAdjacencyConstraints(List.of(r), rotations);
                cb.applyRequireBreakBetweenSegmentsConstraints(List.of(r), rotations);
                cb.applyMaxConsecutiveBlocksConstraints(List.of(r), rotations);

                // Force occupancy at block b for the culprit rotation
                BoolVar occ = vf.getOccupancyVar(r.getId(), culprit.getId(), b);
                if (occ != null) m.addEquality(occ, 1);
                else continue;

                // Apply earliest-start for culprit
                for (int eb = 0; eb < earliest && eb < totalBlocks; eb++) {
                    BoolVar o2 = vf.getOccupancyVar(r.getId(), culprit.getId(), eb);
                    if (o2 != null) m.addEquality(o2, 0);
                }

                CpSolver sv = new CpSolver();
                sv.getParameters().setMaxTimeInSeconds(3);
                sv.getParameters().setNumWorkers(1);
                if (sv.solve(m) != CpSolverStatus.INFEASIBLE) reachable++;
            }

            int needed = ScheduleUnits.blocksToSlots(req.getMinBlocks());
            if (reachable < needed) {
                log.append(String.format(
                    "        ⚠ %-20s  reachableBlocks=%d  needed=%d  [BLOCKED]\n",
                    r.getName(), reachable, needed));
            } else {
                log.append(String.format(
                    "        ✓ %-20s  reachableBlocks=%d  needed=%d\n",
                    r.getName(), reachable, needed));
            }
        }
    }

    private void drillMaxBlocksConstraint(
            StringBuilder log,
            List<Resident> residents, List<Rotation> rotations,
            ScheduleConfig config,
            Map<Integer, Map<Integer, RotationRequirement>> reqMap,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int totalBlocks) {

        List<int[]> addedPairs = new ArrayList<>();
        boolean foundInfeasiblePair = false;
        outer:
        for (Resident r : residents) {
            for (Rotation s : rotations) {
                Map<Integer, RotationRequirement> byPgy = reqMap.getOrDefault(s.getId(), Map.of());
                RotationRequirement req = byPgy.get(r.getPgyLevel());
                // Mirror applyMaxBlocksPerResidentConstraints: bounds are in SLOTS.
                // maxBlocksAllowed is entered in WEEKS -> convert via ScheduleUnits.
                int maxSlots = Math.max(1, ScheduleUnits.weeksToSlots(s.getMaxBlocksAllowed()));
                int minSlots = 0;
                if (req != null && req.isRequired()) {
                    // Mirror ConstraintBuilder.applyMaxBlocksPerResidentConstraints exactly.
                    minSlots = Math.min(ScheduleUnits.blocksToSlots(req.getMinBlocks()), maxSlots);
                }
                addedPairs.add(new int[]{r.getId(), s.getId(), minSlots, maxSlots});

                CpModel m = new CpModel();
                VariableFactory vf2 = new VariableFactory(m, totalBlocks, rotationLengths);
                vf2.createAll(residents, rotations, eligibleByRotation);
                BlockExpansionService bes2 = new BlockExpansionService(m, vf2, totalBlocks, rotationLengths);
                bes2.applyAll(residents, rotations);
                bes2.applyNoOverlapAcrossRotations(residents, rotations);
                ConstraintBuilder cb2 = new ConstraintBuilder(m, vf2, config, totalBlocks, auxCoverage);
                cb2.applyWorkloadCapConstraints(residents, rotations);
                cb2.applyCoverageConstraints(residents, rotations);

                for (int[] pair : addedPairs) {
                    List<BoolVar> pairOccs = new ArrayList<>(vf2.getOccupancyVars(pair[0], pair[1]).values());
                    if (!pairOccs.isEmpty())
                        model_addLinearConstraint(m, pairOccs, pair[2], pair[3]);
                }

                CpSolver sv = new CpSolver();
                sv.getParameters().setMaxTimeInSeconds(5);
                sv.getParameters().setNumWorkers(1);
                CpSolverStatus st = sv.solve(m);

                String pairLabel = String.format("PGY-%d %-15s + %-30s [min=%d max=%d slots]",
                    r.getPgyLevel(), r.getName(), s.getName(), minSlots, maxSlots);

                if (st == CpSolverStatus.INFEASIBLE) {
                    log.append(String.format("    ⚠ INFEASIBLE after adding: %s\n", pairLabel));
                    micoDiagnose(log, residents, rotations, config, eligibleByRotation,
                        rotationLengths, auxCoverage, totalBlocks, addedPairs);
                    foundInfeasiblePair = true;
                    break outer;
                } else {
                    log.append(String.format("    ✓ %s\n", pairLabel));
                }
            }
        }

        if (!foundInfeasiblePair) {
            log.append("\n  All pairs individually feasible — infeasibility is aggregate.\n");
            log.append("  Checking per-rotation capacity after aux coverage offset:\n");
            for (Rotation s : rotations) {
                ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
                Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());

                int totalEffectiveCap = 0;
                int auxBlocksCovered = 0;
                for (int b = 0; b < totalBlocks; b++) {
                    int auxCount = auxByBlock.getOrDefault(b, 0);
                    totalEffectiveCap += Math.max(0, policy.maxPerBlock - auxCount);
                    if (auxCount > 0) auxBlocksCovered++;
                }

                int totalMinDemand = 0;
                for (Resident r : residents) {
                    Map<Integer, RotationRequirement> byPgy = reqMap.getOrDefault(s.getId(), Map.of());
                    RotationRequirement req = byPgy.get(r.getPgyLevel());
                    if (req != null && req.isRequired()) {
                        totalMinDemand += ScheduleUnits.blocksToSlots(req.getMinBlocks());
                    }
                }

                if (totalMinDemand > totalEffectiveCap) {
                    log.append(String.format(
                        "    ⚠ %-35s  demand=%d  effectiveCap=%d  auxBlocks=%d/%d  [OVER-SUBSCRIBED]\n",
                        s.getName(), totalMinDemand, totalEffectiveCap, auxBlocksCovered, totalBlocks));
                } else if (auxBlocksCovered > 0) {
                    log.append(String.format(
                        "    ✓ %-35s  demand=%d  effectiveCap=%d  auxBlocks=%d/%d\n",
                        s.getName(), totalMinDemand, totalEffectiveCap, auxBlocksCovered, totalBlocks));
                }
            }
        }
    }

    private static void model_addLinearConstraint(CpModel m, List<BoolVar> vars, int lo, int hi) {
        m.addLinearConstraint(LinearExpr.sum(vars.toArray(new BoolVar[0])), lo, hi);
    }

    private void micoDiagnose(
            StringBuilder log,
            List<Resident> residents, List<Rotation> rotations,
            ScheduleConfig config,
            Map<Integer, Set<Integer>> eligibleByRotation,
            Map<Integer, int[]> rotationLengths,
            Map<Integer, Map<Integer, Integer>> auxCoverage,
            int totalBlocks,
            List<int[]> pairs) {

        boolean[][] flags = {
            {true,  true,  true },
            {false, true,  true },
            {true,  false, true },
            {false, false, true },
            {true,  true,  false},
        };
        String[] flagNames = {
            "workload=Y coverage=Y pairs=Y",
            "workload=N coverage=Y pairs=Y",
            "workload=Y coverage=N pairs=Y",
            "workload=N coverage=N pairs=Y",
            "workload=Y coverage=Y pairs=N (should be FEASIBLE)",
        };

        for (int i = 0; i < flags.length; i++) {
            boolean withWorkload = flags[i][0];
            boolean withCoverage = flags[i][1];
            boolean withPairs    = flags[i][2];

            CpModel m = new CpModel();
            VariableFactory vf = new VariableFactory(m, totalBlocks, rotationLengths);
            vf.createAll(residents, rotations, eligibleByRotation);
            BlockExpansionService bes = new BlockExpansionService(m, vf, totalBlocks, rotationLengths);
            bes.applyAll(residents, rotations);
            bes.applyNoOverlapAcrossRotations(residents, rotations);
            ConstraintBuilder cb = new ConstraintBuilder(m, vf, config, totalBlocks, auxCoverage);
            if (withWorkload) cb.applyWorkloadCapConstraints(residents, rotations);
            if (withCoverage) cb.applyCoverageConstraints(residents, rotations);
            if (withPairs) {
                for (int[] pair : pairs) {
                    List<BoolVar> pairOccs = new ArrayList<>(vf.getOccupancyVars(pair[0], pair[1]).values());
                    if (!pairOccs.isEmpty()) model_addLinearConstraint(m, pairOccs, pair[2], pair[3]);
                }
            }

            CpSolver sv = new CpSolver();
            sv.getParameters().setMaxTimeInSeconds(5);
            sv.getParameters().setNumWorkers(1);
            CpSolverStatus st = sv.solve(m);
            String res = (st == CpSolverStatus.OPTIMAL || st == CpSolverStatus.FEASIBLE) ? "FEASIBLE" : st.name();
            log.append(String.format("      [%s]: %s\n", flagNames[i], res));
        }

        int[] lastPair = pairs.get(pairs.size() - 1);
        Resident culprit = residents.stream().filter(r -> r.getId() == lastPair[0]).findFirst().orElse(null);
        if (culprit != null) {
            int totalMin = 0, totalMax = 0;
            long count = 0;
            for (int[] p : pairs) {
                if (p[0] == culprit.getId()) { totalMin += p[2]; totalMax += p[3]; count++; }
            }
            int effMin = Math.min(config.getGlobalMinWorkloadBlocks(), totalBlocks);
            int effMax = Math.min(config.getGlobalMaxWorkloadBlocks(), totalBlocks);
            log.append(String.format(
                "      Culprit %s: total [min=%d max=%d wks] from %d constraints; workload cap [%d,%d]\n",
                culprit.getName(), totalMin, totalMax, count, effMin, effMax));
        }

        // Per-rotation capacity breakdown: aggregate min demand vs effective capacity after aux offset.
        // Only shown when coverage is the binding constraint (coverage=N pairs=Y → FEASIBLE but coverage=Y pairs=Y → INFEASIBLE).
        log.append("\n      Per-rotation capacity after aux offset (demand = sum of all residents' minimums):\n");
        Map<Integer, Map<Integer, int[]>> pairsByRotation = new HashMap<>();
        for (int[] p : pairs) {
            pairsByRotation.computeIfAbsent(p[1], k -> new HashMap<>()).put(p[0], p);
        }
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());
            Map<Integer, int[]> residentPairs = pairsByRotation.getOrDefault(s.getId(), Map.of());
            if (residentPairs.isEmpty()) continue;

            int totalEffCap = 0;
            int zeroCapBlocks = 0;
            for (int b = 0; b < totalBlocks; b++) {
                int auxCount = auxByBlock.getOrDefault(b, 0);
                int eff = Math.max(0, policy.maxPerBlock - auxCount);
                totalEffCap += eff;
                if (eff == 0) zeroCapBlocks++;
            }

            int totalMinDemand = residentPairs.values().stream().mapToInt(p -> p[2]).sum();

            if (totalMinDemand > totalEffCap) {
                log.append(String.format(
                    "        ⚠ %-35s  demand=%d  cap=%d  maxPerBlock=%d  auxZeroBlocks=%d/%d  [OVER-SUBSCRIBED]\n",
                    s.getName(), totalMinDemand, totalEffCap,
                    policy.maxPerBlock, zeroCapBlocks, totalBlocks));
            }
        }
    }
}
