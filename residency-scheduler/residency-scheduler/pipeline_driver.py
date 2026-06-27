#!/usr/bin/env python3
"""End-to-end pipeline driver: Phase-0 seed generation -> Phase-1/2 harvest -> Phase-3 Timefold optimization.

WHAT IT DOES (fully automated, zero human input after launch):

  STAGE 1 — Seed generation (Phase 0)
    Generate N cold-start feasible seeds via HeadlessSolveRunner with PHASE3_SKIP=1
    and a very short P0 budget (seeds only; P1/P2/P3 are irrelevant here).
    Seeds are written into the phase0_seed_stats pool by the engine automatically.
    Stage ends when the requested seed count is reached or the pool has enough.

  STAGE 2 — Phase-1/2 harvest
    Run M harvest solves (roundrobin distinct seeds, PHASE3_SKIP=1). Each solve
    commits a feasible hard-valid Phase-2 schedule to the live assignments table
    and score_and_snapshot writes it as a schedule_version + solve_runs row.
    Stage ends when M valid harvest versions are banked.

  STAGE 3 — Phase-3 Timefold optimization
    Select the top-K Phase-2 versions (by fragile ASC, then healthy DESC, then
    heavy_heavy ASC) and run TimefoldOptimizeRunner on each for the configured
    budget. Each Timefold run writes a new version + solve_runs row that is
    directly comparable to harvest runs via score_grid().
    Stage ends when all selected candidates have been optimized.

  FINAL REPORT
    Writes PIPELINE_REPORT.md with per-stage summaries, all run metrics, and
    a ranked finalist table.

DESIGN PRINCIPLES:
  - Fully resumable: pipeline_state.json tracks stage/unit status; a restart
    continues where it left off without duplicating work.
  - Fail-fast with loud alerts: any stage failure writes PIPELINE_STATUS.md
    and the PIPELINE_REPORT.md with a clear FAILED section so a 48-hour
    unattended run does not silently produce nothing.
  - Single-writer gate: pipeline.lock prevents two drivers from colliding.
  - All metrics via score_grid() — no separate metric definitions.
  - Reuses sweep_driver's proven primitives: pid_alive, wait_for_completion,
    integrity_ok, preflight, atomic_write, detach_from_console, etc.

CLI:
    python pipeline_driver.py --seeds 20 --harvest 40 --top-k 10 --p3-budget 300
    python pipeline_driver.py --seeds 20 --harvest 40 --top-k 10 --p3-budget 300 --detach
    python pipeline_driver.py --seeds 20 --harvest 40 --top-k 10 --p3-budget 300 --dry-run

Args:
    --seeds N       Phase-0 seeds to generate (default: 20)
    --harvest M     Phase-2 harvest solves to run (default: 40)
    --top-k K       Phase-2 versions to send to Timefold Phase-3 (default: 10)
    --p3-budget S   Timefold optimization budget in seconds per run (default: 300)
    --detach        Re-spawn as job-object-breakaway detached process
    --dry-run       Print what would run; no java, no DB writes
    --once-stage    Run only the next incomplete stage then exit (for testing)
    --skip-seeds    Skip Stage 1 (assume seed pool is already populated)
    --skip-harvest  Skip Stage 2 (assume harvest versions already exist)
"""
from __future__ import annotations
import argparse, csv, datetime, json, os, sqlite3, subprocess, sys, time

import sweep_driver as sw

ROOT   = sw.ROOT
DB     = sw.DB
LOCK   = os.path.join(ROOT, 'pipeline.lock')
STATE  = os.path.join(ROOT, 'pipeline_state.json')
STATUS = os.path.join(ROOT, 'PIPELINE_STATUS.md')
REPORT = os.path.join(ROOT, 'PIPELINE_REPORT.md')
RESULTS_CSV = os.path.join(ROOT, 'pipeline_results.csv')
LINEAGE_CSV = os.path.join(ROOT, 'phase3_lineage.csv')
RUNS_DIR    = os.path.join(ROOT, 'pipeline_runs')

# Phase-0 seed generation: the engine exits as soon as it finds ONE feasible seed.
# Budget gives P0 plenty of time; P1/P2/P3 are skipped (PHASE3_SKIP=1, PHASE12_SKIP=1).
SEED_GEN_BUDGET = [300, 5, 5, 5]   # P0=ceiling, P1/P2/P3=tiny-unused

# Phase-1/2 harvest budget (same as harvest_driver.py — do not change without understanding the analysis)
HARVEST_BUDGET  = [300, 600, 300, 5]

# Timefold wait: give it the budget + 120s startup/commit margin, then poll.
TF_GRACE_S  = 120
TF_POLL_S   = 30

MAX_CONSECUTIVE_FAILURES = 4


# --------------------------------------------------------------------------- logging

def log(msg: str) -> None:
    ts = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    print(f'[pipeline {ts}] {msg}', flush=True)


# --------------------------------------------------------------------------- lock

def acquire_lock() -> None:
    if os.path.exists(LOCK):
        try:
            old = int(open(LOCK).read().strip() or 0)
        except Exception:
            old = 0
        if sw.pid_alive(old):
            raise sw.Halt(f'another pipeline driver is already running (PID {old}); refusing to start')
        log(f'stale pipeline lock from dead PID {old}; reclaiming')
    sw.atomic_write(LOCK, str(os.getpid()))


def release_lock() -> None:
    try:
        if os.path.exists(LOCK) and open(LOCK).read().strip() == str(os.getpid()):
            os.remove(LOCK)
    except Exception:
        pass


# --------------------------------------------------------------------------- state

def load_state() -> dict:
    if os.path.exists(STATE):
        try:
            return json.load(open(STATE, encoding='utf-8'))
        except Exception:
            log('pipeline_state.json unreadable; starting fresh')
    return {'stage': 'seeds', 'seeds': {}, 'harvest': {}, 'phase3': {}}


def save_state(state: dict) -> None:
    sw.atomic_write(STATE, json.dumps(state, indent=1))


# --------------------------------------------------------------------------- DB helpers

def count_pool_seeds() -> int:
    """Return how many seeds are currently in phase0_seed_stats for year 2."""
    con = sqlite3.connect(DB, timeout=10)
    try:
        row = con.execute(
            "SELECT COUNT(*) FROM phase0_seed_stats WHERE year=2").fetchone()
        return row[0] if row else 0
    except Exception:
        return 0
    finally:
        con.close()


