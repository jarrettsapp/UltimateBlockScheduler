package com.residency.db;

import com.residency.model.Prerequisite;
import com.residency.model.RotationSequenceRule;
import com.residency.model.RotationSequenceRuleType;
import com.residency.model.RotationRequirement;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RulesDAO extends BaseDAO {

    public RulesDAO() throws SQLException {
        super();
    }

    // ── RotationRequirements ────────────────────────────────────────────────

    public List<RotationRequirement> getRequirementsByRotation(int rotationId) throws SQLException {
        List<RotationRequirement> list = new ArrayList<>();
        String sql = "SELECT * FROM rotation_requirements WHERE rotation_id = ? ORDER BY pgy_level";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, rotationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapReq(rs));
            }
        }
        return list;
    }

    public List<RotationRequirement> getAllRequirements() throws SQLException {
        List<RotationRequirement> list = new ArrayList<>();
        String sql = "SELECT * FROM rotation_requirements ORDER BY rotation_id, pgy_level";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(mapReq(rs));
        }
        return list;
    }

    public RotationRequirement getRequirement(int rotationId, int pgyLevel) throws SQLException {
        String sql = "SELECT * FROM rotation_requirements WHERE rotation_id=? AND pgy_level=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, rotationId);
            ps.setInt(2, pgyLevel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapReq(rs);
            }
        }
        return null;
    }

    public void upsertRequirement(RotationRequirement r) throws SQLException {
        String sql = """
            INSERT INTO rotation_requirements (rotation_id, pgy_level, min_blocks, max_blocks, required)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(rotation_id, pgy_level) DO UPDATE SET
                min_blocks=excluded.min_blocks,
                max_blocks=excluded.max_blocks,
                required=excluded.required
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, r.getRotationId());
            ps.setInt(2, r.getPgyLevel());
            ps.setDouble(3, r.getMinBlocks());
            ps.setDouble(4, r.getMaxBlocks());
            ps.setInt(5, r.isRequired() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public void deleteRequirement(int id) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "DELETE FROM rotation_requirements WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    // ── Prerequisites ──────────────────────────────────────────────────────

    public List<Prerequisite> getPrerequisitesByRotation(int rotationId) throws SQLException {
        List<Prerequisite> list = new ArrayList<>();
        String sql = "SELECT * FROM prerequisites WHERE rotation_id = ?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, rotationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapPrereq(rs));
            }
        }
        return list;
    }

    public List<Prerequisite> getAllPrerequisites() throws SQLException {
        List<Prerequisite> list = new ArrayList<>();
        String sql = "SELECT * FROM prerequisites";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(mapPrereq(rs));
        }
        return list;
    }

    public Prerequisite insertPrerequisite(Prerequisite p) throws SQLException {
        String sql = "INSERT INTO prerequisites (rotation_id, prerequisite_rotation_id, pgy_level) VALUES (?, ?, ?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, p.getRotationId());
            ps.setInt(2, p.getPrerequisiteRotationId());
            if (p.getPgyLevel() != null) ps.setInt(3, p.getPgyLevel());
            else ps.setNull(3, Types.INTEGER);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) p.setId(keys.getInt(1));
            }
        }
        return p;
    }

    public void deletePrerequisite(int id) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement("DELETE FROM prerequisites WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    // —— Sequence / adjacency rules ————————————————————————————————————————————

    public List<RotationSequenceRule> getSequenceRulesByRotation(int rotationId) throws SQLException {
        List<RotationSequenceRule> list = new ArrayList<>();
        String sql = "SELECT * FROM rotation_sequence_rules WHERE rotation_id = ? ORDER BY rule_type, related_rotation_id";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, rotationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapSequenceRule(rs));
            }
        }
        return list;
    }

    public List<RotationSequenceRule> getAllSequenceRules() throws SQLException {
        List<RotationSequenceRule> list = new ArrayList<>();
        String sql = "SELECT * FROM rotation_sequence_rules ORDER BY rotation_id, rule_type, related_rotation_id";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(mapSequenceRule(rs));
        }
        return list;
    }

    public RotationSequenceRule insertSequenceRule(RotationSequenceRule rule) throws SQLException {
        String sql = """
            INSERT INTO rotation_sequence_rules (rotation_id, related_rotation_id, rule_type, pgy_level)
            VALUES (?, ?, ?, ?)
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, rule.getRotationId());
            ps.setInt(2, rule.getRelatedRotationId());
            ps.setString(3, rule.getRuleType().name());
            if (rule.getPgyLevel() != null) ps.setInt(4, rule.getPgyLevel());
            else ps.setNull(4, Types.INTEGER);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) rule.setId(keys.getInt(1));
            }
        }
        return rule;
    }

    public void deleteSequenceRule(int id) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement("DELETE FROM rotation_sequence_rules WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    private RotationRequirement mapReq(ResultSet rs) throws SQLException {
        return new RotationRequirement(
            rs.getInt("id"),
            rs.getInt("rotation_id"),
            rs.getInt("pgy_level"),
            rs.getDouble("min_blocks"),
            rs.getDouble("max_blocks"),
            rs.getInt("required") == 1
        );
    }

    private Prerequisite mapPrereq(ResultSet rs) throws SQLException {
        int pgyRaw = rs.getInt("pgy_level");
        Integer pgy = rs.wasNull() ? null : pgyRaw;
        return new Prerequisite(
            rs.getInt("id"),
            rs.getInt("rotation_id"),
            rs.getInt("prerequisite_rotation_id"),
            pgy
        );
    }

    private RotationSequenceRule mapSequenceRule(ResultSet rs) throws SQLException {
        int pgyRaw = rs.getInt("pgy_level");
        Integer pgy = rs.wasNull() ? null : pgyRaw;
        return new RotationSequenceRule(
            rs.getInt("id"),
            rs.getInt("rotation_id"),
            rs.getInt("related_rotation_id"),
            RotationSequenceRuleType.valueOf(rs.getString("rule_type")),
            pgy
        );
    }
}
