package com.residency.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A point-in-time snapshot of all assignments for a given year.
 * Used to support undo after an auto-schedule run.
 */
public class ScheduleSnapshot {

    /** Minimal record of one assignment sufficient to restore it. */
    public static class AssignmentRecord {
        public final int residentId;
        public final int rotationId;
        public final int blockId;
        public final boolean overrideWarning;

        public AssignmentRecord(int residentId, int rotationId, int blockId, boolean overrideWarning) {
            this.residentId = residentId;
            this.rotationId = rotationId;
            this.blockId = blockId;
            this.overrideWarning = overrideWarning;
        }
    }

    private final int year;
    private final LocalDateTime takenAt;
    private final List<AssignmentRecord> records;
    private final String label;

    public ScheduleSnapshot(int year, List<AssignmentRecord> records, String label) {
        this.year = year;
        this.takenAt = LocalDateTime.now();
        this.records = records;
        this.label = label;
    }

    public int getYear() { return year; }
    public LocalDateTime getTakenAt() { return takenAt; }
    public List<AssignmentRecord> getRecords() { return records; }
    public String getLabel() { return label; }

    @Override
    public String toString() {
        return label + " (" + takenAt.toLocalTime().toString().substring(0, 8) + ") — "
            + records.size() + " assignments";
    }
}
