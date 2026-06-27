#!/usr/bin/env python3
"""Candidate-Funnel pipeline evaluator — one report over all funnel data.

Implements the Candidate-Funnel Framework (see CANDIDATE_FUNNEL_FRAMEWORK / CANDIDATE_FUNNEL_PLAN.md)
as concrete measurements over the pipeline's recorded data. The goal: run the pipeline, then run
this, and get an informative readout of where the funnel stands and whether more computation is
likely to help.

It answers, from the data actually in the DB (+ tf_time_to_best.csv):

  PHASE 0   structural diversity of the seed pool on the OCC grid (clean 286-cell Hamming),
            marginal-diversity-gain δ, and a DOWNSTREAM-derived saturation floor (the seed distance
            below which downstream Phase-2 quality differences are noise) (framework Exp E).
  PHASE 1/2 eligibility summary (all Q_hard=0) + reproducibility / change-from-seed if available.
  P2 -> P3  starting-soft-score predictivity: Spearman corr(start,final) plus the variance of the
            FINAL — the real question is whether the final floor is seed-independent (Experiment A).
  PHASE 3   plateau / time-to-best. Prefers solve_run_trajectory (true no-improvement window, KM
            stop time); falls back to tf_time_to_best.csv. Plus within-input variance across the
            10 starts of each multistart run (Experiment B + C).
  FINALISTS score-gap (ε_F) + diversity (ε_D, OCC-grid Hamming) greedy finalist set over Phase-3
            outputs, with a tradeoff-spread readout; target |F| ~= 30-50 (Experiment D / §9).
  VERDICT   a defensible stopping statement assembled from the above.

Soft cost = the schedule_versions.tier3_score (Timefold's own soft objective) wherever a version
exists — ONE source of truth. For Phase-2 starts (no tier3 yet) we reconstruct a rank-equivalent
proxy from fragile/volunteer/healthy.

Read-only on the DB (mode=ro). Usage:

    python evaluate_pipeline_data.py                 # full human report, year 2 (internal year)
    python evaluate_pipeline_data.py --year 2
    python evaluate_pipeline_data.py --json out.json # also emit machine-readable metrics
    python evaluate_pipeline_data.py --sample-seeds 80   # cap P0 pairwise diversity sample (speed)
    python evaluate_pipeline_data.py --eps-f 100 --eps-d 30   # finalist thresholds (tier3 / cells)
"""
from __future__ import annotations
import argparse, csv, json, math, os, re, sqlite3, statistics, sys
from collections import defaultdict

# Windows consoles default to cp1252; force UTF-8 so box-drawing / Greek glyphs render.
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
DB   = os.path.join(ROOT, "residency_scheduler.db")
TTB_CSV = os.path.join(ROOT, "tf_time_to_best.csv")

# Proxy weights for the Phase-2 STARTING soft score only (no tier3 exists pre-Phase-3). Verified
# rank-equivalent (Spearman 1.0) to the real tier3_score on the current 41 Phase-3 versions, so it
# is safe for RANKING the Phase-2 starts; absolute values are not on the tier3 scale.
W_FRAGILE   = 1000
W_VOLUNTEER = 100
W_HEALTHY   = 13

TF_NAME_RE = re.compile(r"tf-opt-from-v(\d+)-")
OCC_RE     = re.compile(r"occ_r(\d+)_s(\d+)_b(\d+)")


def ro(db: str) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{db}?mode=ro", uri=True)


def start_proxy_cost(fragile: int, volunteer: int, healthy: int) -> int:
    """Rank-equivalent proxy for a Phase-2 starting soft score (no tier3 available pre-Phase-3)."""
    return W_FRAGILE * fragile + W_VOLUNTEER * volunteer - W_HEALTHY * healthy


# ---- correlation helpers -------------------------------------------------------------------------
def _pearson(xs, ys):
    n = len(xs)
    if n < 3:
        return None
    mx, my = statistics.mean(xs), statistics.mean(ys)
    num = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    dx = sum((x - mx) ** 2 for x in xs); dy = sum((y - my) ** 2 for y in ys)
    if dx == 0 or dy == 0:
        return None
    return num / (dx * dy) ** 0.5


def _rankdata(vals):
    """Average ranks (handles ties) — for Spearman."""
    order = sorted(range(len(vals)), key=lambda i: vals[i])
    ranks = [0.0] * len(vals)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and vals[order[j + 1]] == vals[order[i]]:
            j += 1
        avg = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            ranks[order[k]] = avg
        i = j + 1
    return ranks


def _spearman(xs, ys):
    if len(xs) < 3:
        return None
    return _pearson(_rankdata(xs), _rankdata(ys))


# ---- OCC-grid distance (the single, correct structural distance basis) ---------------------------
def occ_grid_from_blob(assignment: str) -> dict:
    """{(resident, block): rotation} from a stored seed blob's occ_*=1 vars. Fixed 286-cell grid,
    exactly one rotation per (resident, block) → |A△B|/2 == true Hamming over differing cells."""
    g = {}
    for tok in assignment.split(";"):
        if tok.endswith("=1") and tok.startswith("occ_r"):
            m = OCC_RE.match(tok[:-2])
            if m:
                res, rot, blk = m.groups()
                g[(res, blk)] = rot
    return g


def occ_grid_from_version(con, version_id: int) -> dict:
    """{(resident, block): rotation} from schedule_version_assignments — SAME basis as seed grids."""
    g = {}
    for res, blk, rot in con.execute(
        "SELECT resident_id, block_number, rotation_id FROM schedule_version_assignments "
        "WHERE version_id=?", (version_id,)
    ):
        g[(res, blk)] = rot
    return g


def grid_hamming(g1: dict, g2: dict) -> int:
    """Count of (resident, block) cells whose rotation differs. Keys are identical across schedules
    (every resident has every block), so this is a true Hamming distance."""
    keys = g1.keys() | g2.keys()
    return sum(1 for k in keys if g1.get(k) != g2.get(k))


