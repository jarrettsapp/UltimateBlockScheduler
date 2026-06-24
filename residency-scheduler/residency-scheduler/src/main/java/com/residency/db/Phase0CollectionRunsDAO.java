package com.residency.db;

import java.sql.*;
import java.time.Instant;

/**
 * Durable per-COLLECTION-RUN telemetry for Phase-0 seed-pool seeding.
 *
 * <p>One row per collection solve, recorded the moment the solve finishes — so the
 * time-to-feasibility history accumulates forever instead of being overwritten with
 * {@code phase0_seed_results.csv} each run. This is the cumulative dataset the cap analyses
 * (Kaplan–Meier, Wilson/NNT) consume. Additive + reversible. See SEED_POOL_STATS_IMPLEMENTATION_PLAN.md.
 */
public class Phase0CollectionRunsDAO extends BaseDAO {

    public Phase0CollectionRunsDAO() throws SQLException { super(); }

    /**
     * Records one collection solve.
     *
     * @param year        schedule year.
     * @param status      solver status (OPTIMAL/FEASIBLE = feasible seed found; else censored/capped).
     * @param secs        wall seconds the solve took (time-to-event, or censoring time on a cap).
     * @param capSecs     the Phase-0 time cap used (the censoring boundary), or null if unknown.
     * @param newSeedId   seed_id banked by this run, or null if nothing/duplicate was banked.
     * @param workerCount CP-SAT workers used, or null if unknown.
     */
    public void record(int year, String status, double secs, Integer capSecs,
                       String newSeedId, Integer workerCount) throws SQLException {
        String sql = "INSERT INTO phase0_collection_runs " +
            "(year, run_at, status, secs, cap_secs, new_seed_id, worker_count) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setString(2, Instant.now().toString());
            ps.setString(3, status);
            ps.setDouble(4, secs);
            if (capSecs == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, capSecs);
            ps.setString(6, newSeedId);
            if (workerCount == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, workerCount);
            ps.executeUpdate();
        }
    }

    /**
     * Backfill variant with an explicit timestamp (for reconstructing historical runs whose original
     * time we recorded elsewhere). Same as {@link #record} but {@code runAt} is supplied, not now().
     */
    public void recordAt(int year, String runAt, String status, double secs, Integer capSecs,
                         String newSeedId, Integer workerCount) throws SQLException {
        String sql = "INSERT INTO phase0_collection_runs " +
            "(year, run_at, status, secs, cap_secs, new_seed_id, worker_count) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setString(2, runAt);
            ps.setString(3, status);
            ps.setDouble(4, secs);
            if (capSecs == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, capSecs);
            ps.setString(6, newSeedId);
            if (workerCount == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, workerCount);
            ps.executeUpdate();
        }
    }

    /** Total collection runs recorded for a year (telemetry sanity / "how much history do we have"). */
    public int countForYear(int year) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT COUNT(*) FROM phase0_collection_runs WHERE year = ?")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }
}
