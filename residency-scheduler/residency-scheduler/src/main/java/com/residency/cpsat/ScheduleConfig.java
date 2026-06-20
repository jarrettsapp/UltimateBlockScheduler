package com.residency.cpsat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central configuration for the CP-SAT scheduler.
 * All temporal values are in BLOCKS (1 block = 2 calendar weeks).
 * A full academic year = 26 blocks (blocks 0–25).
 */
public class ScheduleConfig {

    // ── Objective weights ──────────────────────────────────────────────────
    private int weightUndercoverage = 100;
    private int weightOvercoverage  = 20;
    private int weightVariance      = 10;
    private int weightPgyImbalance  = 15;
    /** Penalty per block that violates the 2-inpatient / 1-outpatient cycle (block-scale 4+2). */
    private int weightFourPlusTwo   = 30;
    private int weightInpatientSplit = 50;
    private int weightPostCallHard = 10000;
    private int weightPostCallSoft = 300;
    /**
     * Penalty per "missing" eligible Sunday-call coverer below the target, per weekend.
     * A weekend (block boundary b→b+1) is coverable by a resident who is on a Sunday-source
     * rotation (Inpatient GI / ID / any light) at block b AND is NOT entering a heavy
     * rotation at block b+1 (the manually-imposed pre-rotation rest lock). The objective
     * rewards SURPLUS coverers up to {@link #sundayCoverageTarget} so the downstream call
     * scheduler can balance call-shift counts across residents rather than overloading the
     * lone eligible person on single-coverer weekends. 0 = disabled (Tier-3 soft term).
     */
    private int weightSundayCoverage = 0;
    /** Target number of eligible Sunday coverers per weekend; shortfall below this is penalized. */
    private int sundayCoverageTarget = 2;
    /**
     * Penalty per categorical resident assigned beyond a rotation's
     * {@link RotationPolicy#categoricalSoftCap}, per block (Tier-3 soft objective). Lets a
     * rotation "prefer N categoricals but allow more when it helps" — e.g. VA prefers 2 but
     * may take a 3rd (up to the hard {@link RotationPolicy#categoricalMaxPerBlock}) when that
     * improves weekend coverage. Only fires for rotations with categoricalSoftCap &gt; 0.
     */
    private int weightCategoricalSoftExcess = 5;
    /**
     * When true, the solver enforces a HARD floor of ZERO volunteer weekends — every weekend
     * must have at least one eligible categorical Sunday-Y7 coverer (on a Sunday-source
     * rotation, not heavy that block, not entering heavy the next block). Proven achievable
     * (see SCHEDULE_VERSIONING_PLAN / coverage-floor analysis: 0 volunteers is feasible with
     * the full heavy load and capacity caps). Default OFF — flip on to eliminate the volunteer
     * fallback entirely; flip off to revert if the transition tradeoff isn't worth it.
     */
    private boolean enforceZeroVolunteerWeekends = false;

    /**
     * Maximum number of consecutive weeks (combining heavy + medium tiers) a categorical
     * resident may be assigned without a light-rotation break. This is the true max run: a
     * value of 12 permits e.g. 4 heavy → 4 medium → 4 heavy (12 wk) but forbids a 14th.
     * 0 = disabled.  Applies to categorical residents only (is_auxiliary = false, group != BMC).
     */
    private int maxConsecutiveHeavyMediumWeeks = 12;

    /**
     * When true the limit above is a HARD constraint (solver must obey).
     * When false it is a soft penalty folded into the Phase-3 objective
     * (each over-limit window slot costs {@code weightMaxConsecHeavyMedium} units).
     */
    private boolean maxConsecHeavyMediumHard = true;

    /** Penalty per over-limit window slot when running in soft mode. */
    private int weightMaxConsecHeavyMedium = 20;

    // ── Global staffing caps (in blocks) ──────────────────────────────────
    private int globalMaxWorkloadBlocks = 24;
    private int globalMinWorkloadBlocks = 0;

    // ── Solver tuning ──────────────────────────────────────────────────────
    private int cpSatTimeLimitSeconds = 0;
    private int cpSatNumWorkers = 4;
    private boolean cpSatLogSearch = true;

    // ── Schedule structure ─────────────────────────────────────────────────
    private int totalBlocks = 26;

    // ── Post-call incompatibility sets ────────────────────────────────────
    private Set<Integer> postCallTriggerRotationIds = new HashSet<>();
    private Set<Integer> mandatoryAttendanceRotationIds = new HashSet<>();
    private Set<Integer> discouragedAfterTriggerIds = new HashSet<>();

    // ── Weekend Sunday-coverage tier lists ─────────────────────────────────
    // Authoritative workload tiers for the Sunday-coverage objective. These are kept
    // separate from rotation_type because that flag is unreliable here (it types
    // Inpatient GI / ID as OUTPATIENT and Younker 8 Pulm as INPATIENT). A resident on
    // a heavy rotation is call-ineligible; entering one the next block triggers the
    // pre-rotation rest lock. Sunday-source rotations are those a covering resident may
    // be on (Inpatient GI / ID / any light rotation).
    private Set<Integer> heavyRotationIds = new HashSet<>();
    private Set<Integer> sundaySourceRotationIds = new HashSet<>();

