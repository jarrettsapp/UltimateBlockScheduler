#!/usr/bin/env python3
"""
Collection-cap & worker decision analysis for Phase-0 pool seeding, framed as a clinical
study (user's models: study POWER / sample size, and NUMBER NEEDED TO TREAT).

Reframing
---------
  • "Treatment"        : one Phase-0 collection run at cap C (and a given worker count).
  • "Outcome / event"  : that run yields a FEASIBLE assignment added to the pool.
  • p(C)               : P(feasible within cap C) = events / runs (a proportion).
  • NNT(C)             : runs needed per feasible entry = 1 / p(C).
  • cost(C)            : EXPECTED SECONDS PER FEASIBLE ENTRY = NNT(C) * mean_seconds_per_run.
                         (This is the quantity to MINIMIZE — it already trades wasted-cap
                          time against feasible draws a shorter cap would cut off.)

Why CIs matter (POWER): p(C) is a proportion estimated from a finite n. We report a 95%
Wilson confidence interval for p(C), propagate it to NNT and cost, and compute how many
total runs we'd need to pin p down to a target margin — i.e. whether the study is POWERED
to choose a cap, or whether we must collect more before deciding (the honest answer at small n).

A cap (or worker count) is only declared BETTER when its cost interval is SEPARATED from the
comparator's — not merely a lower point estimate. That's the discipline a single lucky run
cannot satisfy.

Usage:  python analyze_collection_cap.py [csv] [observed_cap] [candidate caps...]
"""
import sys, csv, math, statistics, random

def wilson(k, n, z=1.96):
    """95% Wilson score interval for a binomial proportion k/n (robust at small n / extremes)."""
    if n == 0: return (0.0, 0.0, 0.0)
    p = k / n
    denom = 1 + z*z/n
    centre = (p + z*z/(2*n)) / denom
    half = (z*math.sqrt(p*(1-p)/n + z*z/(4*n*n))) / denom
    return (p, max(0.0, centre - half), min(1.0, centre + half))

def needed_n_for_margin(p, margin=0.10, z=1.96):
    """Approx total runs needed so the 95% CI half-width on p is <= margin (WALD normal approx).

    Note: this is the Wald sample-size formula, looser than the Wilson interval reported elsewhere
    in this script; it is an order-of-magnitude 'are we powered?' check, not an exact bound, and it
    assumes p stays near its current estimate (which is itself uncertain at small n).
    """
    p = min(max(p, 1e-3), 1-1e-3)
    return math.ceil((z*z * p*(1-p)) / (margin*margin))

def bootstrap_cost_ci(rows_data, C, observed_cap, B=5000, pct_lo=2.5, pct_hi=97.5, seed=12345):
    """Nonparametric bootstrap of cost(C) = (mean seconds per run at cap C) / p(C).

    rows_data : list of (is_feasible: bool, time: float) for EVERY run.
    Resamples whole runs with replacement so p(C) and mean_run stay JOINTLY distributed — that
    correlation is exactly what the old analytic CI (which held mean_run fixed) ignored, making it
    too narrow. cost simplifies to total_seconds_spent / feasible_entries.
    Returns (point, lo, hi) percentile CI; lo/hi are NaN if every resample yields 0 feasible.
    """
    rng = random.Random(seed)
    n = len(rows_data)
    def cost_of(sample):
        # feasible entries within the cap
        k = sum(1 for (f, t) in sample if f and t <= C)
        if k == 0:
            return float('inf')
        # a run costs its finish time if it finished feasible within C, else the full cap C
        spent = sum((t if (f and t <= C) else C) for (f, t) in sample)
        return spent / k
    point = cost_of(rows_data)
    boots = []
    for _ in range(B):
        sample = [rows_data[rng.randrange(n)] for _ in range(n)]
        c = cost_of(sample)
        if math.isfinite(c):
            boots.append(c)
    if not boots:
        return (point, float('nan'), float('nan'))
    boots.sort()
    lo = boots[min(len(boots)-1, int(pct_lo/100 * len(boots)))]
    hi = boots[min(len(boots)-1, int(pct_hi/100 * len(boots)))]
    return (point, lo, hi)

csv_path = sys.argv[1] if len(sys.argv) > 1 else "phase0_seed_results.csv"
observed_cap = float(sys.argv[2]) if len(sys.argv) > 2 else 300.0
cands = [float(x) for x in sys.argv[3:]] or [60,90,120,150,180,210,240,300]

