package com.residency.db;

import com.residency.model.ScheduleVersion;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists named schedule snapshots so multiple "final production" schedules can be saved,
 * listed, loaded, and compared over time — instead of every solve overwriting the last.
 *
 * <p>Snapshot assignments are stored by {@code block_number} (1-based) rather than
 * {@code block_id}, so a version remains loadable even if a year's block rows are
 * regenerated. Loading a version REPLACES the live {@code assignments} for that year.
 */
public class ScheduleVersionDAO extends BaseDAO {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ScheduleVersionDAO() throws SQLException { super(); }

    /** A single saved assignment within a version (year-agnostic, by block_number). */
    public record VersionAssignment(int residentId, int rotationId, int blockNumber) {}

    /**
     * Saves the current live schedule for {@code year} as a new named version. Captures the
     * given solve scores (any may be null). Returns the new version id. Throws if the name
     * already exists for the year (UNIQUE constraint) — callers should surface that.
     */
    public int saveVersion(int year, String name, String notes,
                           Integer tier1, Integer tier2, Integer tier3,
                           boolean feasible, String summary) throws SQLException {
        Connection c = getConn();
        boolean prevAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            int versionId;
            String insertVer = """
                INSERT INTO schedule_versions
                  (schedule_year, name, created_at, notes, tier1_score, tier2_score,
                   tier3_score, feasible, summary)
                VALUES (?,?,?,?,?,?,?,?,?)
                """;
            try (PreparedStatement ps = c.prepareStatement(insertVer, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, year);
                ps.setString(2, name);
                ps.setString(3, LocalDateTime.now().format(TS));
                ps.setString(4, notes);
                setIntOrNull(ps, 5, tier1);
                setIntOrNull(ps, 6, tier2);
                setIntOrNull(ps, 7, tier3);
                ps.setInt(8, feasible ? 1 : 0);
                ps.setString(9, summary);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    versionId = keys.getInt(1);
                }
            }

            // Copy current assignments (resolve block_id -> block_number for this year).
            String copy = """
                INSERT INTO schedule_version_assignments (version_id, resident_id, rotation_id, block_number)
                SELECT ?, a.resident_id, a.rotation_id, b.block_number
                FROM assignments a
                JOIN blocks b ON a.block_id = b.id
                WHERE b.schedule_year = ?
                """;
            try (PreparedStatement ps = c.prepareStatement(copy)) {
                ps.setInt(1, versionId);
                ps.setInt(2, year);
                ps.executeUpdate();
            }
            c.commit();
            return versionId;
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(prevAuto);
        }
    }

    /** Lists saved versions for a year, newest first. */
    public List<ScheduleVersion> listVersions(int year) throws SQLException {
        List<ScheduleVersion> out = new ArrayList<>();
        String sql = "SELECT * FROM schedule_versions WHERE schedule_year=? ORDER BY created_at DESC, id DESC";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public ScheduleVersion getVersion(int versionId) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement("SELECT * FROM schedule_versions WHERE id=?")) {
            ps.setInt(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    /** Returns a version's assignments (by block_number) without touching the live schedule. */
    public List<VersionAssignment> getVersionAssignments(int versionId) throws SQLException {
        List<VersionAssignment> out = new ArrayList<>();
        String sql = "SELECT resident_id, rotation_id, block_number FROM schedule_version_assignments WHERE version_id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new VersionAssignment(
                        rs.getInt("resident_id"), rs.getInt("rotation_id"), rs.getInt("block_number")));
                }
            }
        }
        return out;
    }

    /**
     * Loads a version into the live schedule: deletes the current {@code assignments} for
     * the version's year and re-inserts the snapshot (block_number -> block_id). Idempotent.
     */
    public void loadVersion(int versionId) throws SQLException {
        ScheduleVersion v = getVersion(versionId);
        if (v == null) throw new SQLException("Version " + versionId + " not found");
        int year = v.getScheduleYear();

        Connection c = getConn();
        boolean prevAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            // Clear current live assignments for the year.
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=?)")) {
                ps.setInt(1, year);
                ps.executeUpdate();
            }
            // Re-insert, resolving block_number -> block_id for this year.
            String ins = """
                INSERT INTO assignments (resident_id, rotation_id, block_id, override_warning)
                SELECT sva.resident_id, sva.rotation_id, b.id, 0
                FROM schedule_version_assignments sva
                JOIN blocks b ON b.schedule_year=? AND b.block_number=sva.block_number
                WHERE sva.version_id=?
                """;
            try (PreparedStatement ps = c.prepareStatement(ins)) {
                ps.setInt(1, year);
                ps.setInt(2, versionId);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(prevAuto);
        }
    }

    public void deleteVersion(int versionId) throws SQLException {
        // ON DELETE CASCADE clears the snapshot assignments.
        try (PreparedStatement ps = getConn().prepareStatement("DELETE FROM schedule_versions WHERE id=?")) {
            ps.setInt(1, versionId);
            ps.executeUpdate();
        }
    }

    public boolean nameExists(int year, String name) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "SELECT 1 FROM schedule_versions WHERE schedule_year=? AND name=?")) {
            ps.setInt(1, year);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static void setIntOrNull(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER); else ps.setInt(idx, v);
    }

    private ScheduleVersion map(ResultSet rs) throws SQLException {
        ScheduleVersion v = new ScheduleVersion();
        v.setId(rs.getInt("id"));
        v.setScheduleYear(rs.getInt("schedule_year"));
        v.setName(rs.getString("name"));
        v.setCreatedAt(rs.getString("created_at"));
        v.setNotes(rs.getString("notes"));
        v.setTier1Score((Integer) rs.getObject("tier1_score"));
        v.setTier2Score((Integer) rs.getObject("tier2_score"));
        v.setTier3Score((Integer) rs.getObject("tier3_score"));
        v.setFeasible(rs.getInt("feasible") == 1);
        v.setSummary(rs.getString("summary"));
        return v;
    }
}