# --------------------------------------------------------------------------------------------------
# PHASE 0 — seed-pool diversity (OCC grid) + downstream-derived saturation floor
# --------------------------------------------------------------------------------------------------
def phase0_diversity(con, year: int, sample: int) -> dict:
    rows = con.execute(
        "SELECT seed_id, created_at, assignment FROM phase0_seed_assignments "
        "WHERE year=? ORDER BY created_at, seed_id", (year,)
    ).fetchall()
    n = len(rows)
    out: dict = {"seed_count": n, "sampled": 0, "basis": "occ-grid Hamming (286 cells)"}
    if n < 2:
        out["note"] = "fewer than 2 seeds — diversity undefined"
        return out

    use = rows if (sample <= 0 or n <= sample) else rows[:sample]
    grids = [(occ_grid_from_blob(a), sid) for sid, _ts, a in use]
    out["sampled"] = len(grids)

    # marginal diversity gain δ_i (insertion order) on the occ grid
    deltas = []
    for i in range(1, len(grids)):
        gi = grids[i][0]
        deltas.append(min(grid_hamming(gi, grids[j][0]) for j in range(i)))
    out["marginal_gain_recent10"] = (round(statistics.mean(deltas[-10:]), 1) if deltas else None)

    # full pairwise stats on the sample
    pair = []
    for i in range(len(grids)):
        for j in range(i + 1, len(grids)):
            pair.append(grid_hamming(grids[i][0], grids[j][0]))
    if pair:
        out["pairwise_mean"] = round(statistics.mean(pair), 1)
        out["pairwise_min"] = min(pair)
        out["pairwise_max"] = max(pair)

    # engine's own δ from phase0_seed_stats (different, full-var basis — reported, not mixed)
    eng = [r[0] for r in con.execute(
        "SELECT nn_dist_at_insert FROM phase0_seed_stats WHERE year=? AND nn_dist_at_insert >= 0 "
        "ORDER BY ordinal", (year,)).fetchall()]
    if eng:
        out["engine_nn_dist_recent10"] = round(statistics.mean(eng[-10:]), 1)

    # DOWNSTREAM-derived saturation floor: regress |Δ downstream fragile| on seed-pair distance.
    # ε_D := the seed distance below which downstream quality differences are essentially noise.
    out["saturation"] = _downstream_saturation(con, year, grids)
    return out


def _downstream_saturation(con, year: int, grids) -> dict:
    """For seed pairs that BOTH produced a Phase-2 harvest, relate structural seed distance to the
    absolute difference in downstream fragile. If close seeds → near-zero downstream Δ, that gives a
    principled ε_D. Returns the correlation and a suggested floor."""
    # downstream fragile (Phase-2 harvest) keyed by the 8-char seed prefix that solve_runs stores,
    # since phase0_seed_assignments keeps the full 64-char hash. Join on the shared prefix.
    dmap = {}
    for sid, frag in con.execute(
        "SELECT sr.seed_id, m.fragile FROM solve_runs sr JOIN solve_run_metrics m ON m.run_id=sr.id "
        "WHERE sr.run_status='PHASE2_FALLBACK' AND sr.year=? AND m.fragile IS NOT NULL", (year,)):
        dmap[sid[:8]] = frag
    have = [(g, sid) for g, sid in grids if sid[:8] in dmap]
    out = {"pairs_with_downstream": len(have)}
    if len(have) < 5:
        out["note"] = "too few seeds with a downstream Phase-2 result to derive ε_D empirically"
        return out
    dists, dq = [], []
    for i in range(len(have)):
        for j in range(i + 1, len(have)):
            dists.append(grid_hamming(have[i][0], have[j][0]))
            dq.append(abs(dmap[have[i][1][:8]] - dmap[have[j][1][:8]]))
    rho = _spearman(dists, dq)
    out["corr_seeddist_vs_downstream_delta"] = round(rho, 3) if rho is not None else None
    # if more distance → more downstream difference (rho>0), a reasonable ε_D is the 10th-percentile
    # pair distance: below it, seeds are "close" and we check whether downstream Δ there is small.
    sd = sorted(dists)
    p10 = sd[max(0, int(0.10 * len(sd)) - 1)]
    out["eps_d_floor_p10_dist"] = p10
    return out


# --------------------------------------------------------------------------------------------------
# PHASE 1/2 — eligibility, spread, reproducibility / change-from-seed
# --------------------------------------------------------------------------------------------------
def phase12_summary(con, year: int) -> dict:
    rows = con.execute("""
        SELECT sr.id, sr.version_id, sr.seed_id, sr.p1_secs, sr.p2_secs,
               m.fragile, m.healthy, m.volunteer, m.heavy_heavy
        FROM solve_runs sr JOIN solve_run_metrics m ON m.run_id = sr.id
        WHERE sr.run_status = 'PHASE2_FALLBACK' AND sr.year = ?
          AND sr.version_id IS NOT NULL AND m.fragile IS NOT NULL
    """, (year,)).fetchall()
    out: dict = {"count": len(rows), "distinct_seeds": len({r[2] for r in rows})}
    if not rows:
        return out
    frag = [r[5] for r in rows]; heal = [r[6] for r in rows]; vol = [r[7] for r in rows]
    hh = [r[8] for r in rows]; p12 = [(r[3] or 0) + (r[4] or 0) for r in rows]
    out["fragile_range"] = (min(frag), max(frag)); out["fragile_median"] = statistics.median(frag)
    out["healthy_range"] = (min(heal), max(heal))
    out["volunteer_range"] = (min(vol), max(vol)); out["heavy_heavy_max"] = max(hh)
    out["runtime_p12_median_s"] = round(statistics.median(p12), 1) if any(p12) else None
    out["runtime_p12_max_s"] = round(max(p12), 1) if any(p12) else None

    # reproducibility: seeds harvested more than once → within-seed fragile spread
    by_seed = defaultdict(list)
    for r in rows:
        by_seed[r[2]].append(r[5])
    repeated = {s: v for s, v in by_seed.items() if len(v) > 1}
    if repeated:
        within = [statistics.pstdev(v) for v in repeated.values()]
        out["repeated_seeds"] = len(repeated)
        out["within_seed_fragile_sd_mean"] = round(statistics.mean(within), 2)
    else:
        out["repeated_seeds"] = 0
        out["repro_note"] = ("each seed harvested once → within-seed reproducibility not measurable "
                             "here (see the 99-run ICC study: fragile 0.978)")
    out["_runs"] = [
        {"version_id": r[1], "seed_id": r[2], "fragile": r[5], "healthy": r[6],
         "volunteer": r[7], "start_cost": start_proxy_cost(r[5], r[7], r[6])}
        for r in rows
    ]
    return out


