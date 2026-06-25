#!/usr/bin/env python3
"""Compare each run's SEED baseline schedule against its FINISHED (post-P1/P2) schedule.

Answers two questions per run:
  1. How much did Phase 1/2 IMPROVE the seed?  (Δ in fragile/healthy/volunteer/…)
  2. Did P1/P2 actually MOVE the schedule, or just replay the seed?  (slots_changed = count of
     (resident, block) cells whose rotation differs between seed and final.)

Both sides are decoded to the SAME grid format and scored with the SAME score_grid() from
score_and_snapshot.py — one source of truth, no metric drift.

  * SEED  baseline  : the Phase-0 cached pool blob (schedule_config['phase0_feasible_pool_<year>']),
                      each pooled assignment = "name=val;…". We rebuild the grid from occ_r<res>_
                      s<rot>_b<block>=1 vars, and identify each pooled seed by replicating the
                      engine's seedId() = SHA-256( sorted,comma-joined names of all !=0 vars ).
  * FINAL schedule  : the run's saved version (schedule_version_assignments).

Read-only (mode=ro). Usage:
    python compare_seed_vs_final.py                       # all harvest-var-* + harvest-v2-* runs
    python compare_seed_vs_final.py --like 'harvest-v2-%' # a subset
    python compare_seed_vs_final.py --csv compare.csv     # also write a per-run CSV
"""
import sqlite3, argparse, hashlib, csv, sys, re
from score_and_snapshot import score_grid, DB

POOL_KEY = "phase0_feasible_pool_{year}"
REC_SEP = "␞"   # ␞ — same separator the engine uses between pooled assignments
OCC_RE = re.compile(r'^occ_r(\d+)_s(\d+)_b(\d+)$')


def ro(db): return sqlite3.connect(f"file:{db}?mode=ro", uri=True)


def seed_id_of(varvals):
    """Replicate CpSatSchedulerEngine.seedId(): SHA-256 of the sorted, comma-joined names of all
    vars with a non-zero value. varvals = {name: int}."""
    fp = ",".join(sorted(k for k, v in varvals.items() if v != 0))
    return hashlib.sha256(fp.encode("utf-8")).hexdigest()


def decode_pool(c, year, rot_name):
    """Return {full_seed_id: grid}, where grid[resident_id] = list[27] of rotation NAMES, built
    from the occ_*=1 vars of each pooled assignment."""
    row = c.execute("SELECT config_value FROM schedule_config WHERE config_key=?",
                    (POOL_KEY.format(year=year),)).fetchone()
    if not row:
        return {}
    out = {}
    for rec in row[0].split(REC_SEP):
        if not rec.strip():
            continue
        varvals = {}
        for tok in rec.split(";"):
            eq = tok.rfind("=")
            if eq > 0:
                try: varvals[tok[:eq]] = int(tok[eq+1:])
                except ValueError: pass
        sid = seed_id_of(varvals)
        grid = {}
        for name, val in varvals.items():
            if val == 0:
                continue
            m = OCC_RE.match(name)
            if m:
                rid, rotid, b = int(m.group(1)), int(m.group(2)), int(m.group(3))
                # block index in the seed vars is 0-based slot; the grid uses 1-based block number
                # (index w / w+1), matching schedule_version_assignments.block_number. b is 0..25.
                grid.setdefault(rid, [None]*27)[b+1] = rot_name.get(rotid)
        out[sid] = grid
    return out


def final_grid(c, version_id, rot_name):
    g = {}
    for rid, rotid, bn in c.execute(
            "SELECT resident_id,rotation_id,block_number FROM schedule_version_assignments "
            "WHERE version_id=?", (version_id,)):
        if 1 <= bn <= 26:
            g.setdefault(rid, [None]*27)[bn] = rot_name.get(rotid)
    return g