    // ── Per-rotation overrides ─────────────────────────────────────────────
    private Map<Integer, RotationPolicy> rotationPolicies = new HashMap<>();

    // ── Linked rotation sum constraints ───────────────────────────────────
    private List<RotationLinkRule> rotationLinkRules = new ArrayList<>();

    public ScheduleConfig() {}

    /**
     * Enforces: for each resident, sum of blocks assigned to rotAId + rotBId = sumPerResident.
     * Optionally also enforces: total blocks of rotBId across all residents = globalTotalForRotB
     * (set to 0 to skip the global total constraint).
     */
    public static class RotationLinkRule {
        public int id;
        public int rotAId;
        public int rotBId;
        public int sumPerResident;
        public int globalTotalForRotB; // 0 = no global constraint

        public RotationLinkRule() {}
        public RotationLinkRule(int rotAId, int rotBId, int sumPerResident, int globalTotalForRotB) {
            this.rotAId = rotAId;
            this.rotBId = rotBId;
            this.sumPerResident = sumPerResident;
            this.globalTotalForRotB = globalTotalForRotB;
        }
    }

    // ── Inner class: per-rotation policy ──────────────────────────────────
    public static class RotationPolicy {
        public int rotationId;
        /** Allowed segment lengths in blocks (e.g. {2} for a 4-week rotation, {1} for a 2-week half-block). */
        public int[] allowedBlockLengths = {2};
        public boolean requiresConsecutive = false;
        public boolean optionalFullYearCoverage = false;
        public int minPerBlock = 0;
        public int maxPerBlock = 5;
        /**
         * Maximum number of CATEGORICAL residents on this rotation per block, enforced
         * directly on categorical occupancy and independent of auxiliary coverage. 0 = no
         * separate cap (the aux-aware {@link #maxPerBlock} total cap still applies).
         * Use when a rotation's slot limit binds categoricals specifically — e.g. ICU = 1
         * categorical (a TY may add a 2nd body up to the total cap), VA = 2 categoricals.
         */
        public int categoricalMaxPerBlock = 0;
        /**
         * Preferred (soft) maximum categoricals per block. Categoricals beyond this level are
         * still allowed up to {@link #categoricalMaxPerBlock} but are penalized in Phase 3 by
         * {@code weightCategoricalSoftExcess}. 0 = no soft cap.
         */
        public int categoricalSoftCap = 0;
        public Map<Integer, int[]> pgyMinMax = new HashMap<>(); // pgy -> [min, max] per block

        /** Prevent a 1-block segment immediately followed by another 1-block segment of the same rotation. */
        public boolean noBackToBackHalfBlocks = false;

        /** Require at least one block of a different rotation between any two segments on this rotation. */
        public boolean requireBreakBetweenSegments = false;

        /**
         * Maximum number of consecutive blocks a resident may spend on this rotation.
         * 0 = no limit.
         * Example: VA = 2 (max 2 contiguous blocks = 4 calendar weeks), ICU = 2.
         */
        public int maxConsecutiveBlocks = 0;

        /**
         * IDs of other rotations that must not be immediately adjacent to this rotation in
         * either direction (e.g. UPH Cardiology ↔ TIC Cardiology).
         */
        public List<Integer> mutuallyNonAdjacentWith = new ArrayList<>();

        /**
         * Earliest block index (0-based) at which a resident may start this rotation.
         * 0 = no restriction (default). E.g. 2 = not allowed in blocks 0 or 1 (first 4 calendar weeks).
         */
        public int earliestStartBlock = 0;

        /**
         * When true, every segment of this rotation must start on an even-numbered block
         * (0, 2, 4, … = 1A, 2A, 3A, …). Odd-block starts (1B, 2B, …) are forbidden.
         */
        public boolean requireEvenBlockStart = false;

        public RotationPolicy() {}
        public RotationPolicy(int rotationId) { this.rotationId = rotationId; }
    }

    // ── Getters / Setters ──────────────────────────────────────────────────
    public int getWeightUndercoverage()              { return weightUndercoverage; }
    public void setWeightUndercoverage(int v)        { this.weightUndercoverage = v; }

    public int getWeightOvercoverage()               { return weightOvercoverage; }
    public void setWeightOvercoverage(int v)         { this.weightOvercoverage = v; }

    public int getWeightVariance()                   { return weightVariance; }
    public void setWeightVariance(int v)             { this.weightVariance = v; }

    public int getWeightPgyImbalance()               { return weightPgyImbalance; }
    public void setWeightPgyImbalance(int v)         { this.weightPgyImbalance = v; }

