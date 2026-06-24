#!/usr/bin/env python3
"""
Phase-0 collection-cap analysis via SURVIVAL ANALYSIS (Kaplan–Meier), the rigorous successor to the
grid-based analyze_collection_cap.py.

Why survival analysis
---------------------
Each collection run measures a TIME-TO-EVENT: the seconds until a feasible Phase-0 seed is found.
A run that hits the cap is RIGHT-CENSORED — we only learn "time-to-feasible > cap", not its value.
The old grid tool collapses this continuous, censored time into a binary "feasible within C?" at a
few arbitrary caps C, discarding most of the information we paid (in solver-seconds) to measure, and
treating censored runs naively.

Kaplan–Meier estimates the whole survival curve S(t) = P(time-to-feasible > t) from the event times
AND the censored runs, correctly. From one KM fit we get, for every t at once:
  • p(C) = 1 − S(C)                    (feasible rate at any cap, not just grid points)
  • cost(C) = E[min(T,C)] / p(C)       (expected seconds per feasible entry — the throughput metric)
  • the hazard trend                   ("doomed vs unlucky": is a still-running search hopeless?)

KM is UNDEFINED past the largest observed time, so it AUTOMATICALLY refuses to report caps beyond the
data's horizon — the principled version of the grid tool's "UNIDENTIFIABLE" guard.

Framing (don't oversell): KM makes the cap choice RIGOROUS, not a plot twist. If the data's max
feasible time is ~150s, KM confirms a ~90–150s cap with a proper CI and shows a 300s cap is wasteful —
it converts a defensible eyeball call into a powered one.

Assumption: NON-INFORMATIVE CENSORING. Holds here because the cap is a fixed wall-clock independent of
search state (hitting the cap says nothing about the would-be finish time beyond "> cap").

Dependencies: PURE STDLIB (math/csv/sys/statistics) — no numpy, no lifelines. Chosen deliberately:
this repo runs unattended solver sweeps for days, so a missing/mis-pinned package surfacing mid-sweep
is the failure mode to avoid. KM + Greenwood is ~40 lines and textbook. (lifelines would make it a
3-line KaplanMeierFitter call with plotting for free; revisit it only if/when survival REGRESSION —
Cox/AFT to compare worker counts or configs — is needed, which is a separate, deferred question.)

Usage:  python analyze_cap_survival.py [csv] [observed_cap] [candidate caps...]
        CSV columns: run, phase0_status, phase0_secs   (status OPTIMAL/FEASIBLE = event; else censored)
"""
import sys, csv, math, statistics

# This tool prints a few non-ASCII glyphs (curve labels, ≈, etc.). On a Windows cp1252 console that
# raises UnicodeEncodeError; force UTF-8 so the report renders identically on every platform.
try:
    sys.stdout.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass


def kaplan_meier(observations):
    """Kaplan–Meier estimate with Greenwood variance.

    observations : list of (time, event) with event in {0,1}; 1 = feasible found at `time`,
                   0 = censored (capped) at `time` (time-to-feasible > time).
    Returns (curve, horizon) where curve is a list of (t, S_t, var_greenwood) at each DISTINCT
    EVENT time, and horizon = max observed time (S undefined past it).
    """
    obs = sorted(observations, key=lambda x: x[0])
    event_times = sorted({t for (t, e) in obs if e == 1})
    horizon = max((t for (t, _) in obs), default=0.0)
    S = 1.0
    cum_var_sum = 0.0          # running sum d_i / (n_i (n_i - d_i)) for Greenwood
    curve = []
    for t in event_times:
        n_i = sum(1 for (tt, _) in obs if tt >= t)             # at risk just before t
        d_i = sum(1 for (tt, e) in obs if tt == t and e == 1)  # events at t
        if n_i == 0:
            break
        S *= (1.0 - d_i / n_i)
        if n_i - d_i > 0:
            cum_var_sum += d_i / (n_i * (n_i - d_i))
        var = (S * S) * cum_var_sum                            # Greenwood's formula
        curve.append((t, S, var))
    return curve, horizon


