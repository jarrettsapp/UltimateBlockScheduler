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
 * TOPIC-B R4 move (see TIMEFOLD_OPTIMIZATION_HANDOFF.md §6). Adapted from Scheduler 5.0's ejection
 * chains, but DEMAND-DRIVEN by the Sunday objective: for each weak weekend (volunteer/fragile at slot w)
 * it tries to MANUFACTURE a coverer. A coverer at w needs some categorical resident whose rotation@w is
 * a Sunday-source and whose rotation@(w+1) is not heavy.
 *
 * <p>The chain that keeps per-block counts feasible: pick a target cell (residentX, blockW) that is NOT
 * currently a Sunday-source there, and a donor cell (residentY, blockW) that IS on a Sunday-source S at
 * blockW; swap their rotations (X→S, Y→X's old). Block W's per-rotation counts are unchanged, so all
 * per-block hard constraints survive. We only emit chains where X then covers (S is Sunday-source AND
 * X's w+1 is non-heavy) — i.e. the move actually adds a coverer — and where both residents are eligible
 * and same-PGY (keeps pgy-cap counts invariant). Single 2-step chains; the LS acceptor compounds them.
 */
public class SundayEjectionChainMoveFactory implements MoveListFactory<RotationSchedule> {

    private static final int MAX_MOVES = 4000;

    @Override
    public List<? extends Move<RotationSchedule>> createMoveList(RotationSchedule solution) {
        List<ResidentBlockAssignment> all = solution.getAssignments();
        if (all.isEmpty()) return List.of();
        TimefoldFacts tf = all.get(0).getTfFacts();
        int total = tf.totalBlocks();

        // Per-resident slot→rotation, and per-(block) cells.
        Map<Integer, Integer[]> rotByRes = new HashMap<>();
        Map<Integer, List<ResidentBlockAssignment>> byBlock = new HashMap<>();
        for (ResidentBlockAssignment a : all) {
            if (!a.isAssigned()) continue;
            byBlock.computeIfAbsent(a.getBlockNumber(), k -> new ArrayList<>()).add(a);
            if (tf.isCategorical(a.getResidentId())) {
                Integer[] arr = rotByRes.computeIfAbsent(a.getResidentId(), k -> new Integer[total]);
                int s = TimefoldFacts.slotOf(a.getBlockNumber());
                if (s >= 0 && s < total) arr[s] = a.getRotationId();
            }
        }

        List<Move<RotationSchedule>> moves = new ArrayList<>();
        for (int w = 0; w + 1 < total && moves.size() < MAX_MOVES; w++) {
            if (coverers(tf, rotByRes, w) >= 2) continue; // only weak weekends
            int blockW = w + 1; // blockNumber is 1-based; slot w ⇒ block w+1
            List<ResidentBlockAssignment> cells = byBlock.get(blockW);
            if (cells == null) continue;

            for (ResidentBlockAssignment x : cells) {
                if (moves.size() >= MAX_MOVES) break;
                if (!tf.isCategorical(x.getResidentId())) continue;
                // X must NOT already cover at w, and X's NEXT block must be non-heavy (so X can cover).
                Integer xNext = next(rotByRes, x.getResidentId(), w, total);
                if (xNext != null && tf.isHeavy(xNext)) continue;
                Integer xRot = x.getRotationId();
                if (xRot != null && tf.isSundaySource(xRot)) continue; // already a source here

                for (ResidentBlockAssignment y : cells) {
                    if (y == x) continue;
                    Integer yRot = y.getRotationId();
                    if (yRot == null || !tf.isSundaySource(yRot)) continue; // donor must hold a source S
                    if (x.getPgyLevel() != y.getPgyLevel()) continue;       // keep pgy-cap invariant
                    if (!x.getEligibleRotationIds().contains(yRot)) continue;
                    if (xRot == null || !y.getEligibleRotationIds().contains(xRot)) continue;
                    // X→S makes X a Sunday-source at w; X's w+1 already verified non-heavy ⇒ X now covers.
                    moves.add(new RotationReassignMove(List.of(
                        new RotationReassignMove.Step(x, yRot),
                        new RotationReassignMove.Step(y, xRot))));
                    if (moves.size() >= MAX_MOVES) break;
                }
            }
        }
        return moves;
    }

    private static Integer next(Map<Integer, Integer[]> rotByRes, int resId, int w, int total) {
        Integer[] arr = rotByRes.get(resId);
        return (arr != null && w + 1 < total) ? arr[w + 1] : null;
    }

    private static int coverers(TimefoldFacts tf, Map<Integer, Integer[]> rotByRes, int w) {
        int coverers = 0;
        for (Integer[] arr : rotByRes.values()) {
            Integer here = arr[w], nxt = arr[w + 1];
            if (here != null && tf.isSundaySource(here) && (nxt == null || !tf.isHeavy(nxt))) coverers++;
        }
        return coverers;
    }
}
