package com.residency.solver;

import com.residency.cpsat.*;
import com.residency.model.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests for over-constrained scenarios and edge cases.
 * Validates that infeasibility is detected gracefully and that
 * the feasibility analyzer reports actionable diagnostics.
 */
class ConstraintStressTest {

    @BeforeAll
    static void loadNativeLibs() {
        Loader.loadNativeLibraries();
    }

    // ── Test 1: Zero residents → infeasible if coverage required ────────────

    @Test
    void zeroResidents_withMinCoverage_shouldBeInfeasible() {
        ScheduleConfig config = buildConfig(8, 0, 8);
        config.getPolicyFor(1).minPerBlock = 1;

        List<Resident> residents = List.of(); // empty
        List<Rotation> rotations = List.of(rotation(1, "IM", 2, 0, 4));

        FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer(config, 8);
        FeasibilityReport report = analyzer.analyze(
            residents, rotations, List.of(), List.of(), Map.of(1, Set.of()));

        assertTrue(report.hasIssues(), "Zero residents with required coverage should flag issues");
    }

    // ── Test 2: More required weeks than available ──────────────────────────

    @Test
    void impossibleWorkloadBounds_shouldBeDetected() {
        ScheduleConfig config = buildConfig(52, 60, 60); // 60 required weeks in 52-week year

        List<Resident> residents = List.of(resident(1, "Alice", 1));
        List<Rotation> rotations = List.of(rotation(1, "IM", 1, 0, 4));

        FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer(config, 52);
        FeasibilityReport report = analyzer.analyze(
            residents, rotations, List.of(), List.of(), Map.of(1, Set.of(1)));

        assertTrue(report.hasIssues(), "60 required weeks in 52-week year should be infeasible");
        assertTrue(report.getIssues().stream()
            .anyMatch(i -> i.type == FeasibilityReport.IssueType.WORKLOAD_IMPOSSIBLE
                        || i.type == FeasibilityReport.IssueType.OVER_CONSTRAINED));
    }

    // ── Test 3: Capacity too low for min coverage ───────────────────────────

    @Test
    void capacityTooLowForMinCoverage_shouldBeDetected() {
        ScheduleConfig config = buildConfig(4, 0, 4);
        config.getPolicyFor(1).minPerBlock = 5; // need 5/week
        config.getPolicyFor(1).maxPerBlock = 5;

        // Only 2 residents eligible
        List<Resident> residents = List.of(
            resident(1, "Alice", 1),
            resident(2, "Bob", 1)
        );
        List<Rotation> rotations = List.of(rotation(1, "IM", 5, 0, 4));

        FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer(config, 4);
        FeasibilityReport report = analyzer.analyze(
            residents, rotations, List.of(), List.of(),
            Map.of(1, Set.of(1, 2)));

        assertTrue(report.hasIssues(),
            "2 residents can't satisfy minPerWeek=5 requirement");
    }

    // ── Test 4: Fully over-constrained model → solver returns INFEASIBLE ───

    @Test
    void fullyOverConstrained_solverShouldReturnInfeasible() {
        ScheduleConfig config = buildConfig(4, 0, 4);

        List<Resident> residents = List.of(resident(1, "Alice", 1));
        List<Rotation> rotations = List.of(
            rotation(1, "IM",       1, 0, 4),
            rotation(2, "Surgery",  1, 0, 4),
            rotation(3, "Pediatrics",1,0, 4)
        );

        Map<Integer, int[]> rotLengths = Map.of(
            1, new int[]{4}, 2, new int[]{4}, 3, new int[]{4});
        Map<Integer, Set<Integer>> eligible = Map.of(
            1, Set.of(1), 2, Set.of(1), 3, Set.of(1));

        CpModel model = new CpModel();
        VariableFactory vars = new VariableFactory(model, 4, rotLengths);
        vars.createAll(residents, rotations, eligible);

        BlockExpansionService expansion = new BlockExpansionService(model, vars, 4, rotLengths);
        expansion.applyAll(residents, rotations);
        expansion.applyNoOverlapAcrossRotations(residents, rotations);

        ConstraintBuilder constraints = new ConstraintBuilder(model, vars, config, 4);
        // Force all 3 rotations to have min 1 resident/week in 4 weeks
        // but Alice can only be in one place at once → infeasible
        config.getPolicyFor(1).minPerBlock = 1;
        config.getPolicyFor(2).minPerBlock = 1;
        config.getPolicyFor(3).minPerBlock = 1;
        constraints.applyCoverageConstraints(residents, rotations);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(5);
        CpSolverStatus status = solver.solve(model);

        assertEquals(CpSolverStatus.INFEASIBLE, status,
            "One resident cannot cover 3 rotations simultaneously — must be infeasible");
    }

    // ── Test 5: Mixed block lengths (2 and 4 week) ──────────────────────────

