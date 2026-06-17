package com.residency.cpsat;

import com.google.ortools.sat.*;
import com.residency.model.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the CP-SAT objective function (tiered flow):
 *   Phase 1 → buildTier1Counter() then model.minimize(tier1Score)
 *   Phase 2 → buildTier1Counter() for the lock, buildTier2Core() for the objective
 *   Phase 3 → Tier-1 + Tier-2 locks, buildPatternObjective() for the objective
 *
 * All temporal indices are in BLOCKS (1 block = 2 calendar weeks).
 * A 26-block academic year = blocks 0–25.
 */
public class ObjectiveFunctionBuilder {

    private final CpModel model;
    private final VariableFactory vars;
    private final ScheduleConfig config;
    private final int totalBlocks;

    private final Map<String, IntVar> undercoverageVars = new LinkedHashMap<>();
    private final Map<String, IntVar> overcoverageVars  = new LinkedHashMap<>();

    public ObjectiveFunctionBuilder(CpModel model, VariableFactory vars,
                                    ScheduleConfig config, int totalBlocks) {
        this.model       = model;
        this.vars        = vars;
        this.config      = config;
        this.totalBlocks = totalBlocks;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tier 1 — clinical violation counter
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Creates binary indicator variables for every Tier-1 clinical violation
     * (post-call incompatibilities and inpatient→inpatient transitions), sums
     * them into a single IntVar and returns it.
     *
     * Does NOT call model.minimize() — the caller decides.
     */
    public IntVar buildTier1Counter(List<Resident> residents, List<Rotation> rotations) {
        Set<Integer> inpatientIds = rotations.stream()
            .filter(r -> r.getRotationType() == RotationType.INPATIENT)
            .map(Rotation::getId)
            .collect(Collectors.toSet());

        // Three buckets, each weighted separately from ScheduleConfig.
        List<BoolVar> primaryPostCall   = new ArrayList<>(); // trigger → mandatory-attendance
        List<BoolVar> secondaryPostCall  = new ArrayList<>(); // trigger → discouraged
        List<BoolVar> inpatientTransition = new ArrayList<>(); // inpatient → different inpatient

        // ── Post-call incompatibility violations ───────────────────────────
        // Primary (high-penalty): trigger rotation at block t followed by
        // mandatory-attendance rotation at t+1.
        // Secondary (lower-penalty): trigger rotation followed by discouraged rotation.
        if (!config.getPostCallTriggerRotationIds().isEmpty()) {
            for (Resident r : residents) {
                for (int t = 0; t + 1 < totalBlocks; t++) {
                    for (int triggerId : config.getPostCallTriggerRotationIds()) {
                        BoolVar trigOcc = vars.getOccupancyVar(r.getId(), triggerId, t);
                        if (trigOcc == null) continue;

                        // Primary: trigger → mandatory-attendance (weighted by weightPostCallHard)
                        for (int mandId : config.getMandatoryAttendanceRotationIds()) {
                            BoolVar mandOcc = vars.getOccupancyVar(r.getId(), mandId, t + 1);
                            if (mandOcc == null) continue;
                            BoolVar v = model.newBoolVar(
                                String.format("t1h_r%d_t%d_%d_%d", r.getId(), t, triggerId, mandId));
                            model.addGreaterOrEqual(v,
                                LinearExpr.newBuilder().add(trigOcc).add(mandOcc).add(-1));
                            model.addLessOrEqual(v, trigOcc);
                            model.addLessOrEqual(v, mandOcc);
                            primaryPostCall.add(v);
                        }

                        // Secondary: trigger → discouraged (weighted by weightPostCallSoft)
                        for (int discId : config.getDiscouragedAfterTriggerIds()) {
                            BoolVar discOcc = vars.getOccupancyVar(r.getId(), discId, t + 1);
                            if (discOcc == null) continue;
                            BoolVar v = model.newBoolVar(
                                String.format("t1s_r%d_t%d_%d_%d", r.getId(), t, triggerId, discId));
                            model.addGreaterOrEqual(v,
                                LinearExpr.newBuilder().add(trigOcc).add(discOcc).add(-1));
                            model.addLessOrEqual(v, trigOcc);
                            model.addLessOrEqual(v, discOcc);
                            secondaryPostCall.add(v);
                        }
                    }
                }
            }
        }

        // ── Inpatient → different inpatient at block boundary ──────────────
        // Weighted by weightInpatientSplit.
        if (inpatientIds.size() >= 2) {
            List<Integer> inpList = new ArrayList<>(inpatientIds);
            for (Resident r : residents) {
                for (int t = 1; t < totalBlocks; t++) {
                    for (int s1 : inpList) {
                        BoolVar occ1 = vars.getOccupancyVar(r.getId(), s1, t - 1);
                        if (occ1 == null) continue;
                        for (int s2 : inpList) {
                            if (s1 == s2) continue;
                            BoolVar occ2 = vars.getOccupancyVar(r.getId(), s2, t);
                            if (occ2 == null) continue;
                            BoolVar v = model.newBoolVar(
                                String.format("t1inp_r%d_t%d_%d_%d", r.getId(), t, s1, s2));
                            model.addGreaterOrEqual(v,
                                LinearExpr.newBuilder().add(occ1).add(occ2).add(-1));
                            model.addLessOrEqual(v, occ1);
                            model.addLessOrEqual(v, occ2);
                            inpatientTransition.add(v);
                        }
                    }
                }
            }
        }

        // Build weighted sum: each category contributes its configured weight per violation.
        int wHard = config.getWeightPostCallHard();
        int wSoft = config.getWeightPostCallSoft();
        int wInp  = config.getWeightInpatientSplit();
        long maxPossible = Math.max(1,
            (long) wHard * primaryPostCall.size()
            + (long) wSoft * secondaryPostCall.size()
            + (long) wInp  * inpatientTransition.size());

        IntVar tier1Score = model.newIntVar(0, maxPossible, "tier1_score");

        boolean hasTerms = !primaryPostCall.isEmpty() || !secondaryPostCall.isEmpty()
                           || !inpatientTransition.isEmpty();
        if (!hasTerms) {
            model.addEquality(tier1Score, LinearExpr.constant(0));
        } else {
            LinearExprBuilder expr = LinearExpr.newBuilder();
            for (BoolVar v : primaryPostCall)    expr.addTerm(v, wHard);
            for (BoolVar v : secondaryPostCall)   expr.addTerm(v, wSoft);
            for (BoolVar v : inpatientTransition) expr.addTerm(v, wInp);
            model.addEquality(tier1Score, expr);
        }
        return tier1Score;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tier 2 — schedule quality (coverage, variance, PGY balance)
    //  Returns a locked IntVar representing the total weighted cost.
    //  Does NOT call model.minimize() — caller decides.
    // ══════════════════════════════════════════════════════════════════════

    public IntVar buildTier2Core(List<Resident> residents, List<Rotation> rotations) {
        List<IntVar>  terms   = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();

        // α: Undercoverage
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int targetMin = policy.minPerBlock;
            if (targetMin <= 0) continue;
            for (int b = 0; b < totalBlocks; b++) {
                List<BoolVar> blockOccs = new ArrayList<>();
                for (Resident r : residents) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) blockOccs.add(occ);
                }
                if (blockOccs.isEmpty()) continue;
                IntVar under = model.newIntVar(0, targetMin,
                    String.format("t2_under_s%d_b%d", s.getId(), b));
                BoolVar[] arr = blockOccs.toArray(new BoolVar[0]);
                model.addLinearConstraint(
                    LinearExpr.newBuilder().addSum(arr).add(under),
                    targetMin, residents.size() + targetMin);
                undercoverageVars.put(s.getId() + "_" + b, under);
                terms.add(under);
                weights.add(config.getWeightUndercoverage());
            }
        }

