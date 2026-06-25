#!/usr/bin/env python3
"""Backfill the heavy+medium stretch metrics (hm_max_stretch / hm_runs_ge6wk / hm_total_wk) onto
solve_run_metrics rows that predate the metric (the first 3-seed x 10-run variance experiment).

Those rows were scored before score_and_snapshot.py captured HM stretches, so the columns are NULL.
But each run saved a full schedule VERSION (schedule_version_assignments, 348 rows), so we can
recompute the metrics from the committed schedule — no re-solving needed.

CRITICAL: the computation here MUST match score_and_snapshot.py exactly (same HEAVY/MEDIUM sets,
same 1-based block indexing, same consecutive-run loop with weeks=(j-i)*2), so backfilled rows are
directly comparable to live-recorded rows. We import the sets from the script to guarantee that.

Usage:
    python backfill_hm_metrics.py --like 'harvest-var-%' --dry-run   # show what WOULD change
    python backfill_hm_metrics.py --like 'harvest-var-%'             # apply the UPDATEs
"""
import sqlite3, argparse, sys
from collections import Counter
from score_and_snapshot import HEAVY, MEDIUM, DB   # reuse the authoritative tier sets

def hm_metrics_for_version(c, version_id, cat_ids, blocknum_by_rotrow):
    """Recompute (hm_max, hm_runs_gt4, hm_total, histogram) for one saved version, using the SAME
    stretch logic as score_and_snapshot.main(): per categorical resident, walk slots 1..26, find
    maximal heavy/medium runs, length in weeks = (j-i)*2."""
    # Build resident -> [None]*27 grid of rotation NAMES (1-based block index), categoricals only.
    g = {}
    rows = c.execute(
        "SELECT resident_id, rotation_id, block_number FROM schedule_version_assignments "
        "WHERE version_id=?", (version_id,)).fetchall()
    for rid, rotid, bn in rows:
        if rid in cat_ids and 1 <= bn <= 26:
            g.setdefault(rid, [None]*27)[bn] = blocknum_by_rotrow.get(rotid)
    def t(r): return 'HM' if r in HEAVY or r in MEDIUM else 'L'
    stretches = []
    for rid in cat_ids:
        gg = g.get(rid, [None]*27); i = 1
        while i <= 26:
            if t(gg[i]) == 'HM':
                j = i+1
                while j <= 26 and t(gg[j]) == 'HM': j += 1
                stretches.append((j-i)*2)
                i = j
            else:
                i += 1
    hm_max = max(stretches) if stretches else 0
    hm_runs_ge6 = sum(1 for w in stretches if w >= 6)   # 6+ week runs (0 today under the hard cap)
    hm_total = sum(stretches)
    return hm_max, hm_runs_ge6, hm_total, dict(sorted(Counter(stretches).items()))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--like', default='harvest-var-%', help="config_label LIKE pattern to backfill")
    ap.add_argument('--dry-run', action='store_true')
    a = ap.parse_args()

    con = sqlite3.connect(DB)
    c = con.cursor()
    rots = {r[0]: r[1] for r in c.execute('SELECT id,name FROM rotations')}
    cat_ids = set(r[0] for r in c.execute('SELECT id FROM residents WHERE is_auxiliary=0'))

    targets = c.execute(
        "SELECT r.id, r.config_label, r.version_id FROM solve_runs r "
        "JOIN solve_run_metrics m ON m.run_id=r.id "
        "WHERE r.config_label LIKE ? AND m.hm_runs_ge6wk IS NULL AND r.version_id IS NOT NULL "
        "ORDER BY r.id", (a.like,)).fetchall()
    if not targets:
        print(f"nothing to backfill for LIKE {a.like!r} (no rows with NULL hm + a version_id)")
        return
    print(f"{'DRY-RUN: ' if a.dry_run else ''}backfilling {len(targets)} row(s) matching {a.like!r}\n")

    updated = 0
    for run_id, label, vid in targets:
        hm_max, hm_ge6, hm_total, hist = hm_metrics_for_version(c, vid, cat_ids, rots)
        print(f"  run {run_id:3d} {label:28s} v{vid}: "
              f"hm_max={hm_max} runs>=6={hm_ge6} total={hm_total}  hist={hist}")
        if not a.dry_run:
            c.execute("UPDATE solve_run_metrics SET hm_max_stretch=?, hm_runs_ge6wk=?, hm_total_wk=? "
                      "WHERE run_id=?", (hm_max, hm_ge6, hm_total, run_id))
            updated += 1
    if a.dry_run:
        print("\n(dry run — no writes)")
    else:
        con.commit()
        print(f"\nupdated {updated} row(s); integrity:",
              c.execute('PRAGMA integrity_check').fetchone()[0])

if __name__ == '__main__':
    main()
