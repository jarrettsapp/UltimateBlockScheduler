package com.residency.solver;

/**
 * Immutable problem fact representing one required-rotation obligation for a specific resident.
 * Created once per (resident × required RotationRequirement) pair before solving begins.
 *
 * Needed because Timefold constraint streams can only penalise things that exist as facts or
 * planning entities. Without this, a resident who has ZERO assignments on a required rotation
 * is invisible to any groupBy constraint — the group simply never appears.
 */
public class ResidentRequirement {

    private final int residentId;
    private final int rotationId;
    private final double minBlocks;

    public ResidentRequirement(int residentId, int rotationId, double minBlocks) {
        this.residentId = residentId;
        this.rotationId = rotationId;
        this.minBlocks  = minBlocks;
    }

    public int getResidentId() { return residentId; }
    public int getRotationId() { return rotationId; }
    public double getMinBlocks()  { return minBlocks; }

    @Override
    public String toString() {
        return "ResidentRequirement{res=" + residentId + ", rot=" + rotationId
            + ", min=" + minBlocks + "}";
    }
}
