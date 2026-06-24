package com.residency.service;

import com.residency.model.WorkloadTiers;

import java.util.*;

/**
 * Single source of truth for the tracked schedule-quality metrics — capacity compliance,
 * Younker-7 weekend call coverage, and transition quality. Computes the same numbers the
 * iteration report and the in-app version comparison display, so they always agree.
 *
 * <p>Inputs are deliberately simple so this works on both the live schedule and a saved
 * version snapshot: a per-group grid of {@code residentKey -> slotIndex -> rotationName},
 * where slots are the 26 two-week half-blocks (0..25). Rotation tier membership follows the
 * program's authoritative workload tiers (see RULES.md §7), NOT the unreliable
 * {@code rotation_type} flag.
 */
public final class ScheduleMetrics {

    public static final int SLOTS = 26;

    // Authoritative workload tiers (by rotation name) — single source of truth in WorkloadTiers,
    // shared with the solver's eligibility model so the reporter and solver cannot diverge.
    public static final Set<String> HEAVY = WorkloadTiers.HEAVY;
    public static final Set<String> MEDIUM = WorkloadTiers.MEDIUM;
    public static final Set<String> SUNDAY_SOURCE = WorkloadTiers.SUNDAY_SOURCE;

    public enum Tier { HEAVY, MEDIUM, LIGHT }

    public static Tier tierOf(String rotation) {
        if (rotation == null) return Tier.LIGHT;
        if (HEAVY.contains(rotation)) return Tier.HEAVY;
        if (MEDIUM.contains(rotation)) return Tier.MEDIUM;
        return Tier.LIGHT;
    }

    /** Computed metrics for one schedule. */
    public static final class Result {
        // Call coverage (categorical eligibility model).
        public int volunteerWeekends;   // weekends with 0 eligible Sunday coverers
        public int fragileWeekends;     // exactly 1 eligible
        public int healthyWeekends;     // >= 2 eligible
        public int[] covererCounts = new int[SLOTS - 1];
        // Transitions.
        public int heavyToDifferentHeavy;
        public Map<Integer, Integer> heavyMediumRunWeeks = new TreeMap<>(); // run length(wks) -> count
        public int runsOver6Weeks;
        // Capacity compliance (all-groups where noted).
        public List<String> capacityViolations = new ArrayList<>();
        public boolean capacityClean() { return capacityViolations.isEmpty(); }
        // Saturday floor (info).
        public int saturdayNoPulmHalfBlocks;
    }

    /**
     * @param catGrid  categorical residents: key -> [26] rotation names (null/"" = empty)
     * @param auxByRotSlot  total NON-categorical (TY+BMC) occupancy: rotationName -> slot -> count
     *                      (used for the all-groups capacity totals, e.g. ICU cat+TY <= 2)
     */
    public static Result compute(Map<String, String[]> catGrid,
                                 Map<String, int[]> auxByRotSlot) {
        Result r = new Result();
        List<String[]> cats = new ArrayList<>(catGrid.values());

        // ── Coverage: per back-end weekend (boundary slot b -> b+1) ──
        for (int b = 0; b < SLOTS - 1; b++) {
            int eligible = 0;
            for (String[] g : cats) {
                String cur = g[b];
                String next = g[b + 1];
                boolean onSource = cur != null && SUNDAY_SOURCE.contains(cur);
                boolean enteringHeavy = next != null && HEAVY.contains(next);
                boolean onHeavy = cur != null && HEAVY.contains(cur);
                if (onSource && !onHeavy && !enteringHeavy) eligible++;
            }
            r.covererCounts[b] = eligible;
            if (eligible == 0) r.volunteerWeekends++;
            else if (eligible == 1) r.fragileWeekends++;
            else r.healthyWeekends++;
        }

        // ── Transitions ──
        for (String[] g : cats) {
            // direct heavy -> different heavy
            for (int b = 0; b + 1 < SLOTS; b++) {
                String a = g[b], c = g[b + 1];
                if (a != null && c != null && HEAVY.contains(a) && HEAVY.contains(c) && !a.equals(c))
                    r.heavyToDifferentHeavy++;
            }
            // consecutive heavy+medium runs (in weeks; 1 slot = 2 weeks)
            int b = 0;
            while (b < SLOTS) {
                Tier t = tierOf(g[b]);
                if (t == Tier.HEAVY || t == Tier.MEDIUM) {
                    int j = b;
                    while (j < SLOTS && (tierOf(g[j]) == Tier.HEAVY || tierOf(g[j]) == Tier.MEDIUM)) j++;
                    int weeks = (j - b) * 2;
                    r.heavyMediumRunWeeks.merge(weeks, 1, Integer::sum);
                    if (weeks > 6) r.runsOver6Weeks++;
                    b = j;
                } else b++;
            }
        }

        // ── Capacity compliance ──
        int[] catICU = catCount(cats, "ICU");
        int[] catVA = catCount(cats, "VA");
        int[] catBMC = catCount(cats, "Broadlawns");
        int[] catY7D = catCount(cats, "Younker 7 Days");
        for (int b = 0; b < SLOTS; b++) {
            int auxICU = aux(auxByRotSlot, "ICU", b);
            int auxBMC = aux(auxByRotSlot, "Broadlawns", b);
            int auxY7D = aux(auxByRotSlot, "Younker 7 Days", b);
            if (catICU[b] > 1) r.capacityViolations.add(label(b) + ": ICU has " + catICU[b] + " categoricals (max 1)");
            if (catICU[b] + auxICU > 2) r.capacityViolations.add(label(b) + ": ICU total " + (catICU[b] + auxICU) + " (max 2)");
            if (catVA[b] > 2) r.capacityViolations.add(label(b) + ": VA has " + catVA[b] + " categoricals (max 2)");
            if (catBMC[b] + auxBMC > 2) r.capacityViolations.add(label(b) + ": Broadlawns total " + (catBMC[b] + auxBMC) + " (max 2)");
            int y7dTotal = catY7D[b] + auxY7D;
            if (y7dTotal > 2) r.capacityViolations.add(label(b) + ": Younker 7 Days total " + y7dTotal + " (max 2)");
            // Block 13 (slots 24,25) must have 2 categoricals.
            if ((b == 24 || b == 25) && catY7D[b] != 2)
                r.capacityViolations.add(label(b) + ": Younker 7 Days needs 2 categoricals at block 13, has " + catY7D[b]);
        }

        // ── Saturday floor: half-blocks with no categorical on Y8 Pulm ──
        int[] catPulm = catCount(cats, "Younker 8 Pulmonology");
        for (int b = 0; b < SLOTS; b++) if (catPulm[b] == 0) r.saturdayNoPulmHalfBlocks++;

        return r;
    }

    private static int[] catCount(List<String[]> cats, String rot) {
        int[] out = new int[SLOTS];
        for (String[] g : cats)
            for (int b = 0; b < SLOTS; b++)
                if (rot.equals(g[b])) out[b]++;
        return out;
    }

    private static int aux(Map<String, int[]> auxByRotSlot, String rot, int b) {
        int[] arr = auxByRotSlot == null ? null : auxByRotSlot.get(rot);
        return arr == null ? 0 : arr[b];
    }

    public static String label(int slot) {
        return (slot / 2 + 1) + (slot % 2 == 0 ? "A" : "B");
    }

    private ScheduleMetrics() {}
}
