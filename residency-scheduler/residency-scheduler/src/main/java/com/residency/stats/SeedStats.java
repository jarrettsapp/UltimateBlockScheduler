package com.residency.stats;

/**
 * Small-sample statistics for the seed-pool reward record, ported verbatim (in behavior) from
 * {@code analyze_collection_cap.py}. Pure functions — no DB, no UI — so they can be unit-tested
 * without starting JavaFX and reused identically by {@code SeedPoolView}.
 *
 * <p><b>One source, two consumers:</b> this is the ONLY copy of the Wilson-CI / powering math.
 * {@code SeedPoolView} calls it live on every load/refresh (so the displayed intervals are always
 * recomputed from whatever is in the DB right then — nothing cached); {@code SeedStatsTest} calls
 * the same methods directly to prove the math. Do not copy these formulas into the view.
 *
 * <p>Note: NNT = 1/p and cost = NNT × mean-seconds (also in the Python) are about Phase-0
 * <em>collection</em> throughput, not per-seed reward, so they are intentionally NOT ported here —
 * they belong to the collection-cap analysis tools, not the per-seed quality display.
 */
public final class SeedStats {

    private SeedStats() {}

    private static final double Z = 1.96; // 95%

    /**
     * 95% Wilson score interval for a binomial proportion k/n (robust at small n / extremes).
     * Returns {p, lo, hi}, clamped to [0,1]; {0,0,0} when n == 0. Mirrors the Python {@code wilson}.
     */
    public static double[] wilson(int k, int n) {
        if (n == 0) return new double[]{0.0, 0.0, 0.0};
        double p = (double) k / n;
        double denom = 1 + Z * Z / n;
        double centre = (p + Z * Z / (2.0 * n)) / denom;
        double half = (Z * Math.sqrt(p * (1 - p) / n + Z * Z / (4.0 * n * n))) / denom;
        return new double[]{p, Math.max(0.0, centre - half), Math.min(1.0, centre + half)};
    }

    /**
     * Approx total runs needed so the 95% CI half-width on p is &lt;= {@code margin} (Wald normal
     * approx). Order-of-magnitude "are we powered?" check; assumes p stays near its current estimate.
     * Mirrors the Python {@code needed_n_for_margin} with margin = 0.10.
     */
    public static int neededNForMargin(double p, double margin) {
        double pc = Math.min(Math.max(p, 1e-3), 1 - 1e-3);
        return (int) Math.ceil((Z * Z * pc * (1 - pc)) / (margin * margin));
    }

    /** Convenience overload with the standard ±10% margin used throughout. */
    public static int neededNForMargin(double p) {
        return neededNForMargin(p, 0.10);
    }
}
