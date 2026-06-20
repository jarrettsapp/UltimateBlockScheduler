package com.residency.cpsat;

import com.residency.model.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Tier-3 categorical soft-cap objective
 * ({@link ObjectiveFunctionBuilder#buildCategoricalSoftCapObjective}): categoricals beyond a
 * rotation's {@code categoricalSoftCap} are allowed (up to the hard
 * {@code categoricalMaxPerBlock}) but counted as excess so Phase 3 can penalize them.
 */
class CategoricalSoftCapTest {

    @BeforeAll
    static void loadNativeLibs() {
        Loader.loadNativeLibraries();
    }

    /** 3 categoricals with soft cap 2 and hard cap 3: feasible, excess = 1. */
    @Test
    void thirdCategorical_allowedButCountedAsExcess() {
        Fix f = new Fix(/*soft*/2, /*hard*/3, /*residents*/3);
        f.build();
        for (int r = 1; r <= 3; r++) f.model.addEquality(f.vars.getOccupancyVar(r, 1, 0), 1);
        f.cb.applyCategoricalCapConstraints(f.residents, f.rotations); // hard cap 3
        IntVar excess = f.obj.buildCategoricalSoftCapObjective(f.residents, f.rotations);
        f.model.minimize(excess);
        assertNotEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "3 categoricals under hard cap 3 must be feasible");
        assertEquals(1, f.solver.value(excess), "3 categoricals vs soft cap 2 → excess 1");
    }

    /** Exactly at the soft cap: no penalty. */
    @Test
    void atSoftCap_noPenalty() {
        Fix f = new Fix(2, 3, 2);
        f.build();
        for (int r = 1; r <= 2; r++) f.model.addEquality(f.vars.getOccupancyVar(r, 1, 0), 1);
        IntVar excess = f.obj.buildCategoricalSoftCapObjective(f.residents, f.rotations);
        f.model.minimize(excess);
        f.solve();
        assertEquals(0, f.solver.value(excess), "2 categoricals at soft cap 2 → no excess");
    }

    /** No soft cap configured: the objective is identically zero even with 3 categoricals. */
    @Test
    void noSoftCap_objectiveZero() {
        Fix f = new Fix(/*soft*/0, 3, 3);
        f.build();
        for (int r = 1; r <= 3; r++) f.model.addEquality(f.vars.getOccupancyVar(r, 1, 0), 1);
        IntVar excess = f.obj.buildCategoricalSoftCapObjective(f.residents, f.rotations);
        f.model.minimize(excess);
        f.solve();
        assertEquals(0, f.solver.value(excess), "no soft cap → zero penalty");
    }

    private static final class Fix {
        final int totalBlocks = 2;
        final ScheduleConfig config = new ScheduleConfig();
        final List<Resident> residents = new ArrayList<>();
        final List<Rotation> rotations = new ArrayList<>(List.of(rot(1)));
        final Map<Integer, int[]> rotLengths = new HashMap<>();
        final Map<Integer, Set<Integer>> eligible = new HashMap<>();
        CpModel model; VariableFactory vars; BlockExpansionService bes;
        ConstraintBuilder cb; ObjectiveFunctionBuilder obj; CpSolver solver;

        Fix(int softCap, int hardCap, int numResidents) {
            for (int i = 1; i <= numResidents; i++) residents.add(res(i));
            config.setTotalBlocks(totalBlocks);
            config.setGlobalMinWorkloadBlocks(0);
            config.setGlobalMaxWorkloadBlocks(totalBlocks);
            rotLengths.put(1, new int[]{1});
            config.getPolicyFor(1).allowedBlockLengths = new int[]{1};
            config.getPolicyFor(1).minPerBlock = 0;
            config.getPolicyFor(1).maxPerBlock = 9;
            config.getPolicyFor(1).categoricalMaxPerBlock = hardCap;
            config.getPolicyFor(1).categoricalSoftCap = softCap;
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
            obj = new ObjectiveFunctionBuilder(model, vars, config, totalBlocks);
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