def cache_pool_max() -> int:
    """The hard cap on distinct Phase-0 seeds the engine will cache (PHASE0_CACHE_POOL_MAX,
    default 25 — see CpSatSchedulerEngine.CACHE_POOL_MAX_DEFAULT). Once the pool reaches
    this, saveCachedFeasibleHints refuses to add more, so the pool cannot grow and any
    'generate until N unused' loop with N above the achievable ceiling would spin forever."""
    try:
        return max(1, int(os.environ.get('PHASE0_CACHE_POOL_MAX', '') or 25))
    except ValueError:
        return 25


def pool_seed_ids() -> list[str]:
    """Return all seed_ids currently in phase0_seed_stats for year 2 (full hashes)."""
    con = sqlite3.connect(DB, timeout=10)
    try:
        return [r[0] for r in con.execute(
            "SELECT seed_id FROM phase0_seed_stats WHERE year=2 AND seed_id IS NOT NULL"
        ).fetchall() if r[0]]
    except Exception as e:
        log(f'WARNING: could not list pool seed_ids: {e}')
        return []
    finally:
        con.close()


def harvested_seed_ids(state: dict | None = None) -> set[str]:
    """8-char prefixes of seeds that have already been CONSUMED by a Phase-2 harvest.

    A seed is 'used' once any harvest solve has been run from it. We union two records
    so re-runs never re-harvest a seed:
      1. solve_runs rows with run_status='PHASE2_FALLBACK' (the DB's harvest rows), and
      2. pipeline_state.json["harvest"] entries (covers this pipeline's own bookkeeping).
    Compared on 8-char prefix because that is how seed_ids surface in logs/CSV/UI.
    """
    used: set[str] = set()
    con = sqlite3.connect(DB, timeout=10)
    try:
        for r in con.execute(
            "SELECT seed_id FROM solve_runs "
            "WHERE run_status='PHASE2_FALLBACK' AND year=2 AND seed_id IS NOT NULL"
        ).fetchall():
            if r[0]:
                used.add(r[0][:8])
    except Exception as e:
        log(f'WARNING: could not query harvested seed_ids from DB: {e}')
    finally:
        con.close()

    st = state if state is not None else (load_state() if os.path.exists(STATE) else {})
    for rec in (st.get('harvest') or {}).values():
        sid = (rec.get('seed_id') or '')[:8]
        if sid:
            used.add(sid)
    return used


def unused_seeds(state: dict | None = None) -> list[str]:
    """Pool seeds (full hashes) that have NOT yet been consumed by a Phase-2 harvest.

    These are the seeds a fresh 'run N seeds' request should draw from: harvest pins
    each one explicitly so no seed is ever harvested twice. Sorted for determinism.
    """
    used = harvested_seed_ids(state)
    return sorted(sid for sid in pool_seed_ids() if sid[:8] not in used)


def consumed_source_versions(state: dict | None = None) -> set[int]:
    """Phase-2 source version_ids already optimized by Timefold.

    The DB stores only the NEW optimized version, never which Phase-2 version it
    came from, so 'which P2 runs were already optimized' lives only in the pipeline
    bookkeeping. Two records hold it and they can drift, so we read BOTH and union:

      1. pipeline_state.json["phase3"] — authoritative (every entry has src_version_id),
         written atomically as each Timefold run completes.
      2. phase3_lineage.csv — a derived export of the same links.

    Sourcing from state (not the CSV alone) means a missing/empty/stale CSV can never
    silently mark an already-optimized version as "fresh" and re-optimize it. Callers
    pass the loaded state; if omitted we load it. Used to avoid re-optimizing sources.
    """
    used: set[int] = set()

    # 1. Authoritative: the phase3 stage record in pipeline_state.json
    st = state if state is not None else (load_state() if os.path.exists(STATE) else {})
    for rec in (st.get('phase3') or {}).values():
        v = rec.get('src_version_id')
        try:
            if v is not None and str(v).strip() != '':
                used.add(int(v))
        except (ValueError, TypeError):
            pass

    # 2. Derived mirror: phase3_lineage.csv (covers any runs not in this state file)
    if os.path.exists(LINEAGE_CSV):
        try:
            with open(LINEAGE_CSV, newline='', encoding='utf-8') as f:
                for row in csv.DictReader(f):
                    v = (row.get('src_version_id') or '').strip()
                    if v:
                        try:
                            used.add(int(v))
                        except ValueError:
                            pass
        except Exception as e:
            log(f'WARNING: could not read phase3 lineage ledger: {e}')
    return used


def best_harvest_versions(top_k: int, exclude: set[int] | None = None) -> list[dict]:
    """Return the top_k best Phase-2 harvest versions for Timefold, ranked by
    fragile ASC, healthy DESC, heavy_heavy ASC. Versions in `exclude` (already
    optimized — see consumed_source_versions) are skipped. Reads solve_run_metrics
    joined to solve_runs."""
    exclude = exclude or set()
    con = sqlite3.connect(DB, timeout=10)
    try:
        # Over-fetch so excluded (already-optimized) versions don't shrink the result
        # below top_k when plenty of fresh candidates remain.
        rows = con.execute("""
            SELECT sr.id as run_id, sr.version_id, sr.seed_id,
                   srm.fragile, srm.healthy, srm.volunteer, srm.heavy_heavy
            FROM solve_runs sr
            JOIN solve_run_metrics srm ON srm.run_id = sr.id
            WHERE sr.run_status = 'PHASE2_FALLBACK'
              AND sr.feasible = 0
              AND sr.year = 2
              AND sr.version_id IS NOT NULL
              AND srm.fragile IS NOT NULL
            ORDER BY srm.fragile ASC, srm.healthy DESC, srm.heavy_heavy ASC
            LIMIT ?
        """, (top_k + len(exclude),)).fetchall()
        out = [
            {'run_id': r[0], 'version_id': r[1], 'seed_id': r[2],
             'fragile': r[3], 'healthy': r[4], 'volunteer': r[5], 'heavy_heavy': r[6]}
            for r in rows
            if r[1] not in exclude
        ]
        return out[:top_k]
    except Exception as e:
        log(f'WARNING: could not query best harvest versions: {e}')
        return []
    finally:
        con.close()


