package com.residency.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link SeedStats} matches the Python reference in {@code analyze_collection_cap.py} on
 * fixed inputs. This is the "prove the math" half of the one-source-two-consumers design; it does
 * NOT require JavaFX because {@code SeedStats} is a plain class.
 */
class SeedStatsTest {

    private static final double EPS = 1e-9;

    @Test
    void wilson_typicalProportion_matchesPython() {
        // Python reference (analyze_collection_cap.wilson(3,10)): p=0.30, lo=0.1077893, hi=0.6032268
        double[] w = SeedStats.wilson(3, 10);
        assertEquals(0.30, w[0], EPS, "point estimate");
        assertEquals(0.1077893, w[1], 1e-6, "Wilson lower");
        assertEquals(0.6032268, w[2], 1e-6, "Wilson upper");
    }

    @Test
    void wilson_extremeZeroSuccesses_staysInBounds() {
        // Python reference (wilson(0,5)): p=0.0, lo=0.0 (clamped), hi=0.4344915
        double[] w = SeedStats.wilson(0, 5);
        assertEquals(0.0, w[0], EPS);
        assertEquals(0.0, w[1], EPS, "lower clamped to 0");
        assertEquals(0.4344915, w[2], 1e-6, "Wilson upper at k=0");
        assertTrue(w[2] <= 1.0 && w[1] >= 0.0, "interval within [0,1]");
    }

    @Test
    void wilson_zeroN_returnsZeros() {
        assertArrayEquals(new double[]{0.0, 0.0, 0.0}, SeedStats.wilson(0, 0), EPS);
    }

    @Test
    void neededNForMargin_matchesPython() {
        // Python: needed_n_for_margin(0.75, 0.10) = ceil(1.96^2 * 0.75*0.25 / 0.01) = ceil(72.03) = 73
        assertEquals(73, SeedStats.neededNForMargin(0.75, 0.10));
        // p = 0.5 is the worst case (max variance): ceil(1.96^2 * 0.25 / 0.01) = ceil(96.04) = 97
        assertEquals(97, SeedStats.neededNForMargin(0.5));
    }

    @Test
    void neededNForMargin_clampsExtremeP() {
        // p clamped to [1e-3, 1-1e-3] so p=0 or p=1 don't yield 0 runs needed.
        assertTrue(SeedStats.neededNForMargin(0.0) > 0, "p=0 must clamp, not return 0");
        assertEquals(SeedStats.neededNForMargin(0.0), SeedStats.neededNForMargin(1.0),
                "symmetric clamp at both extremes");
    }
}
