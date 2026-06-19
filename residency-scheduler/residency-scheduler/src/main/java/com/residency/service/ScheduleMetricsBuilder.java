package com.residency.service;

import com.residency.db.*;
import com.residency.model.Resident;
import com.residency.model.Rotation;

import java.sql.SQLException;
import java.util.*;

/**
 * Builds {@link ScheduleMetrics} inputs from the database — for either the live schedule of
 * a year or a saved {@link com.residency.model.ScheduleVersion} snapshot — and computes the
 * metrics. Keeps all DB-shaped concerns out of {@link ScheduleMetrics} so that class stays a
 * pure, testable function of grids.
 */
public final class ScheduleMetricsBuilder {

    private final ResidentDAO residentDAO;
    private final RotationDAO rotationDAO;
    private final AssignmentDAO assignmentDAO;
    private final ScheduleVersionDAO versionDAO;

    public ScheduleMetricsBuilder() throws SQLException {
        residentDAO = new ResidentDAO();
        rotationDAO = new RotationDAO();
        assignmentDAO = new AssignmentDAO();
        versionDAO = new ScheduleVersionDAO();
    }

    /** Metrics for the live (currently-committed) schedule of a year. */
    public ScheduleMetrics.Result forLiveYear(int year) throws SQLException {
        Map<Integer, String> rotName = rotationNames();
        Set<Integer> catIds = idSet(residentDAO.getMainResidents());
        Set<Integer> bmcIds = idSet(residentDAO.getByGroup("BMC"));

        Map<String, String[]> catGrid = new LinkedHashMap<>();
        Map<String, int[]> auxByRot = new HashMap<>();

        for (var a : assignmentDAO.getByYear(year)) {
            int slot = a.getBlockNumber() - 1; // 1-based -> 0-based
            if (slot < 0 || slot >= ScheduleMetrics.SLOTS) continue;
            String rot = rotName.get(a.getRotationId());
            if (rot == null) continue;
            if (catIds.contains(a.getResidentId())) {
                catGrid.computeIfAbsent("R" + a.getResidentId(),
                    k -> new String[ScheduleMetrics.SLOTS])[slot] = rot;
            } else {
                // aux (TY + BMC) — counted toward all-groups capacity totals
                auxByRot.computeIfAbsent(rot, k -> new int[ScheduleMetrics.SLOTS])[slot]++;
            }
        }
        return ScheduleMetrics.compute(catGrid, auxByRot);
    }

    /** Metrics for a saved version snapshot (does not touch the live schedule). */
    public ScheduleMetrics.Result forVersion(int versionId) throws SQLException {
        Map<Integer, String> rotName = rotationNames();
        Set<Integer> catIds = idSet(residentDAO.getMainResidents());

        Map<String, String[]> catGrid = new LinkedHashMap<>();
        Map<String, int[]> auxByRot = new HashMap<>();

        for (var va : versionDAO.getVersionAssignments(versionId)) {
            int slot = va.blockNumber() - 1;
            if (slot < 0 || slot >= ScheduleMetrics.SLOTS) continue;
            String rot = rotName.get(va.rotationId());
            if (rot == null) continue;
            if (catIds.contains(va.residentId())) {
                catGrid.computeIfAbsent("R" + va.residentId(),
                    k -> new String[ScheduleMetrics.SLOTS])[slot] = rot;
            } else {
                auxByRot.computeIfAbsent(rot, k -> new int[ScheduleMetrics.SLOTS])[slot]++;
            }
        }
        return ScheduleMetrics.compute(catGrid, auxByRot);
    }

    private Map<Integer, String> rotationNames() throws SQLException {
        Map<Integer, String> m = new HashMap<>();
        for (Rotation r : rotationDAO.getAll()) m.put(r.getId(), r.getName());
        return m;
    }

    private static Set<Integer> idSet(List<Resident> rs) {
        Set<Integer> s = new HashSet<>();
        for (Resident r : rs) s.add(r.getId());
        return s;
    }
}
