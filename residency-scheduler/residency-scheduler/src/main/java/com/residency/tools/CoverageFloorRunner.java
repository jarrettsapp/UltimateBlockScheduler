package com.residency.tools;

import com.residency.cpsat.CpSatSchedulerEngine;

/**
 * Proves the minimum-possible number of volunteer Y7 Sunday weekends for a year, using the
 * same hard-constrained model as a real solve but minimizing volunteer weekends directly.
 *
 * <p>Usage: {@code CoverageFloorRunner <year> [timeLimitSeconds]}. If it reports proven=true,
 * that volunteer count is the true floor — no full-objective solve (however long) can beat it.
 */
public final class CoverageFloorRunner {
    public static void main(String[] args) throws Exception {
        int year = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 0; // 0 = unlimited
        // 3rd arg "trans" = hard-lock volunteers to 0 and minimize transitions instead.
        boolean minTrans = args.length > 2 && args[2].equalsIgnoreCase("trans");
        System.out.printf("=== Coverage-floor proof | year=%d | limit=%ds | mode=%s ===%n",
            year, limit, minTrans ? "0-volunteer + min-transitions" : "min-volunteers");
        long t0 = System.currentTimeMillis();
        var res = new CpSatSchedulerEngine().proveCoverageFloor(year, limit, minTrans);
        System.out.println(res.log());
        System.out.printf("status=%s volunteerWeekends=%d proven=%s  (%.1fs total)%n",
            res.status(), res.volunteerWeekends(), res.proven(),
            (System.currentTimeMillis() - t0) / 1000.0);
        System.exit(0);
    }
    private CoverageFloorRunner() {}
}
