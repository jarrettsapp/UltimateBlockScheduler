#!/usr/bin/env python3
"""Mass-harvest driver — produce N seeded Phase-2-only schedules, record each cleanly.

WHY (the pivot, see PHASE3_HARVEST_HANDOFF.md): no hint/pin lane RELIABLY engages Phase 3
(OR-Tools #5025; per-seed coin-flip, ~⅓ optimize, and a non-engaging run burns the full
~900s P3 budget win-or-lose). But every run's Phase-2 result already scores at/above the v7
benchmark (fragile 7–11, h→h 0). So instead of chasing one reliable optimized schedule, we
MASS-PRODUCE many seeded Phase-2 schedules fast, record every one cleanly into the solve_runs
family, and cherry-pick the best. Targeted Phase-3 optimization on the top-K seeds is a later,
gated phase (set PHASE3_SKIP=0 on those runs).

WHAT it does, per harvest unit (sequential, one DB writer at a time):

  (config left as-is; harvest does NOT rewrite schedule_config) ->
  launch headless CP-SAT with PHASE0_SEED_SELECT=roundrobin (distinct seed each run) and
  PHASE3_SKIP on (Phase-2-only; tiny P3 budget that is never used) -> wait completion-driven ->
  verify (exit 0 / feasible schedule committed / Tier-1=0) -> score_and_snapshot.py (WITHOUT
  --no-save, WITH telemetry args) so a durable solve_runs row lands (run_status=PHASE2_FALLBACK,
  metrics fragile/healthy/h->h) -> append a CSV row -> advance.

This is PURE ORCHESTRATION reusing sweep_driver's proven, battle-tested building blocks
(launch/wait/integrity/lock/detach). It changes no Java and no DB schema. Its lock/state/output
files are DISTINCT from the sweep's, so the two never collide.

Run from the project root:
    python harvest_driver.py --count 5                 # harvest 5 seeded schedules
    python harvest_driver.py --count 25 --detach       # unattended (job-object breakaway)
    python harvest_driver.py --count 3 --dry-run        # parse + print what WOULD run; no java
    python harvest_driver.py --count 1                  # single-run smoke test

NOTE on solve_runs.feasible for harvest rows: a Phase-2-only run has NO "Phase 3 result:" line,
so score_and_snapshot classifies it run_status='PHASE2_FALLBACK' with feasible=0 and
final_objective=NULL — BY DESIGN. That feasible=0 means "no Phase-3 incumbent", NOT "schedule
infeasible": the committed schedule IS feasible (Tier-1=0, validated). Rank harvest rows by the
metrics (fragile / heavy_heavy / healthy), never by final_objective (which is NULL for fallbacks).
See the harvest section of SCHEDULE_SEARCH_PLAN.md.
"""
from __future__ import annotations
import argparse, csv, datetime, os, subprocess, sys, time

# Reuse the sweep driver's hardened primitives verbatim (no fork of the tricky bits).
import sweep_driver as sw

ROOT = sw.ROOT
DB = sw.DB
RUNS_DIR = os.path.join(ROOT, 'harvest_runs')
RESULTS_CSV = os.path.join(ROOT, 'harvest_results.csv')
STATE = os.path.join(ROOT, 'harvest_state.json')
LOCK = os.path.join(ROOT, 'harvest.lock')
STATUS_MD = os.path.join(ROOT, 'HARVEST_STATUS.md')

# Phase-2-only budget: real P0/P1/P2, and a TINY P3 that is never reached (PHASE3_SKIP on skips
# the Phase-3 build/solve entirely). These are CEILINGS, not fixed spends — P1/P2 return the instant
# they prove OPTIMAL (measured 2026-06-25: a seed hit P1 OPTIMAL at ~50s, P2 ~2s more). The ceilings
# are LIBERAL on purpose: P1 is the slow phase and convergence time varies a lot by seed (a ramp run
# clipped its old 120s P1 cap → truncated/invalid). A generous ceiling costs fast seeds nothing and
# lets the slow tail PROVE OPTIMAL; only a genuinely-stuck seed runs long, and that schedule (resting
# on a non-optimal phase) is one we want flagged + excluded, not silently banked. P0 is just a cold
# cache-miss ceiling (seeded validation is ~3s).
HARVEST_BUDGET = [300, 600, 300, 5]   # P0 P1 P2 P3(unused-skipped)
# No cooldown: unlike the sweep (40+ min all-core 10-worker solves → 300s thermal recovery), harvest
# runs are Phase-2-only/seeded/~30-60s and don't sustain a heavy load, so a gap is mostly dead time.
# The next run's safety doesn't depend on it either — the driver already gates on the prior PID being
# dead + DB integrity + the single-writer lock before launching.
COOLDOWN_BETWEEN_RUNS_S = 0