    @Test
    void mixedBlockLengths_shouldCreateVariablesForBothLengths() {
        ScheduleConfig config = buildConfig(8, 0, 8);
        config.getPolicyFor(1).allowedBlockLengths = new int[]{2, 4};

        List<Resident> residents = List.of(resident(1, "Alice", 1));
        List<Rotation> rotations = List.of(rotation(1, "IM", 2, 0, 4));

        Map<Integer, int[]> rotLengths = Map.of(1, new int[]{2, 4});
        Map<Integer, Set<Integer>> eligible = Map.of(1, Set.of(1));

        CpModel model = new CpModel();
        VariableFactory vars = new VariableFactory(model, 8, rotLengths);
        vars.createAll(residents, rotations, eligible);

        // Should have start vars at weeks 0-6 (for 2-week) and 0-4 (for 4-week)
        // Week 0: both lengths valid → 2 length-selection vars
        // Week 7: only 1-week remaining → neither fits... week 6: only 2-week fits
        Map<Integer, BoolVar> startVars = vars.getStartVars(1, 1);
        assertFalse(startVars.isEmpty(), "Should create start variables for mixed block lengths");

        // Start var at week 0 should exist (both 2 and 4 week blocks fit)
        assertNotNull(vars.getStartVar(1, 1, 0), "Should have start var at week 0");
        // Start var at week 7 should NOT exist (neither 2 nor 4 week block fits)
        assertNull(vars.getStartVar(1, 1, 7), "Should NOT have start var at week 7 (2+7>8)");
    }

    // ── Test 6: PGY bottleneck detection ────────────────────────────────────

    @Test
    void pgyBottleneck_shouldBeDetected() {
        ScheduleConfig config = buildConfig(52, 0, 52);
        config.getPolicyFor(1).pgyMinMax.put(3, new int[]{2, 99}); // need 2 PGY-3/week

        // Only 1 PGY-3 resident available
        List<Resident> residents = List.of(
            resident(1, "Alice", 1),
            resident(2, "Bob",   3)  // only one PGY-3
        );
        List<Rotation> rotations = List.of(rotation(1, "Cards", 5, 0, 4));

        FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer(config, 52);
        FeasibilityReport report = analyzer.analyze(
            residents, rotations, List.of(), List.of(),
            Map.of(1, Set.of(1, 2)));

        assertTrue(report.hasIssues(), "Should flag PGY-3 bottleneck");
        assertTrue(report.getIssues().stream()
            .anyMatch(i -> i.type == FeasibilityReport.IssueType.PGY_BOTTLENECK));
    }

    // ── Test 7: Large synthetic dataset — performance check ─────────────────

    @Test
    @Timeout(30) // must complete model build within 30 seconds
    void largeScenario_modelBuildShouldCompleteInReasonableTime() {
        ScheduleConfig config = buildConfig(52, 36, 52);

        // 50 residents, 20 rotations
        List<Resident> residents = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            residents.add(resident(i, "Resident " + i, ((i - 1) % 5) + 1));
        }

        List<Rotation> rotations = new ArrayList<>();
        Map<Integer, int[]> rotLengths = new HashMap<>();
        for (int j = 1; j <= 20; j++) {
            rotations.add(rotation(j, "Rotation " + j, 5, 0, 4));
            rotLengths.put(j, new int[]{4});
        }

        Map<Integer, Set<Integer>> eligible = new HashMap<>();
        for (Rotation r : rotations) {
            Set<Integer> pool = new HashSet<>();
            for (Resident res : residents) pool.add(res.getId());
            eligible.put(r.getId(), pool);
        }

        CpModel model = new CpModel();
        VariableFactory vars = new VariableFactory(model, 52, rotLengths);
        vars.createAll(residents, rotations, eligible);

        // Model should build without OOM or timeout
        assertTrue(vars.totalStartVars() > 0, "Should create variables for large scenario");
        System.out.printf("Large scenario: %d start vars, %d occupancy vars%n",
            vars.totalStartVars(), vars.totalOccupancyVars());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ScheduleConfig buildConfig(int blocks, int minLoad, int maxLoad) {
        ScheduleConfig c = new ScheduleConfig();
        c.setTotalBlocks(blocks);
        c.setGlobalMinWorkloadBlocks(minLoad);
        c.setGlobalMaxWorkloadBlocks(maxLoad);
        return c;
    }

    private Resident resident(int id, String name, int pgy) {
        Resident r = new Resident();
        r.setId(id); r.setName(name); r.setPgyLevel(pgy);
        return r;
    }

    private Rotation rotation(int id, String name, int maxPerBlock, int minBlocks, int maxBlocks) {
        Rotation r = new Rotation();
        r.setId(id); r.setName(name);
        r.setMaxResidentsPerBlock(maxPerBlock);
        r.setMinBlocksRequired(minBlocks);
        r.setMaxBlocksAllowed(maxBlocks);
        return r;
    }
}
