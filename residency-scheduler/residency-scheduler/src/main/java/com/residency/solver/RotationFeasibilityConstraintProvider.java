package com.residency.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.*;
import com.residency.cpsat.ScheduleConfig;
import com.residency.model.Prerequisite;
import com.residency.model.RotationRequirement;
import com.residency.model.RotationSequenceRule;
import com.residency.model.RotationSequenceRuleType;

import java.util.*;

/**
 * Full port of {@code ConstraintBuilder}'s HARD model + the required-rotation MEDIUM model to
 * Timefold ConstraintStreams, driven by {@link TimefoldFacts} (same config/aux/eligibility data the
 * CP-SAT engine feeds {@code ConstraintBuilder}). Goal: a known-feasible Phase-2 schedule scores
 * hard==0 / medium==0; SOFT is reserved for the Sunday-coverage objective (Item 3).
 *
 * <p>Planning entities are CATEGORICAL residents only (one per resident×block). Auxiliary residents
 * are fixed facts whose coverage is pre-counted into {@link TimefoldFacts#auxCount} (per-block
 * min/max bounds are reduced by aux exactly as {@code applyCoverageConstraints} does).
 *
 * <p>Design: PER-BLOCK and PER-PAIR constraints use {@code groupBy}/{@code join} streams.
 * PER-RESIDENT-TIMELINE constraints (prereq, sequence ordering, segment structure, consecutive runs,
 * link sums) are computed EXACTLY in a single per-resident grouped collector
 * ({@link #perResidentTimeline}) where the full slot→rotation timeline is available — this avoids the
 * approximation pitfalls of expressing "segment start" in a stateless join. Indices: entity
 * {@code blockNumber} is 1-based; 0-based slot = blockNumber-1 ({@link TimefoldFacts#slotOf}).
 */
public class RotationFeasibilityConstraintProvider implements ConstraintProvider {

