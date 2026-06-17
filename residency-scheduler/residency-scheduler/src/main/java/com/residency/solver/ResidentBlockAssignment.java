package com.residency.solver;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

@PlanningEntity
public class ResidentBlockAssignment {

    /** Unique across all planning entities — required by Timefold for forEachUniquePair. */
    @PlanningId
    private String id;

    private int residentId;
    private String residentName;
    private int pgyLevel;
    private int blockId;
    private int blockNumber;

    @PlanningVariable(nullable = true)
    private Integer rotationId;

    // Embedded rule data
    private Map<Integer, Integer> maxBlocksPerRotation  = Collections.emptyMap();
    private Map<Integer, Integer> minBlocksRequired     = Collections.emptyMap();
    private Set<Integer>          requiredRotationIds   = Collections.emptySet();
    private Map<Integer, Set<Integer>> prerequisiteMap  = Collections.emptyMap();
    private Map<Integer, Set<Integer>> mustBeAfterMap   = Collections.emptyMap();
    private Map<Integer, Set<Integer>> disallowedImmediatePredecessorMap = Collections.emptyMap();
    private Set<Integer>          eligibleRotationIds   = Collections.emptySet();
    /** rotationId -> max simultaneous residents per block (capacity) */
    private Map<Integer, Integer> maxResidentsPerBlock  = Collections.emptyMap();

    public ResidentBlockAssignment() {}

    public ResidentBlockAssignment(int residentId, String residentName, int pgyLevel,
                                   int blockId, int blockNumber) {
        this.id           = residentId + "_" + blockId;
        this.residentId   = residentId;
        this.residentName = residentName;
        this.pgyLevel     = pgyLevel;
        this.blockId      = blockId;
        this.blockNumber  = blockNumber;
    }

    // Constraint helpers
    public int getMaxBlocksForRotation(int rotId)       { return maxBlocksPerRotation.getOrDefault(rotId, 999); }
    public int getMinBlocksForRotation(int rotId)       { return minBlocksRequired.getOrDefault(rotId, 0); }
    public boolean isRequiredRotation(int rotId)        { return requiredRotationIds.contains(rotId); }
    public Set<Integer> getPrerequisitesFor(int rotId)  { return prerequisiteMap.getOrDefault(rotId, Collections.emptySet()); }
    public Set<Integer> getMustBeAfterFor(int rotId)    { return mustBeAfterMap.getOrDefault(rotId, Collections.emptySet()); }
    public Set<Integer> getDisallowedImmediatePredecessorsFor(int rotId) {
        return disallowedImmediatePredecessorMap.getOrDefault(rotId, Collections.emptySet());
    }
    public int getMaxResidentsForBlock(int rotId)       { return maxResidentsPerBlock.getOrDefault(rotId, 999); }
    public boolean isAssigned()                         { return rotationId != null; }

    // Getters / Setters
    public String getId()                                 { return id; }
    public int getResidentId()                           { return residentId; }
    public void setResidentId(int v)                     { this.residentId = v; }
    public String getResidentName()                      { return residentName; }
    public void setResidentName(String v)                { this.residentName = v; }
    public int getPgyLevel()                             { return pgyLevel; }
    public void setPgyLevel(int v)                       { this.pgyLevel = v; }
    public int getBlockId()                              { return blockId; }
    public void setBlockId(int v)                        { this.blockId = v; }
    public int getBlockNumber()                          { return blockNumber; }
    public void setBlockNumber(int v)                    { this.blockNumber = v; }
    public Integer getRotationId()                       { return rotationId; }
    public void setRotationId(Integer v)                 { this.rotationId = v; }
    public Map<Integer, Integer> getMaxBlocksPerRotation()         { return maxBlocksPerRotation; }
    public void setMaxBlocksPerRotation(Map<Integer, Integer> v)   { this.maxBlocksPerRotation = v; }
    public Map<Integer, Integer> getMinBlocksRequired()            { return minBlocksRequired; }
    public void setMinBlocksRequired(Map<Integer, Integer> v)      { this.minBlocksRequired = v; }
    public Set<Integer> getRequiredRotationIds()                   { return requiredRotationIds; }
    public void setRequiredRotationIds(Set<Integer> v)             { this.requiredRotationIds = v; }
    public Map<Integer, Set<Integer>> getPrerequisiteMap()         { return prerequisiteMap; }
    public void setPrerequisiteMap(Map<Integer, Set<Integer>> v)   { this.prerequisiteMap = v; }
    public Map<Integer, Set<Integer>> getMustBeAfterMap()          { return mustBeAfterMap; }
    public void setMustBeAfterMap(Map<Integer, Set<Integer>> v)    { this.mustBeAfterMap = v; }
    public Map<Integer, Set<Integer>> getDisallowedImmediatePredecessorMap() {
        return disallowedImmediatePredecessorMap;
    }
    public void setDisallowedImmediatePredecessorMap(Map<Integer, Set<Integer>> v) {
        this.disallowedImmediatePredecessorMap = v;
    }
    public Set<Integer> getEligibleRotationIds()                   { return eligibleRotationIds; }
    public void setEligibleRotationIds(Set<Integer> v)             { this.eligibleRotationIds = v; }
    public Map<Integer, Integer> getMaxResidentsPerBlock()         { return maxResidentsPerBlock; }
    public void setMaxResidentsPerBlock(Map<Integer, Integer> v)   { this.maxResidentsPerBlock = v; }

    @Override
    public String toString() {
        return residentName + "/Blk" + blockNumber + "→" + (rotationId == null ? "∅" : rotationId);
    }
}
