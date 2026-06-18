package com.residency.model;

public class Rotation {
    private int id;
    private String name;
    private String department;
    private int maxResidentsPerBlock;
    // NOTE: despite the "Blocks" in these field names, both are stored in WEEKS
    // (entered on the Rotations tab as "Min Weeks" / "Max Weeks", multiples of 2).
    // Convert to the solver's 2-week slot grid via ScheduleUnits.weeksToSlots().
    private int minBlocksRequired;  // min weeks a resident must spend on this rotation
    private int maxBlocksAllowed;   // max weeks a resident can spend on this rotation
    private String description;
    private RotationType rotationType = RotationType.UNSPECIFIED;

    public Rotation() {}

    public Rotation(int id, String name, String department,
                    int maxResidentsPerBlock, int minBlocksRequired, int maxBlocksAllowed, String description) {
        this.id = id;
        this.name = name;
        this.department = department;
        this.maxResidentsPerBlock = maxResidentsPerBlock;
        this.minBlocksRequired = minBlocksRequired;
        this.maxBlocksAllowed = maxBlocksAllowed;
        this.description = description;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public int getMaxResidentsPerBlock() { return maxResidentsPerBlock; }
    public void setMaxResidentsPerBlock(int maxResidentsPerBlock) { this.maxResidentsPerBlock = maxResidentsPerBlock; }

    public int getMinBlocksRequired() { return minBlocksRequired; }
    public void setMinBlocksRequired(int minBlocksRequired) { this.minBlocksRequired = minBlocksRequired; }

    public int getMaxBlocksAllowed() { return maxBlocksAllowed; }
    public void setMaxBlocksAllowed(int maxBlocksAllowed) { this.maxBlocksAllowed = maxBlocksAllowed; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public RotationType getRotationType() { return rotationType; }
    public void setRotationType(RotationType rotationType) { this.rotationType = rotationType; }

    @Override
    public String toString() { return name + " (" + department + ")"; }
}
