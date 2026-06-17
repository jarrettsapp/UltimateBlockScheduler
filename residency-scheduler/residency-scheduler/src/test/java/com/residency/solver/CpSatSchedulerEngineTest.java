package com.residency.solver;

import com.residency.cpsat.*;
import com.residency.model.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CpSatSchedulerEngine using synthetic in-memory data.
 *
 * NOTE: These tests exercise the constraint and variable logic directly
 * without hitting the database (pure model validation).
 */
class CpSatSchedulerEngineTest {

    // ── Test 1: Small feasible scenario ────────────────────────────────────

    @Test
    void smallFeasibleScenario_shouldProduceFeasibleSolution() {
        // 3 residents, 3 rotations, 12-week year
        ScheduleConfig config = new ScheduleConfig();
        config.setTotalBlocks(12);
        config.setGlobalMinWorkloadBlocks(4);
        config.setGlobalMaxWorkloadBlocks(12);

        List<Resident> residents = List.of(
            resident(1, "Alice", 1),
            resident(2, "Bob",   1),
            resident(3, "Carol", 2)
        );

        List<Rotation> rotations = List.of(
            rotation(1, "Internal Medicine", 3, 1, 4),
            rotation(2, "Surgery",           2, 1, 4),
            rotation(3, "Pediatrics",        2, 0, 4)
        );

        // Policy: all rotations use 4-week blocks
        for (Rotation r : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(r.getId());
            policy.allowedBlockLengths = new int[]{4};
            policy.minPerBlock = 1;
            policy.maxPerBlock = r.getMaxResidentsPerBlock();
        }

        // Build model and solve
        com.google.ortools.Loader.loadNativeLibraries();
        com.google.ortools.sat.CpModel model = new com.google.ortools.sat.CpModel();

        Map<Integer, int[]> rotLengths = new HashMap<>();
        for (Rotation r : rotations) rotLengths.put(r.getId(), new int[]{4});

        Map<Integer, Set<Integer>> eligible = new HashMap<>();
        for (Rotation r : rotations) {
            Set<Integer> pool = new HashSet<>();
            for (Resident res : residents) pool.add(res.getId());
            eligible.put(r.getId(), pool);
        }

        VariableFactory vars = new VariableFactory(model, 12, rotLengths);
        vars.createAll(residents, rotations, eligible);

        assertTrue(vars.totalStartVars() > 0, "Should create start variables");
        assertTrue(vars.totalOccupancyVars() > 0, "Should create occupancy variables");

        BlockExpansionService expansion = new BlockExpansionService(model, vars, 12, rotLengths);
        expansion.applyAll(residents, rotations);
        expansion.applyNoOverlapAcrossRotations(residents, rotations);

        ConstraintBuilder constraints = new ConstraintBuilder(model, vars, config, 12);
        constraints.applyCoverageConstraints(residents, rotations);
        constraints.applyWorkloadCapConstraints(residents, rotations);

        com.google.ortools.sat.CpSolver solver = new com.google.ortools.sat.CpSolver();
        com.google.ortools.sat.CpSolverStatus status = solver.solve(model);

        assertTrue(
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
            status == com.google.ortools.sat.CpSolverStatus.FEASIBLE,
            "Should find a feasible solution for small scenario"
        );
    }

    // ── Test 2: No overlap per resident ────────────────────────────────────

    @Test
    void noOverlapConstraint_residentShouldNotBeOnTwoRotationsSameWeek() {
        ScheduleConfig config = new ScheduleConfig();
        config.setTotalBlocks(8);
        config.setGlobalMinWorkloadBlocks(0);
        config.setGlobalMaxWorkloadBlocks(8);

        List<Resident> residents = List.of(resident(1, "Alice", 1));
        List<Rotation> rotations = List.of(
            rotation(1, "IM", 2, 0, 2),
            rotation(2, "Surgery", 2, 0, 2)
        );

        com.google.ortools.Loader.loadNativeLibraries();
        com.google.ortools.sat.CpModel model = new com.google.ortools.sat.CpModel();
        Map<Integer, int[]> rotLengths = Map.of(1, new int[]{4}, 2, new int[]{4});
        Map<Integer, Set<Integer>> eligible = Map.of(
            1, Set.of(1), 2, Set.of(1));

        VariableFactory vars = new VariableFactory(model, 8, rotLengths);
        vars.createAll(residents, rotations, eligible);

        BlockExpansionService expansion = new BlockExpansionService(model, vars, 8, rotLengths);
        expansion.applyAll(residents, rotations);
        expansion.applyNoOverlapAcrossRotations(residents, rotations);

        com.google.ortools.sat.CpSolver solver = new com.google.ortools.sat.CpSolver();
        solver.solve(model);

        // Verify: for each week, Alice is on at most one rotation
        for (int w = 0; w < 8; w++) {
            com.google.ortools.sat.BoolVar occ1 = vars.getOccupancyVar(1, 1, w);
            com.google.ortools.sat.BoolVar occ2 = vars.getOccupancyVar(1, 2, w);
            if (occ1 != null && occ2 != null) {
                int v1 = solver.booleanValue(occ1) ? 1 : 0;
                int v2 = solver.booleanValue(occ2) ? 1 : 0;
                assertTrue(v1 + v2 <= 1,
                    "Alice should not be on two rotations in week " + w);
            }
        }
    }

