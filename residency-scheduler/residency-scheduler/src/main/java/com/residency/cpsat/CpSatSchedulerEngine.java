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
 * Three-phase CP-SAT scheduling engine.
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

    // ══════════════════════════════════════════════════════════════════════
    //  Internal data carriers
    // ══════════════════════════════════════════════════════════════════════

    private record ModelContext(CpModel model, VariableFactory varFactory) {}

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

        int totalBlocks = 26;
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
            ? Map.of()
            : assignmentDAO.getAuxiliaryCoverage(auxIds, year, fillerExclusions);

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

        // ── 4. Three-phase solve ───────────────────────────────────────────

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
            onProgress.accept("Phase 0 INFEASIBLE — no feasible assignment exists. Check solver log.");
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
            mc1.model(), mc1.varFactory(), config, totalBlocks);
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
            try { bestTier1 = (int) solver1.value(tier1Var); } catch (Exception ignored) {}
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
            mc2.model(), mc2.varFactory(), config, totalBlocks);

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
            try { bestTier2 = (long) solver2.value(tier2CostVar); } catch (Exception ignored) {}
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
            mc3.model(), mc3.varFactory(), config, totalBlocks);

        if (bestTier1 < Integer.MAX_VALUE) {
            IntVar tier1Lock3 = obj3.buildTier1Counter(residents, rotations);
            mc3.model().addLessOrEqual(tier1Lock3, bestTier1);
        }
        IntVar tier2Lock3 = obj3.buildTier2Core(residents, rotations);
        if (bestTier2 < Long.MAX_VALUE) mc3.model().addLessOrEqual(tier2Lock3, bestTier2);

        IntVar patternCost = obj3.buildPatternObjective(residents, rotations);
        mc3.model().minimize(patternCost);

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
            SolutionScoreReporter reporter = new SolutionScoreReporter(config, totalBlocks);
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
        cb.applyCoverageConstraints(residents, rotations);
        cb.applyPgyCapConstraints(residents, rotations);
        cb.applyWorkloadCapConstraints(residents, rotations);
        cb.applyMaxBlocksPerResidentConstraints(residents, rotations, reqMap);
        cb.applyFullYearCoverageConstraints(residents, rotations);
        cb.applyPrerequisiteConstraints(residents, prereqMap, rotById);
        cb.applySequenceRules(residents, seqMap);
        cb.applyNoBackToBackHalfBlockConstraints(residents, rotations);
        cb.applyMutualNonAdjacencyConstraints(residents, rotations);
        cb.applyRequireBreakBetweenSegmentsConstraints(residents, rotations);
        cb.applyMaxConsecutiveBlocksConstraints(residents, rotations);
        cb.applyEarliestStartConstraints(residents, rotations);
        cb.applyEvenBlockStartConstraints(residents, rotations);
        cb.applyRotationLinkConstraints(residents, rotations);
        cb.applyRequiresConsecutiveConstraints(residents, rotations);

        return new ModelContext(model, varFactory);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Hint extraction / application (warm-starting between phases)
    // ══════════════════════════════════════════════════════════════════════

    private Map<String, Long> extractHints(CpSolver solver, VariableFactory varFactory,
            List<Resident> residents, List<Rotation> rotations, int totalBlocks) {
        Map<String, Long> hints = new HashMap<>();
        try {
            for (Resident r : residents) {
                for (Rotation s : rotations) {
                    for (int w = 0; w < totalBlocks; w++) {
                        BoolVar occ = varFactory.getOccupancyVar(r.getId(), s.getId(), w);
                        if (occ != null) {
                            try { hints.put(occ.getName(), solver.booleanValue(occ) ? 1L : 0L); }
                            catch (Exception ignored) {}
                        }
                    }
                    varFactory.getStartVars(r.getId(), s.getId()).forEach((week, sv) -> {
                        try { hints.put(sv.getName(), solver.booleanValue(sv) ? 1L : 0L); }
                        catch (Exception ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
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

        try { sol.setObjectiveValue(solver.objectiveValue()); } catch (Exception ignored) {}

        try {
            for (Resident r : residents) {
                for (Rotation s : rotations) {
                    for (int w = 0; w < totalBlocks; w++) {
                        BoolVar occ = varFactory.getOccupancyVar(r.getId(), s.getId(), w);
                        try {
                            if (occ != null && solver.booleanValue(occ))
                                sol.recordAssignment(r.getId(), s.getId(), w);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

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
                              residents, rotations, 26, feasReport, startMs)
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
                maxSchedulable += s.getMaxBlocksAllowed();
                Map<Integer, RotationRequirement> byPgy = reqMap.getOrDefault(s.getId(), Map.of());
                RotationRequirement req = byPgy.get(r.getPgyLevel());
                if (req != null && req.isRequired()) {
                    int[] lengths = config.getPolicyFor(s.getId()).allowedBlockLengths;
                    int minLen = Arrays.stream(lengths).min().orElse(2);
                    int minWks = (int) Math.ceil(req.getMinBlocks()) * minLen;
                    int maxWks = s.getMaxBlocksAllowed();
                    minDemanded += minWks;
                    String flag = (minWks > maxWks) ? " ⚠ minWks>maxWks CONTRADICTION" : "";
                    reqDetail.append(String.format(
                        "    %-30s  minBlocks=%.1f  lengths=%s  minWks=%d  maxWks=%d%s\n",
                        s.getName(), req.getMinBlocks(), Arrays.toString(lengths), minWks, maxWks, flag));
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

        solverLog.append("\n═══ STEPWISE FEASIBILITY DIAGNOSIS ═══\n");
        runStepwiseDiagnosis(solverLog, residents, rotations, config, reqMap, prereqMap,
            eligibleByRotation, rotationLengths, seqMap, auxCoverage, totalBlocks);

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

        String[] labels = {
            "1.  Block expansion + no-overlap",
            "2.  + Workload caps",
            "3.  + Coverage (with aux offset)",
            "4.  + Max blocks per resident",
            "5.  + Prerequisites",
            "6.  + Sequence rules",
            "7.  + No-back-to-back half-blocks",
            "8.  + Mutual non-adjacency",
            "9.  + Require break between segments",
            "10. + Max consecutive blocks",
            "11. + Earliest start block",
            "12. + Even block start",
            "13. + Rotation link rules (Y7N+Elective=N)",
            "14. + PGY cap constraints",
            "15. + Full-year coverage"
        };

        Map<Integer, Rotation> rotById = rotations.stream()
            .collect(Collectors.toMap(Rotation::getId, r -> r));

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
                if (s >= 1)  cb.applyWorkloadCapConstraints(residents, rotations);
                if (s >= 2)  cb.applyCoverageConstraints(residents, rotations);
                if (s >= 3)  cb.applyMaxBlocksPerResidentConstraints(residents, rotations, reqMap);
                if (s >= 4)  cb.applyPrerequisiteConstraints(residents, prereqMap, rotById);
                if (s >= 5)  cb.applySequenceRules(residents, seqMap);
                if (s >= 6)  cb.applyNoBackToBackHalfBlockConstraints(residents, rotations);
                if (s >= 7)  cb.applyMutualNonAdjacencyConstraints(residents, rotations);
                if (s >= 8)  cb.applyRequireBreakBetweenSegmentsConstraints(residents, rotations);
                if (s >= 9)  cb.applyMaxConsecutiveBlocksConstraints(residents, rotations);
                if (s >= 10) cb.applyEarliestStartConstraints(residents, rotations);
                if (s >= 11) cb.applyEvenBlockStartConstraints(residents, rotations);
                if (s >= 12) cb.applyRotationLinkConstraints(residents, rotations);
                if (s >= 13) cb.applyPgyCapConstraints(residents, rotations);
                if (s >= 14) cb.applyFullYearCoverageConstraints(residents, rotations);
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
            if (firstInfeasible == 3) {
                log.append("\n  Drilling into Step 4 — adding (resident, rotation) pairs one at a time:\n");
                drillMaxBlocksConstraint(log, residents, rotations, config, reqMap,
                    eligibleByRotation, rotationLengths, auxCoverage, totalBlocks);
            } else if (firstInfeasible == 10) {
                log.append("\n  Drilling into Step 11 — earliest start block constraints:\n");
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

    /** Applies all 15 constraints then drops each one individually to find which removal restores feasibility. */
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

        String[] labels = {
            "Workload caps",
            "Coverage",
            "Max blocks per resident",
            "Prerequisites",
            "Sequence rules",
            "No-back-to-back half-blocks",
            "Mutual non-adjacency",
            "Require break between segments",
            "Max consecutive blocks",
            "Earliest start block",
            "Even block start",
            "Rotation link rules",
            "PGY cap constraints",
            "Full-year coverage"
        };

        // Use a longer timeout per removal test since we know the full problem is hard
        int removalTimeoutSec = 30;

        for (int drop = 0; drop < labels.length; drop++) {
            CpModel m = new CpModel();
            VariableFactory vf = new VariableFactory(m, totalBlocks, rotationLengths);
            vf.createAll(residents, rotations, eligibleByRotation);
            BlockExpansionService bes = new BlockExpansionService(m, vf, totalBlocks, rotationLengths);
            bes.applyAll(residents, rotations);
            bes.applyNoOverlapAcrossRotations(residents, rotations);
            ConstraintBuilder cb = new ConstraintBuilder(m, vf, config, totalBlocks, auxCoverage);

            if (drop != 0)  cb.applyWorkloadCapConstraints(residents, rotations);
            if (drop != 1)  cb.applyCoverageConstraints(residents, rotations);
            if (drop != 2)  cb.applyMaxBlocksPerResidentConstraints(residents, rotations, reqMap);
            if (drop != 3)  cb.applyPrerequisiteConstraints(residents, prereqMap, rotById);
            if (drop != 4)  cb.applySequenceRules(residents, seqMap);
            if (drop != 5)  cb.applyNoBackToBackHalfBlockConstraints(residents, rotations);
            if (drop != 6)  cb.applyMutualNonAdjacencyConstraints(residents, rotations);
            if (drop != 7)  cb.applyRequireBreakBetweenSegmentsConstraints(residents, rotations);
            if (drop != 8)  cb.applyMaxConsecutiveBlocksConstraints(residents, rotations);
            if (drop != 9)  cb.applyEarliestStartConstraints(residents, rotations);
            if (drop != 10) cb.applyEvenBlockStartConstraints(residents, rotations);
            if (drop != 11) cb.applyRotationLinkConstraints(residents, rotations);
            if (drop != 12) cb.applyPgyCapConstraints(residents, rotations);
            if (drop != 13) cb.applyFullYearCoverageConstraints(residents, rotations);

            CpSolver sv = new CpSolver();
            sv.getParameters().setMaxTimeInSeconds(removalTimeoutSec);
            sv.getParameters().setNumWorkers(config.getCpSatNumWorkers());
            CpSolverStatus st = sv.solve(m);

            String result = switch (st) {
                case OPTIMAL, FEASIBLE -> "FEASIBLE ← removing this unblocks the schedule";
                case INFEASIBLE        -> "still INFEASIBLE";
                default                -> "UNKNOWN (timeout)";
            };
            log.append(String.format("    drop %-35s → %s\n", labels[drop], result));

            if (st == CpSolverStatus.FEASIBLE || st == CpSolverStatus.OPTIMAL) {
                log.append(String.format(
                    "\n  ★ Constraint '%s' is part of the conflict. " +
                    "Review its settings — relaxing or removing it should restore feasibility.\n",
                    labels[drop]));
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
                        int minLen = Arrays.stream(policy.allowedBlockLengths).min().orElse(1);
                        totalMinDemand += (int) Math.ceil(req.getMinBlocks()) * minLen;
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

            int minLen = Arrays.stream(policy.allowedBlockLengths).min().orElse(1);
            int needed = (int) Math.ceil(req.getMinBlocks()) * minLen;
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
                int maxWks = s.getMaxBlocksAllowed();
                int minWks = 0;
                if (req != null && req.isRequired()) {
                    int[] lengths = config.getPolicyFor(s.getId()).allowedBlockLengths;
                    int minLen = Arrays.stream(lengths).min().orElse(2);
                    minWks = (int) Math.ceil(req.getMinBlocks()) * minLen;
                }
                addedPairs.add(new int[]{r.getId(), s.getId(), minWks, maxWks});

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

                String pairLabel = String.format("PGY-%d %-15s + %-30s [min=%d max=%d wks]",
                    r.getPgyLevel(), r.getName(), s.getName(), minWks, maxWks);

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
                        int[] lengths = config.getPolicyFor(s.getId()).allowedBlockLengths;
                        int minLen = Arrays.stream(lengths).min().orElse(2);
                        totalMinDemand += (int) Math.ceil(req.getMinBlocks()) * minLen;
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
