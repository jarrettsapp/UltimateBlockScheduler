package com.residency.cpsat;

import com.residency.model.Rotation;
import com.residency.model.WorkloadTiers;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fix for the silently-disabled Sunday-coverage objective: with empty DB config (the
 * normal case, since there is no UI to set these), the heavy / Sunday-source tier IDs must be
 * resolved by rotation name so {@code buildSundayCoverageObjective} actually runs. An explicit
 * config must still win.
 */
class TierDefaultsTest {

    private static final Set<Integer> HEAVY_IDS  = Set.of(1, 3, 6, 11, 14, 16);
    private static final Set<Integer> SOURCE_IDS = Set.of(2, 4, 8, 9, 13, 15, 17, 18, 19, 21);

    private static Rotation rot(int id, String name) {
        Rotation r = new Rotation();
        r.setId(id);
        r.setName(name);
        return r;
    }

    /** The 16-rotation program set, ids matching the live database. */
    private static List<Rotation> standardRotations() {
        return List.of(
            rot(1, "VA"), rot(3, "Younker 8 Pulmonology"), rot(6, "Broadlawns"),
            rot(11, "Younker 7 Nights"), rot(14, "ICU"), rot(16, "Younker 7 Days"),
            rot(19, "Inpatient GI"), rot(15, "Infectious Disease"),
            rot(2, "Outpatient GI"), rot(4, "Outpatient Pulmonology"), rot(8, "Ambulatory A"),
            rot(9, "Emergency Medicine"), rot(13, "Outpatient UPH Cardiology"),
            rot(17, "Addiction Medicine"), rot(18, "Outpatient TIC Cardiology"), rot(21, "Elective"));
    }

    @Test
    void tiersResolveByNameAndPartitionAllRotations() {
        List<Rotation> rots = standardRotations();
        assertEquals(HEAVY_IDS, WorkloadTiers.heavyIds(rots));
        assertEquals(SOURCE_IDS, WorkloadTiers.sundaySourceIds(rots));
        // heavy and Sunday-source are disjoint and together cover all 16 rotations.
        Set<Integer> union = new HashSet<>(WorkloadTiers.heavyIds(rots));
        union.addAll(WorkloadTiers.sundaySourceIds(rots));
        assertEquals(16, union.size());
    }

    @Test
    void emptyConfigGetsTierDefaultsByName() {
        ScheduleConfig cfg = new ScheduleConfig();
        assertTrue(cfg.getHeavyRotationIds().isEmpty());
        assertTrue(cfg.getSundaySourceRotationIds().isEmpty());

        CpSatSchedulerEngine.applyTierDefaults(cfg, standardRotations());

        assertEquals(HEAVY_IDS, cfg.getHeavyRotationIds(),
            "without this the Sunday-coverage objective silently disables itself");
        assertEquals(SOURCE_IDS, cfg.getSundaySourceRotationIds());
    }

    @Test
    void explicitConfigIsNotOverridden() {
        ScheduleConfig cfg = new ScheduleConfig();
        cfg.setHeavyRotationIds(new HashSet<>(Set.of(99)));
        cfg.setSundaySourceRotationIds(new HashSet<>(Set.of(88)));

        CpSatSchedulerEngine.applyTierDefaults(cfg, standardRotations());

        assertEquals(Set.of(99), cfg.getHeavyRotationIds());
        assertEquals(Set.of(88), cfg.getSundaySourceRotationIds());
    }
}
