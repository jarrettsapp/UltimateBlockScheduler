package com.residency.cpsat;

import com.residency.model.*;

import java.util.*;

/**
 * Post-solve validation of a ScheduleSolution against the authoritative constraint model.
 * All temporal indices are in BLOCKS (1 block = 2 calendar weeks, 26 blocks per year).
 */
public class ScheduleValidator {

    public record ValidationResult(List<String> failures, List<String> warnings) {
        public boolean hasFailed()  { return !failures.isEmpty(); }
        public boolean hasWarnings(){ return !warnings.isEmpty(); }

        public String formatReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n═══ SCHEDULE VALIDATION ═══\n");
            if (failures.isEmpty() && warnings.isEmpty()) {
                sb.append("  ✓ All constraint checks passed.\n");
                return sb.toString();
            }
            for (String f : failures)  sb.append("  FAIL: ").append(f).append('\n');
            for (String w : warnings)  sb.append("  WARN: ").append(w).append('\n');
            return sb.toString();
        }
    }

    private final ScheduleConfig config;
    private final int totalBlocks;

    public ScheduleValidator(ScheduleConfig config, int totalBlocks) {
        this.config      = config;
        this.totalBlocks = totalBlocks;
    }

    public ValidationResult validate(ScheduleSolution solution,
                                     List<Resident> residents,
                                     List<Rotation> rotations) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<Integer, Rotation> rotById = new HashMap<>();
        for (Rotation r : rotations) rotById.put(r.getId(), r);
        Map<Integer, String> rotName = new HashMap<>();
        for (Rotation r : rotations) rotName.put(r.getId(), r.getName());

        for (Resident res : residents) {
            String rLabel = "PGY-" + res.getPgyLevel() + " " + res.getName();

            // Build assigned-blocks map for this resident: rotationId → sorted list of block indices
            Map<Integer, List<Integer>> assignedByRot = new HashMap<>();
            for (Rotation rot : rotations) {
                List<Integer> blks = solution.getAssignedWeeks(res.getId(), rot.getId());
                if (!blks.isEmpty()) {
                    List<Integer> sorted = new ArrayList<>(blks);
                    Collections.sort(sorted);
                    assignedByRot.put(rot.getId(), sorted);
                }
            }

            // ── Check 1: no back-to-back single blocks for same rotation ──────
            for (Rotation rot : rotations) {
                ScheduleConfig.RotationPolicy policy = config.getPolicyFor(rot.getId());
                if (!policy.noBackToBackHalfBlocks) continue;
                List<Integer> blocks = assignedByRot.get(rot.getId());
                if (blocks == null || blocks.size() < 2) continue;

                List<Integer> segStarts = findSegmentStarts(blocks);
                for (int i = 0; i + 1 < segStarts.size(); i++) {
                    int gap = segStarts.get(i + 1) - segStarts.get(i);
                    if (gap == 1) {
                        failures.add(String.format(
                            "%s: back-to-back 1-block segments of '%s' at blocks %d and %d",
                            rLabel, rot.getName(), segStarts.get(i), segStarts.get(i + 1)));
                    }
                }
            }

            // ── Check 2: cross-rotation mutual non-adjacency ──────────────────
            for (Rotation rotA : rotations) {
                ScheduleConfig.RotationPolicy policyA = config.getPolicyFor(rotA.getId());
                for (int rotBId : policyA.mutuallyNonAdjacentWith) {
                    Rotation rotB = rotById.get(rotBId);
                    if (rotB == null) continue;

                    List<Integer> blocksA = assignedByRot.get(rotA.getId());
                    List<Integer> blocksB = assignedByRot.get(rotBId);
                    if (blocksA == null || blocksB == null) continue;

                    int lastA  = blocksA.get(blocksA.size() - 1);
                    int lastB  = blocksB.get(blocksB.size() - 1);
                    int firstA = blocksA.get(0);
                    int firstB = blocksB.get(0);

                    if (lastA == firstB - 1) {
                        failures.add(String.format(
                            "%s: '%s' immediately precedes '%s' (blocks %d→%d)",
                            rLabel, rotA.getName(), rotB.getName(), lastA, firstB));
                    }
                    if (lastB == firstA - 1) {
                        failures.add(String.format(
                            "%s: '%s' immediately precedes '%s' (blocks %d→%d)",
                            rLabel, rotB.getName(), rotA.getName(), lastB, firstA));
                    }
                }
            }

            // ── Check 3: post-call incompatibility ────────────────────────────
            // If a resident is on a trigger rotation at block t, block t+1 must not
            // be a mandatory-attendance or discouraged rotation.
            for (int t = 0; t + 1 < totalBlocks; t++) {
                Integer triggerAtBlock = null;
                for (int triggerId : config.getPostCallTriggerRotationIds()) {
                    List<Integer> tb = assignedByRot.get(triggerId);
                    if (tb != null && tb.contains(t)) {
                        triggerAtBlock = triggerId;
                        break;
                    }
                }
                if (triggerAtBlock == null) continue;

                String trigName = rotName.getOrDefault(triggerAtBlock, "trigger#" + triggerAtBlock);

                for (int mandId : config.getMandatoryAttendanceRotationIds()) {
                    List<Integer> mb = assignedByRot.get(mandId);
                    if (mb != null && mb.contains(t + 1)) {
                        failures.add(String.format(
                            "%s: post-call violation — '%s' (block %d) followed by '%s' (block %d)",
                            rLabel, trigName, t, rotName.getOrDefault(mandId, "rot#" + mandId), t + 1));
                    }
                }

                for (int discId : config.getDiscouragedAfterTriggerIds()) {
                    List<Integer> db = assignedByRot.get(discId);
                    if (db != null && db.contains(t + 1)) {
                        warnings.add(String.format(
                            "%s: discouraged sequence — '%s' (block %d) followed by '%s' (block %d)",
                            rLabel, trigName, t, rotName.getOrDefault(discId, "rot#" + discId), t + 1));
                    }
                }
            }

            // ── Check 4: inpatient-to-inpatient transitions ───────────────────
            Set<Integer> inpatientIds = new HashSet<>();
            for (Rotation rot : rotations) {
                if (rot.getRotationType() == RotationType.INPATIENT) inpatientIds.add(rot.getId());
            }
            for (int t = 1; t < totalBlocks; t++) {
                Integer inpBefore = null;
                Integer inpAfter  = null;
                for (int sid : inpatientIds) {
                    List<Integer> b = assignedByRot.get(sid);
                    if (b != null && b.contains(t - 1)) inpBefore = sid;
                    if (b != null && b.contains(t))     inpAfter  = sid;
                }
                if (inpBefore != null && inpAfter != null && !inpBefore.equals(inpAfter)) {
                    warnings.add(String.format(
                        "%s: inpatient-to-inpatient transition '%s'→'%s' at block %d",
                        rLabel,
                        rotName.getOrDefault(inpBefore, "rot#" + inpBefore),
                        rotName.getOrDefault(inpAfter,  "rot#" + inpAfter),
                        t));
                }
            }
        }

        checkFullYearCoverage(solution, residents, rotations, rotName, failures);

        return new ValidationResult(Collections.unmodifiableList(failures),
                                    Collections.unmodifiableList(warnings));
    }

    /**
     * Returns the start block of each contiguous segment within a sorted list of block indices.
     */
    private static List<Integer> findSegmentStarts(List<Integer> sortedBlocks) {
        List<Integer> starts = new ArrayList<>();
        if (sortedBlocks.isEmpty()) return starts;
        starts.add(sortedBlocks.get(0));
        for (int i = 1; i < sortedBlocks.size(); i++) {
            if (sortedBlocks.get(i) != sortedBlocks.get(i - 1) + 1) {
                starts.add(sortedBlocks.get(i));
            }
        }
        return starts;
    }

    /**
     * Validates full-year coverage for every rotation whose policy has
     * optionalFullYearCoverage=true. Checks that each block meets minPerBlock,
     * matching the hard constraint applied during solving (applyFullYearCoverageConstraints).
     */
    private void checkFullYearCoverage(ScheduleSolution solution,
                                        List<Resident> residents,
                                        List<Rotation> rotations,
                                        Map<Integer, String> rotName,
                                        List<String> failures) {
        for (Rotation rot : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(rot.getId());
            if (!policy.optionalFullYearCoverage) continue;
            String name = rot.getName() != null ? rot.getName() : "rot#" + rot.getId();
            int required = Math.max(1, policy.minPerBlock);
            for (int b = 0; b < totalBlocks; b++) {
                int coverage = 0;
                for (Resident res : residents) {
                    if (solution.getAssignedWeeks(res.getId(), rot.getId()).contains(b)) coverage++;
                }
                if (coverage < required) {
                    failures.add(String.format("'%s' block %d: %d resident(s) assigned, need %d",
                        name, b, coverage, required));
                }
            }
        }
    }
}
