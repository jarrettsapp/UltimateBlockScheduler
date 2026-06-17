package com.residency.cpsat;

import com.residency.db.*;
import com.residency.model.*;

import java.sql.*;
import java.util.*;

/**
 * Post-solve pass: for each "filler rotation" configured per aux group,
 * assigns aux residents of that group to blocks where coverage falls below
 * minPerBlock after main residents and pre-assigned aux residents are placed.
 *
 * BMC on Younker 7 Days is the canonical example: BMC's Younker 7 assignments
 * are cleared before this runs, then regenerated here to fill only the gaps.
 */
public class AuxFillerService {

    private final AssignmentDAO assignmentDAO;
    private final ResidentDAO residentDAO;
    private final AuxFillerRotationDAO fillerDAO;
    private final DatabaseManager dbMgr;

    public AuxFillerService() throws SQLException {
        this.assignmentDAO = new AssignmentDAO();
        this.residentDAO   = new ResidentDAO();
        this.fillerDAO     = new AuxFillerRotationDAO();
        this.dbMgr         = DatabaseManager.getInstance();
    }

    /**
     * Runs the filler pass for all configured groups and rotations.
     *
     * @param year        schedule year
     * @param blocks      ordered list of blocks for the year (block_number 1-based)
     * @param rotations   all rotations
     * @param config      schedule config (for minPerBlock per rotation)
     * @param log         appended with filler activity for the solver log
     */
    public void run(int year, List<Block> blocks, List<Rotation> rotations,
                    ScheduleConfig config, StringBuilder log) throws SQLException {

        Map<String, Set<Integer>> fillerMap = fillerDAO.getAllFillerRotations();
        if (fillerMap.isEmpty()) return;

        log.append("\n═══ AUX FILLER PASS ═══\n");

        Map<Integer, Integer> blockNumberToId = new HashMap<>();
        for (Block b : blocks) blockNumberToId.put(b.getBlockNumber(), b.getId());

        Map<Integer, Rotation> rotById = new HashMap<>();
        for (Rotation r : rotations) rotById.put(r.getId(), r);

        for (Map.Entry<String, Set<Integer>> entry : fillerMap.entrySet()) {
            String group = entry.getKey();
            Set<Integer> fillerRotationIds = entry.getValue();

            List<Resident> fillerResidents = residentDAO.getByGroup(group);
            if (fillerResidents.isEmpty()) {
                log.append(String.format("  Group '%s': no residents found, skipping.\n", group));
                continue;
            }

            for (int rotId : fillerRotationIds) {
                Rotation rotation = rotById.get(rotId);
                if (rotation == null) continue;

                ScheduleConfig.RotationPolicy policy = config.getPolicyFor(rotId);
                int minPerBlock = policy.minPerBlock;

                // Clear old filler assignments for this group + rotation + year
                clearFillerAssignments(group, fillerResidents, rotId, year);

                int filled = 0;
                for (int blockNum = 1; blockNum <= 26; blockNum++) {
                    Integer blockId = blockNumberToId.get(blockNum);
                    if (blockId == null) continue;

                    int currentCount = countAssignmentsForBlock(rotId, blockId);
                    int needed = minPerBlock - currentCount;
                    if (needed <= 0) continue;

                    // Find filler residents not already assigned at this block
                    for (Resident filler : fillerResidents) {
                        if (needed <= 0) break;
                        if (!hasAssignmentAtBlock(filler.getId(), blockId)) {
                            assignmentDAO.insert(new Assignment(0, filler.getId(), rotId, blockId, false));
                            needed--;
                            filled++;
                        }
                    }
                }

                log.append(String.format("  Group '%-6s' → %-35s  filled %d block(s)\n",
                    group, rotation.getName(), filled));
            }
        }
    }

    private void clearFillerAssignments(String group, List<Resident> residents,
                                         int rotId, int year) throws SQLException {
        if (residents.isEmpty()) return;
        String ids = residents.stream()
            .map(r -> String.valueOf(r.getId()))
            .collect(java.util.stream.Collectors.joining(","));
        String sql = """
            DELETE FROM assignments
            WHERE rotation_id = ?
              AND resident_id IN (%s)
              AND block_id IN (SELECT id FROM blocks WHERE schedule_year = ?)
            """.formatted(ids);
        try (PreparedStatement ps = dbMgr.getValidConnection().prepareStatement(sql)) {
            ps.setInt(1, rotId);
            ps.setInt(2, year);
            ps.executeUpdate();
        }
    }

    private int countAssignmentsForBlock(int rotId, int blockId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM assignments WHERE rotation_id=? AND block_id=?";
        try (PreparedStatement ps = dbMgr.getValidConnection().prepareStatement(sql)) {
            ps.setInt(1, rotId);
            ps.setInt(2, blockId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private boolean hasAssignmentAtBlock(int residentId, int blockId) throws SQLException {
        String sql = "SELECT 1 FROM assignments WHERE resident_id=? AND block_id=?";
        try (PreparedStatement ps = dbMgr.getValidConnection().prepareStatement(sql)) {
            ps.setInt(1, residentId);
            ps.setInt(2, blockId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