def log(msg: str) -> None:
    ts = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    print(f'[harvest {ts}] {msg}', flush=True)


# --------------------------------------------------------------------- lock (own file)
def acquire_lock() -> None:
    if os.path.exists(LOCK):
        try:
            old = int(open(LOCK).read().strip() or 0)
        except Exception:
            old = 0
        if sw.pid_alive(old):
            raise sw.Halt(f'another harvest driver is already running (PID {old}); refusing to start')
        log(f'stale harvest lock from dead PID {old}; reclaiming')
    sw.atomic_write(LOCK, str(os.getpid()))


def release_lock() -> None:
    try:
        if os.path.exists(LOCK) and open(LOCK).read().strip() == str(os.getpid()):
            os.remove(LOCK)
    except Exception:
        pass


# --------------------------------------------------------------------- launch (Phase-2-only, seeded)
def launch_harvest(unit, dry=False):
    cp = '<classpath>' if dry else sw.build_classpath()
    env = dict(os.environ)
    env['PHASE3_SKIP'] = '1'                    # Phase-2-only: never attempt/burn Phase 3
    env['SOLVE_TRAJECTORY_CSV'] = unit['traj']  # harmless; stays empty without Phase 3
    if unit.get('fixed_seed'):
        # Pin ONE seed across all runs (seed→seed variance / ICC experiment). roundrobin/random
        # can't repeat a seed, so we pass PHASE0_SEED_ID instead (engine matches by id prefix).
        env['PHASE0_SEED_ID'] = unit['fixed_seed']
        env.pop('PHASE0_SEED_SELECT', None)
    else:
        env['PHASE0_SEED_SELECT'] = 'roundrobin'   # distinct seed each run (fair pool coverage)
    cmd = ['java', '-cp', cp, 'com.residency.tools.HeadlessSolveRunner', '2',
           *map(str, HARVEST_BUDGET)]
    seed_env = (f'PHASE0_SEED_ID={unit["fixed_seed"]}' if unit.get('fixed_seed')
                else 'PHASE0_SEED_SELECT=roundrobin')
    if dry:
        log('WOULD launch: %s PHASE3_SKIP=1 %s  > %s' % (seed_env, ' '.join(cmd), unit['log']))
        return None
    logf = open(unit['log'], 'w')
    p = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=logf, stderr=subprocess.STDOUT)
    log(f'launched {unit["uid"]} PID {p.pid} (Phase-2-only, seeded) -> {os.path.basename(unit["log"])}')
    return p.pid


# --------------------------------------------------------------------- verify (Phase-2-only criterion)
def parse_harvest_metrics(unit):
    """Return dict: feasible (bool), tier1 (int|None), seed_id (str|None). Phase-2-only runs
    have NO 'Phase 3 result:' line, so we do NOT gate on Phase 3 here (unlike the sweep)."""
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
                    # engine logs "...seeded from seed_id=<id8> (...)" — capture the id token
                    tok = line.split('seed_id=', 1)[1].split()[0].strip().strip('().,')
                    if tok:
                        seed_id = tok
    return {'feasible': feasible, 'tier1': tier1, 'seed_id': seed_id}


def is_valid(m) -> bool:
    """A harvest run is VALID iff it committed a feasible schedule with Tier-1 = 0 (the hard
    inpatient-split constraint must hold). We deliberately do NOT require any Phase-3 signal."""
    return bool(m['feasible']) and m['tier1'] == 0