# --------------------------------------------------------------------------------------------------
# PHASE 2 -> PHASE 3 — does the start predict the final? (Spearman + final variance)
# --------------------------------------------------------------------------------------------------
def p2_to_p3(con, year: int, p12_runs: list) -> dict:
    p2_by_version = {r["version_id"]: r for r in p12_runs}
    p3_rows = con.execute(
        "SELECT id, name, tier1_score, tier3_score FROM schedule_versions "
        "WHERE schedule_year=? AND name LIKE 'tf-opt-from-v%' ORDER BY id", (year,)
    ).fetchall()

    pairs = []
    for vid, name, hard, soft in p3_rows:
        if soft is None:
            continue
        m = TF_NAME_RE.search(name)
        if not m:
            continue
        src = int(m.group(1))
        p2 = p2_by_version.get(src)
        if not p2:
            continue
        pairs.append({
            "p3_version": vid, "p2_version": src, "seed_id": p2["seed_id"],
            "start_fragile": p2["fragile"], "final_fragile": _p3_final_fragile(con, vid),
            "start_cost": p2["start_cost"], "final_cost": soft,   # final_cost = real tier3
        })

    out: dict = {"matched": len(pairs)}
    if not pairs:
        out["note"] = "no Phase-3 outputs matched to a Phase-2 starter"
        return out

    starts = [p["start_cost"] for p in pairs]; finals = [p["final_cost"] for p in pairs]
    rho_sf = _spearman(starts, finals)
    out["spearman_start_vs_final"] = round(rho_sf, 3) if rho_sf is not None else None
    out["final_cost_range"] = (min(finals), max(finals))
    out["final_cost_median"] = statistics.median(finals)
    # THE real signal: how constant is the final? coefficient of variation of the final cost.
    fmean = statistics.mean(finals)
    out["final_cost_sd"] = round(statistics.pstdev(finals), 1)
    out["final_cost_cv"] = round(statistics.pstdev(finals) / fmean, 3) if fmean else None
    ffrag = [p["final_fragile"] for p in pairs if p["final_fragile"] is not None]
    out["final_fragile_distinct"] = sorted(set(ffrag))
    out["all_final_fragile_zero"] = bool(ffrag) and all(f == 0 for f in ffrag)
    out["pairs"] = pairs

    rho = out["spearman_start_vs_final"]; cv = out["final_cost_cv"]
    near_constant_final = cv is not None and cv < 0.5 and len(set(finals)) <= max(3, len(finals) // 5)
    if rho is not None and rho > 0.5:
        out["interpretation"] = ("case (1): better Phase-2 start ranks better Phase-3 final "
                                 f"(Spearman {rho}) — keep starting soft score as the PRIMARY "
                                 "advancement criterion.")
    elif near_constant_final or (rho is not None and abs(rho) < 0.3):
        out["interpretation"] = (
            "case (2)/(3): the Phase-3 FINAL is nearly seed-independent "
            f"(final SD {out['final_cost_sd']}, CV {cv}; Spearman(start,final) {rho}). "
            "Phase 3 converges to a similar floor wherever it starts, so the starting score carries "
            "little selection value — ADVANCE BY DIVERSITY (α>0, β≈0 in A_i), not by start score.")
    else:
        out["interpretation"] = "relationship mixed — collect more matched pairs before deciding."
    return out


def _p3_final_fragile(con, version_id: int):
    r = con.execute(
        "SELECT m.fragile FROM solve_run_metrics m JOIN solve_runs sr ON sr.id=m.run_id "
        "WHERE sr.version_id=?", (version_id,)).fetchone()
    return r[0] if r else None


# --------------------------------------------------------------------------------------------------
# CONVERGENCE PROOF — has the BEST-so-far plateaued, and is the ceiling seed-limited or objective-
# limited? This is the core of the "best possible" stopping claim. (framework §13: P(better remains
# undiscovered) < α). It does NOT measure diversity — it measures whether more runs/seeds would beat
# the current best Phase-3 objective.
# --------------------------------------------------------------------------------------------------
def convergence_proof(con, year: int, tier_band: float = 10.0) -> dict:
    """Best-so-far analysis over all hard-valid Phase-3 outputs, in chronological (version id) order.

    Reports:
      * the best tier3 and how many DISTINCT seeds independently reached it (reproducible ceiling?),
      * the records curve (how many times best-so-far improved) and runs-since-last-improvement,
      * a rule-of-three style upper bound on p = P(a single new run beats the current best),
      * the seed-limited-vs-objective-limited verdict: if the best was reached by ≥2 distinct seeds
        and many runs since have failed to beat it, the ceiling is set by the OBJECTIVE/constraints,
        so more seed-gen/harvest will NOT help — only changing the objective/constraints can.
    """
    rows = con.execute("""
        SELECT sv.id, sv.tier3_score, sr.seed_id, m.fragile, m.volunteer, m.healthy
        FROM schedule_versions sv
        JOIN solve_runs sr ON sr.version_id = sv.id
        JOIN solve_run_metrics m ON m.run_id = sr.id
        WHERE sv.schedule_year=? AND sv.name LIKE 'tf-opt-from-v%'
          AND sv.tier1_score=0 AND sv.tier3_score IS NOT NULL
        ORDER BY sv.id
    """, (year,)).fetchall()
    out: dict = {"runs": len(rows)}
    if len(rows) < 3:
        out["note"] = "fewer than 3 Phase-3 outputs — convergence undefined"
        return out

    costs = [r[1] for r in rows]
    best = min(costs)
    out["best_tier3"] = best
    out["tier_band"] = tier_band

    # how many DISTINCT seeds reached the best TIER (within tier_band of the exact best). Slightly
    # different tier3 values that share the same (fragile,volunteer,healthy) profile are the same
    # quality outcome — the tie-break weighting differs by a few points, not the schedule's merit.
    at_best_tier = [r for r in rows if r[1] <= best + tier_band]
    best_seeds = {r[2][:8] for r in at_best_tier}
    best_profiles = {(r[3], r[4], r[5]) for r in at_best_tier}
    out["runs_at_best"] = len(at_best_tier)
    out["distinct_seeds_at_best"] = len(best_seeds)
    out["best_profile"] = sorted(best_profiles)  # (fragile,volunteer,healthy) at the top tier

    # records curve + runs since last MEANINGFUL improvement (chronological). A run "improves" only
    # if it beats the running best by more than tier_band — points inside the band are the same tier.
    running, records, last_improve_idx = float("inf"), 0, 0
    curve = []
    for i, c in enumerate(costs):
        if c < running - tier_band:
            records += 1; last_improve_idx = i
        running = min(running, c)
        curve.append(running)
    out["record_improvements"] = records
    out["runs_since_last_improvement"] = (len(costs) - 1) - last_improve_idx
    out["best_so_far_curve"] = curve

    # Rule-of-three-style bound: with k consecutive non-improving runs and none better, the 95% upper
    # confidence bound on p (chance a fresh run beats the best tier) is ~ 3/k (binomial, 0/k).
    k = out["runs_since_last_improvement"]
    out["p_better_next_run_ub95"] = round(3.0 / k, 3) if k >= 1 else None
    # expected future improvements from N more independent runs (records-process heuristic): if the
    # last record took k runs to NOT recur, the per-run improvement hazard is ≲ 1/k.
    out["expected_improvements_per_10_runs"] = round(10.0 / k, 2) if k >= 1 else None

    # seed-limited vs objective-limited verdict
    repro_ceiling = out["distinct_seeds_at_best"] >= 2
    plateaued = k >= 10
    if repro_ceiling and plateaued:
        out["ceiling_type"] = "OBJECTIVE-LIMITED"
        out["verdict"] = (
            f"The best objective ({best}) was reached independently by "
            f"{out['distinct_seeds_at_best']} distinct seeds, and {k} runs since have not beaten it "
            f"(95% UB on the chance a new run beats it: {out['p_better_next_run_ub95']}). The ceiling "
            "is set by the OBJECTIVE/constraint geometry, not by which seed you start from — so MORE "
            "seed generation or harvest will keep re-hitting this ceiling, NOT exceed it. To go lower "
            "you must change the objective or constraints (e.g. the fragile=1 trade test).")
    elif repro_ceiling and not plateaued:
        out["ceiling_type"] = "LIKELY-OBJECTIVE-LIMITED (need a few more runs)"
        out["verdict"] = (
            f"The best ({best}) is reproducible across {out['distinct_seeds_at_best']} seeds, but only "
            f"{k} runs have passed since the last improvement — keep running until the plateau is "
            "≥10 runs to firm up the bound.")
    elif not repro_ceiling and plateaued:
        out["ceiling_type"] = "POSSIBLY SEED-LIMITED"
        out["verdict"] = (
            f"The best ({best}) was hit by only ONE seed and has held for {k} runs. It may be a lucky "
            "seed rather than a hard ceiling — generate/ harvest more seeds and watch whether another "
            "seed matches it (would confirm objective-limited) or beats it (was seed-limited).")
    else:
        out["ceiling_type"] = "NOT YET CONVERGED"
        out["verdict"] = (f"Only {k} runs since the last improvement and best hit by "
                          f"{out['distinct_seeds_at_best']} seed(s) — too early to claim a ceiling.")
    return out


# --------------------------------------------------------------------------------------------------
# COVERAGE DEPTH — the healthy=N count is a CEILING, but two schedules at the same count differ in
# DEPTH (a weekend with 4 coverers is far more robust than one at the bare minimum of 2). A depth-2
# weekend is "fragile-adjacent": lose one coverer and it drops to fragile. This module reads the
# per-weekend coverer counts (solve_run_weekend) for the best corner and asks: among schedules that
# tie on the healthy COUNT, how deep / how evenly distributed is the coverage, and has DEPTH itself
# converged or is it still improving with more runs?
# --------------------------------------------------------------------------------------------------
def coverage_depth(con, year: int) -> dict:
    """Depth analysis over the top corner (lowest-fragile, highest-healthy production Phase-3 runs).

    For each such run we read its per-weekend coverer counts and compute:
      * depth histogram (how many weekends at 0/1/2/3/4+ coverers),
      * depth-2 count = "fragile-adjacent" healthy weekends (one slip from fragile; lower is better),
      * mean & min depth over the HEALTHY weekends (≥2 coverers),
      * an evenness read (are extra coverers concentrated or spread),
      * a best-so-far convergence on depth-2 count (does adding runs keep reducing fragile-adjacency,
        or has depth plateaued like the count did?).
    """
    # the best corner = min fragile, then max healthy among production Phase-3 runs
    corner = con.execute("""
        SELECT MIN(m.fragile) FROM solve_runs sr
        JOIN schedule_versions sv ON sv.id=sr.version_id
        JOIN solve_run_metrics m ON m.run_id=sr.id
        WHERE sv.name LIKE 'tf-opt-from-v%' AND sv.tier1_score=0 AND sv.tier3_score IS NOT NULL
          AND sv.schedule_year=?
    """, (year,)).fetchone()
    if not corner or corner[0] is None:
        return {"note": "no production Phase-3 runs with metrics"}
    best_frag = corner[0]
    best_heal = con.execute("""
        SELECT MAX(m.healthy) FROM solve_runs sr
        JOIN schedule_versions sv ON sv.id=sr.version_id
        JOIN solve_run_metrics m ON m.run_id=sr.id
        WHERE sv.name LIKE 'tf-opt-from-v%' AND sv.tier1_score=0 AND sv.tier3_score IS NOT NULL
          AND sv.schedule_year=? AND m.fragile=?
    """, (year, best_frag)).fetchone()[0]

    rows = con.execute("""
        SELECT sr.id, sv.id, sv.tier3_score
        FROM solve_runs sr
        JOIN schedule_versions sv ON sv.id=sr.version_id
        JOIN solve_run_metrics m ON m.run_id=sr.id
        WHERE sv.name LIKE 'tf-opt-from-v%' AND sv.tier1_score=0 AND sv.tier3_score IS NOT NULL
          AND sv.schedule_year=? AND m.fragile=? AND m.healthy=?
        ORDER BY sv.id
    """, (year, best_frag, best_heal)).fetchall()
    out: dict = {"corner": f"fragile={best_frag}, healthy={best_heal}",
                 "runs_at_corner": len(rows)}
    if not rows:
        out["note"] = "no runs at the best corner"
        return out

    per_run = []
    for run_id, vid, t3 in rows:
        cov = [c[0] for c in con.execute(
            "SELECT coverers FROM solve_run_weekend WHERE run_id=? ORDER BY weekend_index",
            (run_id,)).fetchall()]
        if not cov:
            continue
        healthy_depths = [x for x in cov if x >= 2]
        hist = {d: sum(1 for x in cov if x == d) for d in range(0, max(cov) + 1)}
        d2 = sum(1 for x in healthy_depths if x == 2)
        per_run.append({
            "version": vid, "tier3": t3,
            "depth2_count": d2,
            "mean_healthy_depth": round(statistics.mean(healthy_depths), 3) if healthy_depths else 0,
            "min_healthy_depth": min(healthy_depths) if healthy_depths else 0,
            "max_depth": max(cov),
            "hist": hist,
        })
    if not per_run:
        out["note"] = "corner runs have no per-weekend coverer rows (solve_run_weekend empty for them)"
        return out

    out["per_run"] = per_run
    d2s = [r["depth2_count"] for r in per_run]
    means = [r["mean_healthy_depth"] for r in per_run]
    out["depth2_count_range"] = (min(d2s), max(d2s))     # fragile-adjacent count (lower=better)
    out["mean_depth_range"] = (min(means), max(means))   # robustness (higher=better)
    out["max_depth_seen"] = max(r["max_depth"] for r in per_run)
    # the best-on-depth run = fewest depth-2 (least fragile-adjacent), tie-break by highest mean
    best = min(per_run, key=lambda r: (r["depth2_count"], -r["mean_healthy_depth"]))
    out["best_depth_version"] = best["version"]
    out["best_depth"] = best

    # has DEPTH converged? best-so-far depth-2 over chronological corner runs (lower is better)
    running, last_improve = 10**9, 0
    for i, r in enumerate(sorted(per_run, key=lambda r: r["version"])):
        if r["depth2_count"] < running:
            running = r["depth2_count"]; last_improve = i
    k = (len(per_run) - 1) - last_improve
    out["runs_since_depth_improved"] = k
    out["best_depth2_so_far"] = running

    n = len(per_run)
    if n < 5:
        out["depth_verdict"] = (
            f"Only {n} run(s) sit at the best corner ({out['corner']}), so DEPTH is barely sampled. "
            "The healthy COUNT is a ceiling, but within it the best schedule has "
            f"{best['depth2_count']} of {best_heal} healthy weekends at the bare minimum (depth-2, "
            f"fragile-adjacent), mean depth {best['mean_healthy_depth']}, max depth "
            f"{out['max_depth_seen']}. Worth running MORE Phase-3 to populate this corner and see "
            "whether depth-2 count keeps dropping (improvable) or plateaus (depth is also capacity-"
            "limited). The current objective already rewards depth (tier3 tracks it), so just more "
            "runs — no objective change needed to TEST it.")
    elif k >= 10:
        out["depth_verdict"] = (
            f"Depth has CONVERGED: best is {running} fragile-adjacent (depth-2) weekends, unbeaten "
            f"for {k} corner runs. Coverage depth is also capacity-limited — more runs won't deepen "
            "it. Only a stronger depth reward (TF_RDEPTH/TF_RBASE) or more coverers could.")
    else:
        out["depth_verdict"] = (
            f"Depth still improving: best {running} depth-2 weekends, only {k} corner runs since the "
            "last depth improvement — keep running to confirm the depth floor.")
    return out


# --------------------------------------------------------------------------------------------------
# PHASE 3 — plateau (trajectory preferred) + time-to-best + within-input variance
# --------------------------------------------------------------------------------------------------
def phase3_plateau(con, year: int, csv_path: str, plateau_eps: float, plateau_window_s: float) -> dict:
    out: dict = {}
    # --- preferred: real no-improvement-window analysis from solve_run_trajectory ---
    traj_runs = con.execute("SELECT COUNT(DISTINCT run_id) FROM solve_run_trajectory").fetchone()[0]
    out["trajectory_runs"] = traj_runs
    if traj_runs:
        out["trajectory"] = _trajectory_plateau(con, plateau_eps, plateau_window_s)
    else:
        out["trajectory"] = {"note": ("solve_run_trajectory is EMPTY — enable SOLVE_TRAJECTORY_CSV "
                                      "logging to compute a true no-improvement stop rule; falling "
                                      "back to time-to-best below.")}
    # --- fallback / complement: tf_time_to_best.csv ---
    out["time_to_best"] = _time_to_best(csv_path)
    out["within_input_variance"] = _within_start_variance(csv_path)
    return out


def _trajectory_plateau(con, eps: float, window_s: float) -> dict:
    """Per run: time of LAST improvement > eps (relative), and whether it plateaued for window_s.
    Kaplan-Meier-style: report the time by which 95% of runs reached their final-within-eps."""
    rows = con.execute("SELECT run_id, elapsed_s, objective FROM solve_run_trajectory "
                       "ORDER BY run_id, elapsed_s").fetchall()
    by_run = defaultdict(list)
    for rid, t, obj in rows:
        by_run[rid].append((t, obj))
    plateau_times = []
    for rid, series in by_run.items():
        if len(series) < 2:
            continue
        final = series[-1][1]
        thresh = abs(final) * eps
        # last time the incumbent was still improving by more than eps toward final
        last_improve = series[0][0]
        for k in range(1, len(series)):
            if abs(series[k][1] - series[k - 1][1]) > thresh:
                last_improve = series[k][0]
        plateau_times.append(last_improve)
    if not plateau_times:
        return {"note": "trajectory present but no usable multi-point series"}
    plateau_times.sort()
    def pct(p): return round(plateau_times[min(len(plateau_times) - 1, int(p / 100 * len(plateau_times)))], 1)
    return {"runs": len(plateau_times), "eps": eps,
            "last_improve_median_s": round(statistics.median(plateau_times), 1),
            "last_improve_p95_s": pct(95),
            "stop_at_95pct_s": pct(95)}


def _time_to_best(csv_path: str) -> dict:
    out = {"available": os.path.exists(csv_path)}
    if not out["available"]:
        out["note"] = f"{os.path.basename(csv_path)} not found"
        return out
    ttb, budgets = [], set()
    with open(csv_path, newline="") as f:
        for row in csv.DictReader(f):
            try:
                ttb.append(float(row["time_to_best_s"])); budgets.add(float(row["budget_s"]))
            except (KeyError, ValueError):
                continue
    if not ttb:
        out["note"] = "no usable rows"; return out
    ttb.sort()
    def pct(p): return round(ttb[min(len(ttb) - 1, int(p / 100 * len(ttb)))], 1)
    out.update({"starts": len(ttb), "budget_s": sorted(budgets),
                "median_s": round(statistics.median(ttb), 1), "p90_s": pct(90),
                "p95_s": pct(95), "max_s": round(max(ttb), 1)})
    if budgets:
        b = max(budgets)
        frac_late = sum(1 for t in ttb if t > 0.9 * b) / len(ttb)
        out["frac_best_in_last_10pct"] = round(frac_late, 3)
        out["budget_guidance"] = (
            f"{frac_late:.0%} of starts peaked in the last 10% of the {b:.0f}s budget — "
            + ("budget may be too short; extend or add starts."
               if frac_late > 0.15 else
               f"budget covers convergence; could trim toward p95={out['p95_s']:.0f}s."))
    return out


def _within_start_variance(csv_path: str) -> dict:
    """Experiment B: across the 10 starts of each multistart run, how much does final soft_cost vary?
    Low spread → one Phase-3 run per Phase-2 schedule is enough; high → elites deserve repeats."""
    if not os.path.exists(csv_path):
        return {"note": "tf_time_to_best.csv not found"}
    by_run = defaultdict(list)
    with open(csv_path, newline="") as f:
        for row in csv.DictReader(f):
            try:
                key = (row["run_at"], row["src_version"]); by_run[key].append(float(row["soft_cost"]))
            except (KeyError, ValueError):
                continue
    spreads, best_vs_median = [], []
    for costs in by_run.values():
        if len(costs) < 2:
            continue
        spreads.append(statistics.pstdev(costs))
        best_vs_median.append(statistics.median(costs) - min(costs))
    if not spreads:
        return {"note": "no multistart groups with >1 start"}
    return {"multistart_groups": len(spreads),
            "within_run_softcost_sd_median": round(statistics.median(spreads), 1),
            "median_minus_best_median": round(statistics.median(best_vs_median), 1),
            "reading": ("multistart spread across the 10 starts is the gain from running 10 vs 1; "
                        "the winner is the min, so this is how much a single random start would lose "
                        "on average.")}


# --------------------------------------------------------------------------------------------------
# FINALISTS — near-best ∧ diverse (OCC grid) + tradeoff spread
# --------------------------------------------------------------------------------------------------
def finalist_set(con, year: int, eps_f: float, eps_d: int,
                 target_lo: int = 30, target_hi: int = 50) -> dict:
    rows = con.execute(
        "SELECT id, name, tier1_score, tier3_score FROM schedule_versions "
        "WHERE schedule_year=? AND name LIKE 'tf-opt-from-v%' AND tier1_score=0 "
        "AND tier3_score IS NOT NULL ORDER BY tier3_score ASC", (year,)).fetchall()
    out: dict = {"candidates": len(rows), "eps_f": eps_f, "eps_d": eps_d,
                 "eps_d_basis": "occ-grid Hamming cells", "target": [target_lo, target_hi]}
    if not rows:
        out["note"] = "no hard-valid Phase-3 versions found"; return out
    best = rows[0][3]; out["best_soft"] = best
    near = [r for r in rows if r[3] <= best + eps_f]; out["near_best"] = len(near)

    accepted = []  # (vid, grid, soft)
    for vid, _name, _hard, soft in near:
        g = occ_grid_from_version(con, vid)
        if all(grid_hamming(g, ag) >= eps_d for _v, ag, _s in accepted):
            accepted.append((vid, g, soft))
        if len(accepted) >= target_hi:
            break
    out["finalist_count"] = len(accepted)
    out["finalists"] = [{"version_id": v, "soft": s} for v, _g, s in accepted]
    out["meets_target"] = target_lo <= len(accepted) <= target_hi

    # tradeoff spread of the finalist set (do they offer DIFFERENT soft tradeoffs?)
    if accepted:
        out["tradeoff_spread"] = _finalist_tradeoffs(con, [v for v, _g, _s in accepted])
    if len(accepted) < target_lo:
        out["gap_note"] = (f"only {len(accepted)} diverse finalists vs target {target_lo}-{target_hi}; "
                           "run more Phase-3 (or relax ε_F/ε_D).")
    return out


def _finalist_tradeoffs(con, version_ids: list) -> dict:
    """Distribution of the soft sub-objectives across finalists — the framework wants finalists that
    represent DIFFERENT tradeoffs, not near-duplicates in objective space."""
    cols = ["volunteer", "healthy", "heavy_heavy", "hm_max_stretch", "saturday_coverage", "runs_gt6wk"]
    vals = {c: [] for c in cols}
    for vid in version_ids:
        r = con.execute(
            f"SELECT {','.join('m.'+c for c in cols)} FROM solve_run_metrics m "
            "JOIN solve_runs sr ON sr.id=m.run_id WHERE sr.version_id=?", (vid,)).fetchone()
        if r:
            for c, v in zip(cols, r):
                if v is not None:
                    vals[c].append(v)
    out = {}
    for c, vs in vals.items():
        if vs:
            out[c] = {"range": (min(vs), max(vs)), "distinct": len(set(vs))}
    return out


# --------------------------------------------------------------------------------------------------
# REPORT
# --------------------------------------------------------------------------------------------------
def _bar(label): return f"\n{'═' * 78}\n {label}\n{'═' * 78}"


def render(rep: dict) -> str:
    L = ["CANDIDATE-FUNNEL PIPELINE EVALUATION",
         f"DB: {os.path.basename(DB)}   year(internal): {rep['year']}"]

    p0 = rep["phase0"]
    L.append(_bar("PHASE 0 — seed-pool diversity (occ-grid Hamming)  (Exp E)"))
    L.append(f"  seeds in pool         : {p0['seed_count']}  (sampled {p0['sampled']})")
    if p0.get("pairwise_mean") is not None:
        L.append(f"  pairwise distance     : mean {p0['pairwise_mean']}  min {p0['pairwise_min']}  "
                 f"max {p0['pairwise_max']}  of 286 cells")
        L.append(f"  marginal gain δ (occ) : recent-10 mean {p0['marginal_gain_recent10']}"
                 + (f"   engine δ recent-10: {p0['engine_nn_dist_recent10']}"
                    if p0.get("engine_nn_dist_recent10") else ""))
        sat = p0.get("saturation", {})
        if sat.get("corr_seeddist_vs_downstream_delta") is not None:
            L.append(f"  downstream link       : Spearman(seed-dist, |Δ downstream fragile|) = "
                     f"{sat['corr_seeddist_vs_downstream_delta']}  "
                     f"(ε_D floor ≈ p10 dist {sat['eps_d_floor_p10_dist']})")
            rho = sat["corr_seeddist_vs_downstream_delta"]
            if rho is not None and rho > 0.2 and p0["marginal_gain_recent10"] and \
               p0["marginal_gain_recent10"] > sat["eps_d_floor_p10_dist"]:
                L.append("  → STILL DIVERSIFYING: new seeds clear the downstream-meaningful floor.")
            elif rho is not None and rho <= 0.2:
                L.append("  → seed structural distance barely tracks downstream quality — extra "
                         "seeds may add structure without downstream value.")
        else:
            L.append(f"  downstream link       : {sat.get('note','n/a')}")
    else:
        L.append(f"  {p0.get('note','')}")

    p12 = rep["phase12"]
    L.append(_bar("PHASE 1/2 — eligibility & spread (Q_hard=0 = eligibility, not rank)"))
    L.append(f"  valid Phase-2 schedules : {p12['count']}  from {p12['distinct_seeds']} distinct seeds")
    if p12.get("count"):
        L.append(f"  fragile range / median  : {p12['fragile_range']} / {p12['fragile_median']}")
        L.append(f"  healthy / volunteer rng : {p12['healthy_range']} / {p12['volunteer_range']}   "
                 f"heavy→heavy max: {p12['heavy_heavy_max']}")
        L.append(f"  P1+P2 runtime med/max   : {p12.get('runtime_p12_median_s')}s / {p12.get('runtime_p12_max_s')}s")
        if p12.get("repeated_seeds"):
            L.append(f"  reproducibility         : {p12['repeated_seeds']} seeds re-harvested; "
                     f"within-seed fragile SD mean {p12['within_seed_fragile_sd_mean']}")
        else:
            L.append(f"  reproducibility         : {p12.get('repro_note','')}")

    tr = rep["p2_to_p3"]
    L.append(_bar("PHASE 2 → PHASE 3 — does the start predict the final?  (Exp A)"))
    L.append(f"  matched start→final pairs : {tr['matched']}")
    if tr.get("matched"):
        L.append(f"  Spearman(start, final)    : {tr['spearman_start_vs_final']}")
        L.append(f"  FINAL cost SD / CV        : {tr['final_cost_sd']} / {tr['final_cost_cv']}  "
                 f"(range {tr['final_cost_range']}, median {tr['final_cost_median']})")
        L.append(f"  final fragile values seen : {tr['final_fragile_distinct']}")
        L.append(f"  → {tr['interpretation']}")
    else:
        L.append(f"  {tr.get('note','')}")

    t3 = rep["phase3"]
    L.append(_bar("PHASE 3 — plateau / time-to-best / multistart variance  (Exp B & C)"))
    tj = t3.get("trajectory", {})
    if tj.get("runs"):
        L.append(f"  trajectory plateau      : {tj['runs']} runs; last-improve median "
                 f"{tj['last_improve_median_s']}s, p95 {tj['last_improve_p95_s']}s "
                 f"← KM-style stop time (ε={tj['eps']})")
    else:
        L.append(f"  trajectory plateau      : {tj.get('note','n/a')}")
    ttb = t3.get("time_to_best", {})
    if ttb.get("starts"):
        L.append(f"  time-to-best (csv)      : median {ttb['median_s']}s  p90 {ttb['p90_s']}s  "
                 f"p95 {ttb['p95_s']}s  max {ttb['max_s']}s  (budget {ttb['budget_s']})")
        if ttb.get("budget_guidance"):
            L.append(f"    → {ttb['budget_guidance']}")
    else:
        L.append(f"  time-to-best (csv)      : {ttb.get('note','n/a')}")
    wv = t3.get("within_input_variance", {})
    if wv.get("multistart_groups"):
        L.append(f"  multistart variance     : {wv['multistart_groups']} runs; within-run soft SD "
                 f"median {wv['within_run_softcost_sd_median']}; a single random start loses ~"
                 f"{wv['median_minus_best_median']} soft vs the 10-start winner (median).")
    else:
        L.append(f"  multistart variance     : {wv.get('note','n/a')}")

    cv = rep["convergence"]
    L.append(_bar("CONVERGENCE PROOF — is the best Phase-3 objective the best possible?"))
    if cv.get("runs", 0) >= 3:
        L.append(f"  Phase-3 outputs        : {cv['runs']}   best tier3: {cv['best_tier3']}  "
                 f"(top tier = within {cv['tier_band']})")
        L.append(f"  best profile (f,v,h)   : {cv['best_profile']}")
        L.append(f"  reached best tier by   : {cv['distinct_seeds_at_best']} distinct seed(s), "
                 f"{cv['runs_at_best']} run(s)")
        L.append(f"  record improvements    : {cv['record_improvements']}   "
                 f"runs since last improvement: {cv['runs_since_last_improvement']}")
        L.append(f"  P(new run beats best)  : ≤ {cv['p_better_next_run_ub95']} (95% UB)   "
                 f"≈ {cv['expected_improvements_per_10_runs']} improvements / 10 more runs")
        L.append(f"  ceiling type           : {cv['ceiling_type']}")
        L.append(f"  → {cv['verdict']}")
    else:
        L.append(f"  {cv.get('note','')}")

    cd = rep["coverage_depth"]
    L.append(_bar("COVERAGE DEPTH — within the healthy=N ceiling, how ROBUST are those weekends?"))
    if cd.get("runs_at_corner"):
        L.append(f"  best corner            : {cd['corner']}   runs at corner: {cd['runs_at_corner']}")
        if cd.get("best_depth"):
            b = cd["best_depth"]
            L.append(f"  best-depth schedule    : v{cd['best_depth_version']}  "
                     f"depth-2 (fragile-adjacent)={b['depth2_count']}  mean depth={b['mean_healthy_depth']}  "
                     f"min={b['min_healthy_depth']}  max={b['max_depth']}")
            L.append(f"  depth histogram        : {b['hist']}  (coverers→#weekends)")
            L.append(f"  across corner runs     : depth-2 count {cd['depth2_count_range']}, "
                     f"mean depth {cd['mean_depth_range']}, max depth seen {cd['max_depth_seen']}")
            L.append(f"  depth convergence      : best {cd['best_depth2_so_far']} depth-2 weekends, "
                     f"{cd['runs_since_depth_improved']} corner runs since last depth gain")
        L.append(f"  → {cd['depth_verdict']}")
    else:
        L.append(f"  {cd.get('note','')}")

    fn = rep["finalists"]
    L.append(_bar("FINALIST SET — near-best ∧ diverse (occ-grid)  target 30–50  (Exp D)"))
    if fn.get("candidates"):
        L.append(f"  hard-valid P3 versions : {fn['candidates']}   best soft (tier3): {fn['best_soft']}")
        L.append(f"  within ε_F={fn['eps_f']} of best : {fn['near_best']}")
        L.append(f"  diverse finalists (ε_D={fn['eps_d']} cells) : {fn['finalist_count']}  "
                 f"(target {fn['target'][0]}-{fn['target'][1]})  meets: {fn['meets_target']}")
        ts = fn.get("tradeoff_spread", {})
        if ts:
            bits = [f"{k} {v['range']}({v['distinct']})" for k, v in ts.items()]
            L.append("  tradeoff spread        : " + "  ".join(bits))
            L.append("    (range(distinct) per sub-objective; wider = more genuinely different options)")
        if fn.get("gap_note"):
            L.append(f"  → {fn['gap_note']}")
    else:
        L.append(f"  {fn.get('note','')}")

    L.append(_bar("VERDICT — defensible stopping statement"))
    for line in rep["verdict"]:
        L.append(f"  {line}")
    return "\n".join(L)


def build_verdict(rep: dict) -> list:
    v = []
    p0 = rep["phase0"]; p12 = rep["phase12"]; tr = rep["p2_to_p3"]; fn = rep["finalists"]
    cvg = rep.get("convergence", {})
    sat = p0.get("saturation", {})
    # LEAD with the convergence proof — it directly answers "is this the best possible objective?"
    if cvg.get("runs", 0) >= 3:
        ct = cvg.get("ceiling_type", "")
        if ct.startswith("OBJECTIVE-LIMITED"):
            v.append(f"BEST OBJECTIVE PROVEN STABLE: best tier3={cvg['best_tier3']} reached by "
                     f"{cvg['distinct_seeds_at_best']} seeds, {cvg['runs_since_last_improvement']} runs "
                     f"with no improvement (≤{cvg['p_better_next_run_ub95']} chance a new run beats it). "
                     "More seeds/harvest will NOT lower it — the ceiling is objective-limited.")
        else:
            v.append(f"Best objective not yet proven optimal ({cvg.get('ceiling_type','')}): "
                     f"{cvg.get('verdict','')}")
    if sat.get("corr_seeddist_vs_downstream_delta") is not None and p0.get("marginal_gain_recent10"):
        if p0["marginal_gain_recent10"] > sat.get("eps_d_floor_p10_dist", 0):
            v.append("Phase 0: new seeds still clear the downstream-meaningful distance floor — pool not saturated.")
        else:
            v.append("Phase 0: new seeds fall below the downstream-meaningful floor — approaching saturation.")
    if tr.get("matched"):
        cv = tr.get("final_cost_cv")
        if tr.get("all_final_fragile_zero") or (cv is not None and cv < 0.5):
            v.append(f"Phase 3 final is nearly seed-independent (final CV {cv}) — selecting Phase-2 "
                     "inputs by starting score adds little; advance by diversity.")
    if fn.get("candidates"):
        fc, lo, hi = fn["finalist_count"], fn["target"][0], fn["target"][1]
        if fn.get("meets_target"):
            v.append(f"Finalist set of {fc} diverse near-best schedules MEETS the {lo}–{hi} target.")
        elif fc < lo:
            v.append(f"Finalist set has {fc} diverse near-best schedules — below target; run more Phase-3 (or relax ε_F/ε_D).")
        else:
            v.append(f"Finalist set of {fc} exceeds target — can tighten ε_F/ε_D for a sharper set.")
    v.append("")
    saturated = (tr.get("all_final_fragile_zero") or
                 (tr.get("final_cost_cv") is not None and tr["final_cost_cv"] < 0.5))
    v.append("Defensible claim: From {} distinct cold-start seeds we produced {} hard-valid Phase-2 "
             "schedules and {} polished Phase-3 candidates; {}{}Additional computation is {} produce "
             "a meaningfully better or more diverse final schedule.".format(
                 p0.get("seed_count", "?"), p12.get("count", "?"), fn.get("candidates", "?"),
                 "the Phase-3 quality floor looks saturated and " if saturated else "",
                 "a diverse finalist set is in hand. " if fn.get("meets_target")
                 else "the finalist set is not yet full. ",
                 "unlikely to" if (fn.get("meets_target") and saturated) else "likely to still"))
    return v


def main() -> int:
    ap = argparse.ArgumentParser(description="Candidate-funnel pipeline evaluator")
    ap.add_argument("--year", type=int, default=2)
    ap.add_argument("--db", default=DB)
    ap.add_argument("--ttb-csv", default=TTB_CSV)
    ap.add_argument("--sample-seeds", type=int, default=120, help="cap seeds for pairwise P0 diversity (0=all)")
    ap.add_argument("--eps-f", type=float, default=100.0, help="finalist score-gap from best, in tier3 units")
    ap.add_argument("--eps-d", type=int, default=20, help="finalist min occ-grid Hamming distance (cells)")
    ap.add_argument("--plateau-eps", type=float, default=0.02, help="rel. improvement that counts (trajectory)")
    ap.add_argument("--plateau-window", type=float, default=60.0, help="no-improvement window seconds")
    ap.add_argument("--tier-band", type=float, default=10.0,
                    help="tier3 points within which two outputs count as the SAME quality tier (convergence)")
    ap.add_argument("--json", help="also write the full metrics report to this JSON path")
    args = ap.parse_args()

    if not os.path.exists(args.db):
        print(f"DB not found: {args.db}", file=sys.stderr); return 2

    con = ro(args.db)
    try:
        rep = {"year": args.year}
        rep["phase0"] = phase0_diversity(con, args.year, args.sample_seeds)
        rep["phase12"] = phase12_summary(con, args.year)
        rep["p2_to_p3"] = p2_to_p3(con, args.year, rep["phase12"].get("_runs", []))
        rep["phase3"] = phase3_plateau(con, args.year, args.ttb_csv, args.plateau_eps, args.plateau_window)
        rep["convergence"] = convergence_proof(con, args.year, args.tier_band)
        rep["coverage_depth"] = coverage_depth(con, args.year)
        rep["finalists"] = finalist_set(con, args.year, args.eps_f, args.eps_d)
        rep["verdict"] = build_verdict(rep)
    finally:
        con.close()

    print(render(rep))

    if args.json:
        slim = json.loads(json.dumps(rep, default=str))
        slim.get("phase12", {}).pop("_runs", None)
        if "pairs" in slim.get("p2_to_p3", {}):
            slim["p2_to_p3"]["pairs"] = slim["p2_to_p3"]["pairs"][:200]
        # best_so_far_curve is kept (short, useful for plotting)
        if "per_run" in slim.get("coverage_depth", {}):
            slim["coverage_depth"]["per_run"] = slim["coverage_depth"]["per_run"][:50]
        with open(args.json, "w") as f:
            json.dump(slim, f, indent=2)
        print(f"\n[wrote JSON → {args.json}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
