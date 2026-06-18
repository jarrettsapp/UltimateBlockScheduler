package com.residency.model;

/**
 * Single source of truth for the project's time-unit conversions.
 *
 * <h2>Why this class exists</h2>
 * The codebase has historically used the word "block" to mean two different
 * durations depending on the layer, which produced a class of silent unit bugs
 * (see REVIEW.md, findings H1/H2). This class fixes the vocabulary in one place
 * so every conversion is explicit and verifiable.
 *
 * <h2>Canonical unit model</h2>
 * <ul>
 *   <li><b>Slot</b> — the atomic scheduling unit = <b>2 calendar weeks</b>.
 *       The solver grid is 26 slots per academic year (52 weeks). Internally
 *       these are the "blocks" that {@code VariableFactory},
 *       {@code BlockExpansionService} and {@code ConstraintBuilder} index over,
 *       and they are what the UI labels 1A, 1B, 2A, 2B … 13A, 13B.</li>
 *   <li><b>Full block</b> — a 4-week clinical block = <b>2 slots</b>
 *       ({@code allowedBlockLengths} value {@code 2}).</li>
 *   <li><b>Half block</b> — a 2-week clinical block = <b>1 slot</b>
 *       ({@code allowedBlockLengths} value {@code 1}).</li>
 * </ul>
 *
 * <h2>Field unit note</h2>
 * {@code Rotation.minBlocksRequired} / {@code Rotation.maxBlocksAllowed} are
 * entered through the Rotations tab in <b>weeks</b> (the spinners and table
 * columns read "Min Weeks" / "Max Weeks", stepping by 2). To turn those
 * week values into the solver's slot grid, divide by {@link #WEEKS_PER_SLOT}.
 * Always route that conversion through {@link #weeksToSlots(int)} rather than
 * hand-writing {@code / 2} (or, as several call sites incorrectly did,
 * {@code / 4}).
 */
public final class ScheduleUnits {

    private ScheduleUnits() {}

    /** Calendar weeks in one scheduling slot. A slot is the atomic 2-week unit. */
    public static final int WEEKS_PER_SLOT = 2;

    /** Slots in one academic year (52 weeks / 2). */
    public static final int SLOTS_PER_YEAR = 26;

    /** Slots in a full 4-week clinical block. */
    public static final int SLOTS_PER_FULL_BLOCK = 2;

    /**
     * Convert a week count (as entered on the Rotations tab) into scheduling
     * slots, rounding up so a partial slot still grants a whole slot of head-room.
     *
     * @param weeks a non-negative number of calendar weeks
     * @return the equivalent number of 2-week slots (ceiling)
     */
    public static int weeksToSlots(int weeks) {
        if (weeks <= 0) return 0;
        return (weeks + WEEKS_PER_SLOT - 1) / WEEKS_PER_SLOT;
    }

    /** Convert a slot count back into calendar weeks (exact). */
    public static int slotsToWeeks(int slots) {
        return slots * WEEKS_PER_SLOT;
    }
}