def km_ci_at(S_t, var_t, z=1.96):
    """Log-log transformed 95% CI for S(t) (keeps the band inside [0,1]; standard for KM tails)."""
    if S_t <= 0.0 or S_t >= 1.0 or var_t <= 0.0:
        return (max(0.0, S_t), min(1.0, S_t))
    se_logS = math.sqrt(var_t) / S_t          # delta-method SE of log S
    c = z * se_logS / math.log(S_t)           # log-log pivot
    lo = S_t ** math.exp(+abs(c))
    hi = S_t ** math.exp(-abs(c))
    return (max(0.0, lo), min(1.0, hi))


def S_at(curve, C):
    """S(C) from the right-continuous KM step function: S at the largest event time <= C."""
    S_C = 1.0
    for (t, S, _) in curve:
        if t <= C:
            S_C = S
        else:
            break
    return S_C


def cost_at_cap(curve, horizon, C):
    """cost(C) = E[min(T,C)] / p(C), with p(C)=1−S(C). Refuses C > horizon (unidentifiable).

    E[min(T,C)] = ∫_0^C S(t) dt  (expected-value-of-censored-time identity), approximated over the
    KM step function up to C.
    """
    if C > horizon:
        return None
    S_C = S_at(curve, C)
    pC = 1.0 - S_C
    if pC <= 0:
        return float('inf')
    area, prev_t, prev_S = 0.0, 0.0, 1.0
    for (t, S, _) in curve:
        seg_end = min(t, C)
        area += prev_S * (seg_end - prev_t)
        prev_t, prev_S = seg_end, S
        if t >= C:
            break
    if prev_t < C:                            # tail from last event time to C at current S
        area += prev_S * (C - prev_t)
    mean_run = area                           # = E[min(T, C)]
    return mean_run / pC


