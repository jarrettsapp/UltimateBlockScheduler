package com.residency.db;

import com.residency.model.Block;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockDAO extends BaseDAO {

    public BlockDAO() throws SQLException {
        super();
    }

    public List<Block> getByYear(int year) throws SQLException {
        normalizeLegacyYearIfNeeded(year);
        return fetchByYear(year);
    }

    public List<Integer> getDistinctYears() throws SQLException {
        List<Integer> years = new ArrayList<>();
        String sql = "SELECT DISTINCT schedule_year FROM blocks ORDER BY schedule_year DESC";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) years.add(rs.getInt(1));
        }
        return years;
    }

    public Block getById(int id) throws SQLException {
        String sql = "SELECT * FROM blocks WHERE id = ?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }
        }
        return null;
    }

    public Block insert(Block b) throws SQLException {
        String sql = "INSERT INTO blocks (block_number, schedule_year, start_date, end_date) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = getConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, b.getBlockNumber());
            ps.setInt(2, b.getScheduleYear());
            ps.setString(3, b.getStartDate() != null ? b.getStartDate().toString() : null);
            ps.setString(4, b.getEndDate() != null ? b.getEndDate().toString() : null);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) b.setId(keys.getInt(1));
            }
        }
        return b;
    }

    public void update(Block b) throws SQLException {
        String sql = "UPDATE blocks SET block_number=?, schedule_year=?, start_date=?, end_date=? WHERE id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, b.getBlockNumber());
            ps.setInt(2, b.getScheduleYear());
            ps.setString(3, b.getStartDate() != null ? b.getStartDate().toString() : null);
            ps.setString(4, b.getEndDate() != null ? b.getEndDate().toString() : null);
            ps.setInt(5, b.getId());
            ps.executeUpdate();
        }
    }

    public void delete(int id) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement("DELETE FROM blocks WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    /** Delete all assignments and blocks for the given year. */
    public void deleteYear(int year) throws SQLException {
        try (PreparedStatement ps = getConn().prepareStatement(
                "DELETE FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year=?)")) {
            ps.setInt(1, year);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = getConn().prepareStatement(
                "DELETE FROM blocks WHERE schedule_year=?")) {
            ps.setInt(1, year);
            ps.executeUpdate();
        }
    }

    /**
     * Generate 26 half-blocks (2 weeks each) for a given academic year starting July 1.
     * Block numbers 1-26 map to labels 1a, 1b, 2a, 2b, … 13a, 13b.
     */
    public void generateBlocksForYear(int year) throws SQLException {
        LocalDate start = LocalDate.of(year, 7, 1);
        for (int i = 1; i <= 26; i++) {
            LocalDate end = start.plusWeeks(2).minusDays(1);
            Block b = new Block(0, i, year, start, end);
            try {
                insert(b);
            } catch (SQLException e) {
                // Ignore duplicate key — blocks already exist for this year
                if (!e.getMessage().contains("UNIQUE")) throw e;
            }
            start = start.plusWeeks(2);
        }
    }

    private List<Block> fetchByYear(int year) throws SQLException {
        List<Block> list = new ArrayList<>();
        String sql = "SELECT * FROM blocks WHERE schedule_year = ? ORDER BY block_number";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        }
        return list;
    }

    /**
     * Older databases stored 13 four-week blocks numbered 1..13.
     * The current scheduler uses 26 half-blocks numbered 1..26.
     * When a legacy year is encountered, rewrite it in place and duplicate any
     * existing assignments from each old block onto its two new half-blocks.
     */
    private void normalizeLegacyYearIfNeeded(int year) throws SQLException {
        List<Block> blocks = fetchByYear(year);
        if (blocks.size() != 13) return;

        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).getBlockNumber() != i + 1) return;
        }

        try (Connection conn = getConn()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                List<LegacyAssignment> legacyAssignments = new ArrayList<>();
                String loadAssignmentsSql = """
                    SELECT a.resident_id, a.rotation_id, a.block_id, a.override_warning, b.block_number
                    FROM assignments a
                    JOIN blocks b ON a.block_id = b.id
                    WHERE b.schedule_year = ?
                    ORDER BY b.block_number, a.resident_id
                    """;
                try (PreparedStatement ps = conn.prepareStatement(loadAssignmentsSql)) {
                    ps.setInt(1, year);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            legacyAssignments.add(new LegacyAssignment(
                                rs.getInt("resident_id"),
                                rs.getInt("rotation_id"),
                                rs.getInt("block_number"),
                                rs.getInt("override_warning") == 1
                            ));
                        }
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM assignments WHERE block_id IN (SELECT id FROM blocks WHERE schedule_year = ?)")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM blocks WHERE schedule_year = ?")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }

                Map<Integer, Integer> newBlockIds = new HashMap<>();
                String insertBlockSql = """
                    INSERT INTO blocks (block_number, schedule_year, start_date, end_date)
                    VALUES (?, ?, ?, ?)
                    """;
                LocalDate start = LocalDate.of(year, 7, 1);
                try (PreparedStatement ps = conn.prepareStatement(insertBlockSql, Statement.RETURN_GENERATED_KEYS)) {
                    for (int blockNumber = 1; blockNumber <= 26; blockNumber++) {
                        LocalDate end = start.plusWeeks(2).minusDays(1);
                        ps.setInt(1, blockNumber);
                        ps.setInt(2, year);
                        ps.setString(3, start.toString());
                        ps.setString(4, end.toString());
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("Failed to create normalized block " + blockNumber + " for year " + year);
                            }
                            newBlockIds.put(blockNumber, keys.getInt(1));
                        }
                        start = start.plusWeeks(2);
                    }
                }

                String insertAssignmentSql = """
                    INSERT INTO assignments (resident_id, rotation_id, block_id, override_warning)
                    VALUES (?, ?, ?, ?)
                    """;
                try (PreparedStatement ps = conn.prepareStatement(insertAssignmentSql)) {
                    for (LegacyAssignment assignment : legacyAssignments) {
                        int firstHalfBlock = assignment.legacyBlockNumber * 2 - 1;
                        int secondHalfBlock = assignment.legacyBlockNumber * 2;
                        insertExpandedAssignment(ps, assignment, newBlockIds.get(firstHalfBlock));
                        insertExpandedAssignment(ps, assignment, newBlockIds.get(secondHalfBlock));
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private void insertExpandedAssignment(PreparedStatement ps, LegacyAssignment assignment, int blockId)
            throws SQLException {
        ps.setInt(1, assignment.residentId);
        ps.setInt(2, assignment.rotationId);
        ps.setInt(3, blockId);
        ps.setInt(4, assignment.overrideWarning ? 1 : 0);
        ps.executeUpdate();
    }

    private record LegacyAssignment(int residentId, int rotationId, int legacyBlockNumber, boolean overrideWarning) {}

    private Block map(ResultSet rs) throws SQLException {
        String startStr = rs.getString("start_date");
        String endStr = rs.getString("end_date");
        return new Block(
            rs.getInt("id"),
            rs.getInt("block_number"),
            rs.getInt("schedule_year"),
            startStr != null ? LocalDate.parse(startStr) : null,
            endStr != null ? LocalDate.parse(endStr) : null
        );
    }
}
