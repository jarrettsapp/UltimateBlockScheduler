package com.residency.cpsat;

import com.residency.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pre-solve feasibility analyzer.
 * All temporal values are in BLOCKS (1 block = 2 calendar weeks).
 */
public class FeasibilityAnalyzer {

    private final ScheduleConfig config;
    private final int totalBlocks;

    public FeasibilityAnalyzer(ScheduleConfig config, int totalBlocks) {
        this.config      = config;
        this.totalBlocks = totalBlocks;
    }

    public FeasibilityReport analyze(
            List<Resident> residents,
            List<Rotation> rotations,
            List<RotationRequirement> requirements,
            List<Prerequisite> prerequisites,
            Map<Integer, Set<Integer>> eligibleResidentsByRotation) {
        return analyze(residents, rotations, requirements, prerequisites, eligibleResidentsByRotation, Map.of());
    }

    public FeasibilityReport analyze(
            List<Resident> residents,
            List<Rotation> rotations,
            List<RotationRequirement> requirements,
            List<Prerequisite> prerequisites,
            Map<Integer, Set<Integer>> eligibleResidentsByRotation,
            Map<Integer, Map<Integer, Integer>> auxCoverage) {

        FeasibilityReport report = new FeasibilityReport();

        checkGlobalCapacity(report, residents, rotations, auxCoverage);
        checkRotationPool(report, residents, rotations, requirements, eligibleResidentsByRotation, auxCoverage);
        checkPgyBottlenecks(report, residents, rotations, requirements);
        checkBlockLengths(report, rotations);
        checkPrerequisiteCycles(report, rotations, prerequisites);

        int totalAvailable = residents.size() * config.getGlobalMaxWorkloadBlocks();
        int totalRequired  = calcTotalRequiredBlocks(rotations, requirements, residents);
        report.setTotalResidentWeeksAvailable(totalAvailable);
        report.setTotalResidentWeeksRequired(totalRequired);

        if (totalRequired > totalAvailable) {
            report.addIssue(
                FeasibilityReport.IssueType.WORKLOAD_IMPOSSIBLE,
                "All rotations",
                String.format("Required %d resident-blocks but only %d available (%d residents × %d max blocks)",
                    totalRequired, totalAvailable, residents.size(), config.getGlobalMaxWorkloadBlocks()),
                "Increase resident count, reduce required blocks, or increase max workload"
            );
        }

        return report;
    }

