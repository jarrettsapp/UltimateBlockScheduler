package com.residency.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScheduleMetrics} — the pure metric computation over schedule grids.
 * Uses small hand-built grids so the expected numbers are obvious.
 */
class ScheduleMetricsTest {

    private static String[] slots() {
        String[] g = new String[ScheduleMetrics.SLOTS];
        Arrays.fill(g, "Ambulatory A"); // all light by default
        return g;
    }

    @Test
    void tierClassification_followsAuthoritativeList_notRotationType() {
        assertEquals(ScheduleMetrics.Tier.HEAVY, ScheduleMetrics.tierOf("ICU"));
        assertEquals(ScheduleMetrics.Tier.HEAVY, ScheduleMetrics.tierOf("Younker 8 Pulmonology"));
        assertEquals(ScheduleMetrics.Tier.MEDIUM, ScheduleMetrics.tierOf("Inpatient GI"));
        assertEquals(ScheduleMetrics.Tier.MEDIUM, ScheduleMetrics.tierOf("Infectious Disease"));
        assertEquals(ScheduleMetrics.Tier.LIGHT, ScheduleMetrics.tierOf("Emergency Medicine"));
        assertEquals(ScheduleMetrics.Tier.LIGHT, ScheduleMetrics.tierOf(null));
    }

    @Test
    void icuCategoricalCapViolation_isDetected() {
        // Two categoricals both on ICU at slot 0 → cap violation (ICU cat <= 1).
        Map<String, String[]> grid = new LinkedHashMap<>();
        String[] a = slots(); a[0] = "ICU";
        String[] b = slots(); b[0] = "ICU";
        grid.put("R1", a); grid.put("R2", b);

        ScheduleMetrics.Result r = ScheduleMetrics.compute(grid, Map.of());
        assertFalse(r.capacityClean(), "two categoricals on ICU must be flagged");
        assertTrue(r.capacityViolations.stream().anyMatch(s -> s.contains("ICU")),
            "violation should mention ICU");
    }

    @Test
    void vaCapAllowsTwoButFlagsThree() {
        // Note: capacityClean() also checks the block-13 Y7 Days rule, which a tiny grid
        // won't satisfy, so assert specifically on the presence/absence of a VA violation.
        Map<String, String[]> two = new LinkedHashMap<>();
        for (int i = 1; i <= 2; i++) { String[] g = slots(); g[4] = "VA"; two.put("R" + i, g); }
        assertFalse(hasViolationContaining(ScheduleMetrics.compute(two, Map.of()), "VA"),
            "2 categoricals on VA is allowed");

        Map<String, String[]> three = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) { String[] g = slots(); g[4] = "VA"; three.put("R" + i, g); }
        assertTrue(hasViolationContaining(ScheduleMetrics.compute(three, Map.of()), "VA"),
            "3 categoricals on VA must be flagged");
    }

    private static boolean hasViolationContaining(ScheduleMetrics.Result r, String token) {
        return r.capacityViolations.stream().anyMatch(s -> s.contains(token));
    }

    @Test
    void icuTotalCountsAuxTowardCapOfTwo() {
        // 1 categorical + 2 aux on ICU at slot 0 → total 3 > 2.
        Map<String, String[]> grid = new LinkedHashMap<>();
        String[] g = slots(); g[0] = "ICU"; grid.put("R1", g);
        Map<String, int[]> aux = new HashMap<>();
        int[] icu = new int[ScheduleMetrics.SLOTS]; icu[0] = 2; aux.put("ICU", icu);

        ScheduleMetrics.Result r = ScheduleMetrics.compute(grid, aux);
        assertTrue(r.capacityViolations.stream().anyMatch(s -> s.contains("ICU total")),
            "ICU total of 3 (1 cat + 2 aux) must be flagged");
    }

    @Test
    void volunteerWeekend_whenEveryoneOnOrEnteringHeavy() {
        // Single resident, on heavy the whole time → never an eligible coverer.
        Map<String, String[]> grid = new LinkedHashMap<>();
        String[] g = new String[ScheduleMetrics.SLOTS];
        Arrays.fill(g, "VA");
        grid.put("R1", g);

        ScheduleMetrics.Result r = ScheduleMetrics.compute(grid, Map.of());
        assertEquals(ScheduleMetrics.SLOTS - 1, r.volunteerWeekends,
            "a resident on heavy all year yields a volunteer at every weekend");
        assertEquals(0, r.healthyWeekends);
    }

    @Test
    void eligibleCoverer_whenOnSourceAndNotEnteringHeavy() {
        // Resident on Inpatient GI (a Sunday source) with a light next block → eligible.
        Map<String, String[]> grid = new LinkedHashMap<>();
        String[] g = slots();
        g[0] = "Inpatient GI"; g[1] = "Ambulatory A";
        grid.put("R1", g);

        ScheduleMetrics.Result r = ScheduleMetrics.compute(grid, Map.of());
        assertEquals(1, r.covererCounts[0], "GI now + light next = 1 eligible coverer at weekend 0");
    }

    @Test
    void preLock_blocksCovererEnteringHeavyNextBlock() {
        // On GI (source) but entering VA (heavy) next → NOT eligible (pre-rotation rest lock).
        Map<String, String[]> grid = new LinkedHashMap<>();
        String[] g = slots();
        g[0] = "Inpatient GI"; g[1] = "VA";
        grid.put("R1", g);

        ScheduleMetrics.Result r = ScheduleMetrics.compute(grid, Map.of());
        assertEquals(0, r.covererCounts[0], "entering heavy next block disqualifies the coverer");
    }

    @Test
    void heavyMediumRunLength_measuredInWeeks() {
        // 4 consecutive heavy/medium slots = 8 weeks.
        Map<String, String[]> grid = new LinkedHashMap<>();
        String[] g = slots();
        g[0] = "VA"; g[1] = "VA"; g[2] = "Inpatient GI"; g[3] = "ICU";
        grid.put("R1", g);

        ScheduleMetrics.Result r = ScheduleMetrics.compute(grid, Map.of());
        assertEquals(1, r.heavyMediumRunWeeks.getOrDefault(8, 0), "one 8-week run expected");
        assertEquals(1, r.runsOver6Weeks, "8 > 6 counts as an over-6-week run");
    }
}
