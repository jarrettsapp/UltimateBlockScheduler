#!/usr/bin/env python3
"""Phase-2 → Phase-3 candidate selector.

Queries the solve_runs family and ranks Phase-2 harvest versions as candidates
for Timefold Phase-3 optimization. Prints a ranked table and optionally writes
a JSON file listing the version_ids to pass to the pipeline or to TimefoldOptimizeRunner.

This implements the "Phase 2 to Phase 3 Transition" section of the candidate-funnel
framework: since all Phase-2 schedules share Q_hard=0, ranking by hard score is
meaningless. Instead we rank by:

  PRIMARY  : fragile ASC   (fewer fragile weekends = higher coverage quality)
  SECONDARY: healthy DESC  (more healthy weekends = more robust coverage)
  TERTIARY : heavy_heavy ASC (no heavy transitions = constraint satisfaction)
  DIVERSITY: exclude near-duplicates (same seed_id already advanced = skip)

USAGE:
    python pipeline_select_candidates.py
    python pipeline_select_candidates.py --top-k 10 --output candidates.json
    python pipeline_select_candidates.py --top-k 15 --epoch post_fix_seeded
    python pipeline_select_candidates.py --show-all   # show all harvest versions, not just top-K

OUTPUTS:
    Stdout  : ranked table of candidates
    JSON    (--output PATH) : list of {version_id, seed_id, fragile, healthy, ...}
"""
from __future__ import annotations
import argparse, json, os, sqlite3, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
DB   = os.path.join(ROOT, 'residency_scheduler.db')


def query_harvest_versions(db_path: str, epoch: str, top_k: int | None,
                            show_all: bool) -> list[dict]:
    """Return Phase-2 harvest versions ranked by fragile/healthy/hh."""
    con = sqlite3.connect(db_path, timeout=10)
    try:
        limit_clause = '' if show_all else f'LIMIT {top_k * 3}'  # overfetch for diversity filtering
        rows = con.execute(f"""
            SELECT
                sr.id           AS run_id,
                sr.version_id,
                sr.seed_id,
                sr.run_at,
                sr.config_label,
                srm.volunteer,
                srm.fragile,
                srm.healthy,
                srm.heavy_heavy,
                srm.hm_max_stretch,
                srm.runs_gt6wk
            FROM solve_runs sr
            JOIN solve_run_metrics srm ON srm.run_id = sr.id
            WHERE sr.run_status = 'PHASE2_FALLBACK'
              AND sr.year = 2
              AND sr.version_id IS NOT NULL
              AND srm.fragile IS NOT NULL
              AND (? = '*' OR sr.data_epoch = ?)
            ORDER BY
                srm.fragile     ASC,
                srm.healthy     DESC,
                srm.heavy_heavy ASC,
                sr.id           DESC
            {limit_clause}
        """, (epoch, epoch)).fetchall()

        cols = ['run_id','version_id','seed_id','run_at','config_label',
                'volunteer','fragile','healthy','heavy_heavy','hm_max_stretch','runs_gt6wk']
        return [dict(zip(cols, r)) for r in rows]
    finally:
        con.close()


def already_timefold_optimized(db_path: str) -> set[str]:
    """Return set of seed_id prefixes (8-char) that have already been sent to Timefold.
    TimefoldOptimizeRunner hardcodes config_label='timefold-opt' and p3_status='TIMEFOLD'."""
    con = sqlite3.connect(db_path, timeout=10)
    try:
        rows = con.execute("""
            SELECT DISTINCT seed_id FROM solve_runs
            WHERE (config_label = 'timefold-opt' OR p3_status = 'TIMEFOLD')
              AND year = 2
              AND seed_id IS NOT NULL
        """).fetchall()
        return {r[0][:8] for r in rows if r[0]}
    except Exception:
        return set()
    finally:
        con.close()


def select_diverse(rows: list[dict], top_k: int) -> list[dict]:
    """From the ranked list, select top_k while preferring diverse seeds.
    Rule: if a seed_id is already represented in the selection, skip subsequent
    versions from the same seed unless we have no other options."""
    selected   = []
    seen_seeds = set()
    deferred   = []

    for row in rows:
        if len(selected) >= top_k:
            break
        seed = (row.get('seed_id') or '')[:8]
        if seed and seed in seen_seeds:
            deferred.append(row)
            continue
        selected.append(row)
        if seed:
            seen_seeds.add(seed)

    # Fill remaining slots with deferred (same-seed) rows if needed
    for row in deferred:
        if len(selected) >= top_k:
            break
        selected.append(row)

    return selected


