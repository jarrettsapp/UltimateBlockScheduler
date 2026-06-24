package com.residency.service;

import com.residency.db.*;
import com.residency.model.*;
import java.sql.SQLException;
import java.util.List;

/**
 * Core business logic: validate assignments against all rules,
 * and perform assignment creation/removal.
 */
public class SchedulingService {

    private final AssignmentDAO assignmentDAO;
    private final RotationDAO rotationDAO;
    private final RulesDAO rulesDAO;
    private final ResidentDAO residentDAO;

    public SchedulingService() throws SQLException {
        this.assignmentDAO = new AssignmentDAO();
        this.rotationDAO = new RotationDAO();
        this.rulesDAO = new RulesDAO();
        this.residentDAO = new ResidentDAO();
    }

    /**
     * Validate placing a resident on a rotation for a given block.
     * Does NOT save anything. Returns all rule violations found.
     */
    public ValidationResult validate(int residentId, int rotationId, int blockId, int blockNumber, int year)
            throws SQLException {

        ValidationResult result = new ValidationResult();
        Resident resident = residentDAO.getById(residentId);
        Rotation rotation = rotationDAO.getById(rotationId);

        if (resident == null || rotation == null) {
            result.addIssue(ValidationResult.Severity.ERROR, "Resident or rotation not found.", "DATA");
            return result;
        }

        // 1. Max residents per block
        int currentCount = assignmentDAO.countByRotationAndBlock(rotationId, blockId);
        if (currentCount >= rotation.getMaxResidentsPerBlock()) {
            result.addIssue(ValidationResult.Severity.WARNING,
                String.format("Rotation '%s' is at capacity (%d/%d) for Block %d.",
                    rotation.getName(), currentCount, rotation.getMaxResidentsPerBlock(), blockNumber),
                "MAX_CAPACITY");
        }

        // 2. Max blocks for this resident on this rotation
        int totalBlocksOnRotation = assignmentDAO.countBlocksByResidentAndRotation(residentId, rotationId);

        // Check PGY-specific rule first, fall back to rotation default.
        // PGY requirements (req.getMin/MaxBlocks) are already expressed in 2-week slots,
        // matching countBlocksByResidentAndRotation (which counts 2-week assignment rows).
        // Rotation defaults are entered in WEEKS, so convert weeks -> slots via ScheduleUnits.
        // NOTE: this previously divided by 4, enforcing only HALF the intended cap here while
        // CP-SAT used /2 — the two validators disagreed. See PROJECT.md Code review, H2.
        RotationRequirement req = rulesDAO.getRequirement(rotationId, resident.getPgyLevel());
        int effectiveMax = (req != null) ? (int)Math.ceil(req.getMaxBlocks()) : Math.max(1, ScheduleUnits.weeksToSlots(rotation.getMaxBlocksAllowed()));
        int effectiveMin = (req != null) ? (int)Math.ceil(req.getMinBlocks()) : Math.max(0, ScheduleUnits.weeksToSlots(rotation.getMinBlocksRequired()));

        if (totalBlocksOnRotation >= effectiveMax) {
            result.addIssue(ValidationResult.Severity.WARNING,
                String.format("%s has already reached the maximum of %d block(s) on '%s'.",
                    resident.getName(), effectiveMax, rotation.getName()),
                "MAX_BLOCKS");
        }

        // 3. Prerequisite check
        List<Prerequisite> prereqs = rulesDAO.getPrerequisitesByRotation(rotationId);
        List<Integer> completedRotations = assignmentDAO.getCompletedRotationIds(residentId);

        for (Prerequisite prereq : prereqs) {
            // Apply if no PGY filter or matches resident's PGY
            if (prereq.getPgyLevel() == null || prereq.getPgyLevel() == resident.getPgyLevel()) {
                if (!completedRotations.contains(prereq.getPrerequisiteRotationId())) {
                    Rotation prereqRotation = rotationDAO.getById(prereq.getPrerequisiteRotationId());
                    String prereqName = prereqRotation != null ? prereqRotation.getName() : "Unknown";
                    result.addIssue(ValidationResult.Severity.WARNING,
                        String.format("%s has not completed prerequisite rotation '%s' before '%s'.",
                            resident.getName(), prereqName, rotation.getName()),
                        "PREREQUISITE");
                }
            }
        }

        // 4. Sequence / adjacency rules
        List<RotationSequenceRule> sequenceRules = rulesDAO.getSequenceRulesByRotation(rotationId);
        List<Assignment> yearAssignments = assignmentDAO.getByResidentAndYear(residentId, year);
        for (RotationSequenceRule rule : sequenceRules) {
            if (rule.getPgyLevel() != null && rule.getPgyLevel() != resident.getPgyLevel()) continue;

            Rotation relatedRotation = rotationDAO.getById(rule.getRelatedRotationId());
            String relatedName = relatedRotation != null ? relatedRotation.getName() : "Unknown";

            switch (rule.getRuleType()) {
                case MUST_BE_AFTER -> {
                    boolean hasPriorRelated = yearAssignments.stream()
                        .anyMatch(a -> a.getBlockNumber() < blockNumber && a.getRotationId() == rule.getRelatedRotationId());
                    if (!hasPriorRelated) {
                        result.addIssue(ValidationResult.Severity.WARNING,
                            String.format("%s should not be placed on '%s' before completing '%s' earlier in the year.",
                                resident.getName(), rotation.getName(), relatedName),
                            "SEQUENCE_ORDER");
                    }
                }
                case CANNOT_IMMEDIATELY_FOLLOW -> {
                    boolean priorBlockIsRelated = yearAssignments.stream()
                        .anyMatch(a -> a.getBlockNumber() == blockNumber - 1 && a.getRotationId() == rule.getRelatedRotationId());
                    if (priorBlockIsRelated) {
                        result.addIssue(ValidationResult.Severity.WARNING,
                            String.format("%s should not have '%s' immediately followed by '%s'.",
                                resident.getName(), relatedName, rotation.getName()),
                            "SEQUENCE_ADJACENCY");
                    }
                }
            }
        }

        return result;
    }

