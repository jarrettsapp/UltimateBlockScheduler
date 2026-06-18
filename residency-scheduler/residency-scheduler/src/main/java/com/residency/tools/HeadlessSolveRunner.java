package com.residency.tools;

import com.residency.cpsat.CpSatSchedulerEngine;
import com.residency.cpsat.ScheduleSolution;
import com.residency.db.DatabaseManager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the CP-SAT scheduler from the command line, without the JavaFX UI, against
 * the same SQLite database the app uses (residency_scheduler.db in the working
 * directory). Intended for headless re-solves and for capturing the full solver
 * log to stdout.
 *
 * <p>Usage:
 * <pre>
 *   java -cp target/residency-scheduler-1.0-SNAPSHOT.jar \
 *        com.residency.tools.HeadlessSolveRunner &lt;year&gt; [t0 t1 t2 t3]
 * </pre>
 * Tier time limits (seconds) default to the app's defaults: 60 120 120 60.
 * The committed schedule is written back to the database exactly as the UI run
 * would do.
 */
public final class HeadlessSolveRunner {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: HeadlessSolveRunner <year> [t0 t1 t2 t3]");
            System.exit(2);
        }
        int year = Integer.parseInt(args[0]);
        int t0 = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int t1 = args.length > 2 ? Integer.parseInt(args[2]) : 120;
        int t2 = args.length > 3 ? Integer.parseInt(args[3]) : 120;
        int t3 = args.length > 4 ? Integer.parseInt(args[4]) : 60;

        System.out.printf("=== Headless CP-SAT solve | year=%d | limits=%d/%d/%d/%d s ===%n",
            year, t0, t1, t2, t3);
        long start = System.currentTimeMillis();

        CpSatSchedulerEngine engine = new CpSatSchedulerEngine();
        ScheduleSolution sol = engine.solve(year, msg -> {
            // Stream progress with a wall-clock prefix so the slow pre-solve phase
            // is visible.
            long s = (System.currentTimeMillis() - start) / 1000;
            System.out.printf("[%4ds] %s%n", s, msg);
        }, t0, t1, t2, t3);

        System.out.println("\n=== RESULT ===");
        System.out.println("status     : " + sol.getStatus());
        System.out.println("feasible   : " + sol.isFeasible());
        System.out.println("objective  : " + sol.getObjectiveValue());
        System.out.println("runtime ms : " + sol.getRuntimeMs());
        System.out.println("summary    : " + sol.statusSummary());

        // Name the specific Tier-1 inpatient→different-inpatient transitions behind any
        // nonzero Tier-1 score (the solver only reports the score, not the offending pair).
        if (sol.isFeasible()) {
            printInpatientTransitions(year, sol);
        }

        // Dump the full solver log (diagnostics + per-phase + validation) so we can
        // review feasibility analysis and constraint scoring without the UI.
        System.out.println("\n=== SOLVER LOG ===");
        System.out.println(sol.getSolverLog());

        System.exit(sol.isFeasible() ? 0 : 1);
    }

    /**
     * Reconstructs each resident's per-block rotation timeline from the committed
     * solution and prints every block boundary where one INPATIENT-typed rotation is
     * immediately followed by a <em>different</em> INPATIENT-typed rotation — i.e. the
     * exact transitions the Tier-1 inpatient-split term penalizes. A clean (Tier-1 = 0)
     * schedule prints "none". Useful when a solve ends with a nonzero Tier-1 score and
     * we need to know which assignment caused it.
     */
    private static void printInpatientTransitions(int year, ScheduleSolution sol) throws Exception {
        Connection conn = DatabaseManager.getInstance().getValidConnection();

        // rotationId → name, and the set of INPATIENT-typed rotation ids.
        Map<Integer, String> rotName = new LinkedHashMap<>();
        Map<Integer, Boolean> isInpatient = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, rotation_type FROM rotations")) {
            while (rs.next()) {
                rotName.put(rs.getInt("id"), rs.getString("name"));
                isInpatient.put(rs.getInt("id"),
                    "INPATIENT".equalsIgnoreCase(rs.getString("rotation_type")));
            }
        }
        // residentId → name; blockId → block_number (0-based slot index) for this year.
        Map<Integer, String> resName = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id, name FROM residents WHERE is_auxiliary = 0")) {
            while (rs.next()) resName.put(rs.getInt("id"), rs.getString("name"));
        }
        Map<Integer, Integer> blockIndex = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT id, block_number FROM blocks WHERE schedule_year = " + year)) {
            while (rs.next()) blockIndex.put(rs.getInt("id"), rs.getInt("block_number"));
        }
        // Normalize block_number to a contiguous 0-based slot order.
        TreeMap<Integer, Integer> sorted = new TreeMap<>();
        int next = 0;
        for (int bn : new java.util.TreeSet<>(blockIndex.values())) sorted.put(bn, next++);

        System.out.println("\n=== Tier-1 inpatient→different-inpatient transitions ===");
        int total = 0;
        // assignments: residentId → rotationId → list of week indices (2 per block).
        for (var resEntry : sol.getAssignments().entrySet()) {
            int rid = resEntry.getKey();
            if (!resName.containsKey(rid)) continue; // categoricals only
            // Build slot → rotationId for this resident. recordAssignment stores the
            // block/slot index directly (the engine's occupancy loop is keyed by slot,
            // 0..totalBlocks-1), so the recorded value IS the slot — do not divide.
            Map<Integer, Integer> slotRot = new TreeMap<>();
            for (var rotEntry : resEntry.getValue().entrySet()) {
                int rotId = rotEntry.getKey();
                for (int slot : rotEntry.getValue()) {
                    slotRot.put(slot, rotId);
                }
            }
            Integer prevSlot = null, prevRot = null;
            for (var e : slotRot.entrySet()) {
                int slot = e.getKey(), rot = e.getValue();
                if (prevSlot != null && slot == prevSlot + 1
                        && Boolean.TRUE.equals(isInpatient.get(prevRot))
                        && Boolean.TRUE.equals(isInpatient.get(rot))
                        && prevRot != rot) {
                    System.out.printf("  %-14s slot %d→%d : %s → %s%n",
                        resName.get(rid), prevSlot, slot,
                        rotName.get(prevRot), rotName.get(rot));
                    total++;
                }
                prevSlot = slot; prevRot = rot;
            }
        }
        if (total == 0) System.out.println("  none (Tier-1 inpatient-split = 0)");
        else System.out.println("  total: " + total
            + "  (Tier-1 contribution = " + total + " × weight_inpatient_split)");
    }

    private HeadlessSolveRunner() {}
}