    public int getWeightFourPlusTwo()                { return weightFourPlusTwo; }
    public void setWeightFourPlusTwo(int v)          { this.weightFourPlusTwo = v; }

    public int getWeightInpatientSplit()             { return weightInpatientSplit; }
    public void setWeightInpatientSplit(int v)       { this.weightInpatientSplit = v; }

    public int getWeightPostCallHard()               { return weightPostCallHard; }
    public void setWeightPostCallHard(int v)         { this.weightPostCallHard = v; }

    public int getWeightPostCallSoft()               { return weightPostCallSoft; }
    public void setWeightPostCallSoft(int v)         { this.weightPostCallSoft = v; }

    public int getWeightSundayCoverage()             { return weightSundayCoverage; }
    public void setWeightSundayCoverage(int v)       { this.weightSundayCoverage = v; }

    public int getSundayCoverageTarget()             { return sundayCoverageTarget; }
    public void setSundayCoverageTarget(int v)       { this.sundayCoverageTarget = v; }

    public int getWeightCategoricalSoftExcess()      { return weightCategoricalSoftExcess; }
    public void setWeightCategoricalSoftExcess(int v) { this.weightCategoricalSoftExcess = v; }

    public boolean isEnforceZeroVolunteerWeekends()        { return enforceZeroVolunteerWeekends; }
    public void setEnforceZeroVolunteerWeekends(boolean v) { this.enforceZeroVolunteerWeekends = v; }

    public int getMaxConsecutiveHeavyMediumWeeks()         { return maxConsecutiveHeavyMediumWeeks; }
    public void setMaxConsecutiveHeavyMediumWeeks(int v)   { this.maxConsecutiveHeavyMediumWeeks = v; }

    public boolean isMaxConsecHeavyMediumHard()            { return maxConsecHeavyMediumHard; }
    public void setMaxConsecHeavyMediumHard(boolean v)     { this.maxConsecHeavyMediumHard = v; }

    public int getWeightMaxConsecHeavyMedium()             { return weightMaxConsecHeavyMedium; }
    public void setWeightMaxConsecHeavyMedium(int v)       { this.weightMaxConsecHeavyMedium = v; }

    public Set<Integer> getHeavyRotationIds()        { return heavyRotationIds; }
    public void setHeavyRotationIds(Set<Integer> v)  { this.heavyRotationIds = v; }

    public Set<Integer> getSundaySourceRotationIds()       { return sundaySourceRotationIds; }
    public void setSundaySourceRotationIds(Set<Integer> v) { this.sundaySourceRotationIds = v; }

    public Set<Integer> getPostCallTriggerRotationIds()        { return postCallTriggerRotationIds; }
    public void setPostCallTriggerRotationIds(Set<Integer> v)  { this.postCallTriggerRotationIds = v; }

    public Set<Integer> getMandatoryAttendanceRotationIds()        { return mandatoryAttendanceRotationIds; }
    public void setMandatoryAttendanceRotationIds(Set<Integer> v)  { this.mandatoryAttendanceRotationIds = v; }

    public Set<Integer> getDiscouragedAfterTriggerIds()        { return discouragedAfterTriggerIds; }
    public void setDiscouragedAfterTriggerIds(Set<Integer> v)  { this.discouragedAfterTriggerIds = v; }

    public int getGlobalMaxWorkloadBlocks()           { return globalMaxWorkloadBlocks; }
    public void setGlobalMaxWorkloadBlocks(int v)     { this.globalMaxWorkloadBlocks = v; }

    public int getGlobalMinWorkloadBlocks()           { return globalMinWorkloadBlocks; }
    public void setGlobalMinWorkloadBlocks(int v)     { this.globalMinWorkloadBlocks = v; }

    public int getCpSatTimeLimitSeconds()            { return cpSatTimeLimitSeconds; }
    public void setCpSatTimeLimitSeconds(int v)      { this.cpSatTimeLimitSeconds = v; }

    public int getCpSatNumWorkers()                  { return cpSatNumWorkers; }
    public void setCpSatNumWorkers(int v)            { this.cpSatNumWorkers = v; }

    public boolean isCpSatLogSearch()                { return cpSatLogSearch; }
    public void setCpSatLogSearch(boolean v)         { this.cpSatLogSearch = v; }

    public int getTotalBlocks()                      { return totalBlocks; }
    public void setTotalBlocks(int v)                { this.totalBlocks = v; }

    public Map<Integer, RotationPolicy> getRotationPolicies() { return rotationPolicies; }
    public void setRotationPolicies(Map<Integer, RotationPolicy> v) { this.rotationPolicies = v; }

    public List<RotationLinkRule> getRotationLinkRules() { return rotationLinkRules; }
    public void setRotationLinkRules(List<RotationLinkRule> v) { this.rotationLinkRules = v; }

    public RotationPolicy getPolicyFor(int rotationId) {
        return rotationPolicies.computeIfAbsent(rotationId, RotationPolicy::new);
    }
}
