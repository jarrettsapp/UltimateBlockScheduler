package com.residency.db;

import java.sql.*;
import java.time.Instant;
import java.util.List;

/**
 * Durable per-REAL-SOLVE rich data layer — the real-solve analogue of
 * {@link Phase0CollectionRunsDAO}, one layer up.
 *
 * <p>One never-overwritten row per real solve in {@code solve_runs}, plus three child tables
 * ({@code solve_run_metrics}, {@code solve_run_weekend}, {@code solve_run_trajectory}) for the
 * maximal sub-component / per-weekend / trajectory detail. Mirrors the
 * {@code phase0_collection_runs} discipline: cumulative, self-describing (full config snapshot +
 * git commit), and tagged with a {@code data_epoch} separation key so pre-I1 and post-fix runs are
 * filterable without touching legacy data. See plan "Phase 2 — Maximal per-run data layer".
 *
 * <p>The schedule itself still lives in the legacy {@code schedule_versions}; {@code version_id}
 * links the rich row to it. {@code seed_id} links to {@code phase0_seed_stats} (the ICC join key).
 *
 * <p>Usage: build a {@link Row}, call {@link #insertRun(Row)} to get the generated run id, then
 * {@link #insertMetrics(long, Metrics)}, {@link #insertWeekendVector(long, int[])} and
 * {@link #insertTrajectory(long, List)}. All best-effort; callers should catch and log so a solve
 * is never broken by telemetry.
 */
public class SolveRunDAO extends BaseDAO {

    public static final String EPOCH_POST_FIX_SEEDED = "post_fix_seeded";

    public SolveRunDAO() throws SQLException { super(); }

    /** Headline {@code solve_runs} row. Public mutable fields keep the wide insert readable. */
    public static class Row {
        public int year;
        public String runAt;          // null ⇒ now()
        public String gitCommit;
        public String dataEpoch = EPOCH_POST_FIX_SEEDED;
        public boolean backfilled = false;
        public String configLabel;
        public String configHash;
        public String configJson;
        public String seedId;         // null for a cold run
        public String seedSelectMode;
        public Integer workerCount;
        public Double p0Secs, p1Secs, p2Secs, p3Secs;
        public String p0Status, p1Status, p2Status, p3Status;
        public Integer tier1Score, tier2Score, tier3Score;
        public Boolean feasible;
        public Integer versionId;
    }

    /** Sub-component breakdown for {@code solve_run_metrics}. Order mirrors the DDL columns. */
    public static class Metrics {
        public Integer t1PostcallPrimary, t1PostcallSecondary, t1InpatientSplit;
        public Integer t2Undercoverage, t2Overcoverage, t2Variance, t2PgyImbalance;
        public Integer t3Pattern4plus2, t3SundayShortfall, t3CategoricalSoft;
        public Integer volunteer, fragile, healthy, heavyHeavy, runsGt6wk;
        public Integer saturdayCoverage;
    }

    /** One incumbent in the Phase-3 objective-vs-time trajectory. */
    public static class TrajectoryPoint {
        public final double elapsedS;
        public final Double objective, bestBound, cpsatWallS;
        public TrajectoryPoint(double elapsedS, Double objective, Double bestBound, Double cpsatWallS) {
            this.elapsedS = elapsedS;
            this.objective = objective;
            this.bestBound = bestBound;
            this.cpsatWallS = cpsatWallS;
        }
    }

