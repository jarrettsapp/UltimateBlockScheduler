package com.residency.cpsat;

import com.residency.db.*;
import com.residency.model.*;

import java.sql.*;
import java.util.*;

/**
 * Backend abstraction for {@link AuxFillerService}'s aux-coverage passes, keyed by
 * blockNumber (1–26) so the IDENTICAL rules (VA TY top-up, Younker-8-Pulm BMC static,
 * Younker-7-Days BMC+TY team) write either to the live {@code assignments} table or to a
 * {@code schedule_version_assignments} snapshot. One rule implementation, no drift.
 *
 * <p>The filler only ever needs four primitives: count bodies on a rotation at a block, check
 * whether a resident is free at a block (the one-assignment-per-resident-per-block rule),
 * place a body, and clear a group's prior filler on a rotation (idempotency). All temporal
 * arguments are blockNumbers; each implementation maps to its own storage.
 */
public interface AuxFillTarget {

    /** Rotations and residents the filler reads (same for both backends). */
    List<Rotation> rotations() throws SQLException;
    List<Resident> residentsInGroup(String group) throws SQLException;
    ScheduleConfig config() throws SQLException;

    /** Bodies currently on {@code rotationId} at {@code blockNumber}. */
    int countAt(int rotationId, int blockNumber);

    /** True iff {@code residentId} has NO assignment at {@code blockNumber} (free to place). */
    boolean isResidentFree(int residentId, int blockNumber);

    /** Place one body: {@code residentId} on {@code rotationId} at {@code blockNumber}. */
    void place(int residentId, int rotationId, int blockNumber);

    /** Remove any prior filler bodies of {@code residentIds} on {@code rotationId} (idempotency). */
    void clearGroupRotation(List<Integer> residentIds, int rotationId);

    // ──────────────────────────────────────────────────────────────────────
    //  Live backend — the legacy behavior: writes the assignments table by blockId.
    // ──────────────────────────────────────────────────────────────────────
    final class LiveTarget implements AuxFillTarget {
        private final AssignmentDAO assignmentDAO;
        private final ResidentDAO residentDAO;
        private final RotationDAO rotationDAO;
        private final ScheduleConfigDAO configDAO;
        private final DatabaseManager dbMgr;
        private final int year;
        private final Map<Integer, Integer> blockNumberToId = new HashMap<>();

        public LiveTarget(int year, List<Block> blocks) throws SQLException {
            this.assignmentDAO = new AssignmentDAO();
            this.residentDAO   = new ResidentDAO();
            this.rotationDAO   = new RotationDAO();
            this.configDAO     = new ScheduleConfigDAO();
            this.dbMgr         = DatabaseManager.getInstance();
            this.year          = year;
            for (Block b : blocks) blockNumberToId.put(b.getBlockNumber(), b.getId());
        }

        private Integer blockId(int blockNumber) { return blockNumberToId.get(blockNumber); }

        public List<Rotation> rotations() throws SQLException { return rotationDAO.getAll(); }
        public List<Resident> residentsInGroup(String g) throws SQLException { return residentDAO.getByGroup(g); }
        public ScheduleConfig config() throws SQLException {
            ScheduleConfig cfg = configDAO.loadConfig();
            for (Rotation r : rotationDAO.getAll())
                cfg.getRotationPolicies().put(r.getId(), configDAO.loadRotationPolicy(r.getId()));
            return cfg;
        }

