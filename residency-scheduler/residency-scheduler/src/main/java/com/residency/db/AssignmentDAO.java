package com.residency.db;

import com.residency.model.Assignment;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class AssignmentDAO extends BaseDAO {

    public AssignmentDAO() throws SQLException {
        super();
    }

    public List<Assignment> getByYear(int year) throws SQLException {
        List<Assignment> list = new ArrayList<>();
        String sql = """
            SELECT a.id, a.resident_id, a.rotation_id, a.block_id, a.override_warning,
                   r.name AS resident_name, rot.name AS rotation_name,
                   b.block_number, b.schedule_year
            FROM assignments a
            JOIN residents r ON a.resident_id = r.id
            JOIN rotations rot ON a.rotation_id = rot.id
            JOIN blocks b ON a.block_id = b.id
            WHERE b.schedule_year = ?
            ORDER BY b.block_number, r.name
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapFull(rs));
            }
        }
        return list;
    }

    public List<Assignment> getByResidentAndYear(int residentId, int year) throws SQLException {
        List<Assignment> list = new ArrayList<>();
        String sql = """
            SELECT a.id, a.resident_id, a.rotation_id, a.block_id, a.override_warning,
                   r.name AS resident_name, rot.name AS rotation_name,
                   b.block_number, b.schedule_year
            FROM assignments a
            JOIN residents r ON a.resident_id = r.id
            JOIN rotations rot ON a.rotation_id = rot.id
            JOIN blocks b ON a.block_id = b.id
            WHERE a.resident_id = ? AND b.schedule_year = ?
            ORDER BY b.block_number
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, residentId);
            ps.setInt(2, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapFull(rs));
            }
        }
        return list;
    }

    /** Count how many residents are assigned to a rotation for a specific block. */
    public int countByRotationAndBlock(int rotationId, int blockId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM assignments WHERE rotation_id=? AND block_id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, rotationId);
            ps.setInt(2, blockId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Count total blocks a resident has been assigned to a given rotation (any year). */
    public int countBlocksByResidentAndRotation(int residentId, int rotationId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM assignments WHERE resident_id=? AND rotation_id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, residentId);
            ps.setInt(2, rotationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Get all rotation IDs a resident has been assigned to (any year), ordered by block number. */
    public List<Integer> getCompletedRotationIds(int residentId) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        String sql = """
            SELECT DISTINCT a.rotation_id
            FROM assignments a
            JOIN blocks b ON a.block_id = b.id
            WHERE a.resident_id = ?
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, residentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return ids;
    }

    /**
     * Returns {rotationId -> {blockIndex(0-based) -> count}} for a set of resident IDs.
     * Used to pre-count auxiliary coverage before the solver runs.
     *
     * @param excludedResidentRotationPairs set of "residentId_rotationId" strings to skip —
     *        used to exclude filler-group assignments that will be regenerated post-solve
     */
    public java.util.Map<Integer, java.util.Map<Integer, Integer>> getAuxiliaryCoverage(
            java.util.List<Integer> residentIds, int year) throws SQLException {
        return getAuxiliaryCoverage(residentIds, year, java.util.Set.of());
    }

    public java.util.Map<Integer, java.util.Map<Integer, Integer>> getAuxiliaryCoverage(
            java.util.List<Integer> residentIds, int year,
            java.util.Set<String> excludedResidentRotationPairs) throws SQLException {
        java.util.Map<Integer, java.util.Map<Integer, Integer>> result = new java.util.HashMap<>();
        if (residentIds.isEmpty()) return result;
        String ids = residentIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        String sql = """
            SELECT a.resident_id, a.rotation_id, b.block_number
            FROM assignments a
            JOIN blocks b ON a.block_id = b.id
            WHERE a.resident_id IN (%s) AND b.schedule_year = ?
            """.formatted(ids);
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int resId  = rs.getInt("resident_id");
                    int rotId  = rs.getInt("rotation_id");
                    if (excludedResidentRotationPairs.contains(resId + "_" + rotId)) continue;
                    int blockIdx = rs.getInt("block_number") - 1; // 0-based
                    result.computeIfAbsent(rotId, k -> new java.util.HashMap<>())
                          .merge(blockIdx, 1, Integer::sum);
                }
            }
        }
        return result;
    }

    public Assignment insert(Assignment a) throws SQLException {
        String sql = "INSERT INTO assignments (resident_id, rotation_id, block_id, override_warning) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, a.getResidentId());
            ps.setInt(2, a.getRotationId());
            ps.setInt(3, a.getBlockId());
            ps.setInt(4, a.isOverrideWarning() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) a.setId(keys.getInt(1));
            }
        }
        return a;
    }

    public void delete(int id) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement("DELETE FROM assignments WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public void deleteByResidentAndBlock(int residentId, int blockId) throws SQLException {
        String sql = "DELETE FROM assignments WHERE resident_id=? AND block_id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, residentId);
            ps.setInt(2, blockId);
            ps.executeUpdate();
        }
    }

    private Assignment mapFull(ResultSet rs) throws SQLException {
        Assignment a = new Assignment(
            rs.getInt("id"),
            rs.getInt("resident_id"),
            rs.getInt("rotation_id"),
            rs.getInt("block_id"),
            rs.getInt("override_warning") == 1
        );
        a.setResidentName(rs.getString("resident_name"));
        a.setRotationName(rs.getString("rotation_name"));
        a.setBlockNumber(rs.getInt("block_number"));
        a.setScheduleYear(rs.getInt("schedule_year"));
        return a;
    }
}
