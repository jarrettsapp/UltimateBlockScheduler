package com.residency.tools;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import com.residency.service.TimefoldSchedulerService;
import com.residency.service.TimefoldSchedulerService.CoverageMetrics;
import com.residency.service.TimefoldSchedulerService.TimedSolve;
import com.residency.solver.RotationSchedule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Move-set BENCHMARK runner (see TIMEFOLD_OPTIMIZATION_HANDOFF.md §6). Runs ONE (variant × seed) solve,
 * captures FINAL metrics + TIME-TO-BEST, and APPENDS one CSV row to {@code move_bench.csv}. Does NOT
 * commit a version or write solve_runs — this is a pure measurement harness for the variant×seed×repeat
 * matrix, so it doesn't pollute the schedule DB with 32 throwaway versions.
 *
 * <p>Variant is taken from the {@code TF_VARIANT} env var (R0/R2/R3/R4); tiered objective from
 * {@code TF_TIER}. Usage: {@code TimefoldMoveBenchRunner <year> <sourceVersionId> <spentSeconds> <rep>}
 */
public final class TimefoldMoveBenchRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: TimefoldMoveBenchRunner <year> <srcVersionId> <spentSeconds> <rep>");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        int srcVersion = Integer.parseInt(args[1]);
        int spent = Integer.parseInt(args[2]);
        int rep = Integer.parseInt(args[3]);
        String variant = System.getenv().getOrDefault("TF_VARIANT", "R0");
        boolean tiered = "1".equals(System.getenv("TF_TIER"));

        int starts = 0;
        String startsEnv = System.getenv("TF_STARTS");
        if (startsEnv != null && !startsEnv.isBlank()) starts = Integer.parseInt(startsEnv.trim());

        TimefoldSchedulerService svc = new TimefoldSchedulerService();
        RotationSchedule warm = svc.buildFeasibilityProblemFromVersion(year, srcVersion);
        CoverageMetrics before = svc.computeMetrics(warm);

        RotationSchedule result;
        double timeToBestS, totalS;
        if (starts > 0) {
            var ms = svc.solveMultiStart(year, srcVersion, spent, starts);
            result = ms.best;
            timeToBestS = ms.bestTimeToBestMs / 1000.0;
            totalS = ms.totalWallMs / 1000.0;
            System.out.printf("[MULTISTART] %d starts, winner=#%d%n", ms.starts, ms.winningStartIndex);
        } else {
            TimedSolve ts = svc.solveFeasibilityTimed(warm, spent);
            result = ts.solution;
            timeToBestS = ts.timeToBestMs / 1000.0;
            totalS = ts.totalMs / 1000.0;
        }
        HardMediumSoftScore score = result.getScore();
        CoverageMetrics after = svc.computeMetrics(result);
        boolean feasible = score.hardScore() == 0 && score.mediumScore() == 0;

        // CSV adds a 'starts' column (0 = single-thread, N = multi-start). timeToBestS/totalS are the
        // winning start's t2b and the parallel batch wall-clock when multi-start.
        String row = String.join(",",
            variant, tiered ? "tier" : "flat", String.valueOf(srcVersion), String.valueOf(rep),
            String.valueOf(spent), String.valueOf(starts),
            String.format("%.1f", timeToBestS),
            String.format("%.1f", totalS),
            String.valueOf(feasible),
            String.valueOf(before.fragile), String.valueOf(after.fragile),
            String.valueOf(before.volunteer), String.valueOf(after.volunteer),
            String.valueOf(before.healthy), String.valueOf(after.healthy),
            String.valueOf(before.shortfallUnits), String.valueOf(after.shortfallUnits),
            String.valueOf(score.softScore()));

        Path csv = Path.of(starts > 0 ? "move_bench_multistart.csv" : "move_bench.csv");
        if (!Files.exists(csv)) {
            Files.writeString(csv,
                "variant,obj,seedVer,rep,spentS,starts,timeToBestS,totalS,feasible,"
                + "fragB,fragA,volB,volA,healB,healA,shortB,shortA,soft\n",
                StandardOpenOption.CREATE);
        }
        Files.writeString(csv, row + "\n", StandardOpenOption.APPEND);

        System.out.printf("[BENCH] %s/%s v%d rep%d starts=%d: frag %d->%d vol %d->%d heal %d->%d | "
            + "best@%.0fs of %.0fs | soft=%d | %s%n",
            variant, tiered ? "tier" : "flat", srcVersion, rep, starts,
            before.fragile, after.fragile, before.volunteer, after.volunteer,
            before.healthy, after.healthy,
            timeToBestS, totalS, score.softScore(),
            feasible ? "FEASIBLE" : "*** INFEASIBLE ***");
    }

    private TimefoldMoveBenchRunner() {}
}
