package com.residency.cpsat;

import java.util.*;

/**
 * Structured result from the CP-SAT solver.
 * Contains the full assignment grid, coverage metrics, and diagnostics.
 */
public class ScheduleSolution {

    public enum Status { OPTIMAL, FEASIBLE, INFEASIBLE, UNKNOWN, MODEL_INVALID }

    // ── Assignment data ────────────────────────────────────────────────────
    // residentId -> rotationId -> list of assigned week indices
    private final Map<Integer, Map<Integer, List<Integer>>> assignments = new LinkedHashMap<>();

    // ── Solver metadata ────────────────────────────────────────────────────
    private Status status = Status.UNKNOWN;
    private double objectiveValue = 0;
    private long runtimeMs = 0;
    private int numBranches = 0;
    private int numConflicts = 0;
    private String solverLog = "";

    // ── Feasibility diagnostics ────────────────────────────────────────────
    private FeasibilityReport feasibilityReport;

    // ── Methods ────────────────────────────────────────────────────────────

    public void recordAssignment(int residentId, int rotationId, int week) {
        assignments
            .computeIfAbsent(residentId, k -> new LinkedHashMap<>())
            .computeIfAbsent(rotationId, k -> new ArrayList<>())
            .add(week);
    }

    public boolean isFeasible() {
        return status == Status.OPTIMAL || status == Status.FEASIBLE;
    }

    public List<Integer> getAssignedWeeks(int residentId, int rotationId) {
        return assignments
            .getOrDefault(residentId, Map.of())
            .getOrDefault(rotationId, List.of());
    }

    public Map<Integer, Map<Integer, List<Integer>>> getAssignments() { return assignments; }

    public Status getStatus()                        { return status; }
    public void setStatus(Status v)                  { this.status = v; }
    public double getObjectiveValue()                { return objectiveValue; }
    public void setObjectiveValue(double v)          { this.objectiveValue = v; }
    public long getRuntimeMs()                       { return runtimeMs; }
    public void setRuntimeMs(long v)                 { this.runtimeMs = v; }
    public int getNumBranches()                      { return numBranches; }
    public void setNumBranches(int v)                { this.numBranches = v; }
    public int getNumConflicts()                     { return numConflicts; }
    public void setNumConflicts(int v)               { this.numConflicts = v; }
    public String getSolverLog()                     { return solverLog; }
    public void setSolverLog(String v)               { this.solverLog = v; }
    public FeasibilityReport getFeasibilityReport()  { return feasibilityReport; }
    public void setFeasibilityReport(FeasibilityReport v) { this.feasibilityReport = v; }

    public String statusSummary() {
        return String.format("[%s] obj=%.1f  runtime=%dms  branches=%d  conflicts=%d  assignments=%d",
            status, objectiveValue, runtimeMs, numBranches, numConflicts,
            assignments.values().stream().mapToInt(m -> m.values().stream().mapToInt(List::size).sum()).sum());
    }
}
