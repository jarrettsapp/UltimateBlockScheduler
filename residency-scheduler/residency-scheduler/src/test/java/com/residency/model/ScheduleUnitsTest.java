package com.residency.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the week <-> slot conversion that several call sites historically got
 * wrong (some divided weeks by 2, others by 4). See REVIEW.md findings H1/H2.
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
}