# --------------------------------------------------------------------- per-unit run
def run_unit(unit, run_counter, dry=False) -> tuple[str, dict]:
    # integrity gate (HALT) — same backstop the sweep uses before every writer run
    if not dry and not sw.integrity_ok(DB):
        raise sw.Halt('master integrity_check != ok before run -- STOP (possible corruption)')

    pid = launch_harvest(unit, dry=dry)
    if dry:
        log(f'WOULD wait ~{sum(HARVEST_BUDGET)//60 or 1}min, then verify/score (no Phase 3)')
        return 'DRY', {}

    outcome = sw.wait_for_completion(unit, pid, lambda u: write_status(unit, run_counter))
    if outcome == 'crashed':
        log(f'{unit["uid"]} crashed/interrupted (no RESULT, process dead)')
        return 'FAILED', {}

    m = parse_harvest_metrics(unit)
    valid = is_valid(m)
    log(f'{unit["uid"]} feasible={m["feasible"]} tier1={m["tier1"]} seed={m["seed_id"]} valid={valid}')

    name = f'{unit["label"]} ({"OK" if valid else "INVALID"})'
    notes = (f'mass-harvest seeded Phase-2-only; PHASE0_SEED_SELECT=roundrobin; '
             f'seed={m["seed_id"]}; ' + ('feasible Tier-1=0' if valid
             else f'INVALID feasible={m["feasible"]} tier1={m["tier1"]}'))
    # Score+snapshot WITHOUT --no-save so the durable solve_runs row is written, WITH the telemetry
    # args so the solve log/trajectory feed the solve_runs family. data_epoch tags the harvest batch.
    version, scores = sw.score_and_snapshot(name, notes, unit=unit)
    status = 'DONE' if valid else 'FAILED'
    append_results_csv(unit, version, scores, m, status)
    return status, scores


# --------------------------------------------------------------------- outputs
def append_results_csv(unit, version, scores, m, status) -> None:
    new = not os.path.exists(RESULTS_CSV)
    with open(RESULTS_CSV, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['timestamp', 'uid', 'seed_id', 'volunteer', 'fragile', 'healthy',
                        'heavy_to_heavy', 'runs>6wk', 'version', 'status'])
        w.writerow([datetime.datetime.now().isoformat(timespec='seconds'), unit['uid'],
                    m.get('seed_id', '') or '', scores.get('volunteer', ''),
                    scores.get('fragile', ''), scores.get('healthy', ''),
                    scores.get('heavy_to_heavy', ''), scores.get('runs>6wk', ''),
                    version or '', status])


def write_status(current_unit, run_counter) -> None:
    lines = ['# Harvest status', '', f'_Updated {sw.fmt_central(time.time())}_', '',
             f'**Running now:** `{current_unit["uid"]}` (run {run_counter})',
             f'**Presumed end:** {sw.fmt_central(time.time() + sum(HARVEST_BUDGET))}', '',
             'Per-run results accumulate in `harvest_results.csv` and the durable `solve_runs` family.',
             'Rank harvest by metrics (fragile / heavy_to_heavy / healthy), NOT final_objective.']
    sw.atomic_write(STATUS_MD, '\n'.join(lines) + '\n')


# --------------------------------------------------------------------- main
def build_units(count, batch_tag, fixed_seed=None):
    units = []
    for i in range(1, count + 1):
        label = f'harvest-{batch_tag}-{i:03d}'
        units.append({
            'uid': f'{label}',
            'label': label,
            'run': i,
            'fixed_seed': fixed_seed,   # None ⇒ roundrobin; set ⇒ pin this seed every run
            'budget': HARVEST_BUDGET,   # wait_for_completion reads unit['budget']
            'log': os.path.join(RUNS_DIR, f'{label}.log'),
            'traj': os.path.join(RUNS_DIR, f'{label}.traj.csv'),
        })
    return units


