package com.residency.cpsat;

import com.google.ortools.sat.*;
import com.residency.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Applies all hard scheduling constraints to the OR-Tools CpModel.
 * All temporal indices are in BLOCKS (1 block = 2 calendar weeks).
 * A full academic year = 26 blocks (indices 0–25).
 */
public class ConstraintBuilder {

    private final CpModel model;
    private final VariableFactory vars;
    private final ScheduleConfig config;
    private final int totalBlocks;
    // Pre-counted auxiliary-resident coverage: rotationId -> blockIndex -> count
    private final Map<Integer, Map<Integer, Integer>> auxCoverage;

    public ConstraintBuilder(CpModel model, VariableFactory vars,
                             ScheduleConfig config, int totalBlocks) {
        this(model, vars, config, totalBlocks, Map.of());
    }

    public ConstraintBuilder(CpModel model, VariableFactory vars,
                             ScheduleConfig config, int totalBlocks,
                             Map<Integer, Map<Integer, Integer>> auxCoverage) {
        this.model       = model;
        this.vars        = vars;
        this.config      = config;
        this.totalBlocks = totalBlocks;
        this.auxCoverage = auxCoverage;
    }

    // ── 1. Block coverage min/max per rotation ─────────────────────────────

    public void applyCoverageConstraints(List<Resident> residents, List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int minPerBlock = policy.minPerBlock;
            int maxPerBlock = policy.maxPerBlock;
            Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());

