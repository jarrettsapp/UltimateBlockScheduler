package com.residency.solver;

import com.residency.cpsat.ScheduleConfig;
import com.residency.model.*;

import java.util.*;

/**
 * Immutable problem facts for the Timefold feasibility/optimization model — the single carrier the
 * {@link RotationFeasibilityConstraintProvider} reads. Built ONCE per solve to mirror exactly what
 * {@code CpSatSchedulerEngine} feeds {@code ConstraintBuilder}, so the Timefold hard model and the
 * CP-SAT hard model are driven by identical data (no drift).
 *
 * <p>Holds:
 * <ul>
 *   <li>the full {@link ScheduleConfig} (per-rotation policies, tier lists, link rules, weights);</li>
 *   <li>auxiliary coverage pre-count {@code rotationId -> blockIndex -> count} (aux residents are
 *       FIXED problem facts, not planning entities — they contribute coverage credit but never move);</li>
 *   <li>eligibility, requirements (rot->pgy->req), prerequisites, sequence rules;</li>
 *   <li>resolved heavy / medium / sunday-source / heavy-or-medium rotation id sets (by name, via
 *       {@link WorkloadTiers});</li>
 *   <li>rotation metadata (name, type, maxBlocksAllowed) and pgy by resident.</li>
 * </ul>
 * Block indices are 0-based slots (0..totalBlocks-1), matching ConstraintBuilder. Version assignments
 * are stored by 1-based {@code block_number}; the entity carries {@code blockNumber} and we treat the
 * 0-based slot as {@code blockNumber - 1} consistently (see {@link #slotOf}).
 */
public final class TimefoldFacts {

    private final ScheduleConfig config;
    private final int totalBlocks;

    private final Map<Integer, Rotation> rotationById;
    private final Map<Integer, Integer> pgyByResident;          // residentId -> pgy
    private final Set<Integer> categoricalResidentIds;          // is_auxiliary=0
    private final Set<Integer> bmcResidentIds;                  // residentGroup == "BMC"

    // aux coverage: rotationId -> (slotIndex -> count of aux bodies)
    private final Map<Integer, Map<Integer, Integer>> auxCoverage;

    private final Map<Integer, Set<Integer>> eligibleByRotation;                 // rotId -> resident ids
    private final Map<Integer, Map<Integer, RotationRequirement>> requirements;  // rotId -> pgy -> req
    private final Map<Integer, List<Prerequisite>> prerequisites;                // rotId -> prereqs
    private final Map<Integer, List<RotationSequenceRule>> sequenceRules;        // rotId -> rules

    private final Set<Integer> heavyIds;
    private final Set<Integer> mediumIds;
    private final Set<Integer> heavyOrMediumIds;
    private final Set<Integer> sundaySourceIds;

    public TimefoldFacts(ScheduleConfig config, int totalBlocks,
                         List<Rotation> rotations,
                         Map<Integer, Integer> pgyByResident,
                         Set<Integer> categoricalResidentIds,
                         Set<Integer> bmcResidentIds,
                         Map<Integer, Map<Integer, Integer>> auxCoverage,
                         Map<Integer, Set<Integer>> eligibleByRotation,
                         Map<Integer, Map<Integer, RotationRequirement>> requirements,
                         Map<Integer, List<Prerequisite>> prerequisites,
                         Map<Integer, List<RotationSequenceRule>> sequenceRules) {
        this.config = config;
        this.totalBlocks = totalBlocks;
        this.rotationById = new HashMap<>();
        for (Rotation r : rotations) this.rotationById.put(r.getId(), r);
        this.pgyByResident = pgyByResident;
        this.categoricalResidentIds = categoricalResidentIds;
        this.bmcResidentIds = bmcResidentIds;
        this.auxCoverage = auxCoverage;
        this.eligibleByRotation = eligibleByRotation;
        this.requirements = requirements;
        this.prerequisites = prerequisites;
        this.sequenceRules = sequenceRules;

        this.heavyIds = WorkloadTiers.heavyIds(rotations);
        this.heavyOrMediumIds = WorkloadTiers.heavyOrMediumIds(rotations);
        this.sundaySourceIds = WorkloadTiers.sundaySourceIds(rotations);
        Set<Integer> med = new HashSet<>();
        for (Rotation r : rotations) if (WorkloadTiers.MEDIUM.contains(r.getName())) med.add(r.getId());
        this.mediumIds = med;
    }

    /** 0-based slot index for a 1-based block_number (entity carries blockNumber). */
    public static int slotOf(int blockNumber) { return blockNumber - 1; }

    public ScheduleConfig config()                     { return config; }
    public int totalBlocks()                           { return totalBlocks; }
    public Rotation rotation(int id)                   { return rotationById.get(id); }
    public String rotationName(int id)                 { Rotation r = rotationById.get(id); return r == null ? null : r.getName(); }
    public int pgyOf(int residentId)                   { return pgyByResident.getOrDefault(residentId, -1); }
    public boolean isCategorical(int residentId)       { return categoricalResidentIds.contains(residentId); }
    public boolean isBmc(int residentId)               { return bmcResidentIds.contains(residentId); }

    public int auxCount(int rotationId, int slot) {
        Map<Integer, Integer> m = auxCoverage.get(rotationId);
        return m == null ? 0 : m.getOrDefault(slot, 0);
    }

    public Set<Integer> eligibleResidents(int rotationId) {
        return eligibleByRotation.getOrDefault(rotationId, Set.of());
    }
    public RotationRequirement requirement(int rotationId, int pgy) {
        Map<Integer, RotationRequirement> m = requirements.get(rotationId);
        return m == null ? null : m.get(pgy);
    }
    public List<Prerequisite> prerequisitesFor(int rotationId) {
        return prerequisites.getOrDefault(rotationId, List.of());
    }
    public List<RotationSequenceRule> sequenceRulesFor(int rotationId) {
        return sequenceRules.getOrDefault(rotationId, List.of());
    }

    public boolean isHeavy(int rotationId)          { return heavyIds.contains(rotationId); }
    public boolean isMedium(int rotationId)         { return mediumIds.contains(rotationId); }
    public boolean isHeavyOrMedium(int rotationId)  { return heavyOrMediumIds.contains(rotationId); }
    public boolean isSundaySource(int rotationId)   { return sundaySourceIds.contains(rotationId); }
    public Set<Integer> heavyIds()                  { return heavyIds; }
    public Set<Integer> sundaySourceIds()           { return sundaySourceIds; }

    public ScheduleConfig.RotationPolicy policy(int rotationId) { return config.getPolicyFor(rotationId); }
    public Collection<Rotation> allRotations()      { return rotationById.values(); }
}
