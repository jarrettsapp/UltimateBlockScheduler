package com.residency.db;

import java.sql.*;
import java.util.*;

public class AuxFillerRotationDAO {

    private final DatabaseManager dbMgr;

    public AuxFillerRotationDAO() throws SQLException {
        this.dbMgr = DatabaseManager.getInstance();
    }

    private Connection getConn() throws SQLException {
        return dbMgr.getValidConnection();
    }

    /** Returns all rotation IDs that are filler (post-solve) for the given group. */
    public Set<Integer> getFillerRotationIds(String group) throws SQLException {
        Set<Integer> ids = new HashSet<>();
        String sql = "SELECT rotation_id FROM aux_filler_rotations WHERE resident_group=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("rotation_id"));
            }
        }
        return ids;
    }

    /** Returns a map of group → set of filler rotation IDs for all groups. */
    public Map<String, Set<Integer>> getAllFillerRotations() throws SQLException {
        Map<String, Set<Integer>> map = new HashMap<>();
        String sql = "SELECT resident_group, rotation_id FROM aux_filler_rotations";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                map.computeIfAbsent(rs.getString("resident_group"), k -> new HashSet<>())
                   .add(rs.getInt("rotation_id"));
            }
        }
        return map;
    }

    public void add(String group, int rotationId) throws SQLException {
        String sql = "INSERT OR IGNORE INTO aux_filler_rotations (resident_group, rotation_id) VALUES (?, ?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, group);
            ps.setInt(2, rotationId);
            ps.executeUpdate();
        }
    }

    public void remove(String group, int rotationId) throws SQLException {
        String sql = "DELETE FROM aux_filler_rotations WHERE resident_group=? AND rotation_id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, group);
            ps.setInt(2, rotationId);
            ps.executeUpdate();
        }
    }
}
