package com.residency.model;

public enum RotationType {
    INPATIENT,
    OUTPATIENT,
    UNSPECIFIED;

    @Override
    public String toString() {
        return switch (this) {
            case INPATIENT   -> "Inpatient";
            case OUTPATIENT  -> "Outpatient";
            case UNSPECIFIED -> "Unspecified";
        };
    }

    public static RotationType fromString(String s) {
        if (s == null) return UNSPECIFIED;
        return switch (s.toUpperCase()) {
            case "INPATIENT"  -> INPATIENT;
            case "OUTPATIENT" -> OUTPATIENT;
            default           -> UNSPECIFIED;
        };
    }
}