def slots_changed(seed_g, final_g, cat):
    """Count (resident, block) cells where the rotation differs between seed and final (categoricals,
    blocks 1..26). A cell present in one but not the other counts as changed."""
    n = 0
    for rid in cat:
        sg = seed_g.get(rid, [None]*27); fg = final_g.get(rid, [None]*27)
        for b in range(1, 27):
            if sg[b] != fg[b]:
                n += 1
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--like', default=None, help="config_label LIKE (default: var-* and v2-*)")
    ap.add_argument('--year', type=int, default=2)
    ap.add_argument('--csv', default=None)
    a = ap.parse_args()

    c = ro(DB).cursor()
    rot_name = {r[0]: r[1] for r in c.execute("SELECT id,name FROM rotations")}
    ress = {r[0]: r[1] for r in c.execute("SELECT id,name FROM residents WHERE is_auxiliary=0")}
    cat = set(ress)

    pool = decode_pool(c, a.year, rot_name)
    print(f"decoded {len(pool)} pooled seeds from the Phase-0 cache\n")

    if a.like:
        where = "r.config_label LIKE ?"; params = (a.like,)
    else:
        where = "(r.config_label LIKE 'harvest-var-%' OR r.config_label LIKE 'harvest-v2-%')"; params = ()
    runs = c.execute(
        f"SELECT r.id, r.config_label, r.seed_id, r.version_id FROM solve_runs r "
        f"WHERE {where} AND r.version_id IS NOT NULL ORDER BY r.id", params).fetchall()

    # solve_runs.seed_id is stored as the 8-char prefix (the engine logs seedId.substring(0,8)),
    # while pool keys are the full 64-char hash. Match by prefix.
    def find_seed(sid):
        if sid in pool:
            return pool[sid]
        hits = [k for k in pool if k.startswith(sid)]
        return pool[hits[0]] if len(hits) == 1 else None

    rows = []
    missing_seed = 0
    METRICS = ['fragile', 'healthy', 'volunteer', 'heavy_heavy', 'hm_max', 'hm_runs_ge6']
    for run_id, label, sid, vid in runs:
        seed_g = find_seed(sid) if sid else None
        if seed_g is None:
            missing_seed += 1
            continue
        fin_g = final_grid(c, vid, rot_name)
        ss = score_grid(seed_g, cat, ress)
        fs = score_grid(fin_g, cat, ress)
        chg = slots_changed(seed_g, fin_g, cat)
        rows.append((label, sid[:8], ss, fs, chg))

    if not rows:
        print("no runs matched (or none had a decodable seed in the pool).")
        if missing_seed: print(f"  ({missing_seed} runs had a seed_id not present in the current pool)")
        return

    # ---- per-run table ----
    hdr = f"{'run':28s} {'seed':8s} | {'slots_chg':9s} | " + " ".join(f"{m[:5]:>11s}" for m in METRICS)
    print(hdr); print("-"*len(hdr))
    for label, s8, ss, fs, chg in rows:
        cells = " ".join(f"{ss[m]:>4}->{fs[m]:<4}" for m in METRICS)
        print(f"{label:28s} {s8:8s} | {chg:9d} | {cells}")

    # ---- aggregate ----
    import statistics as st
    chgs = [r[4] for r in rows]
    total_cat_cells = len(cat) * 26
    print(f"\n=== AGGREGATE over {len(rows)} runs ===")
    print(f"slots_changed: min={min(chgs)} max={max(chgs)} mean={st.mean(chgs):.1f} "
          f"of {total_cat_cells} categorical cells "
          f"({100*st.mean(chgs)/total_cat_cells:.0f}% of the schedule moved on average)")
    for m in METRICS:
        d = [r[3][m]-r[2][m] for r in rows]   # final - seed
        improved = sum(1 for x in d if x < 0); worse = sum(1 for x in d if x > 0); same = sum(1 for x in d if x == 0)
        print(f"  d{m:12s} (final-seed): mean={st.mean(d):+.2f}  "
              f"improved={improved} same={same} worse={worse}  (lower=better)")
    if missing_seed:
        print(f"\nNOTE: {missing_seed} run(s) skipped — seed_id not in current pool (likely pruned).")

    if a.csv:
        with open(a.csv, 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['run', 'seed', 'slots_changed'] +
                       [f'seed_{m}' for m in METRICS] + [f'final_{m}' for m in METRICS])
            for label, s8, ss, fs, chg in rows:
                w.writerow([label, s8, chg] + [ss[m] for m in METRICS] + [fs[m] for m in METRICS])
        print(f"\nwrote {a.csv}")


if __name__ == '__main__':
    main()