    /**
     * Assign a resident to a rotation for a block.
     * Call validate() first and present warnings to the user.
     * Pass overrideWarning=true if user acknowledged warnings.
     */
    public Assignment assign(int residentId, int rotationId, int blockId, boolean overrideWarning)
            throws SQLException {
        // Remove any existing assignment for this resident/block
        assignmentDAO.deleteByResidentAndBlock(residentId, blockId);
        Assignment a = new Assignment(0, residentId, rotationId, blockId, overrideWarning);
        return assignmentDAO.insert(a);
    }

    /**
     * Remove an assignment by its ID.
     */
    public void unassign(int assignmentId) throws SQLException {
        assignmentDAO.delete(assignmentId);
    }

    /**
     * Check completion status: returns a summary of required rotations
     * not yet fulfilled by a resident across their program.
     */
    public List<String> getComplianceIssues(int residentId, int year) throws SQLException {
        Resident resident = residentDAO.getById(residentId);
        if (resident == null) return List.of();

        List<String> issues = new java.util.ArrayList<>();
        List<Rotation> allRotations = rotationDAO.getAll();
        List<Integer> completedIds = assignmentDAO.getCompletedRotationIds(residentId);

        for (Rotation rot : allRotations) {
            RotationRequirement req = rulesDAO.getRequirement(rot.getId(), resident.getPgyLevel());
            if (req == null) continue;
            if (!req.isRequired()) continue;

            int done = assignmentDAO.countBlocksByResidentAndRotation(residentId, rot.getId());
            if (done < req.getMinBlocks()) {
                issues.add(String.format("%s needs %d block(s) on '%s' (has %d)",
                    resident.getName(), req.getMinBlocks(), rot.getName(), done));
            }
        }

        return issues;
    }
}