def timefold_results() -> list[dict]:
    """Return all Timefold-marked solve_runs rows (for the final report).
    TimefoldOptimizeRunner hardcodes config_label='timefold-opt' and
    p3_status='TIMEFOLD' — match on those."""
    con = sqlite3.connect(DB, timeout=10)
    try:
        rows = con.execute("""
            SELECT sr.id, sr.version_id, sr.seed_id, sr.run_at,
                   srm.volunteer, srm.fragile, srm.healthy, srm.heavy_heavy
            FROM solve_runs sr
            LEFT JOIN solve_run_metrics srm ON srm.run_id = sr.id
            WHERE (sr.config_label = 'timefold-opt' OR sr.p3_status = 'TIMEFOLD')
              AND sr.year = 2
            ORDER BY srm.fragile ASC, srm.healthy DESC
        """).fetchall()
        return [
            {'run_id': r[0], 'version_id': r[1], 'seed_id': r[2], 'run_at': r[3],
             'volunteer': r[4], 'fragile': r[5], 'healthy': r[6], 'heavy_heavy': r[7]}
            for r in rows
        ]
    except Exception as e:
        log(f'WARNING: could not query Timefold results: {e}')
        return []
    finally:
        con.close()


# --------------------------------------------------------------------------- Stage 1: seed generation

def run_seed_gen(unit: dict, dry: bool = False) -> tuple[str, dict]:
    """Run one cold-start Phase-0 seed generation. Returns (status, metrics)."""
    if not dry and not sw.integrity_ok(DB):
        raise sw.Halt('DB integrity_check != ok before seed gen -- STOP')

    env = dict(os.environ)
    env['PHASE3_SKIP']          = '1'   # skip Phase-3 build entirely
    env['SOLVE_TRAJECTORY_CSV'] = unit['traj']
    # POOL-SEEDING mode (matches phase0_seed_pool.sh, the tool that built the original pool):
    # the engine ONLY banks a seed into phase0_seed_stats when PHASE0_FIX=cache. COLLECT=1 makes
    # each run solve COLD with a fresh random seed (no replay) so the pool gains genuine variety;
    # PORTFOLIO=fj speeds each cold feasible find ~10x. Without these the run finds a feasible
    # assignment, prints seed_id=, but NEVER persists it -> the pool stays at 0 and Stage 1 spins
    # forever. (See CpSatSchedulerEngine.java banking gate `p0fix.equals("cache")`.)
    env['PHASE0_FIX']           = 'cache'
    env['PHASE0_CACHE_COLLECT'] = '1'
    env['PHASE0_PORTFOLIO']     = 'fj'

    cp = '<classpath>' if dry else sw.build_classpath()
    cmd = ['java', '-cp', cp, 'com.residency.tools.HeadlessSolveRunner', '2',
           *map(str, SEED_GEN_BUDGET)]

    if dry:
        log(f'WOULD launch seed gen {unit["uid"]} PHASE12_SKIP=1 PHASE3_SKIP=1  > {unit["log"]}')
        return 'DRY', {}

    logf = open(unit['log'], 'w')
    p = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=logf, stderr=subprocess.STDOUT)
    log(f'seed gen {unit["uid"]} PID {p.pid} -> {os.path.basename(unit["log"])}')

    # Seed-gen runs are fast (~3-10s); use a tight wait cadence.
    old_grace, old_poll = sw.WAIT_GRACE_S, sw.WAIT_POLL_S
    sw.WAIT_GRACE_S = 10
    sw.WAIT_POLL_S  = 10
    outcome = sw.wait_for_completion(unit, p.pid, lambda u: write_status(u, 'Stage 1: seed gen'))
    sw.WAIT_GRACE_S = old_grace
    sw.WAIT_POLL_S  = old_poll

    if outcome == 'crashed':
        log(f'{unit["uid"]} seed gen crashed')
        return 'FAILED', {}

    # Parse seed_id from log
    seed_id = None
    if os.path.exists(unit['log']):
        with open(unit['log'], encoding='utf-8', errors='replace') as f:
            for line in f:
                if 'seed_id=' in line:
                    tok = line.split('seed_id=', 1)[1].split()[0].strip().strip('().,')
                    if tok:
                        seed_id = tok
                        break
    new_pool = count_pool_seeds()
    log(f'{unit["uid"]} complete; seed={seed_id}; pool now has {new_pool} seeds')
    return 'DONE', {'seed_id': seed_id or '', 'pool_size': new_pool}


def stage1_seeds(state: dict, target_seeds: int, dry: bool = False) -> None:
    """Generate cold-start seeds until at least target_seeds are UNUSED (un-harvested).

    'Run N seeds' means N seeds available to harvest fresh — not N seeds total. A seed
    already consumed by a prior harvest does not count toward the target, so we top up
    the gap between target_seeds and the current unused count. Each new cold seed is
    unused by definition, so the target is met by generating exactly the shortfall.
    """
    log(f'=== STAGE 1: Seed generation (target: {target_seeds} UNUSED seeds) ===')
    seeds_st = state['seeds']
    pool_now    = count_pool_seeds()
    unused_now  = len(unused_seeds(state))
    log(f'Pool has {pool_now} seeds, {unused_now} of them UNUSED; need {target_seeds} unused')
    if unused_now >= target_seeds:
        log(f'Already have {unused_now} >= {target_seeds} unused seeds; skipping seed generation')
        return

    # Seed INVENTORY is now unbounded (per-seed storage in phase0_seed_assignments — the engine
    # banks every distinct feasible seed regardless of the small warm-start replay cap). So the
    # target is taken at face value: generate until there are `target_seeds` unused seeds. The only
    # remaining limit is the solver running out of GENUINELY NEW feasible assignments to find (true
    # diversity saturation), which the no-progress guard below detects so we never spin forever.
    effective_target = target_seeds
    needed = effective_target - unused_now
    log(f'Will generate ~{needed} new seed(s) toward {effective_target} unused '
        f'(inventory is unbounded; pool/replay cap no longer limits generation).')

    run_idx    = max((int(k.split('-r')[1]) for k in seeds_st if '-r' in k), default=0)
    consecutive_failures = 0
    # No-progress guard: if seed-gen keeps completing but unused does not rise (the solver is only
    # re-finding already-cached assignments — genuine diversity saturation), stop after this many
    # barren attempts instead of spinning forever. With unbounded storage this now only triggers on
    # REAL saturation, not the old cache-cap wall.
    no_progress = 0
    NO_PROGRESS_LIMIT = 12

    while len(unused_seeds(state)) < effective_target:
        prev_unused = len(unused_seeds(state))
        run_idx += 1
        uid   = f'seed-gen-r{run_idx:04d}'
        if seeds_st.get(uid, {}).get('status') == 'DONE':
            continue

        unit = {
            'uid':    uid,
            'label':  'seed-gen',
            'run':    run_idx,
            'budget': SEED_GEN_BUDGET,
            'log':    os.path.join(RUNS_DIR, f'{uid}.log'),
            'traj':   os.path.join(RUNS_DIR, f'{uid}.traj.csv'),
        }
        log(f'--- seed gen unit {uid} ---')
        status, metrics = run_seed_gen(unit, dry=dry)
        seeds_st[uid] = {'status': status, **metrics}
        if not dry:
            save_state(state)
        write_pipeline_csv('seed', uid, status, metrics)

        if status == 'FAILED':
            consecutive_failures += 1
            if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                raise sw.Halt(f'{consecutive_failures} consecutive seed-gen failures — stopping')
        else:
            consecutive_failures = 0

        if dry:
            log(f'DRY: would keep generating until {effective_target} unused; stopping after 1')
            break

        # Track whether this attempt actually grew the unused pool.
        if len(unused_seeds(state)) > prev_unused:
            no_progress = 0
        else:
            no_progress += 1
            if no_progress >= NO_PROGRESS_LIMIT:
                cur = len(unused_seeds(state))
                log(f'WARNING: {no_progress} seed-gen attempts in a row produced no new unused seed '
                    f'(pool at {count_pool_seeds()}; only re-finding already-cached assignments — '
                    f'genuine diversity saturation). Stopping seed generation with {cur} unused '
                    f'seed(s) and proceeding to harvest those.')
                break

    final_count = count_pool_seeds()
    final_unused = len(unused_seeds(state))
    log(f'Stage 1 complete. Pool has {final_count} seeds ({final_unused} unused).')