rows = [r for r in csv.DictReader(open(csv_path)) if r.get("run")]
feas_times = []     # times of FEASIBLE (event) runs
censor_times = []   # stop times of capped/non-feasible (censored) runs
rows_data = []      # (is_feasible, time) for EVERY run — bootstrap input
for r in rows:
    try: t = float(r["phase0_secs"])
    except: t = observed_cap
    feasible = r["phase0_status"] in ("OPTIMAL", "FEASIBLE")
    rows_data.append((feasible, t))
    if feasible:
        feas_times.append(t)
    else:
        censor_times.append(t)
n = len(rows)
n_feas = len(feas_times)
# Identifiability horizon: the largest time we observed ANYTHING at. Past this point every run's
# fate is unknown, so p(C) cannot be estimated — caps above it are unidentifiable (Bug A fix).
max_observed = max([*feas_times, *censor_times], default=observed_cap)

print(f"=== Phase-0 collection: study-powered cap analysis (n={n} runs) ===")
print(f"feasible events: {n_feas}   capped/none: {n - n_feas}")
if n == 0:
    sys.exit("no data yet")

# Overall feasible rate + CI + power check.
p, lo, hi = wilson(n_feas, n)
print(f"overall feasible rate p = {p:.0%}  (95% CI {lo:.0%}–{hi:.0%}, width {hi-lo:.0%})")
need = needed_n_for_margin(p, 0.10)
powered = "POWERED" if n >= need else f"UNDERPOWERED — need ~{need} runs total for ±10% CI"
print(f"power check (±10% margin on p, Wald approx): {powered}")
print(f"observed horizon (max time any run reached): {max_observed:.0f}s "
      f"— caps above this are UNIDENTIFIABLE (no run was observed past it)")
if feas_times:
    fs = sorted(feas_times)
    print(f"time-to-feasible (events only): min={fs[0]:.0f}s  median={statistics.median(fs):.0f}s  max={fs[-1]:.0f}s")
print()

# Per-candidate-cap NNT and cost, with CIs.
print(f"{'cap C':>6} | {'p(C)':>5} {'95% CI':>13} | {'NNT':>5} {'95% CI':>11} | "
      f"{'mean s/run':>10} | {'cost s/entry':>12} {'(boot 95% CI)':>17}")
print("-"*100)
best = None
for C in cands:
    if C > max_observed:
        # Bug A: cannot estimate p(C) past the observed horizon — refuse, don't fabricate.
        print(f"{C:6.0f} | UNIDENTIFIABLE — cap exceeds observed horizon {max_observed:.0f}s "
              f"(no run was observed past it; p(C) cannot be estimated from this data)")
        continue
    k = sum(1 for t in feas_times if t <= C)
    pc, plo, phi = wilson(k, n)
    spent = sum(t for t in feas_times if t <= C) + (n - k)*C
    mean_run = spent / n
    def nnt(x): return (1/x) if x > 0 else float('inf')
    nnt_pt, nnt_lo, nnt_hi = nnt(pc), nnt(phi), nnt(plo)   # higher p -> lower NNT
    # Bug B: cost CI via whole-run bootstrap (keeps p & mean_run correlated), not analytic.
    cost_pt, cost_lo, cost_hi = bootstrap_cost_ci(rows_data, C, observed_cap)
    if best is None or cost_pt < best[1]: best = (C, cost_pt, cost_lo, cost_hi)
    ci_p = f"({plo:.0%}-{phi:.0%})"
    ci_nnt = f"({nnt_lo:.1f}-{nnt_hi:.1f})" if math.isfinite(nnt_hi) else "(inf)"
    ci_cost = f"({cost_lo:.0f}-{cost_hi:.0f})" if math.isfinite(cost_hi) else "(inf)"
    print(f"{C:6.0f} | {pc:4.0%} {ci_p:>13} | {nnt_pt:5.1f} {ci_nnt:>11} | "
          f"{mean_run:9.0f}s | {cost_pt:10.0f}s {ci_cost:>17}")
print("-"*100)
if best:
    print(f"Lowest point-estimate cost: cap C={best[0]:.0f}s "
          f"({best[1]:.0f}s/entry, 95% CI {best[2]:.0f}-{best[3]:.0f}s).")
print()
print("DECISION RULE (clinical-trial discipline):")
print("  • Only adopt a non-default cap if its cost 95% CI does NOT overlap the comparator cap's CI")
print("    (the current default cap, when it is within the observed horizon — caps above the horizon")
print("    are UNIDENTIFIABLE here and need data collected at a longer cap to compare).")
print("  • If the study is UNDERPOWERED, collect more runs before deciding — a lower point")
print("    estimate at small n can be chance (the 'powering the study' requirement).")
print("  • NNT(C)=1/p(C) is 'runs needed per feasible entry'; cost = NNT × mean s/run is the")
print("    seconds-per-outcome to minimize. Same framework powers the worker-count comparison.")
