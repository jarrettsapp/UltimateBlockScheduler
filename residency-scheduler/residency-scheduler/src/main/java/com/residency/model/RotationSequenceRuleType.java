package com.residency.model;

public enum RotationSequenceRuleType {
    MUST_BE_AFTER,
    CANNOT_IMMEDIATELY_FOLLOW;

    public String getDisplayName() {
        return switch (this) {
            case MUST_BE_AFTER -> "Must be after";
            case CANNOT_IMMEDIATELY_FOLLOW -> "Cannot immediately follow";
        };
    }
}
