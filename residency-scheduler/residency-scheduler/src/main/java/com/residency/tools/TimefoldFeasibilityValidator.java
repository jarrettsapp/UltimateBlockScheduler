package com.residency.tools;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import com.residency.service.TimefoldSchedulerService;
import com.residency.solver.RotationSchedule;

/**
 * ITEM 2 validator — scores the ported hard model on a set of KNOWN-FEASIBLE schedule versions and
 * asserts each scores hard==0 / medium==0 (no solving; pure warm-start scoring). Any nonzero score
 * names the offending constraint(s), so a porting bug is pinpointed immediately.
 *
 * <p>Usage:
 * <pre>
 *   java ... com.residency.tools.TimefoldFeasibilityValidator &lt;year&gt; &lt;v1,v2,...&gt;
 * </pre>
 * Exit 0 iff every version scored hard==0 &amp;&amp; medium==0.
 */
public final class TimefoldFeasibilityValidator {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: TimefoldFeasibilityValidator <year> <comma-separated versionIds>");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        String[] ids = args[1].split(",");

        TimefoldSchedulerService svc = new TimefoldSchedulerService();
        int clean = 0, dirty = 0;
        boolean first = true;
        for (String s : ids) {
            int vid = Integer.parseInt(s.trim());
            RotationSchedule problem = svc.buildFeasibilityProblemFromVersion(year, vid);
            ScoreAnalysis<HardMediumSoftScore> analysis = svc.analyzeFeasibility(problem);
            HardMediumSoftScore score = analysis.score();
            boolean ok = score.hardScore() == 0 && score.mediumScore() == 0;
            System.out.printf("v%-4d : hard=%d medium=%d soft=%d  %s%n",
                vid, score.hardScore(), score.mediumScore(), score.softScore(), ok ? "OK" : "*** VIOLATION ***");
            if (ok) clean++;
            else {
                dirty++;
                // Name the offending constraints (only on the first dirty version to limit noise).
                if (first) {
                    for (ConstraintAnalysis<HardMediumSoftScore> ca : analysis.constraintAnalyses()) {
                        if (ca.score().hardScore() != 0 || ca.score().mediumScore() != 0) {
                            System.out.printf("    ↳ %-34s %s  (matches=%d)%n",
                                ca.constraintRef().constraintName(), ca.score(),
                                ca.matches() == null ? -1 : ca.matches().size());
                        }
                    }
                    first = false;
                }
            }
        }
        System.out.printf("%n=== %d clean, %d violating, of %d versions ===%n", clean, dirty, ids.length);
        System.exit(dirty == 0 ? 0 : 1);
    }

    private TimefoldFeasibilityValidator() {}
}