def print_table(rows: list[dict], title: str = 'Phase-2 Candidates') -> None:
    print(f'\n{"="*70}')
    print(f'  {title}')
    print(f'{"="*70}')
    hdr = f'{"#":>3}  {"version":>7}  {"seed":>8}  {"vol":>3}  {"frag":>4}  {"heal":>4}  {"h→h":>3}  {"hm_max":>6}  run_at'
    print(hdr)
    print('-' * len(hdr))
    for i, r in enumerate(rows, 1):
        seed_short = (r.get('seed_id') or '')[:8]
        print(f'{i:>3}  {r["version_id"]:>7}  {seed_short:>8}  '
              f'{str(r.get("volunteer","?")):>3}  {str(r.get("fragile","?")):>4}  '
              f'{str(r.get("healthy","?")):>4}  {str(r.get("heavy_heavy","?")):>3}  '
              f'{str(r.get("hm_max_stretch","?")):>6}  '
              f'{(r.get("run_at") or "")[:19]}')
    print(f'{"="*70}\n')


def main() -> int:
    ap = argparse.ArgumentParser(
        description='Rank Phase-2 harvest versions as candidates for Timefold Phase-3 optimization')
    ap.add_argument('--db',      default=DB, help='SQLite DB path')
    ap.add_argument('--top-k',   type=int, default=10,
                    help='Number of candidates to select (default: 10)')
    ap.add_argument('--epoch',   default='post_fix_seeded',
                    help='data_epoch filter (default: post_fix_seeded; use * for all)')
    ap.add_argument('--show-all', action='store_true',
                    help='Show ALL harvest versions, not just the top-K diverse selection')
    ap.add_argument('--output',  default=None,
                    help='Write selected candidates to this JSON file')
    ap.add_argument('--no-diversity', action='store_true',
                    help='Skip diversity filtering (allow multiple versions from same seed)')
    args = ap.parse_args()

    if not os.path.exists(args.db):
        print(f'ERROR: DB not found at {args.db}', file=sys.stderr)
        return 1

    all_rows = query_harvest_versions(args.db, args.epoch, args.top_k, args.show_all)
    if not all_rows:
        print('No Phase-2 harvest versions found in solve_runs.')
        print(f'  (epoch={args.epoch!r}, run_status=PHASE2_FALLBACK, year=2)')
        return 0

    already_done = already_timefold_optimized(args.db)

    if args.show_all:
        print_table(all_rows, f'All Phase-2 harvest versions (epoch={args.epoch!r})')
        print(f'Total: {len(all_rows)} versions')
        return 0

    # Mark versions whose seed has already been through Timefold
    for row in all_rows:
        row['already_p3'] = (row.get('seed_id') or '')[:8] in already_done

    # Select diverse top-K
    if args.no_diversity:
        selected = all_rows[:args.top_k]
    else:
        selected = select_diverse(all_rows, args.top_k)

    print_table(selected, f'Top-{args.top_k} Phase-2 Candidates for Timefold (epoch={args.epoch!r})')

    already_flagged = [r for r in selected if r.get('already_p3')]
    if already_flagged:
        print(f'NOTE: {len(already_flagged)} candidate(s) from seeds already sent to Timefold:')
        for r in already_flagged:
            print(f'  version={r["version_id"]} seed={r.get("seed_id","")[:8]}')
        print()

    print(f'Summary:')
    print(f'  Total Phase-2 harvest versions available: {len(all_rows)}')
    print(f'  Selected for Phase-3:                    {len(selected)}')
    frag_vals = [r.get("fragile") for r in selected if r.get("fragile") is not None]
    if frag_vals:
        print(f'  Fragile range (selected):                {min(frag_vals)} – {max(frag_vals)}')
    heal_vals = [r.get("healthy") for r in selected if r.get("healthy") is not None]
    if heal_vals:
        print(f'  Healthy range (selected):                {min(heal_vals)} – {max(heal_vals)}')
    unique_seeds = len({(r.get("seed_id") or "")[:8] for r in selected})
    print(f'  Distinct seeds represented:              {unique_seeds}')
    print()

    if args.output:
        out = [
            {'version_id': r['version_id'], 'seed_id': r.get('seed_id', ''),
             'fragile': r.get('fragile'), 'healthy': r.get('healthy'),
             'volunteer': r.get('volunteer'), 'heavy_heavy': r.get('heavy_heavy')}
            for r in selected
        ]
        with open(args.output, 'w', encoding='utf-8') as f:
            json.dump(out, f, indent=2)
        print(f'Candidates written to: {args.output}')
        print(f'To run Timefold on each:')
        for item in out:
            print(f'  java -cp <cp> com.residency.tools.TimefoldOptimizeRunner 2 {item["version_id"]} 300')

    return 0


if __name__ == '__main__':
    sys.exit(main())
