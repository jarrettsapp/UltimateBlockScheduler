package com.residency.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the week <-> slot conversion that several call sites historically got
 * wrong (some divided weeks by 2, others by 4). See PROJECT.md (Code review, H1/H2).
 *
 * The canonical rule: 1 slot = 2 weeks. A rotation's max/min are entered in
 * weeks; the solver works in slots; weeks -> slots is divide-by-2 (round up).
 */
class ScheduleUnitsTest {

    @Test
    void weeksToSlots_convertsWholeBlocks() {
        assertEquals(0, ScheduleUnits.weeksToSlots(0));
        assertEquals(1, ScheduleUnits.weeksToSlots(2),  "2 weeks = 1 slot (half block)");
        assertEquals(2, ScheduleUnits.weeksToSlots(4),  "4 weeks = 2 slots (full block)");
        assertEquals(4, ScheduleUnits.weeksToSlots(8),  "default max of 8 weeks = 4 slots");
        assertEquals(26, ScheduleUnits.weeksToSlots(52), "a full year = 26 slots");
    }

    @Test
    void weeksToSlots_roundsPartialSlotUp() {
        // Odd week counts shouldn't be entered via the UI (step is 2), but the
        // helper must still grant a whole slot rather than truncating to zero.
        assertEquals(1, ScheduleUnits.weeksToSlots(1));
        assertEquals(2, ScheduleUnits.weeksToSlots(3));
    }

    @Test
    void weeksToSlots_neverNegative() {
        assertEquals(0, ScheduleUnits.weeksToSlots(-4));
    }

    @Test
    void slotsToWeeks_isInverseOfWholeBlocks() {
        assertEquals(8, ScheduleUnits.slotsToWeeks(4));
        assertEquals(52, ScheduleUnits.slotsToWeeks(ScheduleUnits.SLOTS_PER_YEAR));
    }

    @Test
    void regression_maxBlocksAllowedNoLongerHalvedByFour() {
        // A rotation entered as "8 weeks max" must cap at 4 slots, NOT 2.
        // The /4 bug (SchedulingService, Timefold) produced 2 here, disagreeing
        // with CP-SAT's /2. This guards against that regression.
        int maxWeeksEntered = 8;
        assertEquals(4, ScheduleUnits.weeksToSlots(maxWeeksEntered));
        assertNotEquals(maxWeeksEntered / 4, ScheduleUnits.weeksToSlots(maxWeeksEntered));
    }

    // ── blocksToSlots: per-rotation requirement conversion (RULES.md §13, B1) ──

    @Test
    void blocksToSlots_convertsClinicalBlocksToSlots() {
        // RotationRequirement.minBlocks is in 4-week clinical blocks:
        //   0.5 block = one 2-week slot, 1 block = two slots, 2 blocks = four slots.
        assertEquals(0, ScheduleUnits.blocksToSlots(0.0));
        assertEquals(1, ScheduleUnits.blocksToSlots(0.5), "half block = 1 slot");
        assertEquals(2, ScheduleUnits.blocksToSlots(1.0), "one full block = 2 slots");
        assertEquals(3, ScheduleUnits.blocksToSlots(1.5));
        assertEquals(4, ScheduleUnits.blocksToSlots(2.0), "VA's 2-block requirement = 4 slots");
        assertEquals(0, ScheduleUnits.blocksToSlots(-1.0));
    }

    @Test
    void regression_requirementMinNoLongerUnderEnforced() {
        // The old formula ceil(minBlocks)*minLen under-enforced wherever a 2-week
        // segment was allowed (minLen=1): VA's 2-block requirement yielded only 2
        // slots instead of 4, and a 1-block requirement yielded 1 instead of 2.
        // blocksToSlots must give the correct value regardless of segment length.
        assertEquals(4, ScheduleUnits.blocksToSlots(2.0), "VA must require 4 slots, not 2");
        assertEquals(2, ScheduleUnits.blocksToSlots(1.0), "Outpatient GI must require 2 slots, not 1");
        // The buggy formula for minBlocks=2, minLen=1 produced 2 — assert we beat it.
        assertNotEquals((int) Math.ceil(2.0) * 1, ScheduleUnits.blocksToSlots(2.0));
    }
}
