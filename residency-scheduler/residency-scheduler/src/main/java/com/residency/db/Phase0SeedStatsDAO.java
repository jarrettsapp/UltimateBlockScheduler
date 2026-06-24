package com.residency.db;

import java.sql.*;
import java.time.Instant;

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
     * Registers a seed the first time it enters the pool (no-op if already present). Assigns the
     * next human-friendly ordinal for the year. Idempotent.
     */
    public void ensureSeed(String seedId, int year) throws SQLException {
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
                "INSERT INTO phase0_seed_stats (seed_id, ordinal, year, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, seedId);
            ps.setInt(2, nextOrdinal);
            ps.setInt(3, year);
            ps.setString(4, Instant.now().toString());
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
     * Records a full 4-phase run's final Tier outcome against the seed it started from
     * (updates best_* to the lower value, accumulates sum_* and runs_scored). No-op if the
     * seed isn't tracked. Reward data for the (deferred) exploit/prune policies.
     */
    public void recordOutcome(String seedId, int tier1, int tier2, int tier3) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "UPDATE phase0_seed_stats SET " +
                "  best_tier1 = CASE WHEN best_tier1 IS NULL OR ? < best_tier1 THEN ? ELSE best_tier1 END, " +
                "  best_tier2 = CASE WHEN best_tier2 IS NULL OR ? < best_tier2 THEN ? ELSE best_tier2 END, " +
                "  best_tier3 = CASE WHEN best_tier3 IS NULL OR ? < best_tier3 THEN ? ELSE best_tier3 END, " +
                "  sum_tier1 = sum_tier1 + ?, sum_tier2 = sum_tier2 + ?, sum_tier3 = sum_tier3 + ?, " +
                "  runs_scored = runs_scored + 1 " +
                "WHERE seed_id = ?")) {
            ps.setInt(1, tier1); ps.setInt(2, tier1);
            ps.setInt(3, tier2); ps.setInt(4, tier2);
            ps.setInt(5, tier3); ps.setInt(6, tier3);
            ps.setInt(7, tier1); ps.setInt(8, tier2); ps.setInt(9, tier3);
            ps.setString(10, seedId);
            ps.executeUpdate();
        }
    }
}
