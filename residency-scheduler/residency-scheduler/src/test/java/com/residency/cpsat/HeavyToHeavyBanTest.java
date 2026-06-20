package com.residency.cpsat;

import com.residency.model.*;
import com.google.ortools.Loader;
import com.google.ortools.sat.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link ConstraintBuilder#applyHeavyToHeavyBan} — the absolute rule that a HEAVY rotation
 * may never be immediately followed by a DIFFERENT heavy rotation. Heavy membership is taken from
 * {@link WorkloadTiers#HEAVY} (by name), so the medium/consult rotations Inpatient GI and
 * Infectious Disease are NOT heavy and may follow a heavy rotation.
 */
class HeavyToHeavyBanTest {

    private static final int ICU = 1, VA = 2, GI = 3;

    @BeforeAll
    static void loadNativeLibs() {
        Loader.loadNativeLibraries();
    }

    @Test
    void differentHeavyAdjacency_isInfeasible() {
        Fix f = new Fix();
        f.build();
        // ICU (heavy) at block 0, VA (a DIFFERENT heavy) at block 1.
        f.model.addEquality(f.vars.getOccupancyVar(1, ICU, 0), 1);
        f.model.addEquality(f.vars.getOccupancyVar(1, VA, 1), 1);
        f.cb.applyHeavyToHeavyBan(f.residents, f.rotations);
        assertEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "heavy → different-heavy must be infeasible");
    }

    @Test
    void sameHeavyContinuation_isFeasible() {
        Fix f = new Fix();
        f.build();
        // ICU at block 0 AND block 1 — same heavy rotation continuing, not a jump.
        f.model.addEquality(f.vars.getOccupancyVar(1, ICU, 0), 1);
        f.model.addEquality(f.vars.getOccupancyVar(1, ICU, 1), 1);
        f.cb.applyHeavyToHeavyBan(f.residents, f.rotations);
        assertNotEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "continuing the same heavy rotation across a boundary must stay feasible");
    }

    @Test
    void heavyThenMedium_isFeasible_giIsNotHeavy() {
        Fix f = new Fix();
        f.build();
        // ICU (heavy) at block 0, Inpatient GI (medium, NOT heavy) at block 1.
        f.model.addEquality(f.vars.getOccupancyVar(1, ICU, 0), 1);
        f.model.addEquality(f.vars.getOccupancyVar(1, GI, 1), 1);
        f.cb.applyHeavyToHeavyBan(f.residents, f.rotations);
        assertNotEquals(CpSolverStatus.INFEASIBLE, f.solve(),
            "Inpatient GI is medium, not heavy — heavy → GI must stay feasible");
    }

    // ── minimal single-resident, 2-block fixture ────────────────────────────
    private static final class Fix {
        final int totalBlocks = 2;
        final ScheduleConfig config = new ScheduleConfig();
        final List<Resident> residents = new ArrayList<>(List.of(res(1)));
        final List<Rotation> rotations = new ArrayList<>(List.of(
            rot(ICU, "ICU"), rot(VA, "VA"), rot(GI, "Inpatient GI")));
        final Map<Integer, int[]> rotLengths = new HashMap<>();
        final Map<Integer, Set<Integer>> eligible = new HashMap<>();
        CpModel model; VariableFactory vars; BlockExpansionService bes; ConstraintBuilder cb; CpSolver solver;

        Fix() {
            config.setTotalBlocks(totalBlocks);
            config.setGlobalMinWorkloadBlocks(0);
            config.setGlobalMaxWorkloadBlocks(totalBlocks);
            for (int id : new int[]{ICU, VA, GI}) {
                rotLengths.put(id, new int[]{1});                  // half-block (1 slot) so it can repeat/share
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
            Resident r = new Resident(); r.setId(id); r.setName("R" + id); r.setPgyLevel(1); return r;
        }
        static Rotation rot(int id, String name) {
            Rotation r = new Rotation(); r.setId(id); r.setName(name);
            r.setMaxResidentsPerBlock(9); r.setMaxBlocksAllowed(52); return r;
        }
    }
}