        // β: Overcoverage
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            int targetMax = policy.maxPerBlock;
            for (int b = 0; b < totalBlocks; b++) {
                List<BoolVar> blockOccs = new ArrayList<>();
                for (Resident r : residents) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) blockOccs.add(occ);
                }
                if (blockOccs.isEmpty()) continue;
                IntVar over = model.newIntVar(0, residents.size(),
                    String.format("t2_over_s%d_b%d", s.getId(), b));
                BoolVar[] arr = blockOccs.toArray(new BoolVar[0]);
                model.addLinearConstraint(
                    LinearExpr.newBuilder().addSum(arr).addTerm(over, -1),
                    0, targetMax);
                overcoverageVars.put(s.getId() + "_" + b, over);
                terms.add(over);
                weights.add(config.getWeightOvercoverage());
            }
        }

        // γ: Workload variance (pairwise absolute difference)
        if (config.getWeightVariance() > 0 && residents.size() > 1) {
            List<IntVar> workloads = new ArrayList<>();
            for (Resident r : residents) {
                List<BoolVar> allOccs = new ArrayList<>();
                for (Rotation s : rotations) {
                    for (int b = 0; b < totalBlocks; b++) {
                        BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                        if (occ != null) allOccs.add(occ);
                    }
                }
                if (allOccs.isEmpty()) continue;
                IntVar load = model.newIntVar(0, totalBlocks, "t2_load_r" + r.getId());
                model.addEquality(load, LinearExpr.sum(allOccs.toArray(new BoolVar[0])));
                workloads.add(load);
            }
            for (int i = 0; i < workloads.size(); i++) {
                for (int j = i + 1; j < workloads.size(); j++) {
                    IntVar diff    = model.newIntVar(0, totalBlocks, "t2_wdiff_" + i + "_" + j);
                    IntVar absDiff = model.newIntVar(0, totalBlocks, "t2_wabs_"  + i + "_" + j);
                    model.addEquality(diff,
                        LinearExpr.newBuilder()
                            .add(workloads.get(i))
                            .addTerm(workloads.get(j), -1));
                    model.addAbsEquality(absDiff, diff);
                    terms.add(absDiff);
                    weights.add(config.getWeightVariance());
                }
            }
        }

        // δ: PGY imbalance
        if (config.getWeightPgyImbalance() > 0) {
            Map<Integer, List<Resident>> byPgy = new LinkedHashMap<>();
            for (Resident r : residents)
                byPgy.computeIfAbsent(r.getPgyLevel(), k -> new ArrayList<>()).add(r);
            if (byPgy.size() > 1) {
                for (Rotation s : rotations) {
                    for (int b = 0; b < totalBlocks; b++) {
                        List<IntVar> pgyTotals = new ArrayList<>();
                        for (var pgyEntry : byPgy.entrySet()) {
                            List<BoolVar> pgyOccs = new ArrayList<>();
                            for (Resident r : pgyEntry.getValue()) {
                                BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                                if (occ != null) pgyOccs.add(occ);
                            }
                            if (pgyOccs.isEmpty()) continue;
                            IntVar cnt = model.newIntVar(0, pgyOccs.size(),
                                String.format("t2_pgy%d_s%d_b%d", pgyEntry.getKey(), s.getId(), b));
                            model.addEquality(cnt,
                                LinearExpr.sum(pgyOccs.toArray(new BoolVar[0])));
                            pgyTotals.add(cnt);
                        }
                        for (int i = 0; i < pgyTotals.size(); i++) {
                            for (int j = i + 1; j < pgyTotals.size(); j++) {
                                IntVar diff = model.newIntVar(-residents.size(), residents.size(),
                                    "t2_pgyd_" + i + "_" + j + "_s" + s.getId() + "_b" + b);
                                IntVar abs  = model.newIntVar(0, residents.size(),
                                    "t2_pgya_" + i + "_" + j + "_s" + s.getId() + "_b" + b);
                                model.addEquality(diff,
                                    LinearExpr.newBuilder()
                                        .add(pgyTotals.get(i))
                                        .addTerm(pgyTotals.get(j), -1));
                                model.addAbsEquality(abs, diff);
                                terms.add(abs);
                                weights.add(config.getWeightPgyImbalance());
                            }
                        }
                    }
                }
            }
        }

        // Build a single IntVar = weighted sum of all Tier-2 core terms.
        // Bound conservatively: max weight × totalBlocks × residents × rotations.
        int maxWeight = Math.max(config.getWeightUndercoverage(),
                        Math.max(config.getWeightOvercoverage(),
                        Math.max(config.getWeightVariance(), config.getWeightPgyImbalance())));
        long maxCost = (long) maxWeight * totalBlocks * residents.size() * Math.max(1, rotations.size());
        IntVar tier2Cost = model.newIntVar(0, maxCost, "tier2_core_cost");

        if (terms.isEmpty()) {
            model.addEquality(tier2Cost, 0);
        } else {
            LinearExprBuilder expr = LinearExpr.newBuilder();
            for (int i = 0; i < terms.size(); i++) expr.addTerm(terms.get(i), weights.get(i));
            model.addEquality(tier2Cost, expr);
        }
        return tier2Cost;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tier 3 — 4+2 pattern objective (inpatient/outpatient cycle)
    //  Returns an IntVar counting pattern violations. Does NOT minimize.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the 2+1 block pattern term (2 inpatient blocks, 1 outpatient per cycle).
     * Returns an IntVar counting the total unweighted pattern violations.
     * Call model.minimize() on the result (or a weighted version) in the caller.
     */
    public IntVar buildPatternObjective(List<Resident> residents, List<Rotation> rotations) {
        Set<Integer> inpatientIds = rotations.stream()
            .filter(r -> r.getRotationType() == RotationType.INPATIENT)
            .map(Rotation::getId).collect(Collectors.toSet());
        Set<Integer> outpatientIds = rotations.stream()
            .filter(r -> r.getRotationType() == RotationType.OUTPATIENT)
            .map(Rotation::getId).collect(Collectors.toSet());

        List<BoolVar> violations = new ArrayList<>();

        if (config.getWeightFourPlusTwo() > 0
                && (!inpatientIds.isEmpty() || !outpatientIds.isEmpty())) {
            for (Resident r : residents) {
                for (int b = 0; b < totalBlocks; b++) {
                    int pos = b % 3;
                    if (pos < 2) {
                        for (int rotId : outpatientIds) {
                            BoolVar occ = vars.getOccupancyVar(r.getId(), rotId, b);
                            if (occ != null) violations.add(occ);
                        }
                    } else {
                        for (int rotId : inpatientIds) {
                            BoolVar occ = vars.getOccupancyVar(r.getId(), rotId, b);
                            if (occ != null) violations.add(occ);
                        }
                    }
                }
            }
        }

        long maxViol = (long) residents.size() * totalBlocks;
        IntVar patternCost = model.newIntVar(0, maxViol, "tier3_pattern_cost");
        if (violations.isEmpty()) {
            model.addEquality(patternCost, 0);
        } else {
            model.addEquality(patternCost, LinearExpr.sum(violations.toArray(new BoolVar[0])));
        }
        return patternCost;
    }

    public Map<String, IntVar> getUndercoverageVars() { return undercoverageVars; }
    public Map<String, IntVar> getOvercoverageVars()  { return overcoverageVars;  }
}
