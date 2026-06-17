package com.residency.cpsat;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.residency.model.Resident;
import com.residency.model.Rotation;

import java.util.*;

/**
 * Centralises OR-Tools BoolVar creation.
 *
 * Decision variable:  x[r][s][t] = 1  iff resident r starts rotation s at block t
 *
 * "Start" semantics: x[r][s][t] = 1 means resident r begins a segment of
 * rotation s at block t (0-indexed). The segment then occupies blocks [t, t+blockLength-1].
 * BlockExpansionService derives the occupancy variables from these start vars.
 *
 * One block = 2 calendar weeks. A 26-block academic year spans blocks 0–25.
 * Only creates variables for (resident, rotation, block) triples that are
 * actually feasible given block-length constraints (sparse creation).
 */
public class VariableFactory {

    // x[residentId][rotationId][startBlock] -> BoolVar
    private final Map<Integer, Map<Integer, Map<Integer, BoolVar>>> startVars = new HashMap<>();

    // occupancy[residentId][rotationId][block] -> BoolVar (derived)
    private final Map<Integer, Map<Integer, Map<Integer, BoolVar>>> occupancyVars = new HashMap<>();

    private final CpModel model;
    private final int totalBlocks;
    private final Map<Integer, int[]> rotationAllowedLengths; // rotationId -> allowed block lengths (in blocks)

    public VariableFactory(CpModel model, int totalBlocks,
                           Map<Integer, int[]> rotationAllowedLengths) {
        this.model = model;
        this.totalBlocks = totalBlocks;
        this.rotationAllowedLengths = rotationAllowedLengths;
    }

    /**
     * Create all start and occupancy variables for the given residents and rotations.
     * Only creates variables for valid (resident, rotation, startBlock) combinations.
     */
    public void createAll(List<Resident> residents, List<Rotation> rotations,
                          Map<Integer, Set<Integer>> eligibleResidentsByRotation) {
        for (Resident r : residents) {
            for (Rotation s : rotations) {
                Set<Integer> eligible = eligibleResidentsByRotation.get(s.getId());
                if (eligible != null && !eligible.contains(r.getId())) continue;

                int[] lengths = rotationAllowedLengths.getOrDefault(s.getId(), new int[]{2});

                for (int t = 0; t < totalBlocks; t++) {
                    boolean anyFits = false;
                    for (int L : lengths) {
                        if (t + L <= totalBlocks) { anyFits = true; break; }
                    }
                    if (!anyFits) continue;

                    BoolVar v = model.newBoolVar(
                        String.format("start_r%d_s%d_t%d", r.getId(), s.getId(), t));
                    startVars
                        .computeIfAbsent(r.getId(), k -> new HashMap<>())
                        .computeIfAbsent(s.getId(), k -> new HashMap<>())
                        .put(t, v);
                }

                for (int b = 0; b < totalBlocks; b++) {
                    BoolVar occ = model.newBoolVar(
                        String.format("occ_r%d_s%d_b%d", r.getId(), s.getId(), b));
                    occupancyVars
                        .computeIfAbsent(r.getId(), k -> new HashMap<>())
                        .computeIfAbsent(s.getId(), k -> new HashMap<>())
                        .put(b, occ);
                }
            }
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    /** Get start variable x[r][s][t]. Returns null if not created (ineligible). */
    public BoolVar getStartVar(int residentId, int rotationId, int block) {
        return startVars
            .getOrDefault(residentId, Map.of())
            .getOrDefault(rotationId, Map.of())
            .get(block);
    }

    /** Get all start blocks for (resident, rotation). */
    public Map<Integer, BoolVar> getStartVars(int residentId, int rotationId) {
        return startVars
            .getOrDefault(residentId, Map.of())
            .getOrDefault(rotationId, Map.of());
    }

    /** Get occupancy variable occ[r][s][b]. */
    public BoolVar getOccupancyVar(int residentId, int rotationId, int block) {
        return occupancyVars
            .getOrDefault(residentId, Map.of())
            .getOrDefault(rotationId, Map.of())
            .get(block);
    }

    /** All occupancy vars for (resident, rotation). */
    public Map<Integer, BoolVar> getOccupancyVars(int residentId, int rotationId) {
        return occupancyVars
            .getOrDefault(residentId, Map.of())
            .getOrDefault(rotationId, Map.of());
    }

    /** All resident IDs that have variables. */
    public Set<Integer> getResidentIds()  { return startVars.keySet(); }

    /** All rotation IDs with vars for a given resident. */
    public Set<Integer> getRotationIds(int residentId) {
        return startVars.getOrDefault(residentId, Map.of()).keySet();
    }

    public int getTotalBlocks() { return totalBlocks; }

    /** Total variable count (for diagnostics). */
    public int totalStartVars() {
        return startVars.values().stream()
            .flatMap(m -> m.values().stream())
            .mapToInt(Map::size).sum();
    }

    public int totalOccupancyVars() {
        return occupancyVars.values().stream()
            .flatMap(m -> m.values().stream())
            .mapToInt(Map::size).sum();
    }
}
