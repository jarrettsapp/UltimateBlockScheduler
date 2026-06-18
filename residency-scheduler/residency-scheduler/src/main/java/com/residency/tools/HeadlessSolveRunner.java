package com.residency.tools;

import com.residency.cpsat.CpSatSchedulerEngine;
import com.residency.cpsat.ScheduleSolution;

/**
 * Runs the CP-SAT scheduler from the command line, without the JavaFX UI, against
 * the same SQLite database the app uses (residency_scheduler.db in the working
 * directory). Intended for headless re-solves and for capturing the full solver
 * log to stdout.
 *
 * <p>Usage:
 * <pre>
 *   java -cp target/residency-scheduler-1.0-SNAPSHOT.jar \
 *        com.residency.tools.HeadlessSolveRunner &lt;year&gt; [t0 t1 t2 t3]
 * </pre>
 * Tier time limits (seconds) default to the app's defaults: 60 120 120 60.
 * The committed schedule is written back to the database exactly as the UI run
 * would do.
 */
public final class HeadlessSolveRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: HeadlessSolveRunner <year> [t0 t1 t2 t3]");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        int t0 = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int t1 = args.length > 2 ? Integer.parseInt(args[2]) : 120;
        int t2 = args.length > 3 ? Integer.parseInt(args[3]) : 120;
        int t3 = args.length > 4 ? Integer.parseInt(args[4]) : 60;

        System.out.printf("=== Headless CP-SAT solve | year=%d | limits=%d/%d/%d/%d s ===%n",
            year, t0, t1, t2, t3);
        long start = System.currentTimeMillis();

        CpSatSchedulerEngine engine = new CpSatSchedulerEngine();
        ScheduleSolution sol = engine.solve(year, msg -> {
            // Stream progress with a wall-clock prefix so the slow pre-solve phase
            // is visible.
            long s = (System.currentTimeMillis() - start) / 1000;
            System.out.printf("[%4ds] %s%n", s, msg);
        }, t0, t1, t2, t3);

        System.out.println("\n=== RESULT ===");
        System.out.println("status     : " + sol.getStatus());
        System.out.println("feasible   : " + sol.isFeasible());
        System.out.println("objective  : " + sol.getObjectiveValue());
        System.out.println("runtime ms : " + sol.getRuntimeMs());
        System.out.println("summary    : " + sol.statusSummary());

        // Dump the full solver log (diagnostics + per-phase + validation) so we can
        // review feasibility analysis and constraint scoring without the UI.
        System.out.println("\n=== SOLVER LOG ===");
        System.out.println(sol.getSolverLog());

        System.exit(sol.isFeasible() ? 0 : 1);
    }

    private HeadlessSolveRunner() {}
}
