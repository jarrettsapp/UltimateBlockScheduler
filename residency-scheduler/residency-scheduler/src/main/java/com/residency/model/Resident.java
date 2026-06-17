package com.residency.model;

public class Resident {
    private int id;
    private String name;
    private int pgyLevel;
    private String email;
    private boolean auxiliary;
    private String residentGroup; // e.g. "BMC", "TY", null for main residents

    public Resident() {}

    public Resident(int id, String name, int pgyLevel, String email) {
        this.id = id;
        this.name = name;
        this.pgyLevel = pgyLevel;
        this.email = email;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getPgyLevel() { return pgyLevel; }
    public void setPgyLevel(int pgyLevel) { this.pgyLevel = pgyLevel; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public boolean isAuxiliary() { return auxiliary; }
    public void setAuxiliary(boolean auxiliary) { this.auxiliary = auxiliary; }

    public String getResidentGroup() { return residentGroup; }
    public void setResidentGroup(String residentGroup) { this.residentGroup = residentGroup; }

    @Override
    public String toString() {
        String suffix = auxiliary ? (residentGroup != null ? ", " + residentGroup : ", Aux") : "";
        return name + " (PGY-" + pgyLevel + suffix + ")";
    }
}
