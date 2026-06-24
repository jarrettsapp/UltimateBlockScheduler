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
    // Pre-counted auxiliary-resident coverage: rotationId -> blockIndex -> count.
    // The coverage objective subtracts this from min/max so it matches the hard
    // coverage constraint, which already credits aux coverage (REVIEW: the
    // undercoverage objective previously ignored aux and penalised blocks that
    // were fully staffed by ancillary residents).
    private final Map<Integer, Map<Integer, Integer>> auxCoverage;

    private final Map<String, IntVar> undercoverageVars = new LinkedHashMap<>();
    private final Map<String, IntVar> overcoverageVars  = new LinkedHashMap<>();

    public ObjectiveFunctionBuilder(CpModel model, VariableFactory vars,
                                    ScheduleConfig config, int totalBlocks) {
        this(model, vars, config, totalBlocks, Map.of());
    }

    public ObjectiveFunctionBuilder(CpModel model, VariableFactory vars,
                                    ScheduleConfig config, int totalBlocks,
                                    Map<Integer, Map<Integer, Integer>> auxCoverage) {
        this.model       = model;
        this.vars        = vars;
        this.config      = config;
        this.totalBlocks = totalBlocks;
        this.auxCoverage = auxCoverage;
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

        // α: Undercoverage. Credit pre-counted aux coverage the same way the hard
        // coverage constraint does (effectiveMin = max(0, minPerBlock - auxCount)),
        // so blocks already staffed by ancillary residents are not penalised.
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            if (policy.minPerBlock <= 0) continue;
            Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());
            for (int b = 0; b < totalBlocks; b++) {
                int effectiveMin = Math.max(0, policy.minPerBlock - auxByBlock.getOrDefault(b, 0));
                if (effectiveMin <= 0) continue;
                List<BoolVar> blockOccs = new ArrayList<>();
                for (Resident r : residents) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) blockOccs.add(occ);
                }
                if (blockOccs.isEmpty()) continue;
                IntVar under = model.newIntVar(0, effectiveMin,
                    String.format("t2_under_s%d_b%d", s.getId(), b));
                BoolVar[] arr = blockOccs.toArray(new BoolVar[0]);
                model.addLinearConstraint(
                    LinearExpr.newBuilder().addSum(arr).add(under),
                    effectiveMin, residents.size() + effectiveMin);
                undercoverageVars.put(s.getId() + "_" + b, under);
                terms.add(under);
                weights.add(config.getWeightUndercoverage());
            }
        }

        // β: Overcoverage. Add aux coverage to the headcount so the effective max
        // matches the hard constraint (effectiveMax = max(0, maxPerBlock - auxCount)).
        for (Rotation s : rotations) {
            ScheduleConfig.RotationPolicy policy = config.getPolicyFor(s.getId());
            Map<Integer, Integer> auxByBlock = auxCoverage.getOrDefault(s.getId(), Map.of());
            for (int b = 0; b < totalBlocks; b++) {
                int effectiveMax = Math.max(0, policy.maxPerBlock - auxByBlock.getOrDefault(b, 0));
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
                    0, effectiveMax);
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
     * Builds the 4+2 cadence term: per 3-slot (6-week) cycle, prefer 2 INPATIENT
     * slots (4 weeks) then 1 OUTPATIENT slot (2 weeks). Inpatient is the heavier
     * workload the program spreads out, so the cycle is inpatient-heavy.
     *
     * Implementation: at cycle positions 0,1 (which "want" inpatient) every
     * outpatient occupancy is counted as a violation; at position 2 (which "wants"
     * outpatient) every inpatient occupancy is counted. Minimizing the count
     * therefore pushes toward 2-inpatient / 1-outpatient per cycle. The caller
     * minimizes this (weighted by weightFourPlusTwo).
     *
     * Known limitation (RULES.md §13, B2): the cadence is anchored to the absolute
     * block index (b % 3), so every resident's preferred phase is aligned rather
     * than staggered. If staggering matters for call distribution, replace this
     * with a per-resident max-consecutive-inpatient rule ("option B"). Kept as-is
     * for now — this is a low-priority Tier-3 nudge and the direction is correct.
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
                        // Positions 0,1 of the cycle want INPATIENT — penalize outpatient here.
                        for (int rotId : outpatientIds) {
                            BoolVar occ = vars.getOccupancyVar(r.getId(), rotId, b);
                            if (occ != null) violations.add(occ);
                        }
                    } else {
                        // Position 2 wants OUTPATIENT — penalize inpatient here.
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

    // ══════════════════════════════════════════════════════════════════════
    //  Tier 3 — Sunday weekend-coverage objective
    //  Returns an IntVar = Σ shortfall below the per-weekend coverer target.
    //  Does NOT minimize — the caller folds it into the Phase-3 objective.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Rewards having enough eligible Younker-7 Sunday-night coverers per weekend.
     *
     * A "weekend" sits at the boundary of half-block b → b+1 (the trailing Sat+Sun of
     * block b belongs to block b's rotation). A resident can cover that Sunday iff:
     *   (1) they are on a Sunday-source rotation at block b (Inpatient GI / ID / any
     *       light rotation — heavy rotations are call-ineligible for their whole run), AND
     *   (2) they are NOT entering a heavy rotation at block b+1 (the manually-imposed
     *       pre-rotation rest lock: a resident starting a heavy rotation Monday cannot
     *       burn a call shift the weekend before).
     *
     * The objective penalizes the SHORTFALL below {@code sundayCoverageTarget} eligible
     * coverers, summed over all weekends. Rewarding surplus (target ≥ 2) — not merely
     * ≥ 1 — spreads coverage across multiple residents so the downstream call scheduler
     * can balance per-resident call-shift counts instead of overloading the lone option
     * on single-coverer weekends. A zero-coverer weekend (a forced-volunteer Sunday)
     * incurs the full target-sized penalty, so eliminating those is the steepest gain.
     *
     * Returns a zero IntVar when disabled (weight ≤ 0) or when the tier lists are unset.
     */
    public IntVar buildSundayCoverageObjective(List<Resident> residents) {
        int target = config.getSundayCoverageTarget();
        Set<Integer> heavyIds  = config.getHeavyRotationIds();
        Set<Integer> sourceIds = config.getSundaySourceRotationIds();

        IntVar shortfallTotal = model.newIntVar(0,
            (long) Math.max(0, target) * Math.max(1, totalBlocks), "tier3_sunday_shortfall");

        if (config.getWeightSundayCoverage() <= 0 || target <= 0
                || heavyIds.isEmpty() || sourceIds.isEmpty()) {
            model.addEquality(shortfallTotal, 0);
            return shortfallTotal;
        }

        List<IntVar> weekendShortfalls = new ArrayList<>();
        // Weekends are block boundaries b → b+1, for b in [0, totalBlocks-2].
        for (int b = 0; b + 1 < totalBlocks; b++) {
            List<BoolVar> eligible = new ArrayList<>();
            for (Resident r : residents) {
                // onSource[b]: resident is on a Sunday-source rotation this block.
                List<BoolVar> sourceOccs = new ArrayList<>();
                for (int rotId : sourceIds) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), rotId, b);
                    if (occ != null) sourceOccs.add(occ);
                }
                if (sourceOccs.isEmpty()) continue; // resident can't be a source here

                // enteringHeavy[b+1]: resident is on a heavy rotation next block.
                List<BoolVar> heavyNextOccs = new ArrayList<>();
                for (int rotId : heavyIds) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), rotId, b + 1);
                    if (occ != null) heavyNextOccs.add(occ);
                }

                // elig = onSource AND NOT enteringHeavy.
                // onSource is the sum of mutually-exclusive source occupancies (0/1).
                // enteringHeavy is the sum of heavy occupancies next block (0/1).
                BoolVar elig = model.newBoolVar(
                    String.format("t3cov_elig_r%d_b%d", r.getId(), b));
                BoolVar[] src = sourceOccs.toArray(new BoolVar[0]);
                // elig ≤ Σ source  (can only be eligible if on a source rotation)
                model.addLessOrEqual(elig, LinearExpr.sum(src));
                if (!heavyNextOccs.isEmpty()) {
                    BoolVar[] hn = heavyNextOccs.toArray(new BoolVar[0]);
                    // elig ≤ 1 − Σ heavyNext  (not eligible if entering heavy)
                    model.addLessOrEqual(
                        LinearExpr.newBuilder().add(elig).addSum(hn).build(),
                        1);
                    // elig ≥ Σ source − Σ heavyNext  (force to 1 when on source and not entering heavy)
                    model.addGreaterOrEqual(
                        LinearExpr.newBuilder().add(elig)
                            .addSum(hn)
                            .build(),
                        LinearExpr.sum(src));
                } else {
                    // No heavy-next possibility: elig == onSource.
                    model.addGreaterOrEqual(elig, LinearExpr.sum(src));
                }
                eligible.add(elig);
            }

            // shortfall = max(0, target − Σ eligible)
            IntVar shortfall = model.newIntVar(0, target,
                String.format("t3cov_short_b%d", b));
            if (eligible.isEmpty()) {
                model.addEquality(shortfall, target);
            } else {
                BoolVar[] arr = eligible.toArray(new BoolVar[0]);
                // shortfall ≥ target − Σ eligible   (and shortfall ≥ 0 by domain)
                model.addGreaterOrEqual(
                    LinearExpr.newBuilder().add(shortfall).addSum(arr).build(),
                    target);
            }
            weekendShortfalls.add(shortfall);
        }

        if (weekendShortfalls.isEmpty()) {
            model.addEquality(shortfallTotal, 0);
        } else {
            model.addEquality(shortfallTotal,
                LinearExpr.sum(weekendShortfalls.toArray(new IntVar[0])));
        }
        return shortfallTotal;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Tier 3 — categorical soft-cap excess
    //  Returns an IntVar = Σ over rotations (categoricalSoftCap > 0) and blocks of
    //  max(0, categoricalCount − softCap). Does NOT minimize — folded into Phase 3.
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Penalizes categoricals assigned beyond a rotation's soft cap. A rotation may set
     * {@code categoricalSoftCap = N} to "prefer ≤ N categoricals per block" while still
     * allowing more, up to its hard {@code categoricalMaxPerBlock}; each categorical above N in
     * a block adds 1 to this count (weighted by weightCategoricalSoftExcess in Phase 3). So a
     * 3rd categorical on a soft-cap-2 rotation is permitted but discouraged — taken only when
     * it buys enough elsewhere (e.g. fewer fragile weekends). Zero IntVar when no rotation
     * sets a soft cap.
     */
    public IntVar buildCategoricalSoftCapObjective(List<Resident> residents, List<Rotation> rotations) {
        List<IntVar> excesses = new ArrayList<>();
        for (Rotation s : rotations) {
            int softCap = config.getPolicyFor(s.getId()).categoricalSoftCap;
            if (softCap <= 0) continue;
            for (int b = 0; b < totalBlocks; b++) {
                List<BoolVar> occs = new ArrayList<>();
                for (Resident r : residents) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) occs.add(occ);
                }
                if (occs.size() <= softCap) continue; // count can never exceed the cap here
                IntVar excess = model.newIntVar(0, occs.size() - softCap,
                    String.format("t3_catsoft_s%d_b%d", s.getId(), b));
                // excess + softCap ≥ Σ occ  →  excess ≥ count − softCap; with domain ≥ 0 and
                // minimization this yields excess = max(0, count − softCap).
                model.addGreaterOrEqual(
                    LinearExpr.newBuilder().add(excess).add(softCap).build(),
                    LinearExpr.sum(occs.toArray(new BoolVar[0])));
                excesses.add(excess);
            }
        }
        IntVar total = model.newIntVar(0,
            (long) Math.max(1, residents.size()) * Math.max(1, totalBlocks), "tier3_catsoft_total");
        if (excesses.isEmpty()) {
            model.addEquality(total, 0);
        } else {
            model.addEquality(total, LinearExpr.sum(excesses.toArray(new IntVar[0])));
        }
        return total;
    }

    public Map<String, IntVar> getUndercoverageVars() { return undercoverageVars; }
    public Map<String, IntVar> getOvercoverageVars()  { return overcoverageVars;  }
}
