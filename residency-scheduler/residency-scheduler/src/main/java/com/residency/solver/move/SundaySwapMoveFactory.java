package com.residency.solver.move;

import ai.timefold.solver.core.impl.heuristic.move.Move;
import ai.timefold.solver.core.impl.heuristic.selector.move.factory.MoveListFactory;
import com.residency.solver.ResidentBlockAssignment;
import com.residency.solver.RotationSchedule;
import com.residency.solver.TimefoldFacts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TOPIC-B R3 move (see TIMEFOLD_OPTIMIZATION_HANDOFF.md §6). Adapted from Scheduler 5.0's
 * soft-plateau-repair idea, but shaped to OUR feasibility invariant: a same-block swap of two
 * residents' rotations keeps every per-block (rotation→count) tuple identical, so all per-block
 * capacity / categorical-cap / PGY-cap hard constraints are preserved by construction — the kind of
 * feasibility-preserving move a single change/swap selector won't reliably find from a feasible start.
 *
 * <p>To keep PGY-cap counts invariant too, swaps are restricted to residents of the SAME PGY level and
 * each must be ELIGIBLE for the other's rotation. Moves are biased toward blocks adjacent to weak
 * weekends (a volunteer/fragile weekend at slot w involves blocks w and w+1) so the search spends its
 * budget where the soft objective can actually improve.
 */
public class SundaySwapMoveFactory implements MoveListFactory<RotationSchedule> {

    private static final int MAX_MOVES = 4000;

    @Override
    public List<? extends Move<RotationSchedule>> createMoveList(RotationSchedule solution) {
        List<ResidentBlockAssignment> all = solution.getAssignments();
        if (all.isEmpty()) return List.of();
        TimefoldFacts tf = all.get(0).getTfFacts();
        int total = tf.totalBlocks();

        // Slots involved in a weak (volunteer/fragile) weekend get priority; we still emit all swaps but
        // weak-slot swaps are added first so the move list head is the useful part if MAX_MOVES truncates.
        boolean[] weakSlot = weakSlots(tf, all, total);

        // Group assignable, non-pinned entities by block.
        Map<Integer, List<ResidentBlockAssignment>> byBlock = new HashMap<>();
        for (ResidentBlockAssignment a : all) {
            if (a.isAssigned()) byBlock.computeIfAbsent(a.getBlockNumber(), k -> new ArrayList<>()).add(a);
        }

        List<Move<RotationSchedule>> weak = new ArrayList<>();
        List<Move<RotationSchedule>> other = new ArrayList<>();
        for (Map.Entry<Integer, List<ResidentBlockAssignment>> e : byBlock.entrySet()) {
            int slot = TimefoldFacts.slotOf(e.getKey());
            boolean priority = (slot >= 0 && slot < total) && weakSlot[slot];
            List<ResidentBlockAssignment> cells = e.getValue();
            for (int i = 0; i < cells.size(); i++) {
                ResidentBlockAssignment a = cells.get(i);
                for (int j = i + 1; j < cells.size(); j++) {
                    ResidentBlockAssignment b = cells.get(j);
                    if (!swapKeepsFeasible(a, b)) continue;
                    RotationReassignMove move = new RotationReassignMove(List.of(
                        new RotationReassignMove.Step(a, b.getRotationId()),
                        new RotationReassignMove.Step(b, a.getRotationId())));
                    (priority ? weak : other).add(move);
                }
            }
        }
        weak.addAll(other);
        return weak.size() > MAX_MOVES ? weak.subList(0, MAX_MOVES) : weak;
    }

    /** Same PGY (keeps pgy-cap counts invariant) + each eligible for the other's rotation + different rotations. */
    private static boolean swapKeepsFeasible(ResidentBlockAssignment a, ResidentBlockAssignment b) {
        if (a.getPgyLevel() != b.getPgyLevel()) return false;
        Integer ra = a.getRotationId(), rb = b.getRotationId();
        if (ra == null || rb == null || ra.equals(rb)) return false;
        return a.getEligibleRotationIds().contains(rb) && b.getEligibleRotationIds().contains(ra);
    }

    /** A slot w is "weak" if weekend w (blocks w,w+1) OR weekend w-1 has < 2 coverers. */
    private static boolean[] weakSlots(TimefoldFacts tf, List<ResidentBlockAssignment> all, int total) {
        Map<Integer, Integer[]> byRes = new HashMap<>();
        for (ResidentBlockAssignment a : all) {
            if (!a.isAssigned() || !tf.isCategorical(a.getResidentId())) continue;
            Integer[] arr = byRes.computeIfAbsent(a.getResidentId(), k -> new Integer[total]);
            int s = TimefoldFacts.slotOf(a.getBlockNumber());
            if (s >= 0 && s < total) arr[s] = a.getRotationId();
        }
        boolean[] weak = new boolean[total];
        for (int w = 0; w + 1 < total; w++) {
            int coverers = 0;
            for (Integer[] arr : byRes.values()) {
                Integer here = arr[w], next = arr[w + 1];
                if (here != null && tf.isSundaySource(here) && (next == null || !tf.isHeavy(next))) coverers++;
            }
            if (coverers < 2) { weak[w] = true; weak[w + 1] = true; }
        }
        return weak;
    }
}
