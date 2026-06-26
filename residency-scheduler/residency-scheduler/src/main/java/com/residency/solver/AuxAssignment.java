package com.residency.solver;

/**
 * A FIXED (non-planning) auxiliary-resident assignment — a problem fact, not a planning entity.
 * Aux residents (is_auxiliary=1, e.g. BMC / TY bodies) occupy rotation slots that contribute
 * coverage credit toward per-block min/max but are NEVER moved by Timefold. This mirrors CP-SAT's
 * {@code auxCoverage} pre-count: the solver plans only categorical residents around fixed aux bodies.
 *
 * <p>Coverage is consumed via the {@link TimefoldFacts} aux-count map (pre-aggregated), so the
 * constraint provider does not need to stream these individually for capacity; this fact exists so
 * the solution carries the complete schedule for round-trip/commit and for any constraint that wants
 * to reason over individual aux placements.
 */
public final class AuxAssignment {

    private final int residentId;
    private final int rotationId;
    private final int blockNumber;   // 1-based, matches schedule_version_assignments

    public AuxAssignment(int residentId, int rotationId, int blockNumber) {
        this.residentId = residentId;
        this.rotationId = rotationId;
        this.blockNumber = blockNumber;
    }

    public int getResidentId()  { return residentId; }
    public int getRotationId()  { return rotationId; }
    public int getBlockNumber() { return blockNumber; }
    public int getSlot()        { return TimefoldFacts.slotOf(blockNumber); }

    @Override
    public String toString() {
        return "aux r" + residentId + "/blk" + blockNumber + "→rot" + rotationId;
    }
}
