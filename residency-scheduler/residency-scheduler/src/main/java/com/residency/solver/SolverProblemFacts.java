package com.residency.solver;

import com.residency.model.*;
import java.util.*;

/**
 * All the immutable facts the constraint provider needs during solving.
 * Loaded once from the DB and injected into the planning solution.
 * Keeping this separate from the solution keeps the planning entity lean.
 */
public class SolverProblemFacts {

    // rotationId -> Rotation
    private final Map<Integer, Rotation> rotations;

    // rotationId -> pgyLevel -> RotationRequirement
    private final Map<Integer, Map<Integer, RotationRequirement>> requirements;

    // rotationId -> list of Prerequisite rules
    private final Map<Integer, List<Prerequisite>> prerequisites;

    // blockId -> blockNumber (for ordering checks)
    private final Map<Integer, Integer> blockNumbers;

    public SolverProblemFacts(List<Rotation> rotations,
                               List<RotationRequirement> requirements,
                               List<Prerequisite> prerequisites,
                               List<Block> blocks) {
        this.rotations = new HashMap<>();
        for (Rotation r : rotations) this.rotations.put(r.getId(), r);

        this.requirements = new HashMap<>();
        for (RotationRequirement req : requirements) {
            this.requirements
                .computeIfAbsent(req.getRotationId(), k -> new HashMap<>())
                .put(req.getPgyLevel(), req);
        }

        this.prerequisites = new HashMap<>();
        for (Prerequisite p : prerequisites) {
            this.prerequisites
                .computeIfAbsent(p.getRotationId(), k -> new ArrayList<>())
                .add(p);
        }

        this.blockNumbers = new HashMap<>();
        for (Block b : blocks) this.blockNumbers.put(b.getId(), b.getBlockNumber());
    }

    public Map<Integer, Rotation> getRotations()        { return rotations; }
    public Rotation getRotation(int id)                 { return rotations.get(id); }

    public RotationRequirement getRequirement(int rotationId, int pgyLevel) {
        Map<Integer, RotationRequirement> byPgy = requirements.get(rotationId);
        return byPgy == null ? null : byPgy.get(pgyLevel);
    }

    public Map<Integer, Map<Integer, RotationRequirement>> getAllRequirements() {
        return requirements;
    }

    public List<Prerequisite> getPrerequisites(int rotationId) {
        return prerequisites.getOrDefault(rotationId, List.of());
    }

    public Map<Integer, List<Prerequisite>> getAllPrerequisites() {
        return prerequisites;
    }

    public int getBlockNumber(int blockId) {
        return blockNumbers.getOrDefault(blockId, 0);
    }

    public Set<Integer> allRotationIds() { return rotations.keySet(); }
}
