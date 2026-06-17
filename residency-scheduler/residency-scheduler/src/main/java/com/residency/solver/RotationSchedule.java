package com.residency.solver;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.ProblemFactProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;

import java.util.List;

/**
 * The Timefold planning solution.
 *
 * Contains:
 *  - The list of planning entities (one per resident×block slot)
 *  - The value range (all valid rotation IDs, plus null for unassigned)
 *  - Immutable problem facts (rotations, rules, prerequisites)
 *  - The score (Hard/Medium/Soft)
 *
 * Score semantics:
 *  HARD:   constraints that must never be violated (capacity, one-per-block, prerequisites, max-blocks)
 *  MEDIUM: required rotations that must be fulfilled (min blocks per PGY rule)
 *  SOFT:   optimisation goals (workload balance, minimise unassigned slots)
 */
@PlanningSolution
public class RotationSchedule {

    @PlanningEntityCollectionProperty
    private List<ResidentBlockAssignment> assignments;

    @ProblemFactProperty
    private SolverProblemFacts facts;

    /** One entry per (resident × required rotation) — lets constraints detect zero-assignment cases. */
    @ProblemFactCollectionProperty
    private List<ResidentRequirement> residentRequirements;

    /** All rotation IDs available as values, plus null (unassigned). */
    @ValueRangeProvider
    private List<Integer> rotationIdRange;

    @PlanningScore
    private HardMediumSoftScore score;

    private int scheduleYear;

    public RotationSchedule() {}

    public RotationSchedule(List<ResidentBlockAssignment> assignments,
                             SolverProblemFacts facts,
                             List<ResidentRequirement> residentRequirements,
                             List<Integer> rotationIdRange,
                             int scheduleYear) {
        this.assignments          = assignments;
        this.facts                = facts;
        this.residentRequirements = residentRequirements;
        this.rotationIdRange      = rotationIdRange;
        this.scheduleYear         = scheduleYear;
    }

    // ── Getters / Setters ──────────────────────────────────────────────────

    public List<ResidentBlockAssignment> getAssignments()           { return assignments; }
    public void setAssignments(List<ResidentBlockAssignment> v)     { this.assignments = v; }

    public SolverProblemFacts getFacts()                            { return facts; }
    public void setFacts(SolverProblemFacts v)                      { this.facts = v; }

    public List<ResidentRequirement> getResidentRequirements()              { return residentRequirements; }
    public void setResidentRequirements(List<ResidentRequirement> v)        { this.residentRequirements = v; }

    public List<Integer> getRotationIdRange()                       { return rotationIdRange; }
    public void setRotationIdRange(List<Integer> v)                 { this.rotationIdRange = v; }

    public HardMediumSoftScore getScore()                           { return score; }
    public void setScore(HardMediumSoftScore v)                     { this.score = v; }

    public int getScheduleYear()                                    { return scheduleYear; }
    public void setScheduleYear(int v)                              { this.scheduleYear = v; }

    /** Human-readable score summary. */
    public String scoreSummary() {
        if (score == null) return "Not scored";
        return String.format("Hard: %d  |  Medium: %d  |  Soft: %d",
            score.hardScore(), score.mediumScore(), score.softScore());
    }

    public boolean isFeasible() {
        return score != null && score.hardScore() == 0;
    }
}
