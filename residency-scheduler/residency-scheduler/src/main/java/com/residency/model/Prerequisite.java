package com.residency.model;

/**
 * Represents a prerequisite rule: a resident must complete
 * prerequisiteRotationId before being assigned to rotationId.
 */
public class Prerequisite {
    private int id;
    private int rotationId;           // the rotation that has a prerequisite
    private int prerequisiteRotationId; // must be completed first
    private Integer pgyLevel;         // null = applies to all PGY levels

    public Prerequisite() {}

    public Prerequisite(int id, int rotationId, int prerequisiteRotationId, Integer pgyLevel) {
        this.id = id;
        this.rotationId = rotationId;
        this.prerequisiteRotationId = prerequisiteRotationId;
        this.pgyLevel = pgyLevel;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getRotationId() { return rotationId; }
    public void setRotationId(int rotationId) { this.rotationId = rotationId; }

    public int getPrerequisiteRotationId() { return prerequisiteRotationId; }
    public void setPrerequisiteRotationId(int prerequisiteRotationId) {
        this.prerequisiteRotationId = prerequisiteRotationId;
    }

    public Integer getPgyLevel() { return pgyLevel; }
    public void setPgyLevel(Integer pgyLevel) { this.pgyLevel = pgyLevel; }
}
