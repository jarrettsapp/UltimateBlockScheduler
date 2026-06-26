package com.residency.db;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-seed feasible-ASSIGNMENT storage for the Phase-0 pool (PHASE0_FIX=cache).
 *
 * <p>One row per distinct feasible seed, keyed by {@code (seed_id, year)}. This is the UNBOUNDED
 * seed inventory: it can hold one, ninety-nine, or a million seeds with no cap. It is deliberately
 * separate from the legacy warm-start REPLAY blob ({@code phase0_feasible_pool_<year>} in
 * config), which stays small so replay deserialization stays cheap on every solve.
 *
 * <p>Harvest pins {@code PHASE0_SEED_ID=<seed>} and loads that one seed's assignment by key via
 * {@link #load}, so any seed is individually loadable regardless of how many exist. The
 * {@code assignment} text is the same {@code ';'}-joined {@code var=val} serialization the blob
 * uses, so the two representations are interchangeable. Content-addressed seed_id makes saves
 * naturally idempotent. See PHASE0 seed-storage redesign (Option 3).
 */
public class Phase0SeedAssignmentDAO extends BaseDAO {

    public Phase0SeedAssignmentDAO() throws SQLException { super(); }

    /** Persist one seed's assignment. Idempotent: same (seed_id, year) is a no-op (content-addressed). */
    public void save(String seedId, int year, String assignment) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "INSERT OR IGNORE INTO phase0_seed_assignments (seed_id, year, assignment, created_at) " +
                "VALUES (?,?,?,?)")) {
            ps.setString(1, seedId);
            ps.setInt(2, year);
            ps.setString(3, assignment);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /** The stored assignment text for one seed (full id or unique prefix), or {@code null} if absent. */
    public String load(String seedId, int year) throws SQLException {
        // Exact match first (the common case), then prefix match so an 8-char id from logs works.
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT assignment FROM phase0_seed_assignments WHERE year = ? AND seed_id = ?")) {
            ps.setInt(1, year);
            ps.setString(2, seedId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
        }
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT assignment FROM phase0_seed_assignments WHERE year = ? AND seed_id LIKE ? " +
                "ORDER BY seed_id LIMIT 1")) {
            ps.setInt(1, year);
            ps.setString(2, seedId + "%");
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getString(1); }
        }
        return null;
    }

    /** How many seed assignments are stored for the year (the unbounded inventory size). */
    public int count(int year) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT COUNT(*) FROM phase0_seed_assignments WHERE year = ?")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    /**
     * Up to {@code k} stored assignments for the year, for warm-start replay sampling. {@code k <= 0}
     * returns all. Bounding the replay set is what keeps every solve fast no matter how big the
     * inventory grows. Ordered by seed_id for determinism.
     */
    public List<String> loadSample(int year, int k) throws SQLException {
        String sql = "SELECT assignment FROM phase0_seed_assignments WHERE year = ? ORDER BY seed_id";
        if (k > 0) sql += " LIMIT " + k;
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }
}