def respawn_detached(count, batch_tag, fixed_seed=None) -> int:
    DETACHED_PROCESS = 0x00000008
    CREATE_NEW_PROCESS_GROUP = 0x00000200
    CREATE_BREAKAWAY_FROM_JOB = 0x01000000
    flags = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB
    out = open(os.path.join(ROOT, 'harvest_driver.out.log'), 'ab')
    err = open(os.path.join(ROOT, 'harvest_driver.err.log'), 'ab')
    child_args = [sys.executable, os.path.abspath(__file__),
                  '--count', str(count), '--batch-tag', batch_tag]
    if fixed_seed:
        child_args += ['--fixed-seed', fixed_seed]
    try:
        p = subprocess.Popen(child_args, cwd=ROOT, stdout=out, stderr=err,
                             stdin=subprocess.DEVNULL, creationflags=flags, close_fds=True)
        return p.pid
    except OSError:
        flags2 = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        p = subprocess.Popen(child_args, cwd=ROOT, stdout=out, stderr=err,
                             stdin=subprocess.DEVNULL, creationflags=flags2, close_fds=True)
        return p.pid


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--count', type=int, required=True, help='number of seeded schedules to harvest')
    ap.add_argument('--batch-tag', default=None, help='label suffix for this batch (default: timestamp)')
    ap.add_argument('--fixed-seed', default=None,
                    help='pin ONE seed id (full hash or prefix, e.g. 8-char) for ALL runs — for the '
                         'seed→seed variance / ICC experiment. Default: roundrobin (distinct seed/run).')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--detach', action='store_true', help='re-spawn job-object-breakaway detached, then exit')
    args = ap.parse_args()
    if args.count < 1:
        log('--count must be >= 1'); return 2

    batch_tag = args.batch_tag or datetime.datetime.now().strftime('%m%d-%H%M')

    if args.detach:
        child = respawn_detached(args.count, batch_tag, args.fixed_seed)
        log(f'--detach: re-spawned detached harvest PID {child}; parent exiting. '
            f'Progress in HARVEST_STATUS.md / harvest_driver.out.log.')
        return 0

    sw.detach_from_console()
    # Tighten the inherited sweep wait cadence: harvest runs are ~30-90s, not ~hour-long sweeps, so
    # the sweep's 120s grace + 300s poll would idle the driver for minutes after a finished solve.
    # Overriding the module globals (we import sweep_driver) affects only THIS process, not the sweep.
    sw.WAIT_GRACE_S = 15
    sw.WAIT_POLL_S = 15
    os.makedirs(RUNS_DIR, exist_ok=True)
    units = build_units(args.count, batch_tag, args.fixed_seed)

    seed_note = (f'PINNED seed {args.fixed_seed}' if args.fixed_seed else 'roundrobin (distinct/run)')
    if args.dry_run:
        log(f'DRY RUN: would harvest {len(units)} seeded Phase-2-only schedule(s), batch {batch_tag!r}, {seed_note}')
        for i, u in enumerate(units, 1):
            run_unit(u, i, dry=True)
        log('dry-run complete (no java launched, no DB writes)')
        return 0

    try:
        acquire_lock()
    except sw.Halt as h:
        log('HALT: ' + str(h)); return 3

    try:
        # Preflight: reuse the sweep's checks (branch, compiled traj class, cp.txt, integrity,
        # no other HeadlessSolveRunner). These guarantee a safe single-writer solve environment.
        sw.preflight()
        done = failed = 0
        for i, unit in enumerate(units, 1):
            log(f'--- harvest unit {i}/{len(units)}: {unit["uid"]} ---')
            status, scores = run_unit(unit, i)
            if status == 'DONE':
                done += 1
                log(f'{unit["uid"]} OK  vol={scores.get("volunteer","?")} '
                    f'frag={scores.get("fragile","?")} heal={scores.get("healthy","?")} '
                    f'h->h={scores.get("heavy_to_heavy","?")}')
            else:
                failed += 1
            if i < len(units) and COOLDOWN_BETWEEN_RUNS_S > 0:
                log(f'cooldown: idling {COOLDOWN_BETWEEN_RUNS_S}s before next run')
                time.sleep(COOLDOWN_BETWEEN_RUNS_S)
        log(f'harvest complete: {done} ok, {failed} failed, of {len(units)} requested')
        write_status({'uid': f'(complete: {done}/{len(units)})'}, len(units))
        return 0
    except sw.Halt as h:
        log('HALT: ' + str(h)); return 2
    finally:
        release_lock()


if __name__ == '__main__':
    sys.exit(main())
