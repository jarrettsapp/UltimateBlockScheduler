package com.residency.model;

import java.time.LocalDate;

public class Block {
    private int id;
    private int blockNumber;  // 1-26 for a standard year (1a=1, 1b=2, 2a=3, 2b=4, ...)
    private int scheduleYear; // academic year e.g. 2024
    private LocalDate startDate;
    private LocalDate endDate;

    public Block() {}

    public Block(int id, int blockNumber, int scheduleYear, LocalDate startDate, LocalDate endDate) {
        this.id = id;
        this.blockNumber = blockNumber;
        this.scheduleYear = scheduleYear;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getBlockNumber() { return blockNumber; }
    public void setBlockNumber(int blockNumber) { this.blockNumber = blockNumber; }

    public int getScheduleYear() { return scheduleYear; }
    public void setScheduleYear(int scheduleYear) { this.scheduleYear = scheduleYear; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    /** Returns the display label: 1→"1a", 2→"1b", 3→"2a", 4→"2b", … */
    public String getLabel() {
        int parent = (blockNumber + 1) / 2;
        char half  = (blockNumber % 2 == 1) ? 'a' : 'b';
        return parent + "" + half;
    }

    @Override
    public String toString() {
        return "Block " + getLabel() + " (" + scheduleYear + ")";
    }
}