            for (int b = 0; b < totalBlocks; b++) {
                List<BoolVar> blockOccs = new ArrayList<>();
                for (Resident r : residents) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) blockOccs.add(occ);
                }
                if (blockOccs.isEmpty()) continue;

                // Auxiliary residents already fill some slots; subtract from bounds.
                int auxCount = auxByBlock.getOrDefault(b, 0);
                int effectiveMin = Math.max(0, minPerBlock - auxCount);
                int effectiveMax = Math.max(0, maxPerBlock - auxCount);

                BoolVar[] arr = blockOccs.toArray(new BoolVar[0]);
                if (effectiveMin > 0) {
                    model.addLinearConstraint(LinearExpr.sum(arr), effectiveMin, blockOccs.size());
                }
                model.addLinearConstraint(LinearExpr.sum(arr), 0, effectiveMax);
            }
        }
    }

    // ── 1b. Categorical-only per-block caps ───────────────────────────────

    /**
     * Caps the number of CATEGORICAL residents on a rotation per block, independent of
     * auxiliary coverage (unlike {@link #applyCoverageConstraints}, whose max is reduced
     * by aux). Enforces hard rotation slot limits that bind categoricals specifically —
     * e.g. ICU ≤ 1 categorical (a TY may still add a second body up to the aux-aware total
     * cap), VA ≤ 2 categoricals. {@code residents} here are the categorical residents the
     * solver assigns. Rotations with {@code categoricalMaxPerBlock == 0} are skipped.
     *
     * Younker 7 Days is special-cased: cap = 1 categorical per block EXCEPT block 13
     * (slots 24–25), where exactly 2 categoricals supply the day team (no BMC/TY there).
     */
    public void applyCategoricalCapConstraints(List<Resident> residents, List<Rotation> rotations) {
        for (Rotation s : rotations) {
            int cap = config.getPolicyFor(s.getId()).categoricalMaxPerBlock;
            if (cap <= 0) continue;
            boolean isY7d = "Younker 7 Days".equalsIgnoreCase(s.getName());
            for (int b = 0; b < totalBlocks; b++) {
                // Younker 7 Days block 13 (slots 24,25) has no BMC/TY 2nd body, so it needs
                // EXACTLY 2 categoricals (both the cap and the floor are 2). Elsewhere the
                // cap is the configured value (1) with no categorical floor.
                boolean y7dBlock13 = isY7d && (b == 24 || b == 25);
                int slotCap = y7dBlock13 ? 2 : cap;
                int slotMin = y7dBlock13 ? 2 : 0;
                List<BoolVar> blockOccs = new ArrayList<>();
                for (Resident r : residents) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) blockOccs.add(occ);
                }
                if (blockOccs.isEmpty()) continue;
                model.addLinearConstraint(
                    LinearExpr.sum(blockOccs.toArray(new BoolVar[0])), slotMin, slotCap);
            }
        }
    }

    // ── 2. PGY-level block caps per rotation ──────────────────────────────

    public void applyPgyCapConstraints(List<Resident> residents, List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            if (policy.pgyMinMax.isEmpty()) continue;

            for (var pgyEntry : policy.pgyMinMax.entrySet()) {
                int pgy    = pgyEntry.getKey();
                int minPgy = pgyEntry.getValue()[0];
                int maxPgy = pgyEntry.getValue()[1];

                List<Resident> pgyResidents = residents.stream()
                    .filter(r -> r.getPgyLevel() == pgy)
                    .toList();
                if (pgyResidents.isEmpty()) continue;

                for (int b = 0; b < totalBlocks; b++) {
                    List<BoolVar> pgyOccs = new ArrayList<>();
                    for (Resident r : pgyResidents) {
                        BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                        if (occ != null) pgyOccs.add(occ);
                    }
                    if (pgyOccs.isEmpty()) continue;

                    BoolVar[] arr = pgyOccs.toArray(new BoolVar[0]);
                    if (minPgy > 0)
                        model.addLinearConstraint(LinearExpr.sum(arr), minPgy, pgyOccs.size());
                    model.addLinearConstraint(LinearExpr.sum(arr), 0, maxPgy);
                }
            }
        }
    }

    // ── 3. Resident workload caps (global min/max blocks per year) ─────────

    public void applyWorkloadCapConstraints(List<Resident> residents, List<Rotation> rotations) {
        int minLoad = Math.min(config.getGlobalMinWorkloadBlocks(), totalBlocks);
        int maxLoad = Math.min(config.getGlobalMaxWorkloadBlocks(), totalBlocks);

        for (Resident r : residents) {
            List<BoolVar> allOccs = new ArrayList<>();
            for (Rotation s : rotations) {
                for (int b = 0; b < totalBlocks; b++) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) allOccs.add(occ);
                }
            }
            if (allOccs.isEmpty()) continue;

            BoolVar[] arr = allOccs.toArray(new BoolVar[0]);
            model.addLinearConstraint(LinearExpr.sum(arr), minLoad, maxLoad);
        }
    }

    // ── 4. Max total blocks per resident per rotation (PGY rules) ──────────

    public void applyMaxBlocksPerResidentConstraints(
            List<Resident> residents, List<Rotation> rotations,
            Map<Integer, Map<Integer, RotationRequirement>> requirements) {

        for (Resident r : residents) {
            for (Rotation s : rotations) {
                List<BoolVar> occs = new ArrayList<>(
                    vars.getOccupancyVars(r.getId(), s.getId()).values());
                if (occs.isEmpty()) continue;

                // maxBlocksAllowed is entered in WEEKS on the Rotations tab; convert to
                // the solver's 2-week slot grid via the canonical helper. See ScheduleUnits.
                int maxBlocks = Math.max(1, ScheduleUnits.weeksToSlots(s.getMaxBlocksAllowed()));
                int minBlocks = 0;

                Map<Integer, RotationRequirement> byPgy = requirements.getOrDefault(s.getId(), Map.of());
                RotationRequirement req = byPgy.get(r.getPgyLevel());
                if (req != null && req.isRequired()) {
                    // req.minBlocks is in 4-week clinical blocks (0.5 = one 2-week slot,
                    // 1.0 = two slots). Convert directly to slots. The previous formula
                    // ceil(minBlocks)*minLen under-enforced requirements on rotations that
                    // allow a 2-week segment (minLen=1) — e.g. a 2-block VA requirement
                    // enforced only 2 slots instead of 4. See RULES_REVIEW finding B1.
                    minBlocks = ScheduleUnits.blocksToSlots(req.getMinBlocks());
                    // Never require more than the rotation's own max.
                    minBlocks = Math.min(minBlocks, maxBlocks);
                }

                BoolVar[] arr = occs.toArray(new BoolVar[0]);
                model.addLinearConstraint(LinearExpr.sum(arr), minBlocks, maxBlocks);
            }
        }
    }

    // ── 5. Optional full-year coverage ─────────────────────────────────────

    public void applyFullYearCoverageConstraints(List<Resident> residents,
                                                  List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            if (!policy.optionalFullYearCoverage) continue;

            for (int b = 0; b < totalBlocks; b++) {
                List<BoolVar> blockOccs = new ArrayList<>();
                for (Resident r : residents) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) blockOccs.add(occ);
                }
                if (!blockOccs.isEmpty()) {
                    model.addLinearConstraint(
                        LinearExpr.sum(blockOccs.toArray(new BoolVar[0])),
                        policy.minPerBlock, blockOccs.size());
                }
            }
        }
    }

    // ── 6. Prerequisite ordering ───────────────────────────────────────────

    public void applyPrerequisiteConstraints(
            List<Resident> residents,
            Map<Integer, List<Prerequisite>> prerequisiteMap,
            Map<Integer, Rotation> rotationById) {

        for (Resident r : residents) {
            for (var entry : prerequisiteMap.entrySet()) {
                int rotBId = entry.getKey();
                for (Prerequisite prereq : entry.getValue()) {
                    if (prereq.getPgyLevel() != null
                            && prereq.getPgyLevel() != r.getPgyLevel()) continue;

                    int rotAId = prereq.getPrerequisiteRotationId();

                    for (int b = 0; b < totalBlocks; b++) {
                        BoolVar occB = vars.getOccupancyVar(r.getId(), rotBId, b);
                        if (occB == null) continue;

                        List<BoolVar> aBeforeB = new ArrayList<>();
                        for (int bp = 0; bp < b; bp++) {
                            BoolVar occA = vars.getOccupancyVar(r.getId(), rotAId, bp);
                            if (occA != null) aBeforeB.add(occA);
                        }
                        if (aBeforeB.isEmpty()) {
                            model.addLinearConstraint(LinearExpr.newBuilder().add(occB), 0, 0);
                        } else {
                            BoolVar[] aArr = aBeforeB.toArray(new BoolVar[0]);
                            model.addLinearConstraint(LinearExpr.sum(aArr), 1, aArr.length)
                                 .onlyEnforceIf(occB);
                        }
                    }
                }
            }
        }
    }

    // ── 7. Sequence / adjacency rules ─────────────────────────────────────

    public void applySequenceRules(
            List<Resident> residents,
            Map<Integer, List<RotationSequenceRule>> sequenceRuleMap) {

        for (Resident r : residents) {
            for (var entry : sequenceRuleMap.entrySet()) {
                int rotationId = entry.getKey();
                for (RotationSequenceRule rule : entry.getValue()) {
                    if (rule.getPgyLevel() != null && rule.getPgyLevel() != r.getPgyLevel()) continue;

                    switch (rule.getRuleType()) {
                        case MUST_BE_AFTER -> applyMustBeAfterRule(r, rotationId, rule.getRelatedRotationId());
                        case CANNOT_IMMEDIATELY_FOLLOW -> applyCannotImmediatelyFollowRule(r, rotationId, rule.getRelatedRotationId());
                    }
                }
            }
        }
    }

    private void applyMustBeAfterRule(Resident resident, int rotationId, int relatedRotationId) {
        for (int b = 0; b < totalBlocks; b++) {
            BoolVar occ = vars.getOccupancyVar(resident.getId(), rotationId, b);
            if (occ == null) continue;

            List<BoolVar> priorRelatedOccs = new ArrayList<>();
            for (int bp = 0; bp < b; bp++) {
                BoolVar relatedOcc = vars.getOccupancyVar(resident.getId(), relatedRotationId, bp);
                if (relatedOcc != null) priorRelatedOccs.add(relatedOcc);
            }

            if (priorRelatedOccs.isEmpty()) {
                model.addLinearConstraint(LinearExpr.newBuilder().add(occ), 0, 0);
            } else {
                model.addLinearConstraint(
                        LinearExpr.sum(priorRelatedOccs.toArray(new BoolVar[0])),
                        1,
                        priorRelatedOccs.size())
                    .onlyEnforceIf(occ);
            }
        }
    }

    private void applyCannotImmediatelyFollowRule(Resident resident, int rotationId, int relatedRotationId) {
        Map<Integer, BoolVar> starts = vars.getStartVars(resident.getId(), rotationId);
        for (var startEntry : starts.entrySet()) {
            int startBlock = startEntry.getKey();
            if (startBlock == 0) continue;

            BoolVar startVar = startEntry.getValue();
            // Block immediately before this start
            BoolVar priorOcc = vars.getOccupancyVar(resident.getId(), relatedRotationId, startBlock - 1);
            if (priorOcc != null) {
                model.addLinearConstraint(
                    LinearExpr.sum(new BoolVar[]{startVar, priorOcc}),
                    0, 1);
            }
        }
    }

    // ── 8. No back-to-back single-block segments for the same rotation ─────

    /**
     * For rotations where noBackToBackHalfBlocks=true, prevents two 1-block segments
     * starting at adjacent block positions (t and t+1).
     * Enforced as: start[r][s][t] + start[r][s][t+1] <= 1.
     */
    public void applyNoBackToBackHalfBlockConstraints(List<Resident> residents,
                                                       List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            if (!policy.noBackToBackHalfBlocks) continue;

            for (Resident r : residents) {
                Map<Integer, BoolVar> starts = vars.getStartVars(r.getId(), s.getId());
                for (int t = 0; t + 1 < totalBlocks; t++) {
                    BoolVar st1 = starts.get(t);
                    BoolVar st2 = starts.get(t + 1);
                    if (st1 != null && st2 != null) {
                        model.addLinearConstraint(
                            LinearExpr.sum(new BoolVar[]{st1, st2}),
                            0, 1);
                    }
                }
            }
        }
    }

    // ── 9. Cross-rotation mutual non-adjacency ─────────────────────────────

    public void applyMutualNonAdjacencyConstraints(List<Resident> residents,
                                                    List<Rotation> rotations) {
        Map<Integer, Rotation> rotById = rotations.stream()
            .collect(Collectors.toMap(Rotation::getId, r -> r));

        for (Rotation rotA : rotations) {
            ScheduleConfig.RotationPolicy policyA = config.getPolicyFor(rotA.getId());
            for (int rotBId : policyA.mutuallyNonAdjacentWith) {
                if (!rotById.containsKey(rotBId)) continue;
                for (Resident r : residents) {
                    applyCannotImmediatelyFollowRule(r, rotBId, rotA.getId());
                    applyCannotImmediatelyFollowRule(r, rotA.getId(), rotBId);
                }
            }
        }
    }

    // ── 10. Max consecutive blocks per rotation ────────────────────────────

    /**
     * Sliding window: for each window of (maxConsecutiveBlocks + 1) consecutive blocks,
     * sum of occupancy vars <= maxConsecutiveBlocks.
     */
    public void applyMaxConsecutiveBlocksConstraints(List<Resident> residents,
                                                      List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int maxConsec = policy.maxConsecutiveBlocks;
            if (maxConsec <= 0) continue;

            for (Resident r : residents) {
                for (int t = 0; t + maxConsec < totalBlocks; t++) {
                    List<BoolVar> window = new ArrayList<>();
                    for (int b = t; b <= t + maxConsec; b++) {
                        BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                        if (occ != null) window.add(occ);
                    }
                    if (window.size() == maxConsec + 1) {
                        model.addLinearConstraint(
                            LinearExpr.sum(window.toArray(new BoolVar[0])),
                            0, maxConsec);
                    }
                }
            }
        }
    }

    // ── 11. Earliest start block per rotation ─────────────────────────────

    /**
     * For rotations with earliestStartBlock > 0, zeros out all occupancy vars
     * for blocks before that index. This prevents any assignment in those early blocks.
     */
    public void applyEarliestStartConstraints(List<Resident> residents, List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int earliest = policy.earliestStartBlock;
            if (earliest <= 0) continue;

            for (Resident r : residents) {
                for (int b = 0; b < earliest && b < totalBlocks; b++) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) model.addEquality(occ, 0);
                    BoolVar start = vars.getStartVar(r.getId(), s.getId(), b);
                    if (start != null) model.addEquality(start, 0);
                }
            }
        }
    }

    // ── 12. Require at least one break block between segments ──────────────

    /**
     * For rotations where requireBreakBetweenSegments=true, prevents a new segment
     * from starting immediately after the previous one ends.
     * For each allowed block length L and each start block t:
     *   start[r][s][t] + start[r][s][t+L] <= 1
     */
    public void applyRequireBreakBetweenSegmentsConstraints(List<Resident> residents,
                                                             List<Rotation> rotations) {
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            if (!policy.requireBreakBetweenSegments) continue;

            for (Resident r : residents) {
                Map<Integer, BoolVar> starts = vars.getStartVars(r.getId(), s.getId());
                for (int len : policy.allowedBlockLengths) {
                    for (var entry : starts.entrySet()) {
                        int t1 = entry.getKey();
                        int t2 = t1 + len;
                        if (t2 >= totalBlocks) continue;
                        BoolVar st1 = entry.getValue();
                        BoolVar st2 = starts.get(t2);
                        if (st1 != null && st2 != null) {
                            model.addLinearConstraint(
                                LinearExpr.sum(new BoolVar[]{st1, st2}),
                                0, 1);
                        }
                    }
                }
            }
        }
    }

    // ── 13. Even-block-start constraints ──────────────────────────────────

    /**
     * For rotations with requireEvenBlockStart=true, zeros out all start vars on odd-numbered
     * blocks (1, 3, 5, …). This forces every segment to begin on block 0, 2, 4, … (1A, 2A, 3A…).
     * Occupancy on odd blocks is still possible as the second block of a 2-block segment.
     */
    public void applyEvenBlockStartConstraints(List<Resident> residents, List<Rotation> rotations) {
        for (Rotation s : rotations) {
            if (!config.getPolicyFor(s.getId()).requireEvenBlockStart) continue;
            for (Resident r : residents) {
                Map<Integer, BoolVar> startVars = vars.getStartVars(r.getId(), s.getId());
                for (Map.Entry<Integer, BoolVar> entry : startVars.entrySet()) {
                    if (entry.getKey() % 2 != 0) {
                        model.addEquality(entry.getValue(), 0);
                    }
                }
            }
        }
    }

    // ── 14. Requires-consecutive segments ────────────────────────────────

    /**
     * For rotations where requiresConsecutive=true, enforces that all assigned blocks
     * form a single contiguous range (no gaps). Implemented via: for every triple
     * (t1 < g < t2), if occ[t1]=1 AND occ[t2]=1, then occ[g] must be 1.
     * Expressed as: occ[g] >= occ[t1] + occ[t2] - 1.
     */
    public void applyRequiresConsecutiveConstraints(List<Resident> residents,
                                                     List<Rotation> rotations) {
        for (Rotation s : rotations) {
            if (!config.getPolicyFor(s.getId()).requiresConsecutive) continue;
            for (Resident r : residents) {
                for (int t1 = 0; t1 < totalBlocks - 2; t1++) {
                    BoolVar occ1 = vars.getOccupancyVar(r.getId(), s.getId(), t1);
                    if (occ1 == null) continue;
                    for (int t2 = t1 + 2; t2 < totalBlocks; t2++) {
                        BoolVar occ2 = vars.getOccupancyVar(r.getId(), s.getId(), t2);
                        if (occ2 == null) continue;
                        for (int g = t1 + 1; g < t2; g++) {
                            BoolVar occG = vars.getOccupancyVar(r.getId(), s.getId(), g);
                            if (occG == null) continue;
                            model.addGreaterOrEqual(occG,
                                LinearExpr.newBuilder().add(occ1).add(occ2).add(-1));
                        }
                    }
                }
            }
        }
    }

    // ── 15. Linked rotation sum constraints ───────────────────────────────

    /**
     * For each RotationLinkRule (rotA, rotB, sumPerResident, globalTotalForRotB):
     *  - Per resident: blocks(rotA) + blocks(rotB) = sumPerResident  [hard equality]
     *  - Global (if globalTotalForRotB > 0): sum of all rotB blocks across all residents = globalTotalForRotB
     */
    public void applyRotationLinkConstraints(List<Resident> residents, List<Rotation> rotations) {
        for (ScheduleConfig.RotationLinkRule rule : config.getRotationLinkRules()) {
            List<BoolVar> allRotBVars = new ArrayList<>();

            for (Resident r : residents) {
                List<BoolVar> aVars = new ArrayList<>();
                List<BoolVar> bVars = new ArrayList<>();
                for (int b = 0; b < totalBlocks; b++) {
                    BoolVar oa = vars.getOccupancyVar(r.getId(), rule.rotAId, b);
                    if (oa != null) aVars.add(oa);
                    BoolVar ob = vars.getOccupancyVar(r.getId(), rule.rotBId, b);
                    if (ob != null) { bVars.add(ob); allRotBVars.add(ob); }
                }
                if (aVars.isEmpty() && bVars.isEmpty()) continue;

                // sum(rotA) + sum(rotB) = sumPerResident
                List<BoolVar> combined = new ArrayList<>();
                combined.addAll(aVars);
                combined.addAll(bVars);
                model.addLinearConstraint(
                    LinearExpr.sum(combined.toArray(new BoolVar[0])),
                    rule.sumPerResident, rule.sumPerResident);
            }

            if (rule.globalTotalForRotB > 0 && !allRotBVars.isEmpty()) {
                model.addLinearConstraint(
                    LinearExpr.sum(allRotBVars.toArray(new BoolVar[0])),
                    rule.globalTotalForRotB, rule.globalTotalForRotB);
            }
        }
    }
}
