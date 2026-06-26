package com.residency.cpsat;

import com.residency.db.*;
import com.residency.model.*;

import java.sql.*;
import java.util.*;

/**
 * Post-solve aux-coverage pass: assigns auxiliary residents (BMC, TY) to the blocks the
 * categoricals leave under-covered, per the real-world staffing rules. The rule logic is
 * backend-agnostic ({@link #fillInto(AuxFillTarget)}) so the SAME passes write either to the
 * live {@code assignments} table (post-CP-SAT-solve) or into a {@code schedule_version_assignments}
 * snapshot (so a Timefold version is self-contained / reprintable). No rule duplication.
 *
 * <p>Passes, in order (order matters — Younker-8 BMC static must precede the generic TY gap-fill):
 * <ol>
 *   <li>Younker 8 Pulm BMC static at blocks 11 (6A) &amp; 23 (12A) where no categorical.</li>
 *   <li>Generic per-group filler (from {@code aux_filler_rotations}): top each rotation up to
 *       its {@code minPerBlock} with free group members. Skips Younker 7 Days (dedicated pass).</li>
 *   <li>Younker 7 Days team: BMC 1/block except blocks {13,14,25,26}; TY tops up to 2.</li>
 * </ol>
 */
public class AuxFillerService {

    private final AuxFillerRotationDAO fillerDAO;

    public AuxFillerService() throws SQLException {
        this.fillerDAO = new AuxFillerRotationDAO();
    }

    /**
     * Legacy entry point: runs the filler against the live {@code assignments} table for a year.
     * Delegates to {@link #fillInto(AuxFillTarget)} via a {@link AuxFillTarget.LiveTarget} so the
     * live and version backends share one rule implementation.
     */
    public void run(int year, List<Block> blocks, List<Rotation> rotations,
                    ScheduleConfig config, StringBuilder log) throws SQLException {
        fillInto(new AuxFillTarget.LiveTarget(year, blocks), log);
    }

    /** Convenience overload without a log sink. */
    public int fillInto(AuxFillTarget t) throws SQLException {
        return fillInto(t, new StringBuilder());
    }

    /**
     * Runs all aux passes against {@code t}. Returns the number of aux bodies placed. The same
     * code path serves the live table and a version snapshot — only {@code t} differs.
     */
    public int fillInto(AuxFillTarget t, StringBuilder log) throws SQLException {
        Map<String, Set<Integer>> fillerMap = fillerDAO.getAllFillerRotations();
        if (fillerMap.isEmpty()) return 0;

        log.append("\n═══ AUX FILLER PASS ═══\n");

        List<Rotation> rotations = t.rotations();
        ScheduleConfig config = t.config();
        Map<Integer, Rotation> rotById = new HashMap<>();
        for (Rotation r : rotations) rotById.put(r.getId(), r);

        int placed = 0;

        // 1) Younker 8 Pulm BMC static (must precede generic gap-fill so TY tops up only the
        //    remaining gaps, not the BMC blocks).
        placed += fillYounker8PulmBmc(t, rotations, log);

        // 2) Generic per-group filler.
        for (Map.Entry<String, Set<Integer>> entry : fillerMap.entrySet()) {
            String group = entry.getKey();
            List<Resident> fillerResidents = t.residentsInGroup(group);
            if (fillerResidents.isEmpty()) {
                log.append(String.format("  Group '%s': no residents found, skipping.\n", group));
                continue;
            }
            List<Integer> fillerIds = ids(fillerResidents);

            for (int rotId : entry.getValue()) {
                Rotation rotation = rotById.get(rotId);
                if (rotation == null) continue;
                if ("Younker 7 Days".equalsIgnoreCase(rotation.getName())) continue; // dedicated pass

                int minPerBlock = config.getPolicyFor(rotId).minPerBlock;
                t.clearGroupRotation(fillerIds, rotId);

                int filled = 0;
                for (int blockNum = 1; blockNum <= 26; blockNum++) {
                    int needed = minPerBlock - t.countAt(rotId, blockNum);
                    for (Resident filler : fillerResidents) {
                        if (needed <= 0) break;
                        if (t.isResidentFree(filler.getId(), blockNum)) {
                            t.place(filler.getId(), rotId, blockNum);
                            needed--; filled++; placed++;
                        }
                    }
                }
                log.append(String.format("  Group '%-6s' → %-35s  filled %d block(s)\n",
                    group, rotation.getName(), filled));
            }
        }

        // 3) Younker 7 Days team.
        placed += fillYounker7Days(t, rotations, log);
        return placed;
    }

