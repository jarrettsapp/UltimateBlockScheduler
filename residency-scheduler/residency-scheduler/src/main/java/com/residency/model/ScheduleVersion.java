package com.residency.model;

/**
 * Metadata for a saved, named schedule snapshot (one "final production" of a year's
 * schedule). The actual assignments live in {@code schedule_version_assignments}; this
 * record carries the listing/identity fields and the solve scores captured at save time.
 */
public class ScheduleVersion {
    private int id;
    private int scheduleYear;
    private String name;
    private String createdAt;   // ISO timestamp
    private String notes;
    private Integer tier1Score; // nullable — may be unknown for hand-built schedules
    private Integer tier2Score;
    private Integer tier3Score;
    private boolean feasible = true;
    private String summary;

    public ScheduleVersion() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getScheduleYear() { return scheduleYear; }
    public void setScheduleYear(int v) { this.scheduleYear = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { this.createdAt = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public Integer getTier1Score() { return tier1Score; }
    public void setTier1Score(Integer v) { this.tier1Score = v; }
    public Integer getTier2Score() { return tier2Score; }
    public void setTier2Score(Integer v) { this.tier2Score = v; }
    public Integer getTier3Score() { return tier3Score; }
    public void setTier3Score(Integer v) { this.tier3Score = v; }
    public boolean isFeasible() { return feasible; }
    public void setFeasible(boolean v) { this.feasible = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }

    @Override public String toString() {
        return name + "  (" + (createdAt == null ? "" : createdAt) + ")";
    }
}