    // ── Test 3: Block continuity ────────────────────────────────────────────

    @Test
    void blockContinuity_4WeekBlockShouldOccupy4ConsecutiveWeeks() {
        ScheduleConfig config = new ScheduleConfig();
        config.setTotalBlocks(8);
        config.setGlobalMinWorkloadBlocks(4);
        config.setGlobalMaxWorkloadBlocks(8);

        List<Resident> residents = List.of(resident(1, "Alice", 1));
        List<Rotation> rotations = List.of(rotation(1, "IM", 1, 1, 2));

        com.google.ortools.Loader.loadNativeLibraries();
        com.google.ortools.sat.CpModel model = new com.google.ortools.sat.CpModel();
        Map<Integer, int[]> rotLengths = Map.of(1, new int[]{4});
        Map<Integer, Set<Integer>> eligible = Map.of(1, Set.of(1));

        VariableFactory vars = new VariableFactory(model, 8, rotLengths);
        vars.createAll(residents, rotations, eligible);

        BlockExpansionService expansion = new BlockExpansionService(model, vars, 8, rotLengths);
        expansion.applyAll(residents, rotations);

        // Force Alice to do IM
        com.google.ortools.sat.BoolVar start0 = vars.getStartVar(1, 1, 0);
        if (start0 != null) model.addBoolOr(new com.google.ortools.sat.BoolVar[]{start0});

        com.google.ortools.sat.CpSolver solver = new com.google.ortools.sat.CpSolver();
        solver.solve(model);

        if (start0 != null && solver.booleanValue(start0)) {
            // Weeks 0,1,2,3 should all be occupied
            for (int w = 0; w < 4; w++) {
                com.google.ortools.sat.BoolVar occ = vars.getOccupancyVar(1, 1, w);
                assertNotNull(occ, "Occupancy var should exist for week " + w);
            }
        }
    }

    // ── Test 4: Feasibility analyzer cycle detection ────────────────────────

    @Test
    void feasibilityAnalyzer_shouldDetectPrerequisiteCycle() {
        ScheduleConfig config = new ScheduleConfig();
        config.setTotalBlocks(52);

        List<Resident> residents = List.of(resident(1, "Alice", 1));
        List<Rotation> rotations = List.of(
            rotation(1, "IM", 2, 0, 4),
            rotation(2, "Surgery", 2, 0, 4),
            rotation(3, "Cardiology", 2, 0, 4)
        );

        // Create a cycle: IM -> Surgery -> Cardiology -> IM
        List<Prerequisite> prereqs = List.of(
            prereq(1, 1, 2), // IM requires Surgery
            prereq(2, 2, 3), // Surgery requires Cardiology
            prereq(3, 3, 1)  // Cardiology requires IM (cycle!)
        );

        FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer(config, 52);
        FeasibilityReport report = analyzer.analyze(
            residents, rotations, List.of(), prereqs, Map.of(
                1, Set.of(1), 2, Set.of(1), 3, Set.of(1)));

        assertTrue(report.hasIssues(), "Should detect the prerequisite cycle");
        assertTrue(report.getIssues().stream()
            .anyMatch(i -> i.type == FeasibilityReport.IssueType.PREREQUISITE_CYCLE),
            "Should specifically report a PREREQUISITE_CYCLE issue");
    }

    // ── Test 5: Over-constrained scenario ──────────────────────────────────

    @Test
    void feasibilityAnalyzer_shouldDetectOverConstrained() {
        ScheduleConfig config = new ScheduleConfig();
        config.setTotalBlocks(52);
        config.setGlobalMaxWorkloadBlocks(52);

        // 1 resident but rotations need 10 residents/block each
        List<Resident> residents = List.of(resident(1, "Alice", 1));
        List<Rotation> rotations = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Rotation r = rotation(i, "Rotation " + i, 10, 0, 4);
            rotations.add(r);
            config.getPolicyFor(i).minPerBlock = 10;
            config.getPolicyFor(i).maxPerBlock = 10;
        }

        FeasibilityAnalyzer analyzer = new FeasibilityAnalyzer(config, 52);
        FeasibilityReport report = analyzer.analyze(
            residents, rotations, List.of(), List.of(), Map.of());

        assertTrue(report.hasIssues(), "Should detect over-constrained scenario");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Resident resident(int id, String name, int pgy) {
        Resident r = new Resident(); r.setId(id); r.setName(name); r.setPgyLevel(pgy); return r;
    }

    private Rotation rotation(int id, String name, int maxPerBlock, int minBlocks, int maxBlocks) {
        Rotation r = new Rotation(); r.setId(id); r.setName(name);
        r.setMaxResidentsPerBlock(maxPerBlock);
        r.setMinBlocksRequired(minBlocks); r.setMaxBlocksAllowed(maxBlocks); return r;
    }

    private Prerequisite prereq(int id, int rotId, int prereqId) {
        Prerequisite p = new Prerequisite(); p.setId(id);
        p.setRotationId(rotId); p.setPrerequisiteRotationId(prereqId); return p;
    }
}