    private void checkGlobalCapacity(FeasibilityReport report,
                                     List<Resident> residents,
                                     List<Rotation> rotations,
                                     Map<Integer, Map<Integer, Integer>> auxCoverage) {
        // Per-resident minimum workload exceeds year length → impossible.
        int minLoad = config.getGlobalMinWorkloadBlocks();
        if (minLoad > totalBlocks) {
            report.addIssue(
                FeasibilityReport.IssueType.WORKLOAD_IMPOSSIBLE,
                "Global workload",
                String.format("globalMinWorkloadBlocks (%d) exceeds year length (%d blocks)",
                    minLoad, totalBlocks),
                "Lower globalMinWorkloadBlocks or increase totalBlocks"
            );
        }

        int totalMinRequired = 0;
        int totalEffectiveCapacity = 0;
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());
            totalMinRequired += policy.minPerBlock * totalBlocks;
            for (int b = 0; b < totalBlocks; b++) {
                int auxCount = auxByBlock.getOrDefault(b, 0);
                totalEffectiveCapacity += Math.max(0, policy.maxPerBlock - auxCount);
            }
        }
        int totalCapacity = residents.size() * totalBlocks;

        if (totalMinRequired > totalCapacity) {
            report.addIssue(
                FeasibilityReport.IssueType.OVER_CONSTRAINED,
                "Global",
                String.format("Sum of min-block staffing requirements (%d resident-blocks) "
                    + "exceeds total capacity (%d resident-blocks)",
                    totalMinRequired, totalCapacity),
                "Reduce minPerBlock on some rotations or add more residents"
            );
        }

        // Check whether aux coverage leaves enough room for main residents
        if (!auxCoverage.isEmpty() && totalMinRequired > totalEffectiveCapacity) {
            report.addIssue(
                FeasibilityReport.IssueType.OVER_CONSTRAINED,
                "Global (aux coverage conflict)",
                String.format("After subtracting auxiliary resident coverage, only %d effective "
                    + "resident-block slots remain across all rotations, but minimum staffing "
                    + "requirements total %d resident-blocks",
                    totalEffectiveCapacity, totalMinRequired),
                "Reduce auxiliary resident assignments, lower minPerBlock on affected rotations, "
                    + "or increase maxPerBlock to allow aux + main overlap"
            );
        }
    }

    private void checkRotationPool(FeasibilityReport report,
                                   List<Resident> residents,
                                   List<Rotation> rotations,
                                   List<RotationRequirement> requirements,
                                   Map<Integer, Set<Integer>> eligible,
                                   Map<Integer, Map<Integer, Integer>> auxCoverage) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            Set<Integer> pool = eligible.getOrDefault(s.getId(), Collections.emptySet());
            Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());

            if (pool.isEmpty()) {
                report.addIssue(
                    FeasibilityReport.IssueType.INSUFFICIENT_POOL,
                    s.getName(),
                    "No eligible residents for this rotation",
                    "Add PGY-level requirements or check eligibility rules"
                );
                continue;
            }

            int[] lengths = policy.allowedBlockLengths;
            int maxLen = Arrays.stream(lengths).max().orElse(2);
            int maxBlocksPerResident = config.getGlobalMaxWorkloadBlocks() / maxLen;
            int maxPoolCapacity = pool.size() * maxBlocksPerResident * maxLen;
            int needed = policy.minPerBlock * totalBlocks;

            if (needed > maxPoolCapacity) {
                report.addIssue(
                    FeasibilityReport.IssueType.INSUFFICIENT_POOL,
                    s.getName(),
                    String.format("Pool of %d eligible residents can supply at most %d resident-blocks "
                        + "but rotation needs %d (minPerBlock=%d × %d blocks)",
                        pool.size(), maxPoolCapacity, needed, policy.minPerBlock, totalBlocks),
                    "Increase eligible resident pool, reduce minPerBlock, or allow longer blocks"
                );
            }

            // Check per-rotation aux coverage: if aux fills all slots in too many blocks,
            // main residents may not have enough open blocks to meet the minimum.
            if (!auxByBlock.isEmpty()) {
                int effectiveBlocksAvailable = 0;
                for (int b = 0; b < totalBlocks; b++) {
                    int auxCount = auxByBlock.getOrDefault(b, 0);
                    if (auxCount < policy.maxPerBlock) effectiveBlocksAvailable++;
                }
                int minBlocksNeededPerResident = 0;
                for (RotationRequirement req : requirements) {
                    if (req.getRotationId() == s.getId() && req.isRequired()) {
                        int minLen = Arrays.stream(lengths).min().orElse(1);
                        minBlocksNeededPerResident = Math.max(minBlocksNeededPerResident,
                            (int) Math.ceil(req.getMinBlocks()) * minLen);
                    }
                }
                if (minBlocksNeededPerResident > 0 && effectiveBlocksAvailable < minBlocksNeededPerResident) {
                    report.addIssue(
                        FeasibilityReport.IssueType.INSUFFICIENT_POOL,
                        s.getName(),
                        String.format("Auxiliary residents fill all slots in %d of %d blocks; "
                            + "only %d blocks remain open for main residents but each eligible "
                            + "resident needs at least %d blocks",
                            totalBlocks - effectiveBlocksAvailable, totalBlocks,
                            effectiveBlocksAvailable, minBlocksNeededPerResident),
                        "Reduce auxiliary assignments in this rotation, lower minPerBlock, "
                            + "or increase maxPerBlock to allow aux + main overlap"
                    );
                }
            }
        }
    }

    private void checkPgyBottlenecks(FeasibilityReport report,
                                     List<Resident> residents,
                                     List<Rotation> rotations,
                                     List<RotationRequirement> requirements) {
        Map<Integer, Long> countByPgy = residents.stream()
            .collect(Collectors.groupingBy(Resident::getPgyLevel, Collectors.counting()));

        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            for (var pgyEntry : policy.pgyMinMax.entrySet()) {
                int pgy    = pgyEntry.getKey();
                int minPgy = pgyEntry.getValue()[0];
                long pgyCount = countByPgy.getOrDefault(pgy, 0L);

                if (minPgy > pgyCount) {
                    report.addIssue(
                        FeasibilityReport.IssueType.PGY_BOTTLENECK,
                        s.getName(),
                        String.format("Requires %d PGY-%d residents per block but only %d exist",
                            minPgy, pgy, pgyCount),
                        String.format("Add PGY-%d residents or reduce minPgy for this rotation", pgy)
                    );
                }
            }
        }
    }

    private void checkBlockLengths(FeasibilityReport report, List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int[] lengths = policy.allowedBlockLengths;
            int minLen = Arrays.stream(lengths).min().orElse(1);

            if (minLen > totalBlocks) {
                report.addIssue(
                    FeasibilityReport.IssueType.BLOCK_LENGTH_CONFLICT,
                    s.getName(),
                    String.format("Minimum block length %d exceeds schedule length %d blocks",
                        minLen, totalBlocks),
                    "Reduce block length or extend the academic year"
                );
            }
        }
    }

    private void checkPrerequisiteCycles(FeasibilityReport report,
                                          List<Rotation> rotations,
                                          List<Prerequisite> prerequisites) {
        Map<Integer, Set<Integer>> adj = new HashMap<>();
        for (Prerequisite p : prerequisites) {
            adj.computeIfAbsent(p.getRotationId(), k -> new HashSet<>())
               .add(p.getPrerequisiteRotationId());
        }

        Set<Integer> visited = new HashSet<>();
        Set<Integer> inStack = new HashSet<>();
        Map<Integer, String> names = rotations.stream()
            .collect(Collectors.toMap(Rotation::getId, Rotation::getName));

        for (Rotation r : rotations) {
            if (!visited.contains(r.getId())) {
                List<Integer> cycle = new ArrayList<>();
                if (hasCycle(r.getId(), adj, visited, inStack, cycle)) {
                    String cycleDesc = cycle.stream()
                        .map(id -> names.getOrDefault(id, "ID:" + id))
                        .collect(Collectors.joining(" → "));
                    report.addIssue(
                        FeasibilityReport.IssueType.PREREQUISITE_CYCLE,
                        names.getOrDefault(r.getId(), "ID:" + r.getId()),
                        "Circular prerequisite chain detected: " + cycleDesc,
                        "Remove one prerequisite link to break the cycle"
                    );
                }
            }
        }
    }

    private boolean hasCycle(int node, Map<Integer, Set<Integer>> adj,
                              Set<Integer> visited, Set<Integer> inStack,
                              List<Integer> cycle) {
        visited.add(node);
        inStack.add(node);
        cycle.add(node);
        for (int neighbor : adj.getOrDefault(node, Set.of())) {
            if (!visited.contains(neighbor)) {
                if (hasCycle(neighbor, adj, visited, inStack, cycle)) return true;
            } else if (inStack.contains(neighbor)) {
                cycle.add(neighbor);
                return true;
            }
        }
        inStack.remove(node);
        if (!cycle.isEmpty()) cycle.remove(cycle.size() - 1);
        return false;
    }

    private int calcTotalRequiredBlocks(List<Rotation> rotations,
                                        List<RotationRequirement> requirements,
                                        List<Resident> residents) {
        Map<Integer, Map<Integer, RotationRequirement>> reqMap = new HashMap<>();
        for (RotationRequirement req : requirements) {
            reqMap.computeIfAbsent(req.getRotationId(), k -> new HashMap<>())
                  .put(req.getPgyLevel(), req);
        }

        int total = 0;
        for (Resident r : residents) {
            for (Rotation s : rotations) {
                RotationRequirement req = reqMap.getOrDefault(s.getId(), Map.of())
                                               .get(r.getPgyLevel());
                if (req != null && req.isRequired()) {
                    int[] lengths = config.getPolicyFor(s.getId()).allowedBlockLengths;
                    int minLen = Arrays.stream(lengths).min().orElse(1);
                    total += req.getMinBlocks() * minLen;
                }
            }
        }
        return total;
    }
}
