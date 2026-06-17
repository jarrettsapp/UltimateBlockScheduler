package com.residency.cpsat;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.LinearExpr;
import com.residency.model.Resident;
import com.residency.model.Rotation;

import java.util.*;

/**
 * Enforces contiguous block occupancy.
 *
 * For each start variable x[r][s][t]=1 with block length L:
 *   occupancy[r][s][t], occupancy[r][s][t+1], ..., occupancy[r][s][t+L-1] = 1
 *
 * This is encoded as implication chains:
 *   x[r][s][t] => occupancy[r][s][t+k]  for k in [0, L-1]
 *
 * Also prevents overlapping starts:
 *   addAtMostOne on all start vars that could cover the same block position.
 */
public class BlockExpansionService {

    private final CpModel model;
    private final VariableFactory vars;
    private final int totalBlocks;
    private final Map<Integer, int[]> rotationAllowedLengths; // rotationId -> lengths in blocks

    public BlockExpansionService(CpModel model, VariableFactory vars,
                                 int totalBlocks, Map<Integer, int[]> rotationAllowedLengths) {
        this.model = model;
        this.vars  = vars;
        this.totalBlocks = totalBlocks;
        this.rotationAllowedLengths = rotationAllowedLengths;
    }

    public void applyAll(List<Resident> residents, List<Rotation> rotations) {
        for (Resident r : residents) {
            for (Rotation s : rotations) {
                applyForPair(r.getId(), s.getId());
            }
        }
    }

    private void applyForPair(int residentId, int rotationId) {
        int[] lengths = rotationAllowedLengths.getOrDefault(rotationId, new int[]{2});
        int maxLen = Arrays.stream(lengths).max().orElse(2);

        Map<Integer, BoolVar> startMap = vars.getStartVars(residentId, rotationId);
        if (startMap.isEmpty()) return;

        // 1. Link start vars to occupancy vars
        for (var entry : startMap.entrySet()) {
            int t = entry.getKey();
            BoolVar startVar = entry.getValue();

            List<BoolVar> validLengthVars = new ArrayList<>();
            for (int L : lengths) {
                if (t + L > totalBlocks) continue;

                BoolVar lengthSelected = model.newBoolVar(
                    String.format("len%d_r%d_s%d_t%d", L, residentId, rotationId, t));
                validLengthVars.add(lengthSelected);

                model.addImplication(lengthSelected, startVar);

                for (int k = 0; k < L; k++) {
                    BoolVar occ = vars.getOccupancyVar(residentId, rotationId, t + k);
                    if (occ != null) {
                        model.addImplication(lengthSelected, occ);
                    }
                }
            }

            if (!validLengthVars.isEmpty()) {
                BoolVar[] lvArr = validLengthVars.toArray(new BoolVar[0]);
                model.addLinearConstraint(
                    LinearExpr.sum(lvArr), 1, lengths.length)
                    .onlyEnforceIf(startVar);
            }
        }

        // 2. Occupancy derives from starts: occ[r][s][b] = 1 iff any start covers block b.
        for (int b = 0; b < totalBlocks; b++) {
            BoolVar occ = vars.getOccupancyVar(residentId, rotationId, b);
            if (occ == null) continue;

            List<BoolVar> coveringStarts = new ArrayList<>();
            for (int L : lengths) {
                for (int t = Math.max(0, b - L + 1); t <= b && t < totalBlocks; t++) {
                    if (t + L > totalBlocks) continue;
                    BoolVar sv = vars.getStartVar(residentId, rotationId, t);
                    if (sv != null) coveringStarts.add(sv);
                }
            }
            if (!coveringStarts.isEmpty()) {
                BoolVar[] csArr = coveringStarts.toArray(new BoolVar[0]);
                model.addLinearConstraint(LinearExpr.sum(csArr), 1, csArr.length)
                     .onlyEnforceIf(occ);
            }
        }

        // 3. No-overlap: at most one block start per resident per rotation per window.
        // Use LinkedHashSet to deduplicate vars across lengths.
        for (int b = 0; b < totalBlocks; b++) {
            LinkedHashSet<BoolVar> seen = new LinkedHashSet<>();
            for (int L : lengths) {
                for (int start = Math.max(0, b - L + 1); start <= b; start++) {
                    if (start + L > totalBlocks) continue;
                    BoolVar sv = vars.getStartVar(residentId, rotationId, start);
                    if (sv != null) seen.add(sv);
                }
            }
            if (seen.size() > 1) {
                model.addAtMostOne(seen.toArray(new BoolVar[0]));
            }
        }
    }

    /**
     * Prevent a resident from being in two different rotations at the same block.
     */
    public void applyNoOverlapAcrossRotations(List<Resident> residents,
                                               List<Rotation> rotations) {
        for (Resident r : residents) {
            for (int b = 0; b < totalBlocks; b++) {
                List<BoolVar> blockOccs = new ArrayList<>();
                for (Rotation s : rotations) {
                    BoolVar occ = vars.getOccupancyVar(r.getId(), s.getId(), b);
                    if (occ != null) blockOccs.add(occ);
                }
                if (blockOccs.size() > 1) {
                    model.addAtMostOne(blockOccs.toArray(new BoolVar[0]));
                }
            }
        }
    }
}
