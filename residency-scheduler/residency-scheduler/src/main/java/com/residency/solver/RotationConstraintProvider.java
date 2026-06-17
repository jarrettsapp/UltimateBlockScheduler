package com.residency.solver;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.*;

/**
 * All scheduling constraints as Timefold ConstraintStreams.
 *
 * HARD   — violations make the schedule infeasible
 * MEDIUM — required rotations not fulfilled (feasibility)
 * SOFT   — optimisation goals
 */
public class RotationConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[]{
            // Hard
            everyResidentMustBeAssigned(f),
            rotationCapacityNotExceeded(f),
            maxBlocksPerResidentPerRotation(f),
            prerequisiteOrdering(f),
            sequenceMustBeAfter(f),
            noImmediateForbiddenFollower(f),
            onlyEligibleRotations(f),
            // Medium — required rotations must be fulfilled
            requiredRotationNotStarted(f),
            requiredRotationUnderMinimum(f),
            // Soft — fill the schedule
            minimiseUnassignedSlots(f),
        };
    }

    // ── HARD constraints ───────────────────────────────────────────────────

    // HARD 0: every resident must have a rotation assigned for every block
    private Constraint everyResidentMustBeAssigned(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(a -> !a.isAssigned())
            .penalize(HardMediumSoftScore.ONE_HARD)
            .asConstraint("Every resident must be assigned a rotation");
    }
    private Constraint rotationCapacityNotExceeded(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getBlockId,
                     ResidentBlockAssignment::getRotationId,
                     ConstraintCollectors.toList())
            .filter((blockId, rotId, list) ->
                list.size() > list.get(0).getMaxResidentsForBlock(rotId))
            .penalize(HardMediumSoftScore.ONE_HARD,
                (blockId, rotId, list) ->
                    list.size() - list.get(0).getMaxResidentsForBlock(rotId))
            .asConstraint("Rotation capacity not exceeded");
    }

    // HARD 2: max blocks per resident per rotation (PGY rule)
    private Constraint maxBlocksPerResidentPerRotation(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(ResidentBlockAssignment::isAssigned)
            .groupBy(ResidentBlockAssignment::getResidentId,
                     ResidentBlockAssignment::getRotationId,
                     ConstraintCollectors.toList())
            .filter((resId, rotId, list) ->
                list.size() > list.get(0).getMaxBlocksForRotation(rotId))
            .penalize(HardMediumSoftScore.ONE_HARD,
                (resId, rotId, list) ->
                    list.size() - list.get(0).getMaxBlocksForRotation(rotId))
            .asConstraint("Max blocks per resident per rotation");
    }

    // HARD 3: prerequisite ordering — prereq rotation must be in earlier block
    private Constraint prerequisiteOrdering(ConstraintFactory f) {
        return f.forEachUniquePair(ResidentBlockAssignment.class,
                Joiners.equal(ResidentBlockAssignment::getResidentId))
            .filter((a, b) -> {
                if (!a.isAssigned() || !b.isAssigned()) return false;
                boolean aRequiresB = a.getPrerequisitesFor(a.getRotationId())
                                      .contains(b.getRotationId());
                if (aRequiresB && a.getBlockNumber() <= b.getBlockNumber()) return true;
                boolean bRequiresA = b.getPrerequisitesFor(b.getRotationId())
                                      .contains(a.getRotationId());
                return bRequiresA && b.getBlockNumber() <= a.getBlockNumber();
            })
            .penalize(HardMediumSoftScore.ONE_HARD)
            .asConstraint("Prerequisite ordering");
    }

    private Constraint sequenceMustBeAfter(ConstraintFactory f) {
        return f.forEachUniquePair(ResidentBlockAssignment.class,
                Joiners.equal(ResidentBlockAssignment::getResidentId))
            .filter((a, b) -> {
                if (!a.isAssigned() || !b.isAssigned()) return false;
                boolean aMustBeAfterB = a.getMustBeAfterFor(a.getRotationId()).contains(b.getRotationId());
                if (aMustBeAfterB && a.getBlockNumber() <= b.getBlockNumber()) return true;
                boolean bMustBeAfterA = b.getMustBeAfterFor(b.getRotationId()).contains(a.getRotationId());
                return bMustBeAfterA && b.getBlockNumber() <= a.getBlockNumber();
            })
            .penalize(HardMediumSoftScore.ONE_HARD)
            .asConstraint("Sequence must be after");
    }

    private Constraint noImmediateForbiddenFollower(ConstraintFactory f) {
        return f.forEachUniquePair(ResidentBlockAssignment.class,
                Joiners.equal(ResidentBlockAssignment::getResidentId))
            .filter((a, b) -> {
                if (!a.isAssigned() || !b.isAssigned()) return false;
                if (Math.abs(a.getBlockNumber() - b.getBlockNumber()) != 1) return false;

                ResidentBlockAssignment earlier = a.getBlockNumber() < b.getBlockNumber() ? a : b;
                ResidentBlockAssignment later = earlier == a ? b : a;
                return later.getDisallowedImmediatePredecessorsFor(later.getRotationId())
                    .contains(earlier.getRotationId());
            })
            .penalize(HardMediumSoftScore.ONE_HARD)
            .asConstraint("No forbidden immediate follower");
    }

    // HARD 4: only assign rotations valid for resident's PGY level
    private Constraint onlyEligibleRotations(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(a -> a.isAssigned()
                && !a.getEligibleRotationIds().contains(a.getRotationId()))
            .penalize(HardMediumSoftScore.ONE_HARD)
            .asConstraint("Only eligible rotations for PGY level");
    }

    // ── MEDIUM constraints — required rotations ────────────────────────────

    // MEDIUM 1: required rotation has ZERO assignments — catches the completely-missing case
    // Uses ResidentRequirement problem facts so the constraint fires even when the groupBy
    // in MEDIUM 2 would produce no rows.
    private Constraint requiredRotationNotStarted(ConstraintFactory f) {
        return f.forEach(ResidentRequirement.class)
            .ifNotExists(ResidentBlockAssignment.class,
                Joiners.equal(ResidentRequirement::getResidentId,
                              ResidentBlockAssignment::getResidentId),
                Joiners.equal(ResidentRequirement::getRotationId,
                              ResidentBlockAssignment::getRotationId),
                Joiners.filtering((req, a) -> a.isAssigned()))
            .penalize(HardMediumSoftScore.ONE_MEDIUM,
                (req) -> (int)Math.ceil(req.getMinBlocks()))
            .asConstraint("Required rotation not started");
    }

    // MEDIUM 2: required rotation has SOME assignments but fewer than the minimum
    private Constraint requiredRotationUnderMinimum(ConstraintFactory f) {
        return f.forEach(ResidentRequirement.class)
            .join(ResidentBlockAssignment.class,
                Joiners.equal(ResidentRequirement::getResidentId,
                              ResidentBlockAssignment::getResidentId),
                Joiners.equal(ResidentRequirement::getRotationId,
                              ResidentBlockAssignment::getRotationId),
                Joiners.filtering((req, a) -> a.isAssigned()))
            .groupBy((req, a) -> req, ConstraintCollectors.countBi())
            .filter((req, count) -> count < req.getMinBlocks())
            .penalize(HardMediumSoftScore.ONE_MEDIUM,
                (req, count) -> Math.max(0, (int)Math.ceil(req.getMinBlocks()) - count))
            .asConstraint("Required rotation under minimum");
    }

    // ── SOFT constraints ───────────────────────────────────────────────────

    // SOFT: fill as many slots as possible — weighted high enough that assigning
    // is always better than not assigning (previous soft constraints penalized
    // assignments more than non-assignments, causing the solver to prefer empty schedules)
    private Constraint minimiseUnassignedSlots(ConstraintFactory f) {
        return f.forEach(ResidentBlockAssignment.class)
            .filter(a -> !a.isAssigned())
            .penalize(HardMediumSoftScore.ONE_SOFT)
            .asConstraint("Minimise unassigned slots");
    }
}
