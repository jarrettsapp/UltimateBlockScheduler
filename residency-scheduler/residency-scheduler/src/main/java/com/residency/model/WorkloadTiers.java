package com.residency.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the program's authoritative workload tiers, keyed by rotation
 * <em>name</em>. These deliberately do NOT use {@link RotationType}, which is unreliable here
 * (it types Inpatient GI / Infectious Disease as OUTPATIENT and Younker 8 Pulmonology as
 * INPATIENT). See SCHEDULING_RULES.md §6.
 *
 * <p>Previously the same heavy / Sunday-source name sets were hard-coded independently in
 * {@code ConstraintBuilder.applyZeroVolunteerFloor}, {@code CpSatSchedulerEngine.proveCoverageFloor},
 * and {@code service.ScheduleMetrics}. If those drifted, the solver would optimize one
 * eligibility model while the metrics reporter measured another. They now all reference this
 * class so they cannot diverge.
 */
public final class WorkloadTiers {

    /** Heavy rotations: a resident on one is call-ineligible for its whole duration. */
    public static final Set<String> HEAVY = Set.of(
        "ICU", "VA", "Broadlawns", "Younker 7 Days", "Younker 7 Nights", "Younker 8 Pulmonology");

    /** Medium / consult rotations (lighter; PTO-eligible — this is why they carry call). */
    public static final Set<String> MEDIUM = Set.of("Inpatient GI", "Infectious Disease");

    /**
     * Sunday-call source rotations: a covering categorical may be on any of these (medium/consult
     * plus all light rotations). Equivalent to "every non-heavy rotation" for the current program.
     */
    public static final Set<String> SUNDAY_SOURCE = Set.of(
        "Inpatient GI", "Infectious Disease",
        "Outpatient GI", "Outpatient Pulmonology", "Ambulatory A", "Emergency Medicine",
        "Addiction Medicine", "Elective", "Outpatient TIC Cardiology", "Outpatient UPH Cardiology");

    /** Resolve the heavy-tier rotation IDs present in {@code rotations}, by name. */
    public static Set<Integer> heavyIds(List<Rotation> rotations) {
        return idsFor(rotations, HEAVY);
    }

    /** Resolve the Sunday-source rotation IDs present in {@code rotations}, by name. */
    public static Set<Integer> sundaySourceIds(List<Rotation> rotations) {
        return idsFor(rotations, SUNDAY_SOURCE);
    }

    /** Resolve the combined heavy + medium (consult) rotation IDs present in {@code rotations}. */
    public static Set<Integer> heavyOrMediumIds(List<Rotation> rotations) {
        Set<Integer> ids = new HashSet<>();
        for (Rotation r : rotations) {
            if (HEAVY.contains(r.getName()) || MEDIUM.contains(r.getName())) ids.add(r.getId());
        }
        return ids;
    }

    private static Set<Integer> idsFor(List<Rotation> rotations, Set<String> names) {
        Set<Integer> ids = new HashSet<>();
        for (Rotation r : rotations) {
            if (names.contains(r.getName())) ids.add(r.getId());
        }
        return ids;
    }

    private WorkloadTiers() {}
}