# --------------------------------------------------------------------------- Stage 2: harvest

def run_harvest_unit(unit: dict, dry: bool = False) -> tuple[str, dict]:
    """Run one seeded Phase-1/2 harvest solve. Returns (status, scores)."""
    if not dry and not sw.integrity_ok(DB):
        raise sw.Halt('DB integrity_check != ok before harvest -- STOP')

    env = dict(os.environ)
    env['PHASE3_SKIP']         = '1'
    env['SOLVE_TRAJECTORY_CSV'] = unit['traj']
    # Drive seed selection from the driver so each unused seed is harvested EXACTLY once
    # (no duplicates across runs). PHASE0_SEED_ID pins one specific pooled seed, overriding
    # round-robin (CpSatSchedulerEngine.loadCachedFeasibleHints). The driver computes the
    # unused-seed worklist (unused_seeds()); each unit carries its own 'pin_seed'. If a unit
    # somehow has no pin (shouldn't happen), fall back to round-robin for fair coverage.
    if unit.get('pin_seed'):
        env['PHASE0_SEED_ID'] = unit['pin_seed']
    else:
        env['PHASE0_SEED_SELECT'] = 'roundrobin'

    cp = '<classpath>' if dry else sw.build_classpath()
    cmd = ['java', '-cp', cp, 'com.residency.tools.HeadlessSolveRunner', '2',
           *map(str, HARVEST_BUDGET)]

    if dry:
        pin = unit.get('pin_seed')
        sel = f'PHASE0_SEED_ID={pin[:8]}' if pin else 'roundrobin'
        log(f'WOULD launch harvest {unit["uid"]} PHASE3_SKIP=1 {sel}  > {unit["log"]}')
        return 'DRY', {}

    logf = open(unit['log'], 'w')
    p = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=logf, stderr=subprocess.STDOUT)
    log(f'harvest {unit["uid"]} PID {p.pid} -> {os.path.basename(unit["log"])}')

    old_grace, old_poll = sw.WAIT_GRACE_S, sw.WAIT_POLL_S
    sw.WAIT_GRACE_S = 15
    sw.WAIT_POLL_S  = 15
    outcome = sw.wait_for_completion(unit, p.pid,
                                     lambda u: write_status(u, 'Stage 2: harvest'))
    sw.WAIT_GRACE_S = old_grace
    sw.WAIT_POLL_S  = old_poll

    if outcome == 'crashed':
        log(f'{unit["uid"]} harvest crashed')
        return 'FAILED', {}

    # Parse metrics from log
    feasible, tier1, seed_id = False, None, None
    if os.path.exists(unit['log']):
        with open(unit['log'], encoding='utf-8', errors='replace') as f:
            for line in f:
                if line.startswith('feasible'):
                    feasible = 'true' in line.lower()
                if 'Tier-1 score:' in line:
                    try:
                        tier1 = int(line.split('Tier-1 score:')[1].split()[0])
                    except (IndexError, ValueError):
                        pass
                if seed_id is None and 'seed_id=' in line:
                    tok = line.split('seed_id=', 1)[1].split()[0].strip().strip('().,')
                    if tok:
                        seed_id = tok

    valid = bool(feasible) and tier1 == 0
    # Name the snapshot by the SEED, not the run index. The run index (harvest-rNNNN)
    # resets whenever pipeline_state.json is fresh, so it collides with a prior batch's
    # names — score_and_snapshot then ADOPTS the old version (id 1-5) instead of creating
    # a new one, and Stage 3's dedup rejects all of them. The pinned seed is unique per
    # harvest (each unused seed is harvested once), so it yields a genuinely new version.
    seed_tag = (seed_id or unit.get('pin_seed') or unit['uid'])[:8]
    name  = f'pipeline-harvest-{seed_tag} ({"OK" if valid else "INVALID"})'
    notes = (f'pipeline Stage-2 harvest; uid={unit["uid"]}; seed={seed_id}; '
             + ('feasible Tier-1=0' if valid else f'INVALID feasible={feasible} tier1={tier1}'))

    version, scores = sw.score_and_snapshot(name, notes, unit=unit)
    status = 'DONE' if valid else 'FAILED'
    scores['seed_id'] = seed_id or ''
    log(f'{unit["uid"]} {"OK" if valid else "INVALID"} seed={seed_id} '
        f'vol={scores.get("volunteer","?")} frag={scores.get("fragile","?")} '
        f'heal={scores.get("healthy","?")} version={version}')
    return status, scores


