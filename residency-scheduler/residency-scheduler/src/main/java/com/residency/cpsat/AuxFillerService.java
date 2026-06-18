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

                // Younker 7 Days is handled by a dedicated pass below (BMC=1 + TY top-up to
                // 2). Skip it here so the generic filler doesn't double-fill it.
                if ("Younker 7 Days".equalsIgnoreCase(rotation.getName())) continue;

                // Clear old filler assignments for this group + rotation + year
                clearFillerAssignments(group, fillerResidents, rotId, year);

                int filled = 0;
                for (int blockNum = 1; blockNum <= 26; blockNum++) {
                    Integer blockId = blockNumberToId.get(blockNum);
                    if (blockId == null) continue;

                    int currentCount = countAssignmentsForBlock(rotId, blockId);
                    int needed = minPerBlock - currentCount;
                    if (needed <= 0) continue;

                    // Ordinary filler: find residents not already assigned at this block.
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

        fillYounker7Days(year, blockNumberToId, rotations, log);
    }

    /**
     * Younker 7 Days coverage = EXACTLY 2 bodies per block, composed (per the real-world
     * staffing) as:
     *   • BMC: exactly ONE body on every 4-week block EXCEPT block 7 and block 13 (never 2).
     *   • Categoricals: the solver places 1 per block on most blocks and 2 at block 13.
     *   • TY: fills the 2nd body wherever a categorical is not placed (e.g. blocks 1,3,5 and
     *     block 7). TY placement is variable/external, so we write a TY placeholder here so
     *     the schedule visibly shows the full team of 2.
     * BMC and TY are pools; a placeholder body may be written even if a named pool record is
     * also on another rotation that block. This pass never writes a 2nd BMC on Y7 Days, and
     * never displaces a categorical (categoricals are fixed by the solver before this runs).
     */
    private void fillYounker7Days(int year, Map<Integer, Integer> blockNumberToId,
                                  List<Rotation> rotations, StringBuilder log) throws SQLException {
        Integer y7dId = rotations.stream()
            .filter(r -> "Younker 7 Days".equalsIgnoreCase(r.getName()))
            .map(Rotation::getId).findFirst().orElse(null);
        if (y7dId == null) return;

        List<Resident> bmc = residentDAO.getByGroup("BMC");
        List<Resident> ty  = residentDAO.getAuxiliaryNonGroup(); // TY = auxiliary, no group

        // Clear prior BMC/TY filler on Y7 Days so this pass is idempotent.
        if (!bmc.isEmpty()) clearFillerAssignments("BMC", bmc, y7dId, year);
        clearResidentRotationAssignments(ty, y7dId, year);

        // BMC absent on block 7 (slots → blockNum 13,14) and block 13 (blockNum 25,26).
        Set<Integer> bmcAbsent = Set.of(13, 14, 25, 26);
        int bmcFilled = 0, tyFilled = 0;

        for (int blockNum = 1; blockNum <= 26; blockNum++) {
            Integer blockId = blockNumberToId.get(blockNum);
            if (blockId == null) continue;

            // 1) BMC supplies exactly ONE body, except its absent blocks.
            if (!bmcAbsent.contains(blockNum) && countAssignmentsForBlock(y7dId, blockId) < 2) {
                Integer bid = pickPoolMember(bmc, blockId);
                if (bid != null) {
                    assignmentDAO.insert(new Assignment(0, bid, y7dId, blockId, false));
                    bmcFilled++;
                }
            }

            // 2) TY tops up to 2 (covers blocks with no categorical, and block 7).
            if (countAssignmentsForBlock(y7dId, blockId) < 2) {
                Integer tid = pickPoolMember(ty, blockId);
                if (tid != null) {
                    assignmentDAO.insert(new Assignment(0, tid, y7dId, blockId, false));
                    tyFilled++;
                }
            }
        }
        log.append(String.format("  Younker 7 Days team fill: BMC %d, TY %d block(s)\n",
            bmcFilled, tyFilled));
    }

    /**
     * Picks a pool member's id to place on {@code rotId} at {@code blockId}: returns the
     * first member who has NO assignment at all that block (the DB enforces one assignment
     * per resident per block via a UNIQUE(resident_id, block_id) constraint, so a record
     * already on any rotation that block cannot take a second). Returns null if no member is
     * free — the caller then leaves the slot unfilled rather than violating the constraint.
     */
    private Integer pickPoolMember(List<Resident> pool, int blockId) throws SQLException {
        for (Resident r : pool) {
            if (!hasAssignmentAtBlock(r.getId(), blockId)) return r.getId();
        }
        return null;
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

    /** Clears all assignments of the given residents on one rotation for the year. */
    private void clearResidentRotationAssignments(List<Resident> residents, int rotId, int year)
            throws SQLException {
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
}