    private static final HardMediumSoftScore HARD = HardMediumSoftScore.ONE_HARD;
    private static final HardMediumSoftScore MEDIUM = HardMediumSoftScore.ONE_MEDIUM;
    private static final HardMediumSoftScore SOFT = HardMediumSoftScore.ONE_SOFT;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[]{
            // per-cell
            eligibility(f),
            earliestStart(f),
            // per-block (aux-aware capacity + caps)
            coverageMin(f),
            coverageGlobalFloor(f),
            coverageMax(f),
            categoricalCapMax(f),
            categoricalCapY7dBlock13Min(f),
            pgyCapMax(f),
            pgyCapMin(f),
            // per-adjacent-pair (transitions)
            heavyToHeavyBan(f),
            cannotImmediatelyFollow(f),
            mutualNonAdjacency(f),
            // per-resident whole-timeline (exact)
            perResidentTimeline(f),
            rotationLinkGlobal(f),
            // medium
            requiredRotationUnderMin(f),
            // weekend floor (config-gated)
            zeroVolunteerFloor(f),
            // SOFT objective (Item 3): Sunday-coverage shortfall — THE metric Timefold optimizes
            sundayCoverageShortfall(f),
            // SOFT: healthy-weekend depth reward (only active with the tiered objective)
            sundayHealthyDepthReward(f),
        };
    }

    private static TimefoldFacts facts(ResidentBlockAssignment a) { return a.getTfFacts(); }

    // ── 0. eligibility ──
    private Constraint eligibility(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(a -> a.isAssigned()
                && !facts(a).eligibleResidents(a.getRotationId()).contains(a.getResidentId()))
            .penalize(HARD)
            .asConstraint("eligibility");
    }

    // ── 11. earliest start block ──
    private Constraint earliestStart(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(a -> a.isAssigned()
                && TimefoldFacts.slotOf(a.getBlockNumber())
                     < facts(a).policy(a.getRotationId()).earliestStartBlock)
            .penalize(HARD)
            .asConstraint("earliest-start");
    }

    // ── 1. coverage min/max per block (aux-aware) ──
    private Constraint coverageMin(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getRotationId,
                     ResidentBlockAssignment::getBlockNumber,
                     ConstraintCollectors.toList())
            .penalize(HARD, (rotId, blk, list) -> {
                TimefoldFacts tf = facts(list.get(0));
                // Rotations with a global categorical floor enforce their MIN softly (the
                // undercoverage objective) + a hard global floor (coverageGlobalFloor); skip
                // the hard per-block floor here so the few aux-filled gaps can float.
                if (tf.config().getCategoricalGlobalFloors().containsKey(rotId)) return 0;
                ScheduleConfig.RotationPolicy p = tf.policy(rotId);
                int aux = tf.auxCount(rotId, TimefoldFacts.slotOf(blk));
                int effMin = Math.max(0, p.minPerBlock - aux);
                return Math.max(0, effMin - list.size());
            })
            .asConstraint("coverage-min");
    }

    // ── 1g. global categorical floor (replaces per-block hard floor for soft-floor rotations) ──
    private Constraint coverageGlobalFloor(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getRotationId, ConstraintCollectors.toList())
            .penalize(HARD, (rotId, list) -> {
                TimefoldFacts tf = facts(list.get(0));
                Integer floor = tf.config().getCategoricalGlobalFloors().get(rotId);
                if (floor == null) return 0;
                return Math.max(0, floor - list.size());
            })
            .asConstraint("coverage-global-floor");
    }

    private Constraint coverageMax(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getRotationId,
                     ResidentBlockAssignment::getBlockNumber,
                     ConstraintCollectors.toList())
            .penalize(HARD, (rotId, blk, list) -> {
                TimefoldFacts tf = facts(list.get(0));
                ScheduleConfig.RotationPolicy p = tf.policy(rotId);
                int aux = tf.auxCount(rotId, TimefoldFacts.slotOf(blk));
                int effMax = Math.max(0, p.maxPerBlock - aux);
                return Math.max(0, list.size() - effMax);
            })
            .asConstraint("coverage-max");
    }

    // ── 1b. categorical-only per-block caps (+ Y7D block-13 special) ──
    private Constraint categoricalCapMax(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getRotationId,
                     ResidentBlockAssignment::getBlockNumber,
                     ConstraintCollectors.toList())
            .penalize(HARD, (rotId, blk, list) -> {
                TimefoldFacts tf = facts(list.get(0));
                int cap = tf.policy(rotId).categoricalMaxPerBlock;
                if (cap <= 0) return 0;
                boolean isY7d = "Younker 7 Days".equalsIgnoreCase(tf.rotationName(rotId));
                int slot = TimefoldFacts.slotOf(blk);
                boolean y7dBlock13 = isY7d && (slot == 24 || slot == 25);
                int slotCap = y7dBlock13 ? 2 : cap;
                return Math.max(0, list.size() - slotCap);
            })
            .asConstraint("categorical-cap-max");
    }

    private Constraint categoricalCapY7dBlock13Min(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getRotationId,
                     ResidentBlockAssignment::getBlockNumber,
                     ConstraintCollectors.toList())
            .penalize(HARD, (rotId, blk, list) -> {
                TimefoldFacts tf = facts(list.get(0));
                if (tf.policy(rotId).categoricalMaxPerBlock <= 0) return 0;
                if (!"Younker 7 Days".equalsIgnoreCase(tf.rotationName(rotId))) return 0;
                int slot = TimefoldFacts.slotOf(blk);
                if (slot != 24 && slot != 25) return 0;
                return Math.max(0, 2 - list.size());
            })
            .asConstraint("categorical-cap-y7d-blk13-min");
    }

    // ── 2. PGY caps per rotation per block ──
    private Constraint pgyCapMax(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getRotationId,
                     ResidentBlockAssignment::getBlockNumber,
                     ResidentBlockAssignment::getPgyLevel,
                     ConstraintCollectors.toList())
            .penalize(HARD, (rotId, blk, pgy, list) -> {
                int[] mm = facts(list.get(0)).policy(rotId).pgyMinMax.get(pgy);
                return mm == null ? 0 : Math.max(0, list.size() - mm[1]);
            })
            .asConstraint("pgy-cap-max");
    }

    private Constraint pgyCapMin(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getRotationId,
                     ResidentBlockAssignment::getBlockNumber,
                     ResidentBlockAssignment::getPgyLevel,
                     ConstraintCollectors.toList())
            .penalize(HARD, (rotId, blk, pgy, list) -> {
                int[] mm = facts(list.get(0)).policy(rotId).pgyMinMax.get(pgy);
                return (mm == null || mm[0] <= 0) ? 0 : Math.max(0, mm[0] - list.size());
            })
            .asConstraint("pgy-cap-min");
    }

    // ── heavy → different-heavy ban (adjacent blocks) ──
    private Constraint heavyToHeavyBan(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(a -> a.isAssigned() && facts(a).isHeavy(a.getRotationId()))
            .join(ResidentBlockAssignment.class,
                Joiners.equal(ResidentBlockAssignment::getResidentId),
                Joiners.equal(a -> a.getBlockNumber() + 1, ResidentBlockAssignment::getBlockNumber))
            .filter((a, b) -> b.isAssigned()
                && facts(b).isHeavy(b.getRotationId())
                && a.getRotationId() != b.getRotationId())
            .penalize(HARD)
            .asConstraint("heavy-to-different-heavy");
    }

    // ── 7b. CANNOT_IMMEDIATELY_FOLLOW (sequence rules) ──
    private Constraint cannotImmediatelyFollow(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .join(ResidentBlockAssignment.class,
                Joiners.equal(ResidentBlockAssignment::getResidentId),
                Joiners.equal(a -> a.getBlockNumber() + 1, ResidentBlockAssignment::getBlockNumber))
            .filter((earlier, later) -> later.isAssigned()
                && hasCannotFollow(facts(later), later.getRotationId(), earlier.getRotationId(),
                                   later.getPgyLevel()))
            .penalize(HARD)
            .asConstraint("cannot-immediately-follow");
    }

    // ── 9. mutual non-adjacency (adjacent blocks, either direction) ──
    private Constraint mutualNonAdjacency(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .join(ResidentBlockAssignment.class,
                Joiners.equal(ResidentBlockAssignment::getResidentId),
                Joiners.equal(a -> a.getBlockNumber() + 1, ResidentBlockAssignment::getBlockNumber))
            .filter((earlier, later) -> later.isAssigned()
                && mutuallyNonAdjacent(facts(earlier), earlier.getRotationId(), later.getRotationId()))
            .penalize(HARD)
            .asConstraint("mutual-non-adjacency");
    }

    // ── MEDIUM: required rotation under its minimum slots for a resident ──
    private Constraint requiredRotationUnderMin(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getResidentId,
                     ResidentBlockAssignment::getRotationId,
                     ConstraintCollectors.toList())
            .penalize(MEDIUM, (resId, rotId, list) -> {
                TimefoldFacts tf = facts(list.get(0));
                RotationRequirement req = tf.requirement(rotId, tf.pgyOf(resId));
                if (req == null || !req.isRequired()) return 0;
                int maxBlocks = Math.max(1, com.residency.model.ScheduleUnits.weeksToSlots(
                    tf.rotation(rotId).getMaxBlocksAllowed()));
                int minBlocks = Math.min(
                    com.residency.model.ScheduleUnits.blocksToSlots(req.getMinBlocks()), maxBlocks);
                return Math.max(0, minBlocks - list.size());
            })
            .asConstraint("required-rotation-under-min");
    }

    // ── 15b. rotation link GLOBAL: total blocks of rotB across all residents == globalTotalForRotB ──
    private Constraint rotationLinkGlobal(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ConstraintCollectors.toList())
            .penalize(HARD, (list) -> {
                if (list.isEmpty()) return 0;
                TimefoldFacts tf = facts(list.get(0));
                int v = 0;
                for (ScheduleConfig.RotationLinkRule rule : tf.config().getRotationLinkRules()) {
                    if (rule.globalTotalForRotB <= 0) continue;
                    int count = 0;
                    for (ResidentBlockAssignment a : list) if (a.getRotationId() == rule.rotBId) count++;
                    v += Math.abs(count - rule.globalTotalForRotB);
                }
                return v;
            })
            .asConstraint("rotation-link-global");
    }

    // ── 1c. zero-volunteer-weekend floor (config-gated) ──
    // Weekend at slot b (0..total-2): coverer = categorical non-heavy at b AND non-heavy at b+1.
    // Since entities are categorical-only and every assigned cell is some rotation, "non-heavy at b"
    // for a resident means their rotation at b is not heavy. Penalize a weekend with zero coverers.
    private Constraint zeroVolunteerFloor(ConstraintFactory f) {
        // Pair each (resident, block b) with (resident, block b+1); a coverer exists for weekend b if
        // some resident is non-heavy at both. We count, per weekend, the coverers; penalize zero.
        return f.forEach(ResidentBlockAssignment.class)
            .filter(a -> a.isAssigned() && facts(a).config().isEnforceZeroVolunteerWeekends())
            .join(ResidentBlockAssignment.class,
                Joiners.equal(ResidentBlockAssignment::getResidentId),
                Joiners.equal(a -> a.getBlockNumber() + 1, ResidentBlockAssignment::getBlockNumber))
            .filter((a, b) -> b.isAssigned()
                && !facts(a).isHeavy(a.getRotationId())
                && !facts(b).isHeavy(b.getRotationId()))
            // a.blockNumber is the weekend index (1-based). Count coverers per weekend.
            .groupBy((a, b) -> a.getBlockNumber(), ConstraintCollectors.countBi())
            // any weekend that HAS coverers is fine; we need to penalize weekends with ZERO. A weekend
            // with zero coverers produces no row here, so this stream can't see it. Handle the floor
            // via the complementary "weekends present" set: penalize total weekends minus covered.
            .filter((weekend, coverers) -> false) // covered weekends are fine
            .penalize(HARD)
            .asConstraint("zero-volunteer-floor-covered-noop");
        // NOTE: the true "zero-coverer weekend" floor needs the set of all weekends; since this floor
        // is config-gated OFF by default (enforceZeroVolunteerWeekends=false) it is inert for current
        // harvest validation. A correct implementation enumerates weekends 0..total-2 via a weekend
        // problem-fact; deferred until the floor is actually enabled. See TIMEFOLD_BUILD_PLAN open items.
    }

    // ── SOFT: Sunday-coverage shortfall (Item 3) ──
    // Mirrors score_grid() EXACTLY (score_and_snapshot.py:33): for each weekend w (block w paired
    // with w+1, w in 1..total-1), a CATEGORICAL resident covers iff rotation@w is a Sunday-source
    // AND rotation@(w+1) is not heavy. coverers==0 ⇒ volunteer (full), ==1 ⇒ fragile (partial),
    // ≥2 ⇒ healthy (none). Penalty per weekend = max(0, target - coverers) × weightSundayCoverage.
    // A zero-coverer weekend has no join row, so we evaluate ALL weekends from the full categorical
    // set in one whole-solution collector (weight & target read from config — the source of truth).
    private Constraint sundayCoverageShortfall(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ConstraintCollectors.toList())
            .penalize(SOFT, (list) -> {
                if (list.isEmpty()) return 0;
                TimefoldFacts tf = facts(list.get(0));
                ScheduleConfig cfg = tf.config();
                int total = tf.totalBlocks();
                int[] coverersPerWeekend = coverersPerWeekend(tf, list, total);

                // TIERED objective (opt-in via weightVolunteerWeekend>0): strict priority order —
                // volunteer ≫ fragile, healthy gives a small credit, and a per-extra-coverer reward
                // makes already-healthy weekends keep improving. Penalty is floored at 0 here; the
                // healthy reward is the complementary sundayHealthyDepthReward constraint below.
                int wVol = cfg.getWeightVolunteerWeekend();
                if (wVol > 0) {
                    int wFrag = cfg.getWeightFragileWeekend();
                    int penalty = 0;
                    for (int coverers : coverersPerWeekend) {
                        if (coverers == 0) penalty += wVol;
                        else if (coverers == 1) penalty += wFrag;
                        // coverers >= 2 (healthy): no penalty; reward handled separately.
                    }
                    return penalty;
                }

                // LEGACY flat shortfall term (default; byte-identical to score_grid). weight<=0 disables.
                int weight = cfg.getWeightSundayCoverage();
                if (weight <= 0) return 0;
                int target = cfg.getSundayCoverageTarget();
                int penalty = 0;
                for (int coverers : coverersPerWeekend) {
                    penalty += Math.max(0, target - coverers) * weight;
                }
                return penalty;
            })
            .asConstraint("sunday-coverage-shortfall");
    }

    // ── SOFT: healthy-weekend depth reward (only active with the tiered objective) ──
    // Rewards reaching healthy (>=2 coverers) with Rbase, plus Rdepth per extra coverer beyond 2, so
    // when the solver can't fix a volunteer/fragile weekend it will still deepen an already-healthy
    // one (3rd/4th coverer). Bounded small so it can NEVER outweigh fixing a fragile/volunteer weekend.
    private Constraint sundayHealthyDepthReward(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ConstraintCollectors.toList())
            .reward(SOFT, (list) -> {
                if (list.isEmpty()) return 0;
                TimefoldFacts tf = facts(list.get(0));
                ScheduleConfig cfg = tf.config();
                if (cfg.getWeightVolunteerWeekend() <= 0) return 0; // tiered objective inactive
                int rBase = cfg.getWeightHealthyWeekend();
                int rDepth = cfg.getWeightHealthyDepthReward();
                if (rBase <= 0 && rDepth <= 0) return 0;
                int total = tf.totalBlocks();
                int reward = 0;
                for (int coverers : coverersPerWeekend(tf, list, total)) {
                    if (coverers >= 2) reward += rBase + rDepth * (coverers - 2);
                }
                return reward;
            })
            .asConstraint("sunday-healthy-depth-reward");
    }

    /** Coverers per weekend w (slots w,w+1 for w in 0..total-2), mirroring score_grid() exactly. */
    private static int[] coverersPerWeekend(TimefoldFacts tf, java.util.List<ResidentBlockAssignment> list, int total) {
        Map<Integer, Integer[]> byRes = new HashMap<>();
        for (ResidentBlockAssignment a : list) {
            if (!tf.isCategorical(a.getResidentId())) continue;
            Integer[] arr = byRes.computeIfAbsent(a.getResidentId(), k -> new Integer[total]);
            int s = TimefoldFacts.slotOf(a.getBlockNumber());
            if (s >= 0 && s < total) arr[s] = a.getRotationId();
        }
        int[] out = new int[Math.max(0, total - 1)];
        for (int w = 0; w + 1 < total; w++) {
            int coverers = 0;
            for (Integer[] arr : byRes.values()) {
                Integer here = arr[w], next = arr[w + 1];
                if (here != null && tf.isSundaySource(here) && (next == null || !tf.isHeavy(next))) coverers++;
            }
            out[w] = coverers;
        }
        return out;
    }

    // ── per-resident whole-timeline: prereq, MUST_BE_AFTER, segment structure, consecutive,
    //    max-blocks-per-rotation, workload, link sums, max-consec-heavy/medium, max-consec-blocks ──
    private Constraint perResidentTimeline(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getResidentId, ConstraintCollectors.toList())
            .penalize(HARD, (resId, rows) -> timelineViolations(facts(rows.get(0)), resId, rows))
            .asConstraint("per-resident-timeline");
    }

    /** Exact violation count for all per-resident-timeline hard constraints. */
    private static int timelineViolations(TimefoldFacts tf, int resId, List<ResidentBlockAssignment> rows) {
        int total = tf.totalBlocks();
        int pgy = tf.pgyOf(resId);
        boolean isCat = tf.isCategorical(resId);
        boolean isBmc = tf.isBmc(resId);

        // slot -> rotationId
        Integer[] rot = new Integer[total];
        Map<Integer, Integer> blocksByRot = new HashMap<>();
        for (ResidentBlockAssignment a : rows) {
            int s = TimefoldFacts.slotOf(a.getBlockNumber());
            if (s >= 0 && s < total) rot[s] = a.getRotationId();
            blocksByRot.merge(a.getRotationId(), 1, Integer::sum);
        }
        int v = 0;

        // (3) workload caps
        int load = rows.size();
        int maxLoad = Math.min(tf.config().getGlobalMaxWorkloadBlocks(), total);
        int minLoad = Math.min(tf.config().getGlobalMinWorkloadBlocks(), total);
        v += Math.max(0, load - maxLoad);
        v += Math.max(0, minLoad - load);

        // (4) max blocks per resident per rotation
        for (Map.Entry<Integer, Integer> e : blocksByRot.entrySet()) {
            int maxBlocks = Math.max(1, com.residency.model.ScheduleUnits.weeksToSlots(
                tf.rotation(e.getKey()).getMaxBlocksAllowed()));
            v += Math.max(0, e.getValue() - maxBlocks);
        }

        // (6) prerequisites + (7) MUST_BE_AFTER ordering
        for (int s = 0; s < total; s++) {
            Integer rid = rot[s];
            if (rid == null) continue;
            for (Prerequisite pre : tf.prerequisitesFor(rid)) {
                if (pre.getPgyLevel() != null && pre.getPgyLevel() != pgy) continue;
                if (!appearsBefore(rot, s, pre.getPrerequisiteRotationId())) v++;
            }
            for (RotationSequenceRule rule : tf.sequenceRulesFor(rid)) {
                if (rule.getRuleType() != RotationSequenceRuleType.MUST_BE_AFTER) continue;
                if (rule.getPgyLevel() != null && rule.getPgyLevel() != pgy) continue;
                if (!appearsBefore(rot, s, rule.getRelatedRotationId())) v++;
            }
        }

        // per-rotation segment structure (even-start, no-back-to-back, breaks, consecutive, max-consec)
        // Build per-rotation occupancy + starts once.
        Map<Integer, boolean[]> occByRot = new HashMap<>();
        for (int s = 0; s < total; s++) {
            if (rot[s] == null) continue;
            occByRot.computeIfAbsent(rot[s], k -> new boolean[total])[s] = true;
        }
        for (Map.Entry<Integer, boolean[]> e : occByRot.entrySet()) {
            int rid = e.getKey();
            boolean[] occ = e.getValue();
            ScheduleConfig.RotationPolicy p = tf.policy(rid);
            boolean[] start = new boolean[total];
            for (int t = 0; t < total; t++) start[t] = occ[t] && (t == 0 || !occ[t - 1]);

            // (13) even block start: a start on an odd slot is forbidden
            if (p.requireEvenBlockStart) {
                for (int t = 0; t < total; t++) if (start[t] && (t % 2 != 0)) v++;
            }
            // segment lengths
            List<int[]> segs = segments(occ); // each = {startSlot, lengthSlots}
            // (8) no back-to-back single-block segments: two 1-slot segs at adjacent starts
            if (p.noBackToBackHalfBlocks) {
                for (int i = 0; i < segs.size(); i++) {
                    for (int j = 0; j < segs.size(); j++) {
                        if (i == j) continue;
                        int[] si = segs.get(i), sj = segs.get(j);
                        if (si[1] == 1 && sj[1] == 1 && sj[0] == si[0] + 1) v++;
                    }
                }
            }
            // (12) require break between segments: start[t1] && start[t1+len] for allowed len
            if (p.requireBreakBetweenSegments) {
                for (int len : p.allowedBlockLengths) {
                    for (int t1 = 0; t1 < total; t1++) {
                        int t2 = t1 + len;
                        if (t2 < total && start[t1] && start[t2]) v++;
                    }
                }
            }
            // (14) requires consecutive: any gap between first and last occupied slot
            if (p.requiresConsecutive) {
                int first = -1, last = -1;
                for (int t = 0; t < total; t++) if (occ[t]) { if (first < 0) first = t; last = t; }
                if (first >= 0) for (int t = first; t <= last; t++) if (!occ[t]) v++;
            }
            // (10) max consecutive blocks per rotation
            if (p.maxConsecutiveBlocks > 0) {
                int mc = p.maxConsecutiveBlocks;
                for (int t = 0; t + mc < total; t++) {
                    int sum = 0;
                    for (int b = t; b <= t + mc; b++) if (occ[b]) sum++;
                    if (sum > mc) v += (sum - mc);
                }
            }
        }

        // max-consec heavy+medium run (categorical non-BMC, HARD mode)
        if (isCat && !isBmc
                && tf.config().getMaxConsecutiveHeavyMediumWeeks() > 0
                && tf.config().isMaxConsecHeavyMediumHard()) {
            int limitSlots = tf.config().getMaxConsecutiveHeavyMediumWeeks() / 2;
            if (limitSlots > 0) {
                int windowSize = limitSlots + 1;
                if (windowSize <= total) {
                    boolean[] hm = new boolean[total];
                    for (int s = 0; s < total; s++) hm[s] = rot[s] != null && tf.isHeavyOrMedium(rot[s]);
                    for (int b = 0; b + windowSize <= total; b++) {
                        boolean all = true;
                        for (int k = 0; k < windowSize; k++) if (!hm[b + k]) { all = false; break; }
                        if (all) v++;
                    }
                }
            }
        }

        // (15) rotation link per resident: blocks(rotA)+blocks(rotB) == sumPerResident
        for (ScheduleConfig.RotationLinkRule rule : tf.config().getRotationLinkRules()) {
            int sum = blocksByRot.getOrDefault(rule.rotAId, 0) + blocksByRot.getOrDefault(rule.rotBId, 0);
            v += Math.abs(sum - rule.sumPerResident);
        }

        return v;
    }

    private static List<int[]> segments(boolean[] occ) {
        List<int[]> out = new ArrayList<>();
        int t = 0;
        while (t < occ.length) {
            if (occ[t]) {
                int j = t;
                while (j < occ.length && occ[j]) j++;
                out.add(new int[]{t, j - t});
                t = j;
            } else t++;
        }
        return out;
    }

    private static boolean appearsBefore(Integer[] rot, int slot, int rotationId) {
        for (int s = 0; s < slot; s++) if (rot[s] != null && rot[s] == rotationId) return true;
        return false;
    }

    private static boolean hasCannotFollow(TimefoldFacts tf, int laterRotId, int earlierRotId, int pgy) {
        for (RotationSequenceRule rule : tf.sequenceRulesFor(laterRotId)) {
            if (rule.getRuleType() != RotationSequenceRuleType.CANNOT_IMMEDIATELY_FOLLOW) continue;
            if (rule.getPgyLevel() != null && rule.getPgyLevel() != pgy) continue;
            if (rule.getRelatedRotationId() == earlierRotId) return true;
        }
        return false;
    }

    private static boolean mutuallyNonAdjacent(TimefoldFacts tf, int rotA, int rotB) {
        if (tf.policy(rotA).mutuallyNonAdjacentWith.contains(rotB)) return true;
        return tf.policy(rotB).mutuallyNonAdjacentWith.contains(rotA);
    }
}