        public int countAt(int rotationId, int blockNumber) {
            Integer bid = blockId(blockNumber);
            if (bid == null) return 0;
            String sql = "SELECT COUNT(*) FROM assignments WHERE rotation_id=? AND block_id=?";
            try (PreparedStatement ps = dbMgr.getValidConnection().prepareStatement(sql)) {
                ps.setInt(1, rotationId); ps.setInt(2, bid);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }

        public boolean isResidentFree(int residentId, int blockNumber) {
            Integer bid = blockId(blockNumber);
            if (bid == null) return false;
            String sql = "SELECT 1 FROM assignments WHERE resident_id=? AND block_id=?";
            try (PreparedStatement ps = dbMgr.getValidConnection().prepareStatement(sql)) {
                ps.setInt(1, residentId); ps.setInt(2, bid);
                try (ResultSet rs = ps.executeQuery()) { return !rs.next(); }
            } catch (SQLException e) { throw new RuntimeException(e); }
        }

        public void place(int residentId, int rotationId, int blockNumber) {
            Integer bid = blockId(blockNumber);
            if (bid == null) return;
            try { assignmentDAO.insert(new Assignment(0, residentId, rotationId, bid, false)); }
            catch (SQLException e) { throw new RuntimeException(e); }
        }

        public void clearGroupRotation(List<Integer> residentIds, int rotationId) {
            if (residentIds.isEmpty()) return;
            String ids = idCsv(residentIds);
            String sql = """
                DELETE FROM assignments
                WHERE rotation_id = ?
                  AND resident_id IN (%s)
                  AND block_id IN (SELECT id FROM blocks WHERE schedule_year = ?)
                """.formatted(ids);
            try (PreparedStatement ps = dbMgr.getValidConnection().prepareStatement(sql)) {
                ps.setInt(1, rotationId); ps.setInt(2, year);
                ps.executeUpdate();
            } catch (SQLException e) { throw new RuntimeException(e); }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Version backend — buffers aux rows in memory, flushes to
    //  schedule_version_assignments. Categorical rows are seeded first so the rule
    //  passes see the same per-block coverage the live table would.
    // ──────────────────────────────────────────────────────────────────────
    final class VersionTarget implements AuxFillTarget {
        private final Connection conn;
        private final int versionId;
        private final ResidentDAO residentDAO;
        private final RotationDAO rotationDAO;
        private final ScheduleConfigDAO configDAO;
        // rotationId -> blockNumber -> count, and residentId -> occupied blockNumbers.
        private final Map<Integer, Map<Integer, Integer>> count = new HashMap<>();
        private final Map<Integer, Set<Integer>> occupied = new HashMap<>();
        // Buffered aux rows {residentId, rotationId, blockNumber} to flush.
        private final List<int[]> auxRows = new ArrayList<>();

        public VersionTarget(Connection conn, int versionId) throws SQLException {
            this.conn = conn;
            this.versionId = versionId;
            this.residentDAO = new ResidentDAO();
            this.rotationDAO = new RotationDAO();
            this.configDAO   = new ScheduleConfigDAO();
        }

        /** Seed a pre-existing (categorical) assignment so the passes see current coverage. */
        public void seedExisting(int residentId, int rotationId, int blockNumber) {
            count.computeIfAbsent(rotationId, k -> new HashMap<>()).merge(blockNumber, 1, Integer::sum);
            occupied.computeIfAbsent(residentId, k -> new HashSet<>()).add(blockNumber);
        }

        public List<Rotation> rotations() throws SQLException { return rotationDAO.getAll(); }
        public List<Resident> residentsInGroup(String g) throws SQLException { return residentDAO.getByGroup(g); }
        public ScheduleConfig config() throws SQLException {
            ScheduleConfig cfg = configDAO.loadConfig();
            for (Rotation r : rotationDAO.getAll())
                cfg.getRotationPolicies().put(r.getId(), configDAO.loadRotationPolicy(r.getId()));
            return cfg;
        }

        public int countAt(int rotationId, int blockNumber) {
            return count.getOrDefault(rotationId, Map.of()).getOrDefault(blockNumber, 0);
        }

        public boolean isResidentFree(int residentId, int blockNumber) {
            return !occupied.getOrDefault(residentId, Set.of()).contains(blockNumber);
        }

        public void place(int residentId, int rotationId, int blockNumber) {
            auxRows.add(new int[]{residentId, rotationId, blockNumber});
            count.computeIfAbsent(rotationId, k -> new HashMap<>()).merge(blockNumber, 1, Integer::sum);
            occupied.computeIfAbsent(residentId, k -> new HashSet<>()).add(blockNumber);
        }

        public void clearGroupRotation(List<Integer> residentIds, int rotationId) {
            // No-op for a freshly-built version: there is no prior aux to clear (the version
            // was just created from categorical rows only). Idempotency is inherent.
        }

        /** Writes buffered aux rows into schedule_version_assignments. Returns the row count. */
        public int flush() throws SQLException {
            if (auxRows.isEmpty()) return 0;
            String ins = "INSERT INTO schedule_version_assignments "
                + "(version_id, resident_id, rotation_id, block_number) VALUES (?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                for (int[] r : auxRows) {
                    ps.setInt(1, versionId); ps.setInt(2, r[0]); ps.setInt(3, r[1]); ps.setInt(4, r[2]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return auxRows.size();
        }
    }

    static String idCsv(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) { if (i > 0) sb.append(','); sb.append(ids.get(i)); }
        return sb.toString();
    }
}
