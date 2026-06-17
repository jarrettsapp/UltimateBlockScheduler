package com.residency.model;

public class Assignment {
    private int id;
    private int residentId;
    private int rotationId;
    private int blockId;
    private boolean overrideWarning; // true if user acknowledged a rule warning

    // Denormalized for display convenience
    private String residentName;
    private String rotationName;
    private int blockNumber;
    private int scheduleYear;

    public Assignment() {}

    public Assignment(int id, int residentId, int rotationId, int blockId, boolean overrideWarning) {
        this.id = id;
        this.residentId = residentId;
        this.rotationId = rotationId;
        this.blockId = blockId;
        this.overrideWarning = overrideWarning;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getResidentId() { return residentId; }
    public void setResidentId(int residentId) { this.residentId = residentId; }

    public int getRotationId() { return rotationId; }
    public void setRotationId(int rotationId) { this.rotationId = rotationId; }

    public int getBlockId() { return blockId; }
    public void setBlockId(int blockId) { this.blockId = blockId; }

    public boolean isOverrideWarning() { return overrideWarning; }
    public void setOverrideWarning(boolean overrideWarning) { this.overrideWarning = overrideWarning; }

    public String getResidentName() { return residentName; }
    public void setResidentName(String residentName) { this.residentName = residentName; }

    public String getRotationName() { return rotationName; }
    public void setRotationName(String rotationName) { this.rotationName = rotationName; }

    public int getBlockNumber() { return blockNumber; }
    public void setBlockNumber(int blockNumber) { this.blockNumber = blockNumber; }

    public int getScheduleYear() { return scheduleYear; }
    public void setScheduleYear(int scheduleYear) { this.scheduleYear = scheduleYear; }
}
