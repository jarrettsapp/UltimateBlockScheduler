package com.residency.tools;

import com.residency.db.ScheduleVersionDAO;
import com.residency.model.ScheduleVersion;
import com.residency.service.ScheduleMetrics;
import com.residency.service.ScheduleMetricsBuilder;

/**
 * Read-only console reporter: prints metrics for the live schedule of a year plus every saved
 * version, so a run can be reviewed without the JavaFX UI. Touches nothing.
 *
 *   mvn exec:java -Dexec.mainClass=com.residency.tools.MetricsReportRunner -Dexec.args="2"
 */
public final class MetricsReportRunner {
    public static void main(String[] args) throws Exception {
        int year = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        ScheduleMetricsBuilder b = new ScheduleMetricsBuilder();

        System.out.println("==== LIVE schedule (year " + year + ") ====");
        print(b.forLiveYear(year));

        System.out.println();
        var versions = new ScheduleVersionDAO().listVersions(year);
        System.out.println("==== Saved versions: " + versions.size() + " ====");
        for (ScheduleVersion v : versions) {
            System.out.println("\n-- [" + v.getId() + "] " + v.getName()
                + "  (" + v.getCreatedAt() + ")"
                + (v.getNotes() == null ? "" : "\n   notes: " + v.getNotes()));
            print(b.forVersion(v.getId()));
        }
    }

    private static void print(ScheduleMetrics.Result r) {
        System.out.printf("  volunteer=%d  fragile=%d  healthy=%d  heavy->heavy=%d  runs>6wk=%d  capClean=%b%n",
            r.volunteerWeekends, r.fragileWeekends, r.healthyWeekends,
            r.heavyToDifferentHeavy, r.runsOver6Weeks, r.capacityClean());
        if (!r.capacityClean()) {
            for (String v : r.capacityViolations) System.out.println("    VIOLATION: " + v);
        }
    }
}
