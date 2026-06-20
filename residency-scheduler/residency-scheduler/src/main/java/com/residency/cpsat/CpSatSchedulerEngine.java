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
 * Phase 0 — Feasibility:
 *   Build all Tier-0 hard constraints, NO objective, solve.
 *   If INFEASIBLE: stop and report.
 *   If FEASIBLE: extract variable values as warm-start hints.
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

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile CpSolver activeSolver;
    private volatile ScheduleSolution bestSolution;

    public CpSatSchedulerEngine() throws SQLException {
        residentDAO          = new ResidentDAO();
        rotationDAO          = new RotationDAO();
        blockDAO             = new BlockDAO();
        assignmentDAO        = new AssignmentDAO();
        rulesDAO             = new RulesDAO();
        configDAO            = new ScheduleConfigDAO();
        linkRuleDAO          = new RotationLinkRuleDAO();
        auxFillerRotationDAO = new AuxFillerRotationDAO();
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
     * the wrong constraint for an infeasibility (see REVIEW.md finding M1).
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
        onProgress.accept(String.format(
            "Phase 0 ▶ Finding feasible assignment (limit: %ds)…", tier0LimitSec));
        ModelContext mc0 = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);

        CpSolver solver0 = configureSolver(config, tier0LimitSec);
        activeSolver = solver0;
        CpSolverStatus status0 = solver0.solve(mc0.model());
        activeSolver = null;
        solverLog.append(String.format("\nPhase 0 result: %s  (%.1fs)\n",
            status0, (System.currentTimeMillis() - startMs) / 1000.0));

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

        // ─── Phase 3: Minimize 4+2 pattern with Tier-1 and Tier-2 locked ──
        ModelContext mc3 = buildBaseModel(residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, totalBlocks, auxCoverage);
        if (!p2.hints().isEmpty())
            applyHints(mc3.model(), mc3.varFactory(), residents, rotations, totalBlocks, p2.hints());

        ObjectiveFunctionBuilder obj3 = new ObjectiveFunctionBuilder(
            mc3.model(), mc3.varFactory(), config, totalBlocks, auxCoverage);

        if (bestTier1 < Integer.MAX_VALUE) {
            IntVar tier1Lock3 = obj3.buildTier1Counter(residents, rotations);
            mc3.model().addLessOrEqual(tier1Lock3, bestTier1);
        }
        IntVar tier2Lock3 = obj3.buildTier2Core(residents, rotations);
        if (bestTier2 < Long.MAX_VALUE) mc3.model().addLessOrEqual(tier2Lock3, bestTier2);

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

        CpSolver solver3 = configureSolver(config, tier3LimitSec);
        activeSolver = solver3;
        CpSolverStatus status3 = solver3.solve(mc3.model());
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
        // never disagree about which constraint is which (REVIEW.md M1).
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
    //  Solver configuration
    // ══════════════════════════════════════════════════════════════════════

    private CpSolver configureSolver(ScheduleConfig config, int timeLimitSec) {
        CpSolver solver = new CpSolver();
        solver.getParameters().setNumWorkers(config.getCpSatNumWorkers());
        solver.getParameters().setLogToStdout(false);
        // Adopt + repair the warm-start hint into a feasible incumbent. Without this, a hinted
        // phase (esp. Phase 3, locked to Tier-1/Tier-2 ≤ best) could spend its whole budget
        // without ever turning the prior phase's solution into an incumbent and return UNKNOWN,
        // forcing a fallback to the un-optimized earlier phase. Harmless when no hint is set.
        solver.getParameters().setRepairHint(true);
        if (timeLimitSec > 0) solver.getParameters().setMaxTimeInSeconds(timeLimitSec);
        return solver;
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
                // is entered in WEEKS, so convert. (Previously used raw weeks — see REVIEW.md H2.)
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
