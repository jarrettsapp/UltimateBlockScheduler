package com.residency.db;

import com.residency.model.Rotation;
import com.residency.model.RotationType;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RotationDAO extends BaseDAO {

    public RotationDAO() throws SQLException {
        super();
    }

    public List<Rotation> getAll() throws SQLException {
        List<Rotation> list = new ArrayList<>();
        String sql = "SELECT * FROM rotations ORDER BY department, name";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    public Rotation getById(int id) throws SQLException {
        String sql = "SELECT * FROM rotations WHERE id = ?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }
        }
        return null;
    }

    public Rotation insert(Rotation r) throws SQLException {
        String sql = """
            INSERT INTO rotations (name, department, max_residents_per_block,
                min_blocks_required, max_blocks_allowed, description, rotation_type)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getName());
            ps.setString(2, r.getDepartment());
            ps.setInt(3, r.getMaxResidentsPerBlock());
            ps.setInt(4, r.getMinBlocksRequired());
            ps.setInt(5, r.getMaxBlocksAllowed());
            ps.setString(6, r.getDescription());
            ps.setString(7, r.getRotationType().name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) r.setId(keys.getInt(1));
            }
        }
        return r;
    }

    public void update(Rotation r) throws SQLException {
        String sql = """
            UPDATE rotations SET name=?, department=?, max_residents_per_block=?,
                min_blocks_required=?, max_blocks_allowed=?, description=?, rotation_type=?
            WHERE id=?
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, r.getName());
            ps.setString(2, r.getDepartment());
            ps.setInt(3, r.getMaxResidentsPerBlock());
            ps.setInt(4, r.getMinBlocksRequired());
            ps.setInt(5, r.getMaxBlocksAllowed());
            ps.setString(6, r.getDescription());
            ps.setString(7, r.getRotationType().name());
            ps.setInt(8, r.getId());
            ps.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement("DELETE FROM rotations WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    private Rotation map(ResultSet rs) throws SQLException {
        Rotation r = new Rotation(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("department"),
            rs.getInt("max_residents_per_block"),
            rs.getInt("min_blocks_required"),
            rs.getInt("max_blocks_allowed"),
            rs.getString("description")
        );
        r.setRotationType(RotationType.fromString(rs.getString("rotation_type")));
        return r;
    }
}
