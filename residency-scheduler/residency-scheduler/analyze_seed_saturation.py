#!/usr/bin/env python3
"""
Seed-pool DIVERSITY-SATURATION monitor (read-only).

Reads `nn_dist_at_insert` from phase0_seed_stats (the Hamming distance from each seed to its nearest
existing pool member at the moment it was banked) in insertion order, and reports whether that
distance is TRENDING DOWN as the pool grows.

Why this, not "duplicate rate"
------------------------------
Pool dedup is EXACT-fingerprint: a seed is rejected only if bit-identical in every placement to an
existing one. With ~900 placements, exact re-discovery is astronomically unlikely, so the "duplicate
rate" (delta=0) reads ~0% essentially forever and then jumps — useless in the window that matters.
Saturation is really a GEOMETRIC PACKING problem: as the pool fills, new feasible seeds increasingly
land CLOSE to ones we already have (small nearest-neighbor distance), long before any exact duplicate
appears. A downward trend in nearest-neighbor distance = the search is crowding into already-covered
basins = saturation onset.

This script only DESCRIBES the trend. It does NOT set a "too close" threshold or change collection —
what distance counts as "too close to bother banking" is a domain judgment that interacts with the
quality (ICC) question and is deliberately deferred. (If seed identity turns out not to predict final
schedule quality, near-duplicate seeds don't matter and saturation is moot.)

NULL / -1 handling: seeds banked BEFORE this monitor existed have nn_dist_at_insert = NULL (distance
unreconstructable); the very first seed has -1 (no neighbor). Both are skipped — the trend is only
meaningful over seeds banked after the monitor went live, which is fine since saturation is
forward-looking.

READ-ONLY + run against a COPY of the DB (never the live residency_scheduler.db).

Usage:  python analyze_seed_saturation.py [db_path] [year]
"""
import sys, sqlite3, statistics

try:
    sys.stdout.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass


def main():
    db_path = sys.argv[1] if len(sys.argv) > 1 else "residency_scheduler.db"
    year = int(sys.argv[2]) if len(sys.argv) > 2 else None

    conn = sqlite3.connect(db_path)
    try:
        q = ("SELECT ordinal, nn_dist_at_insert FROM phase0_seed_stats "
             "WHERE nn_dist_at_insert IS NOT NULL AND nn_dist_at_insert >= 0")
        params = ()
        if year is not None:
            q += " AND year = ?"
            params = (year,)
        q += " ORDER BY ordinal ASC"
        rows = conn.execute(q, params).fetchall()
    except sqlite3.OperationalError as e:
        sys.exit(f"could not read phase0_seed_stats.nn_dist_at_insert ({e}); "
                 f"is this a DB with the saturation-monitor schema?")
    finally:
        conn.close()

    # Also report how many seeds were skipped (NULL = pre-monitor, or -1 = first seed) for context.
    conn = sqlite3.connect(db_path)
    try:
        total = conn.execute("SELECT COUNT(*) FROM phase0_seed_stats").fetchone()[0]
    finally:
        conn.close()

    print("=== Seed-pool diversity-saturation monitor ===")
    print(f"DB: {db_path}" + (f"  year={year}" if year is not None else "  (all years)"))
    print(f"seeds with a usable nearest-neighbor distance: {len(rows)}  "
          f"(of {total} tracked; rest are NULL/pre-monitor or the first seed)")
    if len(rows) < 3:
        sys.exit("too few monitored seeds to characterize a trend yet — keep collecting.")

    ords = [r[0] for r in rows]
    dists = [r[1] for r in rows]
    print(f"nearest-neighbor distance: min={min(dists)}  median={int(statistics.median(dists))}  "
          f"max={max(dists)}  (placements differing from closest existing seed at insertion)")

    # Simple OLS slope of distance vs. insertion ordinal. Negative slope = crowding = saturation.
    n = len(rows)
    mx = statistics.mean(ords)
    my = statistics.mean(dists)
    sxx = sum((x - mx) ** 2 for x in ords)
    sxy = sum((x - mx) * (y - my) for x, y in zip(ords, dists))
    slope = sxy / sxx if sxx > 0 else 0.0

    # Compare the first vs. last third as a robust corroboration of the slope.
    third = max(1, n // 3)
    early = statistics.median(dists[:third])
    late = statistics.median(dists[-third:])

    print(f"trend: OLS slope = {slope:+.3f} placements per seed banked")
    print(f"       early-third median dist = {early:.0f}   late-third median dist = {late:.0f}")
    print()
    if slope < -0.05 and late < early:
        print("INTERPRETATION: nearest-neighbor distance is FALLING — new seeds are landing closer to")
        print("  the existing pool = DIVERSITY SATURATION setting in. The lever (when it matters) is")
        print("  seed-EXCLUSION constraints at collection time (forbid re-finding banked seeds), NOT a")
        print("  longer cap. Do not act yet: confirm seed identity predicts quality (ICC) first.")
    elif slope > 0.05 and late > early:
        print("INTERPRETATION: distance is RISING (or noisy upward) — the feasible space is still vast")
        print("  relative to the pool; no saturation. Keep collecting normally.")
    else:
        print("INTERPRETATION: distance is roughly FLAT — no clear saturation signal yet. Re-check as")
        print("  the pool grows; saturation is forward-looking and appears later, not early.")


if __name__ == "__main__":
    main()
