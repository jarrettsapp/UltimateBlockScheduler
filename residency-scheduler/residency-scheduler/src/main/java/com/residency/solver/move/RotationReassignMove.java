package com.residency.solver.move;

import ai.timefold.solver.core.api.score.director.ScoreDirector;
import ai.timefold.solver.core.impl.heuristic.move.AbstractMove;
import ai.timefold.solver.core.impl.heuristic.move.Move;
import com.residency.solver.ResidentBlockAssignment;
import com.residency.solver.RotationSchedule;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * TOPIC-B custom move (see TIMEFOLD_OPTIMIZATION_HANDOFF.md §6). Adapted from Scheduler 5.0's
 * {@code AssignmentReassignMove}: an atomic list of {@code (entity → rotationId)} steps applied as one
 * move, so the move set can build feasibility-preserving compound moves (e.g. swap two residents'
 * rotations within a block, which keeps every per-block count unchanged) that single change/swap
 * selectors cannot express. 1.14.0 classic {@link AbstractMove} (no preview move API in this version).
 */
public class RotationReassignMove extends AbstractMove<RotationSchedule> {

    static final String VAR = "rotationId";

    /** One reassignment: set {@code entity.rotationId = rotationId}. */
    public record Step(ResidentBlockAssignment entity, Integer rotationId) {}

    private final List<Step> steps;

    public RotationReassignMove(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    @Override
    public boolean isMoveDoable(ScoreDirector<RotationSchedule> scoreDirector) {
        // Doable iff at least one step actually changes a value (no-op moves are wasteful, not illegal).
        for (Step s : steps) {
            Integer current = s.entity().getRotationId();
            if (current == null ? s.rotationId() != null : !current.equals(s.rotationId())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doMoveOnGenuineVariables(ScoreDirector<RotationSchedule> scoreDirector) {
        for (Step s : steps) {
            scoreDirector.beforeVariableChanged(s.entity(), VAR);
            s.entity().setRotationId(s.rotationId());
            scoreDirector.afterVariableChanged(s.entity(), VAR);
        }
    }

    @Override
    protected Move<RotationSchedule> createUndoMove(ScoreDirector<RotationSchedule> scoreDirector) {
        // Undo restores each entity's CURRENT value, applied in reverse order.
        List<Step> undo = new ArrayList<>(steps.size());
        for (int i = steps.size() - 1; i >= 0; i--) {
            ResidentBlockAssignment e = steps.get(i).entity();
            undo.add(new Step(e, e.getRotationId()));
        }
        return new RotationReassignMove(undo);
    }

    @Override
    public Move<RotationSchedule> rebase(ScoreDirector<RotationSchedule> destinationScoreDirector) {
        List<Step> rebased = new ArrayList<>(steps.size());
        for (Step s : steps) {
            rebased.add(new Step(
                destinationScoreDirector.lookUpWorkingObject(s.entity()),
                s.rotationId()));
        }
        return new RotationReassignMove(rebased);
    }

    @Override
    public Collection<? extends Object> getPlanningEntities() {
        List<ResidentBlockAssignment> entities = new ArrayList<>(steps.size());
        for (Step s : steps) entities.add(s.entity());
        return entities;
    }

    @Override
    public Collection<? extends Object> getPlanningValues() {
        List<Integer> values = new ArrayList<>(steps.size());
        for (Step s : steps) values.add(s.rotationId());
        return values;
    }

    @Override
    public String getSimpleMoveTypeDescription() {
        return "RotationReassignMove(" + steps.size() + ")";
    }

    @Override
    public String toString() {
        return "RotationReassignMove" + steps;
    }
}
