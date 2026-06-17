package com.residency.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the full result of an auto-scheduling run:
 * assignments made, failures, and scoring details.
 */
public class AutoScheduleResult {

    public static class PlacedAssignment {
        public final int residentId;
        public final String residentName;
        public final int rotationId;
        public final String rotationName;
        public final int blockId;
        public final int blockNumber;
        public final boolean wasRequired;

        public PlacedAssignment(int residentId, String residentName,
                                int rotationId, String rotationName,
                                int blockId, int blockNumber, boolean wasRequired) {
            this.residentId = residentId;
            this.residentName = residentName;
            this.rotationId = rotationId;
            this.rotationName = rotationName;
            this.blockId = blockId;
            this.blockNumber = blockNumber;
            this.wasRequired = wasRequired;
        }
    }

    public static class FailedRequirement {
        public final int residentId;
        public final String residentName;
        public final int rotationId;
        public final String rotationName;
        public final int blocksNeeded;
        public final int blocksPlaced;
        public final String reason;

        public FailedRequirement(int residentId, String residentName,
                                 int rotationId, String rotationName,
                                 int blocksNeeded, int blocksPlaced, String reason) {
            this.residentId = residentId;
            this.residentName = residentName;
            this.rotationId = rotationId;
            this.rotationName = rotationName;
            this.blocksNeeded = blocksNeeded;
            this.blocksPlaced = blocksPlaced;
            this.reason = reason;
        }
    }

    private final List<PlacedAssignment> placed = new ArrayList<>();
    private final List<FailedRequirement> failures = new ArrayList<>();

    private int totalIterations;
    private int swapsAccepted;
    private double initialScore;
    private double finalScore;
    private long runtimeMs;

    public void addPlaced(PlacedAssignment a) { placed.add(a); }
    public void addFailure(FailedRequirement f) { failures.add(f); }

    public List<PlacedAssignment> getPlaced() { return placed; }
    public List<FailedRequirement> getFailures() { return failures; }

    public boolean isFullyOptimal() { return failures.isEmpty(); }

    public int getTotalIterations() { return totalIterations; }
    public void setTotalIterations(int totalIterations) { this.totalIterations = totalIterations; }

    public int getSwapsAccepted() { return swapsAccepted; }
    public void setSwapsAccepted(int swapsAccepted) { this.swapsAccepted = swapsAccepted; }

    public double getInitialScore() { return initialScore; }
    public void setInitialScore(double initialScore) { this.initialScore = initialScore; }

    public double getFinalScore() { return finalScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }

    public long getRuntimeMs() { return runtimeMs; }
    public void setRuntimeMs(long runtimeMs) { this.runtimeMs = runtimeMs; }

    public int countRequired() {
        return (int) placed.stream().filter(p -> p.wasRequired).count();
    }

    public int countOptional() {
        return (int) placed.stream().filter(p -> !p.wasRequired).count();
    }
}
