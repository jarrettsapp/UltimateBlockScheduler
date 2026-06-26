package com.residency.tools;

import com.residency.db.*;
import com.residency.export.ExportService;

import java.sql.*;
import java.util.*;

/**
 * Materializes a saved version into the live {@code assignments} table (replacing the year's
 * rows in a transaction) and exports it to PDF via {@link ExportService}. Usage:
 *   ExportVersionPdf &lt;year&gt; &lt;versionId&gt; &lt;outPath.pdf&gt;
 *
 * <p>Loading the chosen production version into the live table is intended: that table is what
 * the app/exporter read, so the production schedule should live there.
 */
public final class ExportVersionPdf {
    public static void main(String[] args) throws Exception {
        int year = Integer.parseInt(args[0]);
        int versionId = Integer.parseInt(args[1]);
        String out = args[2];

        Connection c = DatabaseManager.getInstance().getValidConnection();
        ScheduleVersionDAO vdao = new ScheduleVersionDAO();
        List<ScheduleVersionDAO.VersionAssignment> rows = vdao.getVersionAssignments(versionId);
        if (rows.isEmpty()) throw new IllegalStateException("version " + versionId + " has no assignments");

        // Map blockNumber -> block_id for the year.
        Map<Integer, Integer> blkId = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, block_number FROM blocks WHERE schedule_year=?")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) blkId.put(rs.getInt("block_number"), rs.getInt("id"));
            }
        }

        boolean prevAuto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM assignments WHERE block_id IN "
                  + "(SELECT id FROM blocks WHERE schedule_year=?)")) {
                del.setInt(1, year);
                del.executeUpdate();
            }
            int written = 0;
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO assignments (resident_id, rotation_id, block_id, override_warning) "
                  + "VALUES (?,?,?,0)")) {
                for (ScheduleVersionDAO.VersionAssignment va : rows) {
                    Integer bid = blkId.get(va.blockNumber());
                    if (bid == null) continue;
                    ins.setInt(1, va.residentId());
                    ins.setInt(2, va.rotationId());
                    ins.setInt(3, bid);
                    ins.addBatch();
                    written++;
                }
                ins.executeBatch();
            }
            c.commit();
            System.out.printf("Materialized version %d into live assignments: %d rows%n", versionId, written);
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(prevAuto);
        }

        new ExportService().exportToPdf(year, out);
        System.out.println("PDF_WRITTEN=" + out);
    }
    private ExportVersionPdf() {}
}
