package com.residency.tools;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import com.residency.db.SolveRunDAO;
import com.residency.service.TimefoldSchedulerService;
import com.residency.service.TimefoldSchedulerService.CoverageMetrics;
import com.residency.solver.RotationSchedule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * ITEM 4/5 — the real Timefold optimize round-trip. Warm-starts from a saved (Phase-2) version,
 * optimizes the Sunday-coverage SOFT objective under the full ported hard model, asserts feasibility
 * is preserved (hard==0/medium==0), commits the result as a NEW version, and records a {@code solve_runs}
 * row (version_id + originating seed_id, Timefold-marked) so the result sits alongside harvest runs.
 *
 * <p>Usage:
 * <pre>
 *   java ... com.residency.tools.TimefoldOptimizeRunner &lt;year&gt; &lt;sourceVersionId&gt; [spentSeconds]
 * </pre>
 * spentSeconds defaults to 120. Prints BEFORE vs AFTER fragile/healthy/volunteer so the improvement
 * (if any) is visible — the Item-5 A/B question: can Timefold beat the seed's Phase-2 schedule?
 */
public final class TimefoldOptimizeRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: TimefoldOptimizeRunner <year> <sourceVersionId> [spentSeconds]");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        int srcVersion = Integer.parseInt(args[1]);
        // Budget: arg > TF_SPENT env > production default (600s). Starts: TF_STARTS env > default (10).
        int spent = args.length > 2 ? Integer.parseInt(args[2])
            : envInt("TF_SPENT", TimefoldSchedulerService.DEFAULT_SPENT_SECONDS);
        int starts = envInt("TF_STARTS", TimefoldSchedulerService.DEFAULT_STARTS);

        System.out.printf("=== Timefold OPTIMIZE | year=%d | source v%d | %d starts × %ds ===%n",
            year, srcVersion, starts, spent);

        TimefoldSchedulerService svc = new TimefoldSchedulerService();

        // BEFORE: build warm start and measure starting metrics.
        RotationSchedule warm = svc.buildFeasibilityProblemFromVersion(year, srcVersion);
        CoverageMetrics before = svc.computeMetrics(warm);
        System.out.printf("BEFORE (warm start): vol=%d frag=%d heal=%d hh=%d shortfall=%d%n",
            before.volunteer, before.fragile, before.healthy, before.heavyHeavy, before.shortfallUnits);

        // OPTIMIZE — production multi-start (best of N independent solvers from the SAME warm start).
        long t0 = System.currentTimeMillis();
        var multi = svc.solveMultiStart(year, srcVersion, spent, starts);
        RotationSchedule solved = multi.best;
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("multi-start: winner=#%d, best@%.0fs of %.0fs wall%n",
            multi.winningStartIndex, multi.bestTimeToBestMs / 1000.0, multi.totalWallMs / 1000.0);
        HardMediumSoftScore score = solved.getScore();
        CoverageMetrics after = svc.computeMetrics(solved);
        System.out.printf("AFTER  (%.0fs): score=%s%n", ms / 1000.0, score);
        System.out.printf("AFTER  metrics:      vol=%d frag=%d heal=%d hh=%d shortfall=%d soft=%d%n",
            after.volunteer, after.fragile, after.healthy, after.heavyHeavy, after.shortfallUnits,
            -score.softScore());

        boolean feasible = score.hardScore() == 0 && score.mediumScore() == 0;
        if (!feasible) {
            System.out.println("*** WARNING: result is NOT feasible (hard/medium != 0) — NOT committing. ***");
            System.exit(1);
        }

        // Delta report (the A/B answer). Positive = improvement (fewer fragile/volunteer/shortfall).
        System.out.printf("DELTA: fragile %+d, volunteer %+d, shortfall %+d  → %s%n",
            after.fragile - before.fragile, after.volunteer - before.volunteer,
            after.shortfallUnits - before.shortfallUnits,
            (after.shortfallUnits < before.shortfallUnits) ? "IMPROVED"
                : (after.shortfallUnits == before.shortfallUnits) ? "no change" : "WORSE");

        // COMMIT new version.
        String name = "tf-opt-from-v" + srcVersion + "-" + System.currentTimeMillis();
        int newVersion = svc.commitToVersion(solved, year, name,
            String.format("Timefold optimize from v%d (%ds): frag %d→%d, vol %d→%d",
                srcVersion, spent, before.fragile, after.fragile, before.volunteer, after.volunteer));
        System.out.println("NEW_VERSION_ID=" + newVersion);

        // RECORD solve_runs row (linked to new version + originating seed, Timefold-marked).
        String seedId = lookupSeedForVersion(srcVersion);
        recordSolveRun(year, newVersion, seedId, spent, score, after);
        System.out.printf("recorded solve_runs (seed=%s, version=%d)%n", seedId, newVersion);

        System.exit(0);
    }

    /** The originating seed for the source version, via the harvest solve_runs linkage. */
    private static String lookupSeedForVersion(int versionId) {
        try {
            Connection c = com.residency.db.DatabaseManager.getInstance().getValidConnection();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT seed_id FROM solve_runs WHERE version_id=? AND seed_id IS NOT NULL LIMIT 1")) {
                ps.setInt(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
        } catch (Exception e) {
            System.out.println("seed lookup failed (non-fatal): " + e.getMessage());
        }
        return null;
    }

    private static void recordSolveRun(int year, int versionId, String seedId, int spent,
                                       HardMediumSoftScore score, CoverageMetrics m) throws Exception {
        SolveRunDAO dao = new SolveRunDAO();
        SolveRunDAO.Row row = new SolveRunDAO.Row();
        row.year = year;
        row.configLabel = "timefold-opt";
        row.seedId = seedId;
        row.seedSelectMode = "timefold_warmstart";
        row.p3Secs = (double) spent;
        row.p3Status = "TIMEFOLD";
        row.tier1Score = -score.hardScore();   // 0 when feasible
        row.tier2Score = -score.mediumScore(); // 0 when feasible
        // tier3 = the SOFT objective (tiered Sunday-coverage). Stored as a positive cost
        // (soft score is ≤0 penalty) so it sorts like tier1/tier2 and is queryable per run
        // for later cross-run comparison. score.softScore() fits an int (penalties are bounded).
        row.tier3Score = (int) -score.softScore();
        row.feasible = score.hardScore() == 0 && score.mediumScore() == 0;
        row.versionId = versionId;
        long runId = dao.insertRun(row);

        SolveRunDAO.Metrics met = new SolveRunDAO.Metrics();
        met.volunteer = m.volunteer;
        met.fragile = m.fragile;
        met.healthy = m.healthy;
        met.heavyHeavy = m.heavyHeavy;
        met.t3SundayShortfall = m.shortfallUnits;
        dao.insertMetrics(runId, met);
        dao.insertWeekendVector(runId, m.perWeekend);
    }

    private static int envInt(String key, int dflt) {
        String v = System.getenv(key);
        try { return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private TimefoldOptimizeRunner() {}
}
