package com.residency.tools;

import com.residency.db.ScheduleVersionDAO;
import com.residency.service.TimefoldSchedulerService;
import com.residency.solver.ResidentBlockAssignment;
import com.residency.solver.RotationSchedule;

import java.util.List;

/**
 * ITEM 1 — warm-start spike (see TIMEFOLD_BUILD_PLAN.md).
 *
 * <p>Proves Timefold can ingest a saved (Phase-2) schedule version as its STARTING solution and
 * round-trip it through the version tables, using the existing constraints, with a trivial solve
 * budget. The goal is NOT optimization yet — it is to confirm the warm-start mechanism is reliable
 * (the crux that killed CP-SAT Phase 3 via OR-Tools #5025). Under a trivial budget on a feasible
 * start, the result should be essentially unchanged from the source.
 *
 * <p>Usage:
 * <pre>
 *   java -cp target/residency-scheduler-1.0-SNAPSHOT.jar \
 *        com.residency.tools.TimefoldWarmStartRunner &lt;year&gt; &lt;sourceVersionId&gt; [spentSeconds]
 * </pre>
 * spentSeconds defaults to 3 (trivial). The runner prints the warm-start ingestion report, the
 * Timefold score before/after, the new version id, and a cell-level diff count vs. the source so a
 * human (or score_grid()) can confirm faithful round-trip.
 */
public final class TimefoldWarmStartRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                "Usage: TimefoldWarmStartRunner <year> <sourceVersionId> [spentSeconds]");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        int sourceVersionId = Integer.parseInt(args[1]);
        // spentSeconds: pass 0 to SKIP solving entirely (pure ingestion + commit round-trip check).
        int spent = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        boolean noSolve = spent <= 0;

        System.out.printf("=== Timefold WARM-START spike | year=%d | source version=%d | budget=%ds ===%n",
            year, sourceVersionId, spent);

        TimefoldSchedulerService svc = new TimefoldSchedulerService();

        // 1. Build the warm-start problem from the saved version.
        RotationSchedule problem = svc.buildSolutionFromVersion(year, sourceVersionId);

        // Snapshot the starting (resident_id, block_number) -> rotationId map for the diff.
        var startMap = new java.util.HashMap<String, Integer>();
        for (ResidentBlockAssignment a : problem.getAssignments()) {
            if (a.isAssigned()) startMap.put(a.getResidentId() + "_" + a.getBlockNumber(), a.getRotationId());
        }
        System.out.println("warm-start starting score (unsolved): " + problem.scoreSummary());

        // 2. Solve with a trivial budget — OR skip solving entirely (spent<=0) to validate that
        //    ingestion + commit round-trips the schedule faithfully, independent of the (currently
        //    generic, pre-port) constraint model. Once Item 2 ports the real hard model, a real
        //    budget here should leave a feasible schedule essentially unchanged.
        RotationSchedule solved;
        if (noSolve) {
            System.out.println("NO-SOLVE mode: committing the ingested warm start verbatim (round-trip check).");
            solved = problem;
        } else {
            long t0 = System.currentTimeMillis();
            solved = svc.solveSync(problem, spent);
            long ms = System.currentTimeMillis() - t0;
            System.out.printf("solved in %dms → score: %s  feasible=%b%n",
                ms, solved.scoreSummary(), solved.isFeasible());
        }

        // 3. Diff the solved solution against the warm start (how much did a trivial solve move?).
        int changed = 0, nowNull = 0;
        for (ResidentBlockAssignment a : solved.getAssignments()) {
            String k = a.getResidentId() + "_" + a.getBlockNumber();
            Integer was = startMap.get(k);
            Integer now = a.getRotationId();
            if (now == null) { if (was != null) nowNull++; continue; }
            if (was == null || !was.equals(now)) changed++;
        }
        System.out.printf("cell diff vs. source: %d changed, %d became unassigned%n", changed, nowNull);

        // 4. Commit the result to a NEW version (the comparable round-trip).
        String name = "tf-warmstart-from-v" + sourceVersionId + "-" + System.currentTimeMillis();
        int newVersionId = svc.commitToVersion(solved, year, name,
            "Item 1 warm-start spike: ingested version " + sourceVersionId
                + ", budget " + spent + "s, " + changed + " cells changed");
        System.out.println("NEW_VERSION_ID=" + newVersionId);

        // 5. Report row counts for the source vs. new version so faithful round-trip is verifiable.
        ScheduleVersionDAO dao = new ScheduleVersionDAO();
        List<ScheduleVersionDAO.VersionAssignment> srcRows = dao.getVersionAssignments(sourceVersionId);
        List<ScheduleVersionDAO.VersionAssignment> newRows = dao.getVersionAssignments(newVersionId);
        System.out.printf("source version rows=%d  new version rows=%d%n", srcRows.size(), newRows.size());
        System.out.println("Next: run score_and_snapshot.score_grid on both versions to confirm metrics match.");

        System.exit(solved.isFeasible() ? 0 : 1);
    }

    private TimefoldWarmStartRunner() {}
}
