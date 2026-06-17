package com.residency.cpsat;

import com.residency.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes a human-readable breakdown of how well each constraint category
 * was satisfied in the final solution. Appended to the solver log after solving.
 *
 * All checks are done analytically from the assignment map — no solver access needed.
 */
public class SolutionScoreReporter {

    private final ScheduleConfig config;
    private final int totalBlocks;

    public SolutionScoreReporter(ScheduleConfig config, int totalBlocks) {
        this.config      = config;
        this.totalBlocks = totalBlocks;
    }

    public String buildReport(ScheduleSolution solution,
                              List<Resident> residents,
                              List<Rotation> rotations) {
        if (!solution.isFeasible()) return "\n(No feasible solution — score report unavailable)\n";

        // Build assignment map: residentId → rotationId → sorted list of block indices
        Map<Integer, Map<Integer, List<Integer>>> assigned = new HashMap<>();
        for (Resident r : residents) {
            Map<Integer, List<Integer>> byRot = new HashMap<>();
            for (Rotation s : rotations) {
                List<Integer> blks = new ArrayList<>(solution.getAssignedWeeks(r.getId(), s.getId()));
                if (!blks.isEmpty()) {
                    Collections.sort(blks);
                    byRot.put(s.getId(), blks);
                }
            }
            assigned.put(r.getId(), byRot);
        }

        Map<Integer, String> rotName = rotations.stream()
            .collect(Collectors.toMap(Rotation::getId, Rotation::getName));
        Set<Integer> inpatientIds = rotations.stream()
            .filter(r -> r.getRotationType() == RotationType.INPATIENT)
            .map(Rotation::getId).collect(Collectors.toSet());
        Set<Integer> outpatientIds = rotations.stream()
            .filter(r -> r.getRotationType() == RotationType.OUTPATIENT)
            .map(Rotation::getId).collect(Collectors.toSet());

        StringBuilder sb = new StringBuilder();
        // ── Pre-compute weighted score breakdown ──────────────────────────
        // Mirrors ObjectiveFunctionBuilder exactly so the numbers match the solver.

        // α: undercoverage per rotation
        Map<String, long[]> underByRot = new LinkedHashMap<>(); // rotName → [rawBlocks, weightedCost]
        long totalUnder = 0;
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            if (policy.minPerBlock <= 0) continue;
            long rawBlocks = 0;
            for (int b = 0; b < totalBlocks; b++) {
                int coverage = 0;
                for (Resident r : residents)
                    if (assigned.get(r.getId()).getOrDefault(s.getId(), List.of()).contains(b)) coverage++;
                rawBlocks += Math.max(0, policy.minPerBlock - coverage);
            }
            long weighted = rawBlocks * config.getWeightUndercoverage();
            if (rawBlocks > 0) underByRot.put(s.getName(), new long[]{rawBlocks, weighted});
            totalUnder += weighted;
        }

