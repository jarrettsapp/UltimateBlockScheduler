package com.residency.cpsat;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured diagnostic report produced by FeasibilityAnalyzer.
 * Identifies why a schedule may be infeasible or sub-optimal.
 */
public class FeasibilityReport {

    public enum IssueType {
        PGY_BOTTLENECK,         // Too few PGY-N residents for required rotation coverage
        INSUFFICIENT_POOL,      // Not enough eligible residents for a rotation
        OVER_CONSTRAINED,       // Rotation demands exceed total available resident-weeks
        WORKLOAD_IMPOSSIBLE,    // Global workload bounds can't be satisfied
        BLOCK_LENGTH_CONFLICT,  // No valid block placements exist given constraints
        PREREQUISITE_CYCLE,     // Circular prerequisite chain detected
        CAPACITY_INFEASIBLE     // Max residents/block too low for min coverage
    }

    public static class Issue {
        public final IssueType type;
        public final String rotationName;
        public final String description;
        public final String suggestion;

        public Issue(IssueType type, String rotationName, String description, String suggestion) {
            this.type         = type;
            this.rotationName = rotationName;
            this.description  = description;
            this.suggestion   = suggestion;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s → %s", type, rotationName, description, suggestion);
        }
    }

    private final List<Issue> issues = new ArrayList<>();
    private boolean feasible = true;
    private int totalResidentWeeksAvailable;
    private int totalResidentWeeksRequired;

    public void addIssue(IssueType type, String rotationName, String description, String suggestion) {
        issues.add(new Issue(type, rotationName, description, suggestion));
        feasible = false;
    }

    public List<Issue> getIssues()                  { return issues; }
    public boolean isFeasible()                     { return feasible; }
    public boolean hasIssues()                      { return !issues.isEmpty(); }
    public int getTotalResidentWeeksAvailable()     { return totalResidentWeeksAvailable; }
    public void setTotalResidentWeeksAvailable(int v) { this.totalResidentWeeksAvailable = v; }
    public int getTotalResidentWeeksRequired()      { return totalResidentWeeksRequired; }
    public void setTotalResidentWeeksRequired(int v)  { this.totalResidentWeeksRequired = v; }

    public String summary() {
        if (issues.isEmpty()) return "✅ No feasibility issues detected.";
        StringBuilder sb = new StringBuilder();
        sb.append("⚠ ").append(issues.size()).append(" issue(s) detected:\n");
        for (Issue i : issues) sb.append("  • ").append(i).append("\n");
        return sb.toString();
    }
}
