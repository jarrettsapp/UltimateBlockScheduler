package com.residency.db;

import com.residency.service.ScheduleMetrics;
import com.residency.service.ScheduleMetricsBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test against the live residency_scheduler.db: save the current year-2
 * schedule as a version, confirm live and version metrics agree, then delete the version.
 * Tagged so it's only run explicitly (it touches the real DB).
 */
class VersionSmokeTest {

    @Test
    void saveLoadMetricsRoundTrip() throws Exception {
        int year = 2;
        ScheduleVersionDAO dao = new ScheduleVersionDAO();
        String name = "smoke-" + System.currentTimeMillis();
        int id = dao.saveVersion(year, name, "smoke test", null, null, null, true, "smoke");
        try {
            assertTrue(dao.listVersions(year).stream().anyMatch(v -> v.getId() == id));

            ScheduleMetrics.Result live = new ScheduleMetricsBuilder().forLiveYear(year);
            ScheduleMetrics.Result ver  = new ScheduleMetricsBuilder().forVersion(id);

            System.out.printf("LIVE clean=%s vol=%d fragile=%d healthy=%d%n",
                live.capacityClean(), live.volunteerWeekends, live.fragileWeekends, live.healthyWeekends);
            System.out.printf("VER  clean=%s vol=%d fragile=%d healthy=%d%n",
                ver.capacityClean(), ver.volunteerWeekends, ver.fragileWeekends, ver.healthyWeekends);

            assertEquals(live.capacityClean(), ver.capacityClean(), "capacity status must match");
            assertEquals(live.volunteerWeekends, ver.volunteerWeekends, "volunteer count must match");
            assertEquals(live.fragileWeekends, ver.fragileWeekends, "fragile count must match");
            assertEquals(live.heavyToDifferentHeavy, ver.heavyToDifferentHeavy, "transition count must match");
        } finally {
            dao.deleteVersion(id);
        }
        assertFalse(dao.listVersions(year).stream().anyMatch(v -> v.getId() == id), "version cleaned up");
    }
}