    /** Inserts the headline row and returns the generated {@code solve_runs.id}. */
    public long insertRun(Row r) throws SQLException {
        String sql = "INSERT INTO solve_runs (" +
            "year, run_at, git_commit, data_epoch, backfilled, config_label, config_hash, config_json, " +
            "seed_id, seed_select_mode, worker_count, " +
            "p0_secs, p1_secs, p2_secs, p3_secs, p0_status, p1_status, p2_status, p3_status, " +
            "tier1_score, tier2_score, tier3_score, feasible, version_id) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setInt(i++, r.year);
            ps.setString(i++, r.runAt != null ? r.runAt : Instant.now().toString());
            ps.setString(i++, r.gitCommit);
            ps.setString(i++, r.dataEpoch != null ? r.dataEpoch : EPOCH_POST_FIX_SEEDED);
            ps.setInt(i++, r.backfilled ? 1 : 0);
            ps.setString(i++, r.configLabel);
            ps.setString(i++, r.configHash);
            ps.setString(i++, r.configJson);
            ps.setString(i++, r.seedId);
            ps.setString(i++, r.seedSelectMode);
            setIntOrNull(ps, i++, r.workerCount);
            setDoubleOrNull(ps, i++, r.p0Secs);
            setDoubleOrNull(ps, i++, r.p1Secs);
            setDoubleOrNull(ps, i++, r.p2Secs);
            setDoubleOrNull(ps, i++, r.p3Secs);
            ps.setString(i++, r.p0Status);
            ps.setString(i++, r.p1Status);
            ps.setString(i++, r.p2Status);
            ps.setString(i++, r.p3Status);
            setIntOrNull(ps, i++, r.tier1Score);
            setIntOrNull(ps, i++, r.tier2Score);
            setIntOrNull(ps, i++, r.tier3Score);
            if (r.feasible == null) ps.setNull(i++, Types.INTEGER); else ps.setInt(i++, r.feasible ? 1 : 0);
            setIntOrNull(ps, i++, r.versionId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("solve_runs insert did not return a generated id");
    }

    /** Inserts (or replaces) the metrics row for a run. */
    public void insertMetrics(long runId, Metrics m) throws SQLException {
        String sql = "INSERT OR REPLACE INTO solve_run_metrics (" +
            "run_id, t1_postcall_primary, t1_postcall_secondary, t1_inpatient_split, " +
            "t2_undercoverage, t2_overcoverage, t2_variance, t2_pgy_imbalance, " +
            "t3_pattern_4plus2, t3_sunday_shortfall, t3_categorical_soft, " +
            "volunteer, fragile, healthy, heavy_heavy, runs_gt6wk, saturday_coverage) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, runId);
            setIntOrNull(ps, i++, m.t1PostcallPrimary);
            setIntOrNull(ps, i++, m.t1PostcallSecondary);
            setIntOrNull(ps, i++, m.t1InpatientSplit);
            setIntOrNull(ps, i++, m.t2Undercoverage);
            setIntOrNull(ps, i++, m.t2Overcoverage);
            setIntOrNull(ps, i++, m.t2Variance);
            setIntOrNull(ps, i++, m.t2PgyImbalance);
            setIntOrNull(ps, i++, m.t3Pattern4plus2);
            setIntOrNull(ps, i++, m.t3SundayShortfall);
            setIntOrNull(ps, i++, m.t3CategoricalSoft);
            setIntOrNull(ps, i++, m.volunteer);
            setIntOrNull(ps, i++, m.fragile);
            setIntOrNull(ps, i++, m.healthy);
            setIntOrNull(ps, i++, m.heavyHeavy);
            setIntOrNull(ps, i++, m.runsGt6wk);
            setIntOrNull(ps, i++, m.saturdayCoverage);
            ps.executeUpdate();
        }
    }

    /**
     * Inserts the per-weekend coverer vector ({@code coverers[weekendIndex]}). Replaces any prior
     * vector for the run so a re-score is idempotent.
     */
    public void insertWeekendVector(long runId, int[] coverers) throws SQLException {
        Connection c = getConn();
        try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM solve_run_weekend WHERE run_id = ?")) {
            del.setLong(1, runId);
            del.executeUpdate();
        }
        String sql = "INSERT INTO solve_run_weekend (run_id, weekend_index, coverers) VALUES (?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int w = 0; w < coverers.length; w++) {
                ps.setLong(1, runId);
                ps.setInt(2, w);
                ps.setInt(3, coverers[w]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Persists the full Phase-3 trajectory (replaces any prior trajectory for the run). */
    public void insertTrajectory(long runId, List<TrajectoryPoint> points) throws SQLException {
        Connection c = getConn();
        try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM solve_run_trajectory WHERE run_id = ?")) {
            del.setLong(1, runId);
            del.executeUpdate();
        }
        String sql = "INSERT OR REPLACE INTO solve_run_trajectory " +
            "(run_id, elapsed_s, objective, best_bound, cpsat_wall_s) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (TrajectoryPoint p : points) {
                ps.setLong(1, runId);
                ps.setDouble(2, p.elapsedS);
                setDoubleOrNull(ps, 3, p.objective);
                setDoubleOrNull(ps, 4, p.bestBound);
                setDoubleOrNull(ps, 5, p.cpsatWallS);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Total real-solve rows recorded for a year+epoch (telemetry sanity / "how much do we have"). */
    public int countForYear(int year, String dataEpoch) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT COUNT(*) FROM solve_runs WHERE year = ? AND data_epoch = ?")) {
            ps.setInt(1, year);
            ps.setString(2, dataEpoch);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static void setIntOrNull(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER); else ps.setInt(idx, v);
    }

    private static void setDoubleOrNull(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.REAL); else ps.setDouble(idx, v);
    }
}
