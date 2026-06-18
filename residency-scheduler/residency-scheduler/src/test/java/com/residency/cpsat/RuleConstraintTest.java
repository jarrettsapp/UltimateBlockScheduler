package com.residency.cpsat;

import com.residency.model.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantics tests for the rule-based hard constraints that previously had no
 * coverage (REVIEW.md M4): prerequisites, sequence/adjacency rules, and the
 * per-resident max-blocks (slot) cap.
 *
 * Each test builds a tiny model, applies one constraint family, pins down an
 * assignment, and asserts the solver honours the rule — or returns INFEASIBLE
 * when the rule makes the pinned scenario impossible.
 */
class RuleConstraintTest {

    @BeforeAll
    static void loadNativeLibs() {
        Loader.loadNativeLibraries();
    }

    // ── Prerequisites ───────────────────────────────────────────────────────

    @Test
    void prerequisite_rotationCannotStartInFirstBlockIfPrereqUnmet() {
        // B (rot 2) requires A (rot 1). Force B into block 0; A cannot have run
        // before block 0, so the model must be INFEASIBLE.
        Fixture f = new Fixture(8, new int[]{2});
        f.eligible(1, 1).eligible(2, 1);
        f.build();

        // Prereq: rotation 2 requires rotation 1 first (all PGY levels).
        Map<Integer, List<Prerequisite>> prereqMap = Map.of(
            2, List.of(new Prerequisite(1, 2, 1, null)));
        f.cb.applyPrerequisiteConstraints(f.residents, prereqMap, f.rotById());

        // Force rotation 2 to occupy block 0.
        f.model.addEquality(f.vars.getOccupancyVar(1, 2, 0), 1);

        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "B in block 0 with no room for prerequisite A beforehand must be infeasible");
    }

    @Test
    void prerequisite_satisfiedWhenPrereqHasRoomEarlier() {
        // Same prereq, but force B late (block 4) so A can run earlier. Feasible,
        // and in any solution A must occupy some block strictly before B.
        Fixture f = new Fixture(8, new int[]{2});
        f.eligible(1, 1).eligible(2, 1);
        f.build();

        Map<Integer, List<Prerequisite>> prereqMap = Map.of(
            2, List.of(new Prerequisite(1, 2, 1, null)));
        f.cb.applyPrerequisiteConstraints(f.residents, prereqMap, f.rotById());

        f.model.addEquality(f.vars.getOccupancyVar(1, 2, 4), 1);

        CpSolverStatus status = f.solve();
        assertTrue(status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE,
            "B in block 4 leaves room for prerequisite A — should be feasible");

        boolean aBeforeB = false;
        for (int b = 0; b < 4; b++) {
            if (f.solver.booleanValue(f.vars.getOccupancyVar(1, 1, b))) { aBeforeB = true; break; }
        }
        assertTrue(aBeforeB, "Prerequisite A must occupy some block before B");
    }

    @Test
    void prerequisite_scopedToOtherPgy_doesNotConstrainThisResident() {
        // Prereq applies only to PGY-2, but our resident is PGY-1, so forcing B
        // into block 0 must remain feasible (the rule is skipped for PGY-1).
        Fixture f = new Fixture(8, new int[]{2});
        f.eligible(1, 1).eligible(2, 1);
        f.build();

        Map<Integer, List<Prerequisite>> prereqMap = Map.of(
            2, List.of(new Prerequisite(1, 2, 1, 2))); // pgyLevel = 2 only
        f.cb.applyPrerequisiteConstraints(f.residents, prereqMap, f.rotById());

        f.model.addEquality(f.vars.getOccupancyVar(1, 2, 0), 1);

        CpSolverStatus status = f.solve();
        assertTrue(status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE,
            "PGY-scoped prerequisite must not constrain a resident of a different PGY level");
    }

    // ── Sequence / adjacency rules ──────────────────────────────────────────

    @Test
    void cannotImmediatelyFollow_blocksAdjacentStart() {
        // Rule: rotation 2 cannot immediately follow rotation 1. Pin rotation 1
        // into block 0 (a 2-block segment covers blocks 0-1) and force a rotation-2
        // segment to start at block 2 (right after). Must be INFEASIBLE.
        Fixture f = new Fixture(8, new int[]{2});
        f.eligible(1, 1).eligible(2, 1);
        f.build();

        Map<Integer, List<RotationSequenceRule>> seqMap = Map.of(
            2, List.of(new RotationSequenceRule(1, 2, 1,
                RotationSequenceRuleType.CANNOT_IMMEDIATELY_FOLLOW, null)));
        f.cb.applySequenceRules(f.residents, seqMap);

        // rotation 1 occupies block 1 (so block 1 is the immediate predecessor of a block-2 start)
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 1), 1);
        // rotation 2 starts at block 2
        f.model.addEquality(f.vars.getStartVar(1, 2, 2), 1);

        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "rotation 2 starting immediately after rotation 1 must be blocked");
    }

    @Test
    void cannotImmediatelyFollow_allowsNonAdjacentPlacement() {
        // Same rule, but leave a gap: rotation 1 in block 0-1, rotation 2 starting
        // at block 4. Not immediately following → feasible.
        Fixture f = new Fixture(8, new int[]{2});
        f.eligible(1, 1).eligible(2, 1);
        f.build();

        Map<Integer, List<RotationSequenceRule>> seqMap = Map.of(
            2, List.of(new RotationSequenceRule(1, 2, 1,
                RotationSequenceRuleType.CANNOT_IMMEDIATELY_FOLLOW, null)));
        f.cb.applySequenceRules(f.residents, seqMap);

        f.model.addEquality(f.vars.getStartVar(1, 1, 0), 1);
        f.model.addEquality(f.vars.getStartVar(1, 2, 4), 1);

        CpSolverStatus status = f.solve();
        assertTrue(status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE,
            "A gap between the two rotations should satisfy CANNOT_IMMEDIATELY_FOLLOW");
    }

    @Test
    void mustBeAfter_forbidsRelatedRotationNeverRunning() {
        // Rule: rotation 2 MUST_BE_AFTER rotation 1. Force rotation 2 into block 0
        // and forbid rotation 1 entirely → nothing can precede → INFEASIBLE.
        Fixture f = new Fixture(8, new int[]{2});
        f.eligible(1, 1).eligible(2, 1);
        f.build();

        Map<Integer, List<RotationSequenceRule>> seqMap = Map.of(
            2, List.of(new RotationSequenceRule(1, 2, 1,
                RotationSequenceRuleType.MUST_BE_AFTER, null)));
        f.cb.applySequenceRules(f.residents, seqMap);

        f.model.addEquality(f.vars.getOccupancyVar(1, 2, 0), 1);

        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "MUST_BE_AFTER with the related rotation unable to precede must be infeasible");
    }

    // ── Per-resident max-blocks (slot) cap ──────────────────────────────────

    @Test
    void maxBlocksPerResident_capsOccupancyAtConvertedSlots() {
        // Rotation maxBlocksAllowed = 4 WEEKS -> 2 slots. With half-block (1-slot)
        // segments allowed, a single resident may occupy at most 2 blocks of it.
        Fixture f = new Fixture(8, new int[]{1});
        f.eligible(1, 1);
        f.build();
        f.rotations.get(0).setMaxBlocksAllowed(4); // 4 weeks = 2 slots

        f.cb.applyMaxBlocksPerResidentConstraints(f.residents, f.rotations, Map.of());

        // Try to force 3 separate blocks of the rotation — exceeds the 2-slot cap.
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 0), 1);
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 2), 1);
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 4), 1);

        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "3 blocks must violate a 4-week (=2-slot) max-blocks cap");
    }

    @Test
    void maxBlocksPerResident_allowsUpToConvertedSlots() {
        Fixture f = new Fixture(8, new int[]{1});
        f.eligible(1, 1);
        f.build();
        f.rotations.get(0).setMaxBlocksAllowed(4); // 2 slots

        f.cb.applyMaxBlocksPerResidentConstraints(f.residents, f.rotations, Map.of());

        // Exactly 2 blocks — at the cap, should be feasible.
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 0), 1);
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 2), 1);

        CpSolverStatus status = f.solve();
        assertTrue(status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE,
            "2 blocks is exactly the 4-week (=2-slot) cap — should be feasible");
    }

    // ── Test fixture ────────────────────────────────────────────────────────

    /** Minimal single-resident, two-rotation model harness for constraint tests. */
    private static final class Fixture {
        final int totalBlocks;
        final ScheduleConfig config = new ScheduleConfig();
        final List<Resident> residents = List.of(res(1, 1));
        final List<Rotation> rotations = new ArrayList<>(List.of(rot(1), rot(2)));
        final Map<Integer, int[]> rotLengths = new HashMap<>();
        final Map<Integer, Set<Integer>> eligible = new HashMap<>();

        CpModel model;
        VariableFactory vars;
        BlockExpansionService bes;
        ConstraintBuilder cb;
        CpSolver solver;

        Fixture(int totalBlocks, int[] lengths) {
            this.totalBlocks = totalBlocks;
            config.setTotalBlocks(totalBlocks);
            config.setGlobalMinWorkloadBlocks(0);
            config.setGlobalMaxWorkloadBlocks(totalBlocks);
            for (Rotation r : rotations) {
                rotLengths.put(r.getId(), lengths.clone());
                config.getPolicyFor(r.getId()).allowedBlockLengths = lengths.clone();
                config.getPolicyFor(r.getId()).minPerBlock = 0; // no forced coverage
                config.getPolicyFor(r.getId()).maxPerBlock = 5;
            }
        }

        Fixture eligible(int rotId, int... residentIds) {
            Set<Integer> pool = new HashSet<>();
            for (int id : residentIds) pool.add(id);
            eligible.put(rotId, pool);
            return this;
        }

        void build() {
            // Default any unset eligibility to "resident 1 eligible".
            for (Rotation r : rotations) eligible.putIfAbsent(r.getId(), Set.of(1));
            model = new CpModel();
            vars = new VariableFactory(model, totalBlocks, rotLengths);
            vars.createAll(residents, rotations, eligible);
            bes = new BlockExpansionService(model, vars, totalBlocks, rotLengths);
            bes.applyAll(residents, rotations);
            bes.applyNoOverlapAcrossRotations(residents, rotations);
            cb = new ConstraintBuilder(model, vars, config, totalBlocks);
        }

        Map<Integer, Rotation> rotById() {
            Map<Integer, Rotation> m = new HashMap<>();
            for (Rotation r : rotations) m.put(r.getId(), r);
            return m;
        }

        CpSolverStatus solve() {
            solver = new CpSolver();
            solver.getParameters().setMaxTimeInSeconds(5);
            solver.getParameters().setNumWorkers(1);
            return solver.solve(model);
        }

        static Resident res(int id, int pgy) {
            Resident r = new Resident(); r.setId(id); r.setName("R" + id); r.setPgyLevel(pgy); return r;
        }

        static Rotation rot(int id) {
            Rotation r = new Rotation(); r.setId(id); r.setName("Rot" + id);
            r.setMaxResidentsPerBlock(5); r.setMaxBlocksAllowed(52); return r;
        }
    }
}
