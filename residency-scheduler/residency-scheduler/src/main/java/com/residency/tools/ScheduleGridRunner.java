package com.residency.tools;

import com.residency.db.*;
import com.residency.model.Resident;
import com.residency.model.Rotation;
import com.residency.model.ScheduleVersion;

import java.util.*;

/**
 * Dumps a resident×block schedule grid for the live schedule or any saved version.
 *
 *   mvn exec:java -Dexec.mainClass=com.residency.tools.ScheduleGridRunner -Dexec.args="2"
 *   mvn exec:java -Dexec.mainClass=com.residency.tools.ScheduleGridRunner -Dexec.args="2 10"
 *
 * First arg = year, second (optional) = version id. Omit version id to dump the live schedule.
 */
public final class ScheduleGridRunner {

    // Canonical short abbreviations matching the PDF (keyed by actual DB rotation names)
    private static final Map<String, String> ABBREV = new LinkedHashMap<>();
    static {
        ABBREV.put("ICU", "ICU");
        ABBREV.put("VA", "VA");
        ABBREV.put("Broadlawns", "BMC");
        ABBREV.put("BMC", "BMC");
        ABBREV.put("Younker 7 Days", "Y7D");
        ABBREV.put("Y7D", "Y7D");
        ABBREV.put("Younker 7 Nights", "Y7N");
        ABBREV.put("Y7N", "Y7N");
        ABBREV.put("Younker 8 Pulmonology", "Y8");
        ABBREV.put("Y8", "Y8");
        ABBREV.put("Inpatient GI", "GI");
        ABBREV.put("GI", "GI");
        ABBREV.put("Infectious Disease", "ID");
        ABBREV.put("ID", "ID");
        ABBREV.put("Primary Care Clinic", "AMB A");
        ABBREV.put("Ambulatory A", "AMB A");
        ABBREV.put("AMB A", "AMB A");
        ABBREV.put("Cardiology Clinic TIC", "OPC TIC");
        ABBREV.put("Outpatient TIC Cardiology", "OPC TIC");
        ABBREV.put("OPC TIC", "OPC TIC");
        ABBREV.put("Cardiology Clinic UPH", "OPC UPH");
        ABBREV.put("Outpatient UPH Cardiology", "OPC UPH");
        ABBREV.put("OPC UPH", "OPC UPH");
        ABBREV.put("Outpatient GI", "AMB GI");
        ABBREV.put("AMB GI", "AMB GI");
        ABBREV.put("Outpatient Pulm", "AMB P");
        ABBREV.put("Outpatient Pulmonology", "AMB P");
        ABBREV.put("AMB P", "AMB P");
        ABBREV.put("Emergency Medicine", "ER");
        ABBREV.put("ER", "ER");
        ABBREV.put("Addiction Medicine", "ADDMED");
        ABBREV.put("ADDMED", "ADDMED");
        ABBREV.put("Elective", "Elec");
        ABBREV.put("Elec", "Elec");
    }

    public static void main(String[] args) throws Exception {
        int year = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        Integer versionId = args.length > 1 ? Integer.parseInt(args[1]) : null;

        ResidentDAO residentDAO = new ResidentDAO();
        RotationDAO rotationDAO = new RotationDAO();
        AssignmentDAO assignmentDAO = new AssignmentDAO();
        ScheduleVersionDAO versionDAO = new ScheduleVersionDAO();

        // Build lookup maps
        Map<Integer, String> rotName = new HashMap<>();
        for (Rotation r : rotationDAO.getAll()) rotName.put(r.getId(), r.getName());

        List<Resident> cats = residentDAO.getMainResidents();
        cats.sort(Comparator.comparing(Resident::getName));
        Map<Integer, String> resName = new LinkedHashMap<>();
        for (Resident r : cats) resName.put(r.getId(), r.getName());
        Set<Integer> catIds = resName.keySet();

        // slot -> rotation name per categorical resident
        Map<Integer, String[]> grid = new LinkedHashMap<>();
        for (int id : catIds) grid.put(id, new String[26]);

        if (versionId == null) {
            System.out.println("=== LIVE schedule (year " + year + ") ===");
            for (var a : assignmentDAO.getByYear(year)) {
                int slot = a.getBlockNumber() - 1;
                if (slot < 0 || slot >= 26) continue;
                if (!catIds.contains(a.getResidentId())) continue;
                String rot = rotName.get(a.getRotationId());
                if (rot != null) grid.get(a.getResidentId())[slot] = rot;
            }
        } else {
            ScheduleVersion v = versionDAO.getVersion(versionId);
            System.out.println("=== Version [" + versionId + "] " + (v != null ? v.getName() : "?") + " ===");
            for (var va : versionDAO.getVersionAssignments(versionId)) {
                int slot = va.blockNumber() - 1;
                if (slot < 0 || slot >= 26) continue;
                if (!catIds.contains(va.residentId())) continue;
                String rot = rotName.get(va.rotationId());
                if (rot != null) grid.get(va.residentId())[slot] = rot;
            }
        }

        // Print header
        String[] cols = {"1a","1b","2a","2b","3a","3b","4a","4b","5a","5b","6a","6b","7a","7b",
                         "8a","8b","9a","9b","10a","10b","11a","11b","12a","12b","13a","13b"};
        System.out.print(String.format("%-6s", "Res"));
        for (String c : cols) System.out.print(String.format(" %-7s", c));
        System.out.println();

        for (var e : grid.entrySet()) {
            String label = resName.get(e.getKey());
            // Extract single letter (A, B, ...) from name like "Resident A"
            String letter = label.contains(" ") ? label.substring(label.lastIndexOf(' ') + 1) : label;
            System.out.print(String.format("%-6s", letter));
            for (String rot : e.getValue()) {
                String abbr = rot == null ? "" : ABBREV.getOrDefault(rot, rot);
                System.out.print(String.format(" %-7s", abbr));
            }
            System.out.println();
        }
    }
}
