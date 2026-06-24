package com.residency.db;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-seed tracking for the Phase-0 feasible-assignment POOL (PHASE0_FIX=cache).
 *
 * <p>Each pooled seed is keyed by {@code seed_id} = SHA-256 of its occupancy fingerprint —
 * content-based, stable, and collision-free at thousands+ of seeds. This DAO records each
 * seed's usage (for coverage-first / round-robin selection) and reward (full-run Tier outcomes,
 * for the deferred exploit/prune policies). Additive + reversible. See SEED_POOL_TRACKING_PLAN.md.
 */
public class Phase0SeedStatsDAO extends BaseDAO {

    public Phase0SeedStatsDAO() throws SQLException { super(); }

    /**
     * Backfill registration: registers a seed with NO neighbor-distance context (writes NULL
     * {@code nn_dist_at_insert}). Use for seeds whose insertion-order distance can't be reconstructed
     * — i.e. pool entries banked before the saturation monitor existed. No-op if already present.
     */
    public void ensureSeed(String seedId, int year) throws SQLException {
        ensureSeed(seedId, year, null);
    }

    /**
     * Registers a NEW seed entering the pool, recording its nearest-neighbor distance at insertion
     * (saturation monitor). No-op if already present. Assigns the next human-friendly ordinal for
     * the year. Idempotent.
     *
     * @param nnDistAtInsert Hamming distance from this seed to its nearest existing pool member at
     *                       insertion; -1 for the first seed (no neighbor); {@code null} when there
     *                       is no distance context (backfill). A fixed historical fact — never
     *                       updated on later re-registration.
     */
    public void ensureSeed(String seedId, int year, Integer nnDistAtInsert) throws SQLException {
        // Already tracked? Then nothing to do.
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT 1 FROM phase0_seed_stats WHERE seed_id = ?")) {
            ps.setString(1, seedId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return; }
        }
        int nextOrdinal;
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM phase0_seed_stats WHERE year = ?")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); nextOrdinal = rs.getInt(1); }
        }
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT INTO phase0_seed_stats (seed_id, ordinal, year, created_at, nn_dist_at_insert) " +
                "VALUES (?,?,?,?,?)")) {
            ps.setString(1, seedId);
            ps.setInt(2, nextOrdinal);
            ps.setInt(3, year);
            ps.setString(4, Instant.now().toString());
            if (nnDistAtInsert == null) ps.setNull(5, Types.INTEGER);
            else ps.setInt(5, nnDistAtInsert);
            ps.executeUpdate();
        }
    }

    /**
     * Coverage-first selection: returns the seed_id that has been started the FEWEST times,
     * breaking ties by least-recently-used (oldest last_used_at, nulls first). Guarantees every
     * seed is used before any repeats, then cycles fairly. Null if no seeds tracked for the year.
     */
    public String pickRoundRobin(int year) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT seed_id FROM phase0_seed_stats WHERE year = ? " +
                "ORDER BY times_started ASC, (last_used_at IS NULL) DESC, last_used_at ASC, ordinal ASC " +
                "LIMIT 1")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Marks a seed as used for a run start (increments times_started, sets last_used_at). */
    public void markUsed(String seedId) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "UPDATE phase0_seed_stats SET times_started = times_started + 1, last_used_at = ? " +
                "WHERE seed_id = ?")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, seedId);
            ps.executeUpdate();
        }
    }

    /**
     * Records a full 4-phase run's final Tier outcome against the seed it started from. Updates:
     * <ul>
     *   <li>{@code best_tier1/2/3} — each tier's INDEPENDENT minimum (telemetry; may span runs);</li>
     *   <li>{@code best_run_tier1/2/3} — the LEXICOGRAPHICALLY-best run (T1, then T2, then T3), the
     *       three columns moving together so they always describe ONE real schedule;</li>
     *   <li>{@code sum_tier1/2/3} + {@code runs_scored} — running totals for averages.</li>
     * </ul>
     * No-op if the seed isn't tracked. Reward data for the (deferred) exploit/prune policies.
     */
    public void recordOutcome(String seedId, int tier1, int tier2, int tier3) throws SQLException {
        // Decide in Java whether (tier1,tier2,tier3) is lexicographically better than the stored
        // best run — clearer than a triplicated SQL CASE, and this runs once per full solve (cold path).
        boolean replaceBestRun;
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT best_run_tier1, best_run_tier2, best_run_tier3 FROM phase0_seed_stats WHERE seed_id = ?")) {
            ps.setString(1, seedId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;                 // seed not tracked — nothing to record
                int b1 = rs.getInt(1); boolean had = !rs.wasNull();
                int b2 = rs.getInt(2);
                int b3 = rs.getInt(3);
                replaceBestRun = !had || lexLess(tier1, tier2, tier3, b1, b2, b3);
            }
        }
        StringBuilder sql = new StringBuilder(
                "UPDATE phase0_seed_stats SET " +
                "  best_tier1 = CASE WHEN best_tier1 IS NULL OR ? < best_tier1 THEN ? ELSE best_tier1 END, " +
                "  best_tier2 = CASE WHEN best_tier2 IS NULL OR ? < best_tier2 THEN ? ELSE best_tier2 END, " +
                "  best_tier3 = CASE WHEN best_tier3 IS NULL OR ? < best_tier3 THEN ? ELSE best_tier3 END, " +
                "  sum_tier1 = sum_tier1 + ?, sum_tier2 = sum_tier2 + ?, sum_tier3 = sum_tier3 + ?, " +
                "  runs_scored = runs_scored + 1 ");
        if (replaceBestRun) {
            sql.append(", best_run_tier1 = ?, best_run_tier2 = ?, best_run_tier3 = ? ");
        }
        sql.append("WHERE seed_id = ?");
        try (PreparedStatement ps = getConn().prepareStatement(sql.toString())) {
            int i = 1;
            ps.setInt(i++, tier1); ps.setInt(i++, tier1);
            ps.setInt(i++, tier2); ps.setInt(i++, tier2);
            ps.setInt(i++, tier3); ps.setInt(i++, tier3);
            ps.setInt(i++, tier1); ps.setInt(i++, tier2); ps.setInt(i++, tier3);
            if (replaceBestRun) { ps.setInt(i++, tier1); ps.setInt(i++, tier2); ps.setInt(i++, tier3); }
            ps.setString(i, seedId);
            ps.executeUpdate();
        }
    }

    /** True iff (a1,a2,a3) is lexicographically less than (b1,b2,b3): compare T1, then T2, then T3. */
    private static boolean lexLess(int a1, int a2, int a3, int b1, int b2, int b3) {
        if (a1 != b1) return a1 < b1;
        if (a2 != b2) return a2 < b2;
        return a3 < b3;
    }

    // ====================================================================================
    // Read-only queries for the Seed Pool viewer (SeedPoolView). SELECT-only; no mutation.
    // ====================================================================================

    /** Distinct years that have any pooled seeds, ascending. Drives the year picker. Read-only. */
    public List<Integer> listYears() throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT DISTINCT year FROM phase0_seed_stats ORDER BY year ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    /**
     * All seeds for a year with usage + reward + saturation fields, ordered by ordinal. Read-only.
     * avg_tierN is computed in SQL but NULL when runs_scored = 0 (no divide-by-zero). best_*,
     * best_run_*, last_used_at, and nn_dist_at_insert are returned as-is (possibly NULL). Callers
     * must treat the nullable fields as possibly-null.
     */
    public List<SeedStatRow> listSeeds(int year) throws SQLException {
        List<SeedStatRow> out = new ArrayList<>();
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT seed_id, ordinal, year, created_at, times_started, last_used_at, " +
                "       best_tier1, best_tier2, best_tier3, " +
                "       best_run_tier1, best_run_tier2, best_run_tier3, " +
                "       CASE WHEN runs_scored > 0 THEN 1.0*sum_tier1/runs_scored ELSE NULL END AS avg_tier1, " +
                "       CASE WHEN runs_scored > 0 THEN 1.0*sum_tier2/runs_scored ELSE NULL END AS avg_tier2, " +
                "       CASE WHEN runs_scored > 0 THEN 1.0*sum_tier3/runs_scored ELSE NULL END AS avg_tier3, " +
                "       runs_scored, nn_dist_at_insert " +
                "FROM phase0_seed_stats WHERE year = ? ORDER BY ordinal ASC")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SeedStatRow(
                            rs.getString("seed_id"),
                            rs.getInt("ordinal"),
                            rs.getInt("year"),
                            rs.getString("created_at"),
                            rs.getInt("times_started"),
                            rs.getString("last_used_at"),
                            nullableInt(rs, "best_tier1"),
                            nullableInt(rs, "best_tier2"),
                            nullableInt(rs, "best_tier3"),
                            nullableInt(rs, "best_run_tier1"),
                            nullableInt(rs, "best_run_tier2"),
                            nullableInt(rs, "best_run_tier3"),
                            nullableDouble(rs, "avg_tier1"),
                            nullableDouble(rs, "avg_tier2"),
                            nullableDouble(rs, "avg_tier3"),
                            rs.getInt("runs_scored"),
                            nullableInt(rs, "nn_dist_at_insert")));
                }
            }
        }
        return out;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    /**
     * Read-only DTO for one {@code phase0_seed_stats} row, carrying every column the viewer needs
     * plus the SQL-computed per-tier averages. Nullable fields use boxed types ({@code Integer}/
     * {@code Double}) and are null exactly when the underlying value is NULL / undefined.
     *
     * <p>Note the distinction baked into the columns: {@code bestTierN} are INDEPENDENT per-tier
     * minima (telemetry; may span different runs — NOT one schedule), whereas {@code bestRunTierN}
     * are the single lexicographically-best run (the three move together = one real schedule).
     */
    public static final class SeedStatRow {
        public final String seedId;
        public final int ordinal;
        public final int year;
        public final String createdAt;
        public final int timesStarted;
        public final String lastUsedAt;            // null if never used
        public final Integer bestTier1, bestTier2, bestTier3;          // per-tier minima (telemetry)
        public final Integer bestRunTier1, bestRunTier2, bestRunTier3; // one coherent best run
        public final Double avgTier1, avgTier2, avgTier3;              // null when runsScored == 0
        public final int runsScored;
        public final Integer nnDistAtInsert;       // null = pre-monitor, -1 = first seed

        public SeedStatRow(String seedId, int ordinal, int year, String createdAt, int timesStarted,
                           String lastUsedAt, Integer bestTier1, Integer bestTier2, Integer bestTier3,
                           Integer bestRunTier1, Integer bestRunTier2, Integer bestRunTier3,
                           Double avgTier1, Double avgTier2, Double avgTier3, int runsScored,
                           Integer nnDistAtInsert) {
            this.seedId = seedId; this.ordinal = ordinal; this.year = year; this.createdAt = createdAt;
            this.timesStarted = timesStarted; this.lastUsedAt = lastUsedAt;
            this.bestTier1 = bestTier1; this.bestTier2 = bestTier2; this.bestTier3 = bestTier3;
            this.bestRunTier1 = bestRunTier1; this.bestRunTier2 = bestRunTier2; this.bestRunTier3 = bestRunTier3;
            this.avgTier1 = avgTier1; this.avgTier2 = avgTier2; this.avgTier3 = avgTier3;
            this.runsScored = runsScored; this.nnDistAtInsert = nnDistAtInsert;
        }
    }
}
