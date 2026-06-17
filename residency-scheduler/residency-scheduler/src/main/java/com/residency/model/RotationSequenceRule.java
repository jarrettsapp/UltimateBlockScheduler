package com.residency.model;

/**
 * Separate from academic prerequisites.
 *
 * Examples:
 * - Night Medicine MUST_BE_AFTER Ward Senior
 * - Night Medicine CANNOT_IMMEDIATELY_FOLLOW GI
 */
public class RotationSequenceRule {
    private int id;
    private int rotationId;
    private int relatedRotationId;
    private RotationSequenceRuleType ruleType;
    private Integer pgyLevel;

    public RotationSequenceRule() {}

    public RotationSequenceRule(int id, int rotationId, int relatedRotationId,
                                RotationSequenceRuleType ruleType, Integer pgyLevel) {
        this.id = id;
        this.rotationId = rotationId;
        this.relatedRotationId = relatedRotationId;
        this.ruleType = ruleType;
        this.pgyLevel = pgyLevel;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getRotationId() { return rotationId; }
    public void setRotationId(int rotationId) { this.rotationId = rotationId; }

    public int getRelatedRotationId() { return relatedRotationId; }
    public void setRelatedRotationId(int relatedRotationId) { this.relatedRotationId = relatedRotationId; }

    public RotationSequenceRuleType getRuleType() { return ruleType; }
    public void setRuleType(RotationSequenceRuleType ruleType) { this.ruleType = ruleType; }

    public Integer getPgyLevel() { return pgyLevel; }
    public void setPgyLevel(Integer pgyLevel) { this.pgyLevel = pgyLevel; }
}
