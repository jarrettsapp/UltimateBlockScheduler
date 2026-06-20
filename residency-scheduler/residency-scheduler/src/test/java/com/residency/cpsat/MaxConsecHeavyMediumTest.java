package com.residency.cpsat;

import com.residency.model.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ConstraintBuilder#applyMaxConsecHeavyMedium} (hard mode). The configured value is
 * the true maximum consecutive heavy+medium RUN in weeks: 12 permits a 6-slot (12-week) run such
 * as 4 heavy → 4 medium → 4 heavy, but forbids a 7th consecutive heavy/medium slot. Heavy and
 * medium count together; a light rotation resets the run.
 */
class MaxConsecHeavyMediumTest {

    private static final int ICU = 1;     // heavy
    private static final int GI_IN = 2;   // medium (Inpatient GI)
    private static final int GI_OUT = 3;  // light  (Outpatient GI) — resets the run

    @BeforeAll
    static void loadNativeLibs() {
        Loader.loadNativeLibraries();
    }

    @Test
    void run12wk_sixHeavySlots_isFeasible() {
        Fix f = new Fix(/*limitWeeks*/12);
        f.build();
        for (int b = 0; b <= 5; b++) f.model.addEquality(f.vars.getOccupancyVar(1, ICU, b), 1); // 6 slots = 12 wk
        f.cb.applyMaxConsecHeavyMedium(f.residents, f.rotations);
        assertNotEquals(CpSolverStatus.INFEASIBLE, f.solve(), "a 12-week run must be allowed");
    }

    @Test
    void run14wk_sevenHeavySlots_isInfeasible() {
        Fix f = new Fix(12);
        f.build();
        for (int b = 0; b <= 6; b++) f.model.addEquality(f.vars.getOccupancyVar(1, ICU, b), 1); // 7 slots = 14 wk
        f.cb.applyMaxConsecHeavyMedium(f.residents, f.rotations);
        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(), "a 14-week run must be forbidden");
    }

    @Test
    void heavyPlusMedium_countTogether_sevenSlots_isInfeasible() {
        Fix f = new Fix(12);
        f.build();
        // 3 heavy (ICU) then 4 medium (Inpatient GI) = 7 consecutive heavy/medium slots.
        for (int b = 0; b <= 2; b++) f.model.addEquality(f.vars.getOccupancyVar(1, ICU, b), 1);
        for (int b = 3; b <= 6; b++) f.model.addEquality(f.vars.getOccupancyVar(1, GI_IN, b), 1);
        f.cb.applyMaxConsecHeavyMedium(f.residents, f.rotations);
        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "heavy and medium combine — 7 consecutive H/M slots must be forbidden");
    }

    @Test
    void lightRotation_resetsTheRun_isFeasible() {
        Fix f = new Fix(12);
        f.build();
        // 6 heavy, a light break, then more heavy — never 7 consecutive H/M.
        for (int b = 0; b <= 5; b++) f.model.addEquality(f.vars.getOccupancyVar(1, ICU, b), 1);
        f.model.addEquality(f.vars.getOccupancyVar(1, GI_OUT, 6), 1); // light break
        f.cb.applyMaxConsecHeavyMedium(f.residents, f.rotations);
        assertNotEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "a light rotation resets the run, so this must be feasible");
    }

    private static final class Fix {
        final int totalBlocks = 8;
        final ScheduleConfig config = new ScheduleConfig();
        final List<Resident> residents = new ArrayList<>(List.of(res(1)));
        final List<Rotation> rotations = new ArrayList<>(List.of(
            rot(ICU, "ICU"), rot(GI_IN, "Inpatient GI"), rot(GI_OUT, "Outpatient GI")));
        final Map<Integer, int[]> rotLengths = new HashMap<>();
        final Map<Integer, Set<Integer>> eligible = new HashMap<>();
        CpModel model; VariableFactory vars; BlockExpansionService bes; ConstraintBuilder cb; CpSolver solver;

        Fix(int limitWeeks) {
            config.setTotalBlocks(totalBlocks);
            config.setGlobalMinWorkloadBlocks(0);
            config.setGlobalMaxWorkloadBlocks(totalBlocks);
            config.setMaxConsecutiveHeavyMediumWeeks(limitWeeks);
            config.setMaxConsecHeavyMediumHard(true);
            for (int id : new int[]{ICU, GI_IN, GI_OUT}) {
                rotLengths.put(id, new int[]{1});
                config.getPolicyFor(id).allowedBlockLengths = new int[]{1};
                config.getPolicyFor(id).minPerBlock = 0;
                config.getPolicyFor(id).maxPerBlock = 9;
                eligible.put(id, new HashSet<>(Set.of(1)));
            }
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
            Resident r = new Resident(); r.setId(id); r.setName("R" + id); r.setPgyLevel(1);
            r.setAuxiliary(false); return r;
        }
        static Rotation rot(int id, String name) {
            Rotation r = new Rotation(); r.setId(id); r.setName(name);
            r.setMaxResidentsPerBlock(9); r.setMaxBlocksAllowed(52); return r;
        }
    }
}