        // β: overcoverage per rotation
        Map<String, long[]> overByRot = new LinkedHashMap<>();
        long totalOver = 0;
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            long rawBlocks = 0;
            for (int b = 0; b < totalBlocks; b++) {
                int coverage = 0;
                for (Resident r : residents)
                    if (assigned.get(r.getId()).getOrDefault(s.getId(), List.of()).contains(b)) coverage++;
                rawBlocks += Math.max(0, coverage - policy.maxPerBlock);
            }
            long weighted = rawBlocks * config.getWeightOvercoverage();
            if (rawBlocks > 0) overByRot.put(s.getName(), new long[]{rawBlocks, weighted});
            totalOver += weighted;
        }

        // γ: workload variance (pairwise absolute differences)
        long totalVariance = 0;
        if (config.getWeightVariance() > 0 && residents.size() > 1) {
            List<Integer> loads = new ArrayList<>();
            for (Resident r : residents)
                loads.add(assigned.get(r.getId()).values().stream().mapToInt(List::size).sum());
            for (int i = 0; i < loads.size(); i++)
                for (int j = i + 1; j < loads.size(); j++)
                    totalVariance += (long) Math.abs(loads.get(i) - loads.get(j)) * config.getWeightVariance();
        }

        // δ: PGY imbalance
        long totalPgyImbalance = 0;
        if (config.getWeightPgyImbalance() > 0) {
            Map<Integer, List<Resident>> byPgy = new LinkedHashMap<>();
            for (Resident r : residents) byPgy.computeIfAbsent(r.getPgyLevel(), k -> new ArrayList<>()).add(r);
            List<Integer> pgyLevels = new ArrayList<>(byPgy.keySet());
            for (Rotation s : rotations) {
                for (int b = 0; b < totalBlocks; b++) {
                    List<Integer> pgyCounts = new ArrayList<>();
                    for (int pgy : pgyLevels) {
                        int cnt = 0;
                        for (Resident r : byPgy.get(pgy))
                            if (assigned.get(r.getId()).getOrDefault(s.getId(), List.of()).contains(b)) cnt++;
                        pgyCounts.add(cnt);
                    }
                    for (int i = 0; i < pgyCounts.size(); i++)
                        for (int j = i + 1; j < pgyCounts.size(); j++)
                            totalPgyImbalance += (long) Math.abs(pgyCounts.get(i) - pgyCounts.get(j)) * config.getWeightPgyImbalance();
                }
            }
        }

        // ε: pattern (Tier 3)
        long totalPattern = 0;
        Set<Integer> inpatientIdsP = rotations.stream()
            .filter(r -> r.getRotationType() == RotationType.INPATIENT)
            .map(Rotation::getId).collect(Collectors.toSet());
        Set<Integer> outpatientIdsP = rotations.stream()
            .filter(r -> r.getRotationType() == RotationType.OUTPATIENT)
            .map(Rotation::getId).collect(Collectors.toSet());
        if (config.getWeightFourPlusTwo() > 0) {
            for (Resident r : residents) {
                for (int b = 0; b < totalBlocks; b++) {
                    int pos = b % 3;
                    Set<Integer> wrong = pos < 2 ? outpatientIdsP : inpatientIdsP;
                    for (int rotId : wrong)
                        if (assigned.get(r.getId()).getOrDefault(rotId, List.of()).contains(b))
                            totalPattern += config.getWeightFourPlusTwo();
                }
            }
        }

        long grandTotal = totalUnder + totalOver + totalVariance + totalPgyImbalance + totalPattern;

        sb.append("\n═══════════════════════════════════════════════════\n");
        sb.append("  FINAL CONSTRAINT SCORE REPORT\n");
        sb.append("═══════════════════════════════════════════════════\n");

        // ── Score summary at the top ───────────────────────────────────────
        sb.append(String.format("\n  TOTAL SCORE: %d\n\n", grandTotal));
        sb.append(String.format("  %-35s  %6s  wt  %8s\n", "Component", "raw", "weighted"));
        sb.append("  " + "─".repeat(60) + "\n");
        sb.append(String.format("  %-35s  %6s  ×%d  %8d%s\n",
            "α Undercoverage", underByRot.values().stream().mapToLong(v -> v[0]).sum(),
            config.getWeightUndercoverage(), totalUnder,
            totalUnder == grandTotal ? "  ◄ 100%" : totalUnder > grandTotal * 0.5 ? "  ◄ dominant" : ""));
        sb.append(String.format("  %-35s  %6s  ×%d  %8d%s\n",
            "β Overcoverage", overByRot.values().stream().mapToLong(v -> v[0]).sum(),
            config.getWeightOvercoverage(), totalOver,
            totalOver == grandTotal ? "  ◄ 100%" : totalOver > grandTotal * 0.5 ? "  ◄ dominant" : ""));
        sb.append(String.format("  %-35s  %6s  ×%d  %8d%s\n",
            "γ Workload variance", "",
            config.getWeightVariance(), totalVariance,
            totalVariance == grandTotal ? "  ◄ 100%" : totalVariance > grandTotal * 0.5 ? "  ◄ dominant" : ""));
        sb.append(String.format("  %-35s  %6s  ×%d  %8d%s\n",
            "δ PGY imbalance", "",
            config.getWeightPgyImbalance(), totalPgyImbalance,
            totalPgyImbalance == grandTotal ? "  ◄ 100%" : totalPgyImbalance > grandTotal * 0.5 ? "  ◄ dominant" : ""));
        sb.append(String.format("  %-35s  %6s  ×%d  %8d%s\n",
            "ε 2+1 pattern violations", "",
            config.getWeightFourPlusTwo(), totalPattern,
            totalPattern == grandTotal ? "  ◄ 100%" : totalPattern > grandTotal * 0.5 ? "  ◄ dominant" : ""));
        sb.append("  " + "─".repeat(60) + "\n");
        sb.append(String.format("  %-35s  %6s       %8d\n\n", "TOTAL", "", grandTotal));

        if (!underByRot.isEmpty()) {
            sb.append("  Undercoverage by rotation (contributing rotations only):\n");
            underByRot.entrySet().stream()
                .sorted((a2, b2) -> Long.compare(b2.getValue()[1], a2.getValue()[1]))
                .forEach(e -> sb.append(String.format(
                    "    %-35s  %d blk(s) short  → +%d\n", e.getKey(), e.getValue()[0], e.getValue()[1])));
            sb.append("\n");
        }
        if (!overByRot.isEmpty()) {
            sb.append("  Overcoverage by rotation (contributing rotations only):\n");
            overByRot.entrySet().stream()
                .sorted((a2, b2) -> Long.compare(b2.getValue()[1], a2.getValue()[1]))
                .forEach(e -> sb.append(String.format(
                    "    %-35s  %d blk(s) over   → +%d\n", e.getKey(), e.getValue()[0], e.getValue()[1])));
            sb.append("\n");
        }

        // ── Tier 1: Clinical quality ──────────────────────────────────────
        sb.append("\n── Tier 1: Clinical Quality ──\n");

        int postCallHard = 0, postCallSoft = 0;
        List<String> postCallHardDetail = new ArrayList<>();
        List<String> postCallSoftDetail = new ArrayList<>();

        for (Resident r : residents) {
            Map<Integer, List<Integer>> byRot = assigned.get(r.getId());
            for (int t = 0; t + 1 < totalBlocks; t++) {
                for (int triggerId : config.getPostCallTriggerRotationIds()) {
                    List<Integer> tb = byRot.getOrDefault(triggerId, List.of());
                    if (!tb.contains(t)) continue;
                    String trigName = rotName.getOrDefault(triggerId, "rot#" + triggerId);

                    for (int mandId : config.getMandatoryAttendanceRotationIds()) {
                        List<Integer> mb = byRot.getOrDefault(mandId, List.of());
                        if (mb.contains(t + 1)) {
                            postCallHard++;
                            postCallHardDetail.add(String.format(
                                "    %s: %s (blk %d) → %s (blk %d)",
                                r.getName(), trigName, t,
                                rotName.getOrDefault(mandId, "rot#" + mandId), t + 1));
                        }
                    }
                    for (int discId : config.getDiscouragedAfterTriggerIds()) {
                        List<Integer> db = byRot.getOrDefault(discId, List.of());
                        if (db.contains(t + 1)) {
                            postCallSoft++;
                            postCallSoftDetail.add(String.format(
                                "    %s: %s (blk %d) → %s (blk %d)",
                                r.getName(), trigName, t,
                                rotName.getOrDefault(discId, "rot#" + discId), t + 1));
                        }
                    }
                }
            }
        }

        sb.append(String.format("  Post-call hard violations:     %d\n", postCallHard));
        postCallHardDetail.forEach(d -> sb.append(d).append('\n'));
        sb.append(String.format("  Post-call soft (discouraged):  %d\n", postCallSoft));
        postCallSoftDetail.forEach(d -> sb.append(d).append('\n'));

        int inpTransitions = 0;
        List<String> inpDetail = new ArrayList<>();
        List<Integer> inpList = new ArrayList<>(inpatientIds);
        for (Resident r : residents) {
            Map<Integer, List<Integer>> byRot = assigned.get(r.getId());
            for (int t = 1; t < totalBlocks; t++) {
                Integer before = null, after = null;
                for (int sid : inpList) {
                    List<Integer> b = byRot.getOrDefault(sid, List.of());
                    if (b.contains(t - 1)) before = sid;
                    if (b.contains(t))     after  = sid;
                }
                if (before != null && after != null && !before.equals(after)) {
                    inpTransitions++;
                    inpDetail.add(String.format(
                        "    %s: %s → %s at block %d",
                        r.getName(),
                        rotName.getOrDefault(before, "rot#" + before),
                        rotName.getOrDefault(after,  "rot#" + after), t));
                }
            }
        }
        sb.append(String.format("  Inpatient→inpatient transitions: %d\n", inpTransitions));
        inpDetail.forEach(d -> sb.append(d).append('\n'));

        long tier1Score =
            (long) postCallHard    * config.getWeightPostCallHard()
          + (long) postCallSoft    * config.getWeightPostCallSoft()
          + (long) inpTransitions  * config.getWeightInpatientSplit();
        sb.append(String.format(
            "  ─ Tier-1 score: %d  (%d primary ×%d + %d secondary ×%d + %d inpatient ×%d)\n",
            tier1Score,
            postCallHard,   config.getWeightPostCallHard(),
            postCallSoft,   config.getWeightPostCallSoft(),
            inpTransitions, config.getWeightInpatientSplit()));

        // ── Tier 2: Schedule quality ──────────────────────────────────────
        sb.append("\n── Tier 2: Schedule Quality ──\n");

        // Coverage per rotation
        sb.append("  Coverage (min/max residents per block):\n");
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int min = policy.minPerBlock;
            int max = policy.maxPerBlock;
            int underCount = 0, overCount = 0;
            for (int b = 0; b < totalBlocks; b++) {
                int coverage = 0;
                for (Resident r : residents) {
                    List<Integer> blks = assigned.get(r.getId())
                        .getOrDefault(s.getId(), List.of());
                    if (blks.contains(b)) coverage++;
                }
                if (min > 0 && coverage < min) underCount++;
                if (coverage > max) overCount++;
            }
            if (underCount > 0 || overCount > 0) {
                sb.append(String.format("    %-30s  under: %d blk(s)  over: %d blk(s)\n",
                    s.getName(), underCount, overCount));
            }
        }

        // Workload per resident
        sb.append("  Workload (blocks assigned per resident):\n");
        int minLoad = Integer.MAX_VALUE, maxLoad = 0, totalLoad = 0;
        for (Resident r : residents) {
            int load = assigned.get(r.getId()).values().stream()
                .mapToInt(List::size).sum();
            minLoad = Math.min(minLoad, load);
            maxLoad = Math.max(maxLoad, load);
            totalLoad += load;
            sb.append(String.format("    %-20s  %d blocks\n", r.getName(), load));
        }
        if (!residents.isEmpty()) {
            sb.append(String.format("    Range: %d–%d  (avg %.1f)  spread: %d\n",
                minLoad == Integer.MAX_VALUE ? 0 : minLoad,
                maxLoad,
                residents.isEmpty() ? 0.0 : (double) totalLoad / residents.size(),
                maxLoad - (minLoad == Integer.MAX_VALUE ? 0 : minLoad)));
        }

        // Max consecutive blocks
        sb.append("  Max consecutive block violations:\n");
        boolean anyConsecViol = false;
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int maxConsec = policy.maxConsecutiveBlocks;
            if (maxConsec <= 0) continue;
            for (Resident r : residents) {
                List<Integer> blks = assigned.get(r.getId())
                    .getOrDefault(s.getId(), List.of());
                int maxRun = longestRun(blks);
                if (maxRun > maxConsec) {
                    anyConsecViol = true;
                    sb.append(String.format("    %s on %-25s  run=%d  (max allowed %d)\n",
                        r.getName(), s.getName(), maxRun, maxConsec));
                }
            }
        }
        if (!anyConsecViol) sb.append("    ✓ None\n");

        // No back-to-back single-block segments
        sb.append("  Back-to-back single-block violations:\n");
        boolean anyBtbViol = false;
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            if (!policy.noBackToBackHalfBlocks) continue;
            for (Resident r : residents) {
                List<Integer> blks = assigned.get(r.getId())
                    .getOrDefault(s.getId(), List.of());
                for (int i = 0; i + 1 < blks.size(); i++) {
                    if (blks.get(i + 1) == blks.get(i) + 1) {
                        anyBtbViol = true;
                        sb.append(String.format("    %s on %-25s  blocks %d & %d adjacent\n",
                            r.getName(), s.getName(), blks.get(i), blks.get(i + 1)));
                    }
                }
            }
        }
        if (!anyBtbViol) sb.append("    ✓ None\n");

        // Earliest start violations
        sb.append("  Earliest-start violations:\n");
        boolean anyEarlyViol = false;
        for (Rotation s : rotations) {
            int earliest = config.getPolicyFor(s.getId()).earliestStartBlock;
            if (earliest <= 0) continue;
            for (Resident r : residents) {
                List<Integer> blks = assigned.get(r.getId())
                    .getOrDefault(s.getId(), List.of());
                for (int b : blks) {
                    if (b < earliest) {
                        anyEarlyViol = true;
                        sb.append(String.format("    %s on %-25s  block %d (min allowed %d)\n",
                            r.getName(), s.getName(), b, earliest));
                    }
                }
            }
        }
        if (!anyEarlyViol) sb.append("    ✓ None\n");

        // 2+1 pattern (block-scale inpatient/outpatient cycle)
        if (!inpatientIds.isEmpty() && !outpatientIds.isEmpty()) {
            int patternViolations = 0;
            for (Resident r : residents) {
                Map<Integer, List<Integer>> byRot = assigned.get(r.getId());
                for (int b = 0; b < totalBlocks; b++) {
                    int pos = b % 3;
                    boolean isInpatientSlot = pos < 2;
                    for (int rotId : (isInpatientSlot ? outpatientIds : inpatientIds)) {
                        if (byRot.getOrDefault(rotId, List.of()).contains(b))
                            patternViolations++;
                    }
                }
            }
            sb.append(String.format("  2+1 inpatient/outpatient pattern violations: %d block(s)\n",
                patternViolations));
        }

        sb.append("\n═══════════════════════════════════════════════════\n");
        return sb.toString();
    }

    /** Returns the length of the longest contiguous run in a sorted list of block indices. */
    private static int longestRun(List<Integer> sorted) {
        if (sorted.isEmpty()) return 0;
        int max = 1, cur = 1;
        for (int i = 1; i < sorted.size(); i++) {
            cur = (sorted.get(i) == sorted.get(i - 1) + 1) ? cur + 1 : 1;
            max = Math.max(max, cur);
        }
        return max;
    }
}
