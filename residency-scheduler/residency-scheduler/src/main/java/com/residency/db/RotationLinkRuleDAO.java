package com.residency.db;

import com.residency.cpsat.ScheduleConfig.RotationLinkRule;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RotationLinkRuleDAO {

    public List<RotationLinkRule> getAll() throws SQLException {
        List<RotationLinkRule> list = new ArrayList<>();
        String sql = "SELECT id, rot_a_id, rot_b_id, sum_per_resident, global_total_for_rot_b FROM rotation_link_rules";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                RotationLinkRule r = new RotationLinkRule();
                r.id                  = rs.getInt("id");
                r.rotAId              = rs.getInt("rot_a_id");
                r.rotBId              = rs.getInt("rot_b_id");
                r.sumPerResident      = rs.getInt("sum_per_resident");
                r.globalTotalForRotB  = rs.getInt("global_total_for_rot_b");
                list.add(r);
            }
        }
        return list;
    }

    public void insert(RotationLinkRule rule) throws SQLException {
        String sql = "INSERT INTO rotation_link_rules (rot_a_id, rot_b_id, sum_per_resident, global_total_for_rot_b) VALUES (?,?,?,?)";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, rule.rotAId);
            ps.setInt(2, rule.rotBId);
            ps.setInt(3, rule.sumPerResident);
            ps.setInt(4, rule.globalTotalForRotB);
            ps.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        String sql = "DELETE FROM rotation_link_rules WHERE id = ?";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }
}
