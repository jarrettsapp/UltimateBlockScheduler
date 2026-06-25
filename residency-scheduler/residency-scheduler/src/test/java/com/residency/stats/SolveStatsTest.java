package com.residency.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves {@link SolveStats} on fixed inputs — the "prove the math" half of the
 * one-source-two-consumers design (mirrors {@link SeedStatsTest}). No JavaFX: SolveStats is a plain
 * class. The bootstrap is pinned via a fixed RNG seed (reproducibility) plus hand-checkable
 * properties; ICC is pinned against hand-computed ANOVA values.
 */
class SolveStatsTest {

    private static final double EPS = 1e-9;

    // ── Bootstrap difference CI ────────────────────────────────────────────

    @Test
    void bootstrapDiff_pointIsExactDifferenceOfMeans() {
        double[] a = {10, 12, 14};       // mean 12
        double[] b = {20, 22, 24};       // mean 22
        SolveStats.DiffCi ci = SolveStats.bootstrapDiffCi(a, b, 2000, 12345L);
        assertEquals(10.0, ci.point, EPS, "point = mean(b) − mean(a)");
        assertTrue(ci.lo <= ci.point && ci.point <= ci.hi, "point within CI");
    }

    @Test
    void bootstrapDiff_isReproducibleForFixedSeed() {
        double[] a = {3, 5, 9, 2, 7};
        double[] b = {4, 6, 8, 3, 5};
        SolveStats.DiffCi c1 = SolveStats.bootstrapDiffCi(a, b, 3000, 999L);
        SolveStats.DiffCi c2 = SolveStats.bootstrapDiffCi(a, b, 3000, 999L);
        assertEquals(c1.lo, c2.lo, EPS, "same seed ⇒ same lower bound");
        assertEquals(c1.hi, c2.hi, EPS, "same seed ⇒ same upper bound");
    }

    @Test
    void bootstrapDiff_clearlySeparatedConfigsExcludeZero() {
        // A is uniformly worse than B with no overlap ⇒ the difference CI must exclude 0 (adopt B).
        double[] a = {20, 21, 22, 20, 21};
        double[] b = {2, 3, 2, 1, 3};
        SolveStats.DiffCi ci = SolveStats.bootstrapDiffCi(a, b, 5000, 12345L);
        assertTrue(ci.point < 0, "B clearly lower");
        assertTrue(ci.separatedFromZero(), "non-overlapping data ⇒ CI excludes 0");
        assertTrue(ci.hi < 0, "entire CI below 0");
    }

    @Test
    void bootstrapDiff_identicalConfigsDoNotSeparate() {
        // Same distribution ⇒ the difference straddles 0 (do NOT adopt — it's noise).
        double[] a = {10, 11, 9, 10, 11, 9};
        double[] b = {10, 9, 11, 10, 9, 11};
        SolveStats.DiffCi ci = SolveStats.bootstrapDiffCi(a, b, 5000, 12345L);
        assertFalse(ci.separatedFromZero(), "identical configs ⇒ CI includes 0");
    }

    @Test
    void bootstrapDiff_emptyGroupRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SolveStats.bootstrapDiffCi(new double[]{1}, new double[]{}, 100, 1L));
    }

    // ── ICC (does the starting seed predict the final metric?) ─────────────

    @Test
    void icc_perfectSeparationIsOne() {
        // Group A all 10, group B all 20: all variance is between groups ⇒ ICC = 1.
        double[][] groups = {{10, 10}, {20, 20}};
        assertEquals(1.0, SolveStats.iccOneWay(groups), EPS);
    }

    @Test
    void icc_noBetweenGroupSignalClampsToZero() {
        // Identical within-group spread, identical group means ⇒ MSB=0 < MSW ⇒ clamp to 0.
        double[][] groups = {{5, 7}, {5, 7}};
        assertEquals(0.0, SolveStats.iccOneWay(groups), EPS);
    }

    @Test
    void icc_needsTwoGroupsAndReplication() {
        assertTrue(Double.isNaN(SolveStats.iccOneWay(new double[][]{{1, 2, 3}})),
            "one group ⇒ NaN");
        assertTrue(Double.isNaN(SolveStats.iccOneWay(new double[][]{{1}, {2}})),
            "no within-group replication ⇒ NaN");
    }

    // ── Powering helpers (delegated to SeedStats — verify the delegation) ──

    @Test
    void wilsonAndPower_delegateToSeedStats() {
        assertArrayEquals(SeedStats.wilson(3, 10), SolveStats.wilson(3, 10), EPS);
        assertEquals(SeedStats.neededNForMargin(0.5, 0.10),
            SolveStats.neededNForMargin(0.5, 0.10));
    }
}