    /**
     * Younker 8 Pulm BMC static coverage: BMC supplies exactly ONE body at block 6A (blockNumber
     * 11) and 12A (blockNumber 23), only where no categorical already covers it.
     */
    private int fillYounker8PulmBmc(AuxFillTarget t, List<Rotation> rotations, StringBuilder log)
            throws SQLException {
        Integer y8Id = rotIdByName(rotations, "Younker 8 Pulmonology");
        if (y8Id == null) return 0;
        List<Resident> bmc = t.residentsInGroup("BMC");
        if (bmc.isEmpty()) return 0;
        t.clearGroupRotation(ids(bmc), y8Id);

        int filled = 0;
        for (int blockNum : new int[]{11, 23}) {
            if (t.countAt(y8Id, blockNum) >= 1) continue; // categorical already there
            Integer bid = pickFree(t, bmc, blockNum);
            if (bid != null) { t.place(bid, y8Id, blockNum); filled++; }
        }
        log.append(String.format("  Younker 8 Pulm BMC static fill: %d block(s)\n", filled));
        return filled;
    }

    /**
     * Younker 7 Days team = 2 bodies/block: BMC supplies ONE except blocks {13,14,25,26}; TY
     * tops up to 2 (covers no-categorical blocks and block 7).
     */
    private int fillYounker7Days(AuxFillTarget t, List<Rotation> rotations, StringBuilder log)
            throws SQLException {
        Integer y7dId = rotIdByName(rotations, "Younker 7 Days");
        if (y7dId == null) return 0;

        List<Resident> bmc = t.residentsInGroup("BMC");
        List<Resident> ty  = t.residentsInGroup("TY");
        if (!bmc.isEmpty()) t.clearGroupRotation(ids(bmc), y7dId);
        t.clearGroupRotation(ids(ty), y7dId);

        Set<Integer> bmcAbsent = Set.of(13, 14, 25, 26);
        int bmcFilled = 0, tyFilled = 0;
        for (int blockNum = 1; blockNum <= 26; blockNum++) {
            // 1) BMC supplies one body, except its absent blocks.
            if (!bmcAbsent.contains(blockNum) && t.countAt(y7dId, blockNum) < 2) {
                Integer bid = pickFree(t, bmc, blockNum);
                if (bid != null) { t.place(bid, y7dId, blockNum); bmcFilled++; }
            }
            // 2) TY tops up to 2.
            if (t.countAt(y7dId, blockNum) < 2) {
                Integer tid = pickFree(t, ty, blockNum);
                if (tid != null) { t.place(tid, y7dId, blockNum); tyFilled++; }
            }
        }
        log.append(String.format("  Younker 7 Days team fill: BMC %d, TY %d block(s)\n",
            bmcFilled, tyFilled));
        return bmcFilled + tyFilled;
    }

    /** First pool member free at {@code blockNumber}, or null. */
    private Integer pickFree(AuxFillTarget t, List<Resident> pool, int blockNumber) {
        for (Resident r : pool) if (t.isResidentFree(r.getId(), blockNumber)) return r.getId();
        return null;
    }

    private static Integer rotIdByName(List<Rotation> rotations, String name) {
        return rotations.stream()
            .filter(r -> name.equalsIgnoreCase(r.getName()))
            .map(Rotation::getId).findFirst().orElse(null);
    }

    private static List<Integer> ids(List<Resident> residents) {
        List<Integer> out = new ArrayList<>(residents.size());
        for (Resident r : residents) out.add(r.getId());
        return out;
    }
}
