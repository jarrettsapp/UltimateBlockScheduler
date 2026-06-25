package com.residency.stats;

import java.util.Random;

/**
 * Small-sample statistics for the REAL-SOLVE data layer — the analogue of {@link SeedStats} one
 * layer up. Pure functions (no DB, no UI) so they unit-test without JavaFX and are reused
 * identically by the Python analyses' Java-side checks.
 *
 * <p><b>One source, two consumers:</b> this is the only copy of the bootstrap diff-CI / powering
 * math for real solves; {@code analyze_config_compare.py} mirrors {@link #bootstrapDiffCi} and
 * {@code SolveStatsTest} pins it against fixed inputs. The bootstrap uses the SAME percentile-index
 * convention and {@link Random} seeding as {@code analyze_collection_cap.bootstrap_cost_ci} so the
 * Java and Python implementations agree run-for-run on a fixed seed.
 *
 * <p>The core decision question: <em>is config B's metric reliably different from config A's, across
 * solver noise?</em> Answered by resampling WHOLE runs (preserving within-config correlation) and
 * reporting a percentile CI on the difference of means. Adopt B over A only if that CI excludes 0 —
 * the same non-overlap rule the seed cap analysis uses to avoid chasing noise.
 */
public final class SolveStats {

    private SolveStats() {}

    /** Result of a bootstrap difference CI: point estimate (meanB − meanA) and percentile bounds. */
    public static final class DiffCi {
        public final double point, lo, hi;
        public DiffCi(double point, double lo, double hi) { this.point = point; this.lo = lo; this.hi = hi; }
        /** True iff the CI excludes 0 (the adopt rule): the difference is separated from noise. */
        public boolean separatedFromZero() { return (lo > 0 && hi > 0) || (lo < 0 && hi < 0); }
    }

    /**
     * Nonparametric bootstrap of the difference in means (B − A) between two configs' per-run metric
     * samples. Resamples each group's whole runs with replacement (independently), so each group's
     * mean carries its own sampling variability. Mirrors the percentile convention of the Python
     * {@code bootstrap_cost_ci}: sort the {@code B} bootstrap diffs and index at
     * {@code int(pct/100 * len)} (clamped to len−1).
     *
     * @param a       config-A per-run metric values (≥1).
     * @param b       config-B per-run metric values (≥1).
     * @param iters   bootstrap resamples (e.g. 5000).
     * @param seed    RNG seed (fixed ⇒ reproducible, matching the Python default 12345).
     * @return {@link DiffCi} with point = mean(b) − mean(a) and the 2.5/97.5 percentile bounds.
     */
    public static DiffCi bootstrapDiffCi(double[] a, double[] b, int iters, long seed) {
        return bootstrapDiffCi(a, b, iters, seed, 2.5, 97.5);
    }

    public static DiffCi bootstrapDiffCi(double[] a, double[] b, int iters, long seed,
                                         double pctLo, double pctHi) {
        if (a.length == 0 || b.length == 0)
            throw new IllegalArgumentException("both groups need ≥1 run");
        double point = mean(b) - mean(a);
        Random rng = new Random(seed);
        double[] boots = new double[iters];
        for (int i = 0; i < iters; i++) {
            double sa = resampleMean(a, rng);
            double sb = resampleMean(b, rng);
            boots[i] = sb - sa;
        }
        java.util.Arrays.sort(boots);
        double lo = boots[Math.min(boots.length - 1, (int) (pctLo / 100.0 * boots.length))];
        double hi = boots[Math.min(boots.length - 1, (int) (pctHi / 100.0 * boots.length))];
        return new DiffCi(point, lo, hi);
    }

    /**
     * One-way ANOVA intraclass correlation: the fraction of total variance in a metric explained by
     * group membership (here: starting {@code seed_id}). ICC ≈ 0 ⇒ the seed does not predict the
     * final metric (solver noise dominates → seed exploit/prune is not yet justified); ICC near 1 ⇒
     * the seed strongly determines the outcome. Uses the standard ANOVA estimator
     * {@code (MSB − MSW) / (MSB + (n0 − 1) MSW)} with the unbalanced-group {@code n0} correction;
     * clamped to [0,1]. Returns {@code NaN} if fewer than 2 groups or no within-group replication.
     *
     * @param groups ragged array: {@code groups[g]} = the metric values of the runs sharing seed g.
     */
    public static double iccOneWay(double[][] groups) {
        int k = 0, nTotal = 0;
        double grand = 0;
        for (double[] g : groups) { if (g.length > 0) { k++; nTotal += g.length; for (double v : g) grand += v; } }
        if (k < 2 || nTotal <= k) return Double.NaN; // need ≥2 groups and ≥1 replicate overall
        grand /= nTotal;

        double ssBetween = 0, ssWithin = 0, sumNiSq = 0;
        for (double[] g : groups) {
            if (g.length == 0) continue;
            double gm = mean(g);
            ssBetween += g.length * (gm - grand) * (gm - grand);
            for (double v : g) ssWithin += (v - gm) * (v - gm);
            sumNiSq += (double) g.length * g.length;
        }
        double msBetween = ssBetween / (k - 1);
        double msWithin  = ssWithin / (nTotal - k);
        // n0: average group size with the bias correction for unequal group sizes.
        double n0 = (nTotal - sumNiSq / nTotal) / (k - 1);
        if (msWithin == 0 && msBetween == 0) return 0.0;
        double icc = (msBetween - msWithin) / (msBetween + (n0 - 1) * msWithin);
        return Math.max(0.0, Math.min(1.0, icc));
    }

    /**
     * 95% Wilson score interval for k/n (delegates to {@link SeedStats#wilson} — one copy of the
     * formula). Provided here so the real-solve analyses don't reach across to the seed package.
     */
    public static double[] wilson(int k, int n) {
        return SeedStats.wilson(k, n);
    }

    /** Approx runs needed for a ±margin 95% CI half-width on a proportion (delegates to SeedStats). */
    public static int neededNForMargin(double p, double margin) {
        return SeedStats.neededNForMargin(p, margin);
    }

    public static double mean(double[] x) {
        double s = 0; for (double v : x) s += v; return s / x.length;
    }

    private static double resampleMean(double[] x, Random rng) {
        double s = 0;
        for (int i = 0; i < x.length; i++) s += x[rng.nextInt(x.length)];
        return s / x.length;
    }
}
