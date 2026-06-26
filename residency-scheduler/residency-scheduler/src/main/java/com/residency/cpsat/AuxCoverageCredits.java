package com.residency.cpsat;

import com.residency.model.Resident;
import com.residency.model.Rotation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for the SYNTHETIC auxiliary-coverage credit applied at model-build
 * time, shared by BOTH solvers (CP-SAT {@link CpSatSchedulerEngine} and the Timefold
 * {@code TimefoldSchedulerService}) so they cannot drift.
 *
 * <p>Why synthetic credit (not just physical aux rows): some rotations demand more per-block
 * coverage than the categoricals can supply, with the gap covered by auxiliaries (TY/BMC).
 * The aux rows are written POST-solve and, for a movable schedule, are EXCLUDED from the
 * coverage facts both solvers count (so the optimizer is free to shift categoricals into
 * those slots). That exclusion would leave the hard coverage floor unsatisfiable — so both
 * solvers instead credit the aux coverage synthetically here, identically.
 *
 * <h2>Rules encoded (user-confirmed, 2026-06-26)</h2>
 * <ul>
 *   <li><b>VA</b> — TY fill the 2nd body on every block: credit {@code min_per_week − 1}
 *       (= 1) per block, clamped to the TY count. Drops VA effectiveMin 2→1 so a single
 *       categorical per block suffices; the per-resident VA requirement (4 slots × 11 = 44)
 *       already forces categoricals to carry their 44 of 52, leaving 8 for TY.</li>
 *   <li><b>Younker 8 Pulmonology</b> — BMC statically staff 6A (slot 10) and 12A (slot 22):
 *       credit 1 there. The remaining TY gap is handled by a per-solver GLOBAL categorical
 *       floor (see {@link #younker8CategoricalFloor}), NOT a per-block credit, so the 2 TY
 *       slots float freely.</li>
 * </ul>
 *
 * <p>All credits use {@code merge(slot, credit, Math::max)} so they never stack on top of a
 * real physical aux assignment that survived exclusion — the synthetic floor and a real row
 * are the same 1 slot, never 2.
 */
public final class AuxCoverageCredits {

    /** Younker 8 Pulm BMC-static slot indices: 6A = slot 10, 12A = slot 22. */
    public static final int[] YOUNKER8_BMC_SLOTS = { 10, 22 };

    private AuxCoverageCredits() {}

    private static long countGroup(List<Resident> auxResidents, String group) {
        return auxResidents.stream()
            .filter(r -> group.equalsIgnoreCase(r.getResidentGroup()))
            .count();
    }

    private static Integer rotationIdByName(List<Rotation> rotations, String name) {
        return rotations.stream()
            .filter(r -> name.equalsIgnoreCase(r.getName()))
            .map(Rotation::getId).findFirst().orElse(null);
    }

    /**
     * Mutates {@code auxCoverage} (rotationId → slotIndex → count) in place with the VA and
     * Younker-8-Pulm BMC-static per-block credits described in the class javadoc.
     */
    public static void applyPerBlockCredits(Map<Integer, Map<Integer, Integer>> auxCoverage,
            List<Rotation> rotations, List<Resident> auxResidents,
            ScheduleConfig config, int totalBlocks) {

        long tyCount  = countGroup(auxResidents, "TY");
        long bmcCount = countGroup(auxResidents, "BMC");

        // VA: TY fill the 2nd-body gap on every block (effectiveMin 2→1).
        Integer vaId = rotationIdByName(rotations, "VA");
        if (vaId != null && tyCount > 0) {
            int minPerBlock = config.getPolicyFor(vaId).minPerBlock;
            if (minPerBlock > 1) {
                int credit = (int) Math.min(minPerBlock - 1, tyCount); // = 1 with min=2, 4 TY
                Map<Integer, Integer> byBlock = auxCoverage.computeIfAbsent(vaId, k -> new HashMap<>());
                for (int b = 0; b < totalBlocks; b++) byBlock.merge(b, credit, Math::max);
            }
        }

        // Younker 8 Pulm: BMC static at 6A/12A.
        Integer y8Id = rotationIdByName(rotations, "Younker 8 Pulmonology");
        if (y8Id != null && bmcCount > 0 && config.getPolicyFor(y8Id).minPerBlock > 0) {
            Map<Integer, Integer> byBlock = auxCoverage.computeIfAbsent(y8Id, k -> new HashMap<>());
            for (int slot : YOUNKER8_BMC_SLOTS) {
                if (slot < totalBlocks) byBlock.merge(slot, 1, Math::max);
            }
        }
    }

    /**
     * The hard GLOBAL floor on total categorical Younker-8-Pulm occupancy: total demand
     * (min_per_week × 26) minus aux supply (≤2 BMC static + all TY). Returns -1 if Y8Pulm
     * has no per-block min or no aux exist (no floor needed). Both solvers enforce this in
     * place of the per-block hard floor so the TY slots float.
     */
    public static int younker8CategoricalFloor(List<Rotation> rotations,
            List<Resident> auxResidents, ScheduleConfig config, int totalBlocks) {
        Integer y8Id = rotationIdByName(rotations, "Younker 8 Pulmonology");
        if (y8Id == null) return -1;
        int minPerBlock = config.getPolicyFor(y8Id).minPerBlock;
        if (minPerBlock <= 0) return -1;
        long bmcCount = countGroup(auxResidents, "BMC");
        long tyCount  = countGroup(auxResidents, "TY");
        if (bmcCount == 0 && tyCount == 0) return -1;
        long auxSupply = Math.min(bmcCount, 2) + tyCount;
        return (int) Math.max(0, (long) minPerBlock * totalBlocks - auxSupply);
    }

    /** The Younker-8-Pulm rotation id, or null. Convenience for callers registering the floor. */
    public static Integer younker8RotationId(List<Rotation> rotations) {
        return rotationIdByName(rotations, "Younker 8 Pulmonology");
    }
}