def hazard_trend(observations):
    """Describe whether the per-event-time hazard d_i/n_i trends DOWN (late runs increasingly
    hopeless → cap aggressively) or stays roughly FLAT (a long-running search is just unlucky →
    a longer cap may still pay). Returns a one-line human-readable string."""
    obs = sorted(observations, key=lambda x: x[0])
    event_times = sorted({t for (t, e) in obs if e == 1})
    haz = []
    for t in event_times:
        n_i = sum(1 for (tt, _) in obs if tt >= t)
        d_i = sum(1 for (tt, e) in obs if tt == t and e == 1)
        if n_i > 0:
            haz.append(t and d_i / n_i)
    if len(haz) < 3:
        return "hazard: too few events to characterize the trend"
    first_half = statistics.mean(haz[:len(haz)//2])
    second_half = statistics.mean(haz[len(haz)//2:])
    if second_half > first_half * 1.3:
        return ("hazard RISING — events cluster late; a longer cap keeps paying off "
                "(long-running searches are still likely to succeed)")
    if second_half < first_half * 0.7:
        return ("hazard FALLING — late searches increasingly hopeless; cap aggressively "
                "(a run still going past the median is unlikely to finish)")
    return "hazard ~FLAT — roughly constant per-second chance; a long-running search is just unlucky"


def main():
    csv_path = sys.argv[1] if len(sys.argv) > 1 else "phase0_seed_results.csv"
    observed_cap = float(sys.argv[2]) if len(sys.argv) > 2 else 300.0
    cands = [float(x) for x in sys.argv[3:]] or [60, 90, 120, 150, 180, 210, 240, 300]

    rows = [r for r in csv.DictReader(open(csv_path)) if r.get("run")]
    observations = []
    n_events = 0
    for r in rows:
        try:
            t = float(r["phase0_secs"])
        except (KeyError, ValueError, TypeError):
            t = observed_cap
        event = 1 if r["phase0_status"] in ("OPTIMAL", "FEASIBLE") else 0
        observations.append((t, event))
        n_events += event
    n = len(observations)

    print(f"=== Phase-0 collection: Kaplan–Meier cap analysis (n={n} runs) ===")
    print(f"feasible events: {n_events}   censored (capped/none): {n - n_events}")
    if n == 0:
        sys.exit("no data yet")

    curve, horizon = kaplan_meier(observations)
    print(f"observed horizon (max time any run reached): {horizon:.0f}s "
          f"— S(t) and cost(C) are UNDEFINED past it")
    print()

    # KM curve table.
    print("Kaplan–Meier survival curve  S(t) = P(time-to-feasible > t):")
    print(f"{'t (s)':>7} | {'S(t)':>6} | {'p(t)=1-S':>9} | {'95% CI on S(t)':>18}")
    print("-" * 50)
    for (t, S, var) in curve:
        lo, hi = km_ci_at(S, var)
        print(f"{t:7.0f} | {S:6.2f} | {1 - S:9.2f} | {('(' + format(lo, '.2f') + '-' + format(hi, '.2f') + ')'):>18}")
    print("-" * 50)
    print(hazard_trend(observations))
    print()

    # Per-candidate cap: p(C), mean s/run, cost s/entry.
    print(f"{'cap C':>6} | {'p(C)':>5} | {'mean s/run':>10} | {'cost s/entry':>12}")
    print("-" * 48)
    best = None
    for C in cands:
        cost = cost_at_cap(curve, horizon, C)
        if cost is None:
            print(f"{C:6.0f} | UNIDENTIFIABLE — cap exceeds observed horizon {horizon:.0f}s")
            continue
        pC = 1.0 - S_at(curve, C)
        mean_run = cost * pC if math.isfinite(cost) else float('nan')
        if best is None or (math.isfinite(cost) and cost < best[1]):
            best = (C, cost)
        cost_str = f"{cost:10.0f}s" if math.isfinite(cost) else "       inf"
        print(f"{C:6.0f} | {pC:4.0%} | {mean_run:9.0f}s | {cost_str}")
    print("-" * 48)

    # Continuous cost minimum: scan 1s steps across the horizon. Start only once a non-trivial
    # fraction of runs has finished (p(C) >= MIN_P), so the minimum isn't latched onto a single
    # lucky-fast event where p is estimated from one observation (a small-n artifact, not a real
    # optimum). MIN_P = 0.25 by default.
    MIN_P = 0.25
    if curve:
        scan = []
        c = curve[0][0]
        while c <= horizon:
            pC = 1.0 - S_at(curve, c)
            cost = cost_at_cap(curve, horizon, c)
            if pC >= MIN_P and cost is not None and math.isfinite(cost):
                scan.append((c, cost))
            c += 1.0
        if scan:
            c_star, cost_star = min(scan, key=lambda x: x[1])
            print(f"Continuous cost minimum (over caps with p(C)>={MIN_P:.0%}): "
                  f"cap C* ~= {c_star:.0f}s  ({cost_star:.0f}s per feasible entry).")
        else:
            print(f"Continuous cost minimum: no cap reaches p(C)>={MIN_P:.0%} within the horizon "
                  f"— collect more data before choosing a cap.")
    if best:
        print(f"Best candidate cap (from the list): C = {best[0]:.0f}s ({best[1]:.0f}s/entry).")
    print()
    print("READING THIS:")
    print("  • p(C)=1−S(C) and cost(C) come straight off the KM curve — every cap at once, censored")
    print("    runs handled correctly. Caps past the horizon are refused (undefined), not guessed.")
    print("  • The continuous C* is the throughput-optimal cap; confirm a candidate's cost CI is")
    print("    SEPARATED from the comparator before adopting it (collect more data if they overlap).")
    print("  • cost optimizes THROUGHPUT (seconds per feasible seed), NOT seed QUALITY. Whether a")
    print("    shorter cap discards slow-to-find seeds that yield BETTER schedules is the separate")
    print("    ICC/quality question — keep the two independent.")


if __name__ == "__main__":
    main()