def stage2_harvest(state: dict, harvest_count: int, dry: bool = False) -> None:
    """Harvest up to harvest_count UNUSED seeds — each seed harvested EXACTLY once.

    The driver pins each unit to one unused pool seed (PHASE0_SEED_ID) so no seed is ever
    harvested twice, and a re-run picks up only seeds still un-harvested. The worklist is
    the unused-seed set capped at harvest_count; if fewer unused seeds exist than requested
    we harvest what's available (Stage 1 should have topped the pool up first).
    """
    log(f'=== STAGE 2: Phase-1/2 harvest (target: {harvest_count} unused seeds) ===')
    harvest_st = state['harvest']
    done_count = sum(1 for r in harvest_st.values() if r.get('status') == 'DONE')

    # Seeds already harvested by THIS pipeline (recorded in state) — never re-issue them.
    issued = {(r.get('seed_id') or '')[:8]
              for r in harvest_st.values() if r.get('seed_id')}
    worklist = [s for s in unused_seeds(state) if s[:8] not in issued][:harvest_count]
    log(f'{len(worklist)} unused seed(s) to harvest this run '
        f'(have {done_count} done; requested {harvest_count}; '
        f'{len(unused_seeds(state))} unused in pool)')
    if not worklist:
        log('No unused seeds left to harvest — Stage 2 has nothing to do.')
        log(f'Stage 2 complete. {done_count} valid harvest versions banked.')
        return

    run_idx              = max((int(k.split('-r')[1]) for k in harvest_st if '-r' in k), default=0)
    consecutive_failures = 0

    for pin_seed in worklist:
        run_idx += 1
        uid = f'harvest-r{run_idx:04d}'
        if harvest_st.get(uid, {}).get('status') == 'DONE':
            done_count += 1
            continue

        unit = {
            'uid':       uid,
            'label':     f'pipeline-harvest-{uid}',
            'run':       run_idx,
            'budget':    HARVEST_BUDGET,
            'pin_seed':  pin_seed,
            'log':       os.path.join(RUNS_DIR, f'{uid}.log'),
            'traj':      os.path.join(RUNS_DIR, f'{uid}.traj.csv'),
        }
        log(f'--- harvest unit {uid} seed={pin_seed[:8]} ({done_count}/{harvest_count} done) ---')
        status, scores = run_harvest_unit(unit, dry=dry)
        # Always record the pinned seed so a failed unit's seed still counts as consumed
        # (we attempted it) and is not re-issued; the engine echoes the same id on success.
        scores.setdefault('seed_id', pin_seed)
        harvest_st[uid] = {'status': status, **scores}
        if not dry:
            save_state(state)
        write_pipeline_csv('harvest', uid, status, scores)

        if status == 'DONE':
            done_count += 1
            consecutive_failures = 0
        else:
            consecutive_failures += 1
            if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                raise sw.Halt(f'{consecutive_failures} consecutive harvest failures — stopping')

        if dry:
            log(f'DRY: would harvest each of the {len(worklist)} unused seeds; stopping after 1')
            break

    log(f'Stage 2 complete. {done_count} valid harvest versions banked.')


# --------------------------------------------------------------------------- Stage 3: Timefold Phase-3

