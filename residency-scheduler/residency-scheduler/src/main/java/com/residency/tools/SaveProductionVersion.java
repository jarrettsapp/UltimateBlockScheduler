package com.residency.tools;

import com.residency.service.TimefoldSchedulerService;
import com.residency.solver.RotationSchedule;

/**
 * One-off: re-commit an existing (already-optimized) version through the SELF-CONTAINED
 * commitToVersion path so the new version carries full aux coverage + tier3. Usage:
 *   SaveProductionVersion &lt;year&gt; &lt;srcVersionId&gt; "&lt;name&gt;"
 */
public final class SaveProductionVersion {
    public static void main(String[] args) throws Exception {
        com.google.ortools.Loader.loadNativeLibraries();
        int year = Integer.parseInt(args[0]);
        int src  = Integer.parseInt(args[1]);
        String name = args.length > 2 ? args[2] : ("PRODUCTION (from v" + src + ")");

        TimefoldSchedulerService svc = new TimefoldSchedulerService();
        RotationSchedule problem = svc.buildFeasibilityProblemFromVersion(year, src);
        // Short solve: the problem is warm-started from the already-optimal source version, so
        // this just CALCULATES the score (and keeps the optimal assignment) — needed because
        // commitToVersion reads solution.getScore() for the tier columns.
        RotationSchedule solved = svc.solveFeasibility(problem, 5);
        System.out.println("re-scored: " + solved.getScore());
        int newId = svc.commitToVersion(solved, year, name,
            "Self-contained production snapshot re-committed from version " + src
            + " (categorical + aux coverage included).");
        System.out.println("NEW_PRODUCTION_VERSION_ID=" + newId);
    }
    private SaveProductionVersion() {}
}
