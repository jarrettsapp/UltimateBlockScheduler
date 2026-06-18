package com.residency.db;

import com.residency.model.Resident;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ResidentDAO {

    private final DatabaseManager dbMgr;

    public ResidentDAO() throws SQLException {
        this.dbMgr = DatabaseManager.getInstance();
    }

    private Connection getConn() throws SQLException {
        return dbMgr.getValidConnection();
    }

    public List<Resident> getAll() throws SQLException {
        List<Resident> list = new ArrayList<>();
        String sql = "SELECT * FROM residents ORDER BY pgy_level, name";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    public List<Resident> getByPgy(int pgyLevel) throws SQLException {
        List<Resident> list = new ArrayList<>();
        String sql = "SELECT * FROM residents WHERE pgy_level = ? ORDER BY name";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, pgyLevel);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public Resident getById(int id) throws SQLException {
        String sql = "SELECT * FROM residents WHERE id = ?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }
        }
        return null;
    }

    public List<Resident> getMainResidents() throws SQLException {
        List<Resident> list = new ArrayList<>();
        String sql = "SELECT * FROM residents WHERE is_auxiliary=0 ORDER BY pgy_level, name";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    public List<Resident> getAuxiliaryResidents() throws SQLException {
        List<Resident> list = new ArrayList<>();
        String sql = "SELECT * FROM residents WHERE is_auxiliary=1 ORDER BY name";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    /** Auxiliary residents with no resident_group (the TY pool: not BMC, not categorical). */
    public List<Resident> getAuxiliaryNonGroup() throws SQLException {
        List<Resident> list = new ArrayList<>();
        String sql = "SELECT * FROM residents WHERE is_auxiliary=1 AND resident_group IS NULL ORDER BY name";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    public List<Resident> getByGroup(String group) throws SQLException {
        List<Resident> list = new ArrayList<>();
        String sql = "SELECT * FROM residents WHERE resident_group=? ORDER BY name";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    public Resident insert(Resident r) throws SQLException {
        String sql = "INSERT INTO residents (name, pgy_level, email, is_auxiliary, resident_group) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getName());
            ps.setInt(2, r.getPgyLevel());
            ps.setString(3, r.getEmail());
            ps.setInt(4, r.isAuxiliary() ? 1 : 0);
            ps.setString(5, r.getResidentGroup());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) r.setId(keys.getInt(1));
            }
        }
        return r;
    }

    public void update(Resident r) throws SQLException {
        String sql = "UPDATE residents SET name=?, pgy_level=?, email=?, is_auxiliary=?, resident_group=? WHERE id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, r.getName());
            ps.setInt(2, r.getPgyLevel());
            ps.setString(3, r.getEmail());
            ps.setInt(4, r.isAuxiliary() ? 1 : 0);
            ps.setString(5, r.getResidentGroup());
            ps.setInt(6, r.getId());
            ps.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement("DELETE FROM residents WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    public List<Integer> getDistinctPgyLevels() throws SQLException {
        List<Integer> levels = new ArrayList<>();
        String sql = "SELECT DISTINCT pgy_level FROM residents ORDER BY pgy_level";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) levels.add(rs.getInt(1));
        }
        return levels;
    }

    private Resident map(ResultSet rs) throws SQLException {
        Resident r = new Resident(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getInt("pgy_level"),
            rs.getString("email")
        );
        try { r.setAuxiliary(rs.getInt("is_auxiliary") == 1); }
        catch (SQLException ignored) {}
        try { r.setResidentGroup(rs.getString("resident_group")); }
        catch (SQLException ignored) {}
        return r;
    }
}