def run_timefold(unit: dict, dry: bool = False) -> tuple[str, dict]:
    """Run TimefoldOptimizeRunner on one Phase-2 version. Returns (status, metrics)."""
    if not dry and not sw.integrity_ok(DB):
        raise sw.Halt('DB integrity_check != ok before Timefold run -- STOP')

    cp = '<classpath>' if dry else sw.build_classpath()
    cmd = ['java', '-cp', cp, 'com.residency.tools.TimefoldOptimizeRunner',
           '2', str(unit['version_id']), str(unit['p3_budget_s'])]

    if dry:
        log(f'WOULD run TimefoldOptimizeRunner year=2 version={unit["version_id"]} '
            f'budget={unit["p3_budget_s"]}s  > {unit["log"]}')
        return 'DRY', {}

    # Accumulate per-start time-to-best rows into ONE cumulative CSV across the whole pipeline
    # (one row per parallel start, every Timefold run appends). This is the dataset the time-cap
    # analysis consumes — over an extensive run it builds the full time-to-best distribution.
    env = dict(os.environ)
    env['TF_TRAJECTORY_CSV'] = os.path.join(ROOT, 'tf_time_to_best.csv')
    logf = open(unit['log'], 'w')
    p = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=logf, stderr=subprocess.STDOUT)
    log(f'Timefold {unit["uid"]} PID {p.pid} version={unit["version_id"]} '
        f'budget={unit["p3_budget_s"]}s -> {os.path.basename(unit["log"])}')

    # Wait for Timefold to finish. TimefoldOptimizeRunner does NOT print "=== RESULT ==="
    # so we can't use sweep_driver's log_has_result(). Instead: poll until the PID dies,
    # then give it 30s for file flush, then check for NEW_VERSION_ID in the log.
    grace = min(TF_GRACE_S, max(30, unit['p3_budget_s'] // 2))
    log(f'{unit["uid"]} waiting up to {unit["p3_budget_s"] + TF_GRACE_S}s for Timefold to finish ...')
    time.sleep(grace)
    deadline = time.time() + unit['p3_budget_s'] + TF_GRACE_S
    while True:
        if not sw.pid_alive(p.pid):
            time.sleep(5)   # let the JVM flush its output
            break
        if time.time() > deadline:
            log(f'{unit["uid"]} exceeded deadline; assuming crashed')
            break
        write_status(unit, 'Stage 3: Timefold Phase-3')
        time.sleep(TF_POLL_S)
    # Failure is detected below by absence of NEW_VERSION_ID in the log.

    # Parse output from Timefold runner
    new_version_id = None
    metrics        = {}
    if os.path.exists(unit['log']):
        with open(unit['log'], encoding='utf-8', errors='replace') as f:
            for line in f:
                line = line.strip()
                if line.startswith('NEW_VERSION_ID='):
                    try:
                        new_version_id = int(line.split('=', 1)[1].strip())
                    except ValueError:
                        pass
                if 'AFTER  metrics:' in line or line.startswith('AFTER metrics:'):
                    # "AFTER  metrics:      vol=X frag=Y heal=Z hh=W shortfall=V"
                    payload = line.split('metrics:', 1)[1] if 'metrics:' in line else ''
                    for tok in payload.split():
                        if '=' in tok:
                            k, v = tok.split('=', 1)
                            try:
                                metrics[k.strip()] = int(v.strip())
                            except ValueError:
                                metrics[k.strip()] = v.strip()
                if line.startswith('DELTA:'):
                    metrics['delta_line'] = line

    feasible_assert = new_version_id is not None
    if not feasible_assert:
        log(f'{unit["uid"]} Timefold FAILED: no NEW_VERSION_ID in output')
        return 'FAILED', {}

    metrics['new_version_id'] = new_version_id
    metrics['src_version_id'] = unit['version_id']
    metrics['seed_id']        = unit['seed_id']
    log(f'{unit["uid"]} OK -> new_version={new_version_id} '
        f'frag={metrics.get("frag","?")} vol={metrics.get("vol","?")} '
        f'heal={metrics.get("heal","?")} {metrics.get("delta_line","")}')
    return 'DONE', metrics


def stage3_timefold(state: dict, top_k: int, p3_budget_s: int, dry: bool = False) -> None:
    """Select top-K Phase-2 versions and run Timefold on each."""
    log(f'=== STAGE 3: Timefold Phase-3 optimization (top-{top_k}, {p3_budget_s}s each) ===')
    p3_st = state['phase3']
    done_count = sum(1 for r in p3_st.values() if r.get('status') == 'DONE')

    # Select candidates (may have been selected in a prior (resumed) run)
    if 'candidates' not in state:
        already = consumed_source_versions(state)
        candidates = best_harvest_versions(top_k, exclude=already)
        if already:
            log(f'Excluding {len(already)} Phase-2 version(s) already optimized '
                f'(per pipeline_state.json + phase3_lineage.csv)')
        if not candidates:
            raise sw.Halt('Stage 3: no valid (un-optimized) Phase-2 harvest versions found. '
                          'Stage 2 must produce at least 1 valid run first, '
                          'or all candidates were already sent to Timefold.')
        state['candidates'] = candidates
        log(f'Selected {len(candidates)} Phase-2 candidates for Timefold:')
        for c in candidates:
            log(f'  version={c["version_id"]} seed={c["seed_id"]} '
                f'frag={c["fragile"]} heal={c["healthy"]} vol={c["volunteer"]}')
        if not dry:
            save_state(state)
    else:
        candidates = state['candidates']
        log(f'Resuming with {len(candidates)} previously-selected candidates '
            f'({done_count} already done)')

    consecutive_failures = 0
    for i, cand in enumerate(candidates, 1):
        uid = f'p3-v{cand["version_id"]}'
        if p3_st.get(uid, {}).get('status') == 'DONE':
            log(f'{uid} already done; skipping')
            continue

        unit = {
            'uid':          uid,
            'label':        f'pipeline-p3-{uid}',
            'run':          i,
            'version_id':   cand['version_id'],
            'seed_id':      cand['seed_id'],
            'p3_budget_s':  p3_budget_s,
            'budget':       [0, 0, 0, p3_budget_s + TF_GRACE_S],  # for wait_for_completion
            'log':          os.path.join(RUNS_DIR, f'{uid}.log'),
            'traj':         os.path.join(RUNS_DIR, f'{uid}.traj.csv'),
        }
        log(f'--- Timefold {i}/{len(candidates)}: {uid} '
            f'(src frag={cand["fragile"]} heal={cand["healthy"]}) ---')
        status, metrics = run_timefold(unit, dry=dry)
        p3_st[uid] = {'status': status, **metrics}
        if not dry:
            save_state(state)
            write_phase3_lineage(uid, status, cand, metrics, p3_budget_s)
        write_pipeline_csv('phase3', uid, status, metrics)

        if status == 'DONE':
            done_count += 1
            consecutive_failures = 0
        else:
            consecutive_failures += 1
            if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                raise sw.Halt(f'{consecutive_failures} consecutive Timefold failures — stopping')

        if dry:
            log(f'DRY: would run all {len(candidates)} Timefold optimizations; stopping after 1')
            break

    log(f'Stage 3 complete. {done_count}/{len(candidates)} Timefold runs succeeded.')


# --------------------------------------------------------------------------- outputs

def write_status(current_unit: dict, stage_label: str) -> None:
    lines = ['# Pipeline status', '',
             f'_Updated {sw.fmt_central(time.time())}_', '',
             f'**Stage:** {stage_label}',
             f'**Running now:** `{current_unit["uid"]}`',
             f'**Presumed end:** {sw.fmt_central(time.time() + sum(current_unit["budget"]))}', '',
             'See `PIPELINE_REPORT.md` for cumulative results.',
             'See `pipeline_results.csv` for the raw run log.']
    sw.atomic_write(STATUS, '\n'.join(lines) + '\n')


def write_pipeline_csv(stage: str, uid: str, status: str, metrics: dict) -> None:
    new = not os.path.exists(RESULTS_CSV)
    with open(RESULTS_CSV, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['timestamp', 'stage', 'uid', 'status', 'seed_id',
                        'volunteer', 'fragile', 'healthy', 'heavy_heavy', 'soft',
                        'version_id', 'notes'])
        w.writerow([
            datetime.datetime.now().isoformat(timespec='seconds'),
            stage, uid, status,
            metrics.get('seed_id', ''),
            metrics.get('volunteer', metrics.get('vol', '')),
            metrics.get('fragile',   metrics.get('frag', '')),
            metrics.get('healthy',   metrics.get('heal', '')),
            metrics.get('heavy_heavy', metrics.get('hh', '')),
            metrics.get('soft', ''),
            metrics.get('version_id', metrics.get('new_version_id', '')),
            metrics.get('delta_line', ''),
        ])


def write_phase3_lineage(uid: str, status: str, cand: dict, metrics: dict,
                         p3_budget_s: int) -> None:
    """Record the Phase-3 source->result link the DB does not capture.

    solve_runs stores only the NEW optimized version, never which Phase-2
    version it came from, so 'which P2 runs were already optimized' is otherwise
    unanswerable (and Stage 3 can re-optimize the same source). This ledger closes
    that gap on the pipeline side: one row per Timefold run linking the source
    Phase-2 version to the new version, with before/after metrics and the delta.
    """
    new = not os.path.exists(LINEAGE_CSV)
    new_version = metrics.get('new_version_id', metrics.get('version_id', ''))
    with open(LINEAGE_CSV, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow([
                'timestamp', 'uid', 'status', 'seed_id',
                'src_version_id', 'new_version_id', 'p3_budget_s',
                'src_fragile', 'src_healthy', 'src_volunteer', 'src_heavy_heavy',
                'new_fragile', 'new_healthy', 'new_volunteer', 'new_heavy_heavy', 'new_soft',
                'delta_line',
            ])
        w.writerow([
            datetime.datetime.now().isoformat(timespec='seconds'),
            uid, status, cand.get('seed_id', ''),
            cand.get('version_id', ''), new_version, p3_budget_s,
            cand.get('fragile', ''), cand.get('healthy', ''),
            cand.get('volunteer', ''), cand.get('heavy_heavy', ''),
            metrics.get('frag', ''), metrics.get('heal', ''),
            metrics.get('vol', ''), metrics.get('hh', ''), metrics.get('soft', ''),
            metrics.get('delta_line', ''),
        ])


def reconcile_lineage_csv(state: dict) -> None:
    """Ensure phase3_lineage.csv contains a row for every completed phase3 unit in state.

    The CSV is a derived export, but it can be deleted or drift out of sync (as happened
    when a prior pipeline's 20 runs left it empty). consumed_source_versions() already
    reads state as authoritative, so dedup is safe regardless — but we also backfill any
    missing rows here so external tools (and the skill's inventory query) that read the
    CSV stay consistent. Idempotent: only appends src_version_ids not already present.
    """
    p3 = {uid: r for uid, r in (state.get('phase3') or {}).items()
          if r.get('status') == 'DONE' and r.get('src_version_id') not in (None, '')}
    if not p3:
        return
    present: set[str] = set()
    if os.path.exists(LINEAGE_CSV):
        try:
            with open(LINEAGE_CSV, newline='', encoding='utf-8') as f:
                for row in csv.DictReader(f):
                    v = (row.get('src_version_id') or '').strip()
                    if v:
                        present.add(v)
        except Exception as e:
            log(f'WARNING: could not read lineage CSV for reconcile: {e}')
            return
    missing = [(uid, r) for uid, r in p3.items() if str(r['src_version_id']) not in present]
    if not missing:
        return
    harvest = state.get('harvest') or {}
    src_by_seed = {(r.get('seed_id') or '')[:8]: r for r in harvest.values() if r.get('seed_id')}
    ts = datetime.datetime.now().isoformat(timespec='seconds')
    for uid, r in missing:
        s = src_by_seed.get((r.get('seed_id') or '')[:8], {})
        cand = {'seed_id': r.get('seed_id', ''), 'version_id': r['src_version_id'],
                'fragile': s.get('fragile', ''), 'healthy': s.get('healthy', ''),
                'volunteer': s.get('volunteer', ''), 'heavy_heavy': s.get('heavy_to_heavy', '')}
        write_phase3_lineage(uid, 'DONE', cand, r, r.get('p3_budget_s', 600))
    log(f'Reconciled phase3_lineage.csv: backfilled {len(missing)} missing row(s) from state.')


def write_final_report(state: dict, args: argparse.Namespace) -> None:
    """Write PIPELINE_REPORT.md summarising all three stages."""
    seeds_st   = state.get('seeds',    {})
    harvest_st = state.get('harvest',  {})
    p3_st      = state.get('phase3',   {})
    candidates = state.get('candidates', [])

    seed_done    = sum(1 for r in seeds_st.values()   if r.get('status') == 'DONE')
    harvest_done = sum(1 for r in harvest_st.values() if r.get('status') == 'DONE')
    p3_done      = sum(1 for r in p3_st.values()      if r.get('status') == 'DONE')

    lines = [
        '# Pipeline Run Report',
        '',
        f'_Generated {sw.fmt_central(time.time())}_',
        '',
        '## Configuration',
        '',
        f'- Seeds requested: {args.seeds}',
        f'- Harvest runs: {args.harvest}',
        f'- Phase-3 candidates (top-K): {args.top_k}',
        f'- Timefold budget per run: {args.p3_budget}s',
        '',
        '## Stage Summary',
        '',
        f'| Stage | Requested | Completed |',
        f'|---|---|---|',
        f'| 1 — Seed generation | {args.seeds} pool target | {seed_done} runs (pool may have grown) |',
        f'| 2 — Phase-1/2 harvest | {args.harvest} valid | {harvest_done} |',
        f'| 3 — Timefold Phase-3 | {len(candidates)} candidates | {p3_done} |',
        '',
    ]

    # Stage 2 best harvest results
    lines += [
        '## Stage 2: Best Harvest Versions (ranked)',
        '',
        '| uid | seed | vol | frag | heal | h→h | version |',
        '|---|---|---|---|---|---|---|',
    ]
    harvest_rows = [(uid, r) for uid, r in harvest_st.items() if r.get('status') == 'DONE']
    harvest_rows.sort(key=lambda x: (
        int(x[1].get('fragile', 99) or 99),
        -int(x[1].get('healthy', 0) or 0),
        int(x[1].get('heavy_heavy', 99) or 99)
    ))
    for uid, r in harvest_rows:
        lines.append(f'| {uid} | {r.get("seed_id","")[:8]} | {r.get("volunteer","-")} | '
                     f'{r.get("fragile","-")} | {r.get("healthy","-")} | '
                     f'{r.get("heavy_heavy","-")} | {r.get("version_id","-")} |')
    lines.append('')

    # Stage 3 Timefold results
    lines += [
        '## Stage 3: Timefold Phase-3 Results (ranked by fragile)',
        '',
        '| uid | src_version | seed | vol | frag | heal | delta | new_version |',
        '|---|---|---|---|---|---|---|---|',
    ]
    p3_rows = [(uid, r) for uid, r in p3_st.items() if r.get('status') == 'DONE']
    p3_rows.sort(key=lambda x: (
        int(x[1].get('frag', 99) or 99),
        -int(x[1].get('heal', 0) or 0)
    ))
    for uid, r in p3_rows:
        lines.append(f'| {uid} | {r.get("src_version_id","-")} | {r.get("seed_id","")[:8]} | '
                     f'{r.get("vol","-")} | {r.get("frag","-")} | {r.get("heal","-")} | '
                     f'{r.get("delta_line","")[:60]} | {r.get("new_version_id","-")} |')
    lines.append('')

    # Finalist note
    if p3_done > 0:
        lines += [
            '## Finalist Guidance',
            '',
            'All Phase-3 results above preserved hard feasibility (Timefold asserts hard==0 on commit).',
            'To select a finalist, sort by fragile ASC then healthy DESC.',
            'Use `score_and_snapshot.py --no-save --version <id>` to inspect any version.',
            f'For deeper analysis run: `python analyze_solve_quality.py`',
            '',
        ]

    sw.atomic_write(REPORT, '\n'.join(lines) + '\n')
    log(f'Final report written -> {os.path.basename(REPORT)}')


# --------------------------------------------------------------------------- detach

def respawn_detached(argv_forwarded: list[str]) -> int:
    DETACHED_PROCESS          = 0x00000008
    CREATE_NEW_PROCESS_GROUP  = 0x00000200
    CREATE_BREAKAWAY_FROM_JOB = 0x01000000
    flags = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB
    out = open(os.path.join(ROOT, 'pipeline_driver.out.log'), 'ab')
    err = open(os.path.join(ROOT, 'pipeline_driver.err.log'), 'ab')
    child_args = [sys.executable, os.path.abspath(__file__)] + argv_forwarded
    try:
        p = subprocess.Popen(child_args, cwd=ROOT, stdout=out, stderr=err,
                             stdin=subprocess.DEVNULL, creationflags=flags, close_fds=True)
        return p.pid
    except OSError:
        flags2 = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        p = subprocess.Popen(child_args, cwd=ROOT, stdout=out, stderr=err,
                             stdin=subprocess.DEVNULL, creationflags=flags2, close_fds=True)
        return p.pid


# --------------------------------------------------------------------------- main

def main() -> int:
    ap = argparse.ArgumentParser(
        description='End-to-end pipeline: seed generation -> Phase-1/2 harvest -> Timefold Phase-3')
    ap.add_argument('--seeds',      type=int, default=20,
                    help='Target seed pool size after Stage 1 (default: 20)')
    ap.add_argument('--harvest',    type=int, default=40,
                    help='Number of valid Phase-1/2 harvest runs (default: 40)')
    ap.add_argument('--top-k',      type=int, default=10,
                    help='Top-K Phase-2 versions to send to Timefold (default: 10)')
    ap.add_argument('--p3-budget',  type=int, default=300,
                    help='Timefold optimization budget per run in seconds (default: 300)')
    ap.add_argument('--detach',     action='store_true',
                    help='Re-spawn as job-object-breakaway detached process (for unattended runs)')
    ap.add_argument('--dry-run',    action='store_true',
                    help='Print what would run; no java, no DB writes')
    ap.add_argument('--once-stage', action='store_true',
                    help='Run only the next incomplete stage, then exit')
    ap.add_argument('--skip-seeds',   action='store_true',
                    help='Skip Stage 1 (assume pool is already populated)')
    ap.add_argument('--skip-harvest', action='store_true',
                    help='Skip Stage 2 (assume harvest versions already exist in DB)')
    args = ap.parse_args()

    if args.detach:
        # Forward all args except --detach to the child
        fwd = [a for a in sys.argv[1:] if a != '--detach']
        child_pid = respawn_detached(fwd)
        log(f'--detach: re-spawned detached pipeline PID {child_pid}; parent exiting. '
            f'Progress in PIPELINE_STATUS.md / pipeline_driver.out.log.')
        return 0

    sw.detach_from_console()
    os.makedirs(RUNS_DIR, exist_ok=True)

    if args.dry_run:
        log(f'DRY RUN: seeds={args.seeds} harvest={args.harvest} '
            f'top_k={args.top_k} p3_budget={args.p3_budget}s')
        state = load_state()
        reconcile_lineage_csv(state)
        if not args.skip_seeds:
            stage1_seeds(state, args.seeds, dry=True)
        if not args.skip_harvest:
            stage2_harvest(state, args.harvest, dry=True)
        stage3_timefold(state, args.top_k, args.p3_budget, dry=True)
        log('dry-run complete')
        return 0

    try:
        acquire_lock()
    except sw.Halt as h:
        log('HALT: ' + str(h))
        return 3

    try:
        sw.preflight()
        sw.backup_master('pipeline-start')
        state = load_state()
        reconcile_lineage_csv(state)

        log(f'Pipeline starting: seeds={args.seeds} harvest={args.harvest} '
            f'top_k={args.top_k} p3_budget={args.p3_budget}s')

        if not args.skip_seeds:
            stage1_seeds(state, args.seeds)
            state['stage'] = 'harvest'
            save_state(state)
            if args.once_stage:
                log('--once-stage: Stage 1 complete; stopping')
                write_final_report(state, args)
                return 0

        if not args.skip_harvest:
            stage2_harvest(state, args.harvest)
            state['stage'] = 'phase3'
            save_state(state)
            if args.once_stage:
                log('--once-stage: Stage 2 complete; stopping')
                write_final_report(state, args)
                return 0

        stage3_timefold(state, args.top_k, args.p3_budget)
        state['stage'] = 'done'
        save_state(state)

        write_final_report(state, args)
        log('=== PIPELINE COMPLETE ===')
        log(f'Final report: {REPORT}')
        log(f'Run log: {RESULTS_CSV}')

        # Print finalist summary to stdout for easy capture
        p3_done = [(uid, r) for uid, r in state['phase3'].items() if r.get('status') == 'DONE']
        p3_done.sort(key=lambda x: (int(x[1].get('frag', 99) or 99),
                                    -int(x[1].get('heal', 0) or 0)))
        log('=== TOP TIMEFOLD RESULTS ===')
        for uid, r in p3_done[:5]:
            log(f'  {uid}: frag={r.get("frag","?")} vol={r.get("vol","?")} '
                f'heal={r.get("heal","?")} version={r.get("new_version_id","?")}')

        return 0

    except sw.Halt as h:
        log('HALT: ' + str(h))
        sw.atomic_write(STATUS,
            f'# Pipeline HALTED\n\n_Updated {sw.fmt_central(time.time())}_\n\n'
            f'**Reason:** {h}\n\nSee `pipeline_driver.out.log` for full detail.\n')
        try:
            write_final_report(load_state(), args)
        except Exception:
            pass
        return 2
    finally:
        release_lock()


if __name__ == '__main__':
    sys.exit(main())
