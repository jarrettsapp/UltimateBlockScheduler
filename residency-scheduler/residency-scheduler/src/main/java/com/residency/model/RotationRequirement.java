package com.residency.model;

/**
 * Defines how many blocks a resident at a given PGY level must/can spend on a rotation.
 * Also tracks prerequisite rotations.
 */
public class RotationRequirement {
    private int id;
    private int rotationId;
    private int pgyLevel;
    private double minBlocks;  // 0 = not required; supports half-blocks (0.5, 1.0, 1.5, etc.)
    private double maxBlocks;
    private boolean required; // must complete at least minBlocks

    public RotationRequirement() {}

    public RotationRequirement(int id, int rotationId, int pgyLevel,
                                double minBlocks, double maxBlocks, boolean required) {
        this.id = id;
        this.rotationId = rotationId;
        this.pgyLevel = pgyLevel;
        this.minBlocks = minBlocks;
        this.maxBlocks = maxBlocks;
        this.required = required;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getRotationId() { return rotationId; }
    public void setRotationId(int rotationId) { this.rotationId = rotationId; }

    public int getPgyLevel() { return pgyLevel; }
    public void setPgyLevel(int pgyLevel) { this.pgyLevel = pgyLevel; }

    public double getMinBlocks() { return minBlocks; }
    public void setMinBlocks(double minBlocks) { this.minBlocks = minBlocks; }

    public double getMaxBlocks() { return maxBlocks; }
    public void setMaxBlocks(double maxBlocks) { this.maxBlocks = maxBlocks; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
}
