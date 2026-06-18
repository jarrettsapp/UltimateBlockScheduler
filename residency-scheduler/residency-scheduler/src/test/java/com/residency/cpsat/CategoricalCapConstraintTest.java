package com.residency.cpsat;

import com.residency.model.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConstraintBuilder#applyCategoricalCapConstraints} — the per-block
 * categorical-only capacity caps (ICU ≤ 1, VA ≤ 2, etc.) added for the rotation-capacity
 * fixes. These caps bind the number of categorical residents on a rotation in a block,
 * independent of auxiliary coverage.
 */
class CategoricalCapConstraintTest {

    @BeforeAll
    static void loadNativeLibs() {
        Loader.loadNativeLibraries();
    }

    /** With a categorical cap of 1, two residents cannot both occupy the rotation in the same block. */
    @Test
    void cap1_forbidsTwoCategoricalsInSameBlock() {
        Cap f = new Cap(4, /*cap*/1);
        f.build();
        // Force both residents onto rotation 1 in block 0 → must be INFEASIBLE under cap 1.
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 0), 1);
        f.model.addEquality(f.vars.getOccupancyVar(2, 1, 0), 1);
        f.cb.applyCategoricalCapConstraints(f.residents, f.rotations);
        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "two categoricals in one block must be infeasible when the categorical cap is 1");
    }

    /** Cap of 1 still allows a single categorical in the block. */
    @Test
    void cap1_allowsOneCategorical() {
        Cap f = new Cap(4, 1);
        f.build();
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 0), 1);
        f.cb.applyCategoricalCapConstraints(f.residents, f.rotations);
        CpSolverStatus s = f.solve();
        assertTrue(s == CpSolverStatus.OPTIMAL || s == CpSolverStatus.FEASIBLE,
            "one categorical under cap 1 should be feasible");
    }

    /** Cap of 2 permits two but forbids three categoricals in one block (the VA rule). */
    @Test
    void cap2_forbidsThreeCategoricals() {
        Cap f = new Cap(4, 2, /*residents*/3);
        f.build();
        for (int r = 1; r <= 3; r++) f.model.addEquality(f.vars.getOccupancyVar(r, 1, 0), 1);
        f.cb.applyCategoricalCapConstraints(f.residents, f.rotations);
        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "three categoricals in one block must be infeasible when the cap is 2");
    }

    @Test
    void cap2_allowsTwoCategoricals() {
        Cap f = new Cap(4, 2, 3);
        f.build();
        f.model.addEquality(f.vars.getOccupancyVar(1, 1, 0), 1);
        f.model.addEquality(f.vars.getOccupancyVar(2, 1, 0), 1);
        f.cb.applyCategoricalCapConstraints(f.residents, f.rotations);
        CpSolverStatus s = f.solve();
        assertTrue(s == CpSolverStatus.OPTIMAL || s == CpSolverStatus.FEASIBLE,
            "two categoricals under cap 2 should be feasible");
    }

    // ── minimal multi-resident fixture ──────────────────────────────────────
    private static final class Cap {
        final int totalBlocks;
        final ScheduleConfig config = new ScheduleConfig();
        final List<Resident> residents;
        final List<Rotation> rotations = new ArrayList<>(List.of(rot(1)));
        final Map<Integer, int[]> rotLengths = new HashMap<>();
        final Map<Integer, Set<Integer>> eligible = new HashMap<>();
        CpModel model; VariableFactory vars; BlockExpansionService bes; ConstraintBuilder cb; CpSolver solver;

        Cap(int totalBlocks, int cap) { this(totalBlocks, cap, 2); }

        Cap(int totalBlocks, int cap, int numResidents) {
            this.totalBlocks = totalBlocks;
            List<Resident> rs = new ArrayList<>();
            for (int i = 1; i <= numResidents; i++) rs.add(res(i));
            residents = rs;
            config.setTotalBlocks(totalBlocks);
            config.setGlobalMinWorkloadBlocks(0);
            config.setGlobalMaxWorkloadBlocks(totalBlocks);
            // Rotation 1 = a half-block (1-slot) rotation so multiple residents can share a block.
            rotLengths.put(1, new int[]{1});
            config.getPolicyFor(1).allowedBlockLengths = new int[]{1};
            config.getPolicyFor(1).minPerBlock = 0;
            config.getPolicyFor(1).maxPerBlock = 9;
            config.getPolicyFor(1).categoricalMaxPerBlock = cap;
            Set<Integer> pool = new HashSet<>();
            for (Resident r : residents) pool.add(r.getId());
            eligible.put(1, pool);
        }

        void build() {
            model = new CpModel();
            vars = new VariableFactory(model, totalBlocks, rotLengths);
            vars.createAll(residents, rotations, eligible);
            bes = new BlockExpansionService(model, vars, totalBlocks, rotLengths);
            bes.applyAll(residents, rotations);
            bes.applyNoOverlapAcrossRotations(residents, rotations);
            cb = new ConstraintBuilder(model, vars, config, totalBlocks);
        }

        CpSolverStatus solve() {
            solver = new CpSolver();
            solver.getParameters().setMaxTimeInSeconds(5);
            solver.getParameters().setNumWorkers(1);
            return solver.solve(model);
        }

        static Resident res(int id) {
            Resident r = new Resident(); r.setId(id); r.setName("R" + id); r.setPgyLevel(1); return r;
        }
        static Rotation rot(int id) {
            Rotation r = new Rotation(); r.setId(id); r.setName("Rot" + id);
            r.setMaxResidentsPerBlock(9); r.setMaxBlocksAllowed(52); return r;
        }
    }
}
