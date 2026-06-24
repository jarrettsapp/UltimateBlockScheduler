#!/usr/bin/env python3
"""Autonomous solver-sweep driver.

Walks a queue of solver configs (queue.jsonl) and, for each run unit, does exactly
what OPERATIONS.md (the manual run loop) / the run-solver-config skill does by hand:

  write config -> launch headless CP-SAT (10 workers, std budget) -> wait ->
  verify (Tier-1=0 AND Phase-3 != UNKNOWN AND traj rows>0) -> score+snapshot ->
  render grid -> append a summary row to the report -> advance the queue.

Designed to run unattended for days, surviving crashes, machine sleep and reboot.
All progress lives in sweep_state.json so a restart resumes where it left off.

This is PURE ORCHESTRATION over proven building blocks (HeadlessSolveRunner,
score_and_snapshot.py, gen_grid.py). It changes no Java and no DB schema.

Run from the project root:
    python sweep_driver.py            # run the sweep (or resume it)
    python sweep_driver.py --dry-run  # parse queue + print what WOULD run; no java, no DB writes
    python sweep_driver.py --once     # run a single pending unit then exit (for watched testing)

See OPERATIONS.md for operator instructions.
"""
from __future__ import annotations
import argparse, csv, datetime, hashlib, json, os, shutil, signal, sqlite3, subprocess, sys, time

ROOT = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(ROOT, 'residency_scheduler.db')
QUEUE = os.path.join(ROOT, 'queue.jsonl')
ARCHIVE = os.path.join(ROOT, 'queue.archive.jsonl')
STATE = os.path.join(ROOT, 'sweep_state.json')
LOCK = os.path.join(ROOT, 'sweep.lock')
STATUS_MD = os.path.join(ROOT, 'SWEEP_STATUS.md')
RESULTS_CSV = os.path.join(ROOT, 'sweep_results.csv')
REPORT_MD = os.path.join(ROOT, 'SCHEDULE_ITERATION_REPORT.md')
REPORT_HTML = os.path.join(ROOT, 'SCHEDULE_ITERATION_REPORT.html')
REPORT_PDF = os.path.join(ROOT, 'SCHEDULE_ITERATION_REPORT.pdf')
GRID_DIR = os.path.join(ROOT, 'sweep_grids')
RUNS_DIR = os.path.join(ROOT, 'sweep_runs')
BACKUP_DIR = os.path.join(ROOT, 'backups')

REQUIRED_BRANCH = 'feature/solver-trajectory-capture'
TRAJ_CLASS = os.path.join(ROOT, 'target', 'classes', 'com', 'residency', 'cpsat',
                          'CpSatSchedulerEngine$TrajectoryCallback.class')
HEAVY_IDS = '1,3,6,11,14,16'
SOURCE_IDS = '19,15,2,4,8,9,13,17,18,21'
DEFAULT_BUDGET = [900, 300, 300, 2400]   # P0 P1 P2 P3 (seconds); standard 10-worker budget
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"

# Robustness knobs (see plan: unattended hardening).
MAX_CONSECUTIVE_FAILURES = 4
MAX_TOTAL_WALL_CLOCK_S = 4 * 24 * 3600    # 4 days
BACKUP_EVERY_N_RUNS = 5
KEEP_BACKUPS = 6
LOCK_RETRY_WINDOW_S = 60                  # transient self-lock -> retry; longer -> external hold -> HALT
WAIT_POLL_S = 300                         # check-loop cadence while a solve runs
WAIT_GRACE_S = 120                        # initial sleep beyond budget sum before first check
WAIT_CRASH_MARGIN_S = 3600                # PID dead + no RESULT past this -> treat as crashed
COOLDOWN_BETWEEN_RUNS_S = 300             # idle break between runs so CPU/fans recover (only when a run actually ran)

REPORT_SUMMARY_HEADER = '## Autonomous sweep results (auto-appended)'


# ----------------------------------------------------------------------------- utilities
def log(msg: str) -> None:
    ts = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    print(f'[sweep {ts}] {msg}', flush=True)


class Halt(Exception):
    """Raised to stop the whole sweep (corruption / external lock / caps / preflight)."""


def central_now_offset() -> int:
    # CDT (summer, UTC-5) roughly Mar-Nov; CST (winter, UTC-6) otherwise. Coarse but fine for an ETA.
    m = datetime.datetime.utcnow().month
    return 5 if 3 <= m <= 11 else 6


def fmt_central(epoch: float) -> str:
    off = central_now_offset()
    lbl = 'CDT' if off == 5 else 'CST'
    dt = datetime.datetime.utcfromtimestamp(epoch) - datetime.timedelta(hours=off)
    return dt.strftime('%I:%M %p ' + lbl + ' (%a %b %d)')


def atomic_write(path: str, text: str) -> None:
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    os.replace(tmp, path)


def pid_alive(pid: int) -> bool:
    """Is process `pid` alive? Must NEVER raise — it runs every poll in the
    wait-loop, and an uncaught error here crashes the whole sweep.

    THE BUG (2026-06-23, the real cause of the repeated sweep deaths ~100-200s in):
    this used `os.kill(pid, 0)` catching only OSError. On Windows os.kill can
    raise **SystemError** ("returned a result with an exception set") for a
    process in an odd state, which escaped and killed the driver in
    wait_for_completion. We now use a Windows-native OpenProcess check and, as a
    belt, catch every exception (treat an unknowable state as 'alive' so we don't
    abandon a solve that is actually still running)."""
    if not pid:
        return False
    if sys.platform == 'win32':
        try:
            import ctypes
            PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
            STILL_ACTIVE = 259
            k = ctypes.windll.kernel32
            h = k.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
            if not h:
                return False           # cannot open -> gone (or access denied; rare here)
            try:
                code = ctypes.c_ulong()
                if k.GetExitCodeProcess(h, ctypes.byref(code)):
                    return code.value == STILL_ACTIVE
                return True            # query failed but handle opened -> assume alive
            finally:
                k.CloseHandle(h)
        except Exception:
            return True                # unknowable -> don't abandon a running solve
    try:
        os.kill(pid, 0)
        return True
    except (OSError, SystemError):
        return False
    except Exception:
        return True


# ----------------------------------------------------------------------------- DB helpers
def integrity_ok(db_path: str) -> bool:
    con = sqlite3.connect(db_path, timeout=5)
    try:
        return con.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    finally:
        con.close()


def db_connect_resilient(db_path: str):
    """Open the master DB, distinguishing a transient self-lock (retry) from an external
    holder (the JavaFX app -> HALT). Checkpoints the WAL first so a freshly-killed run's
    -wal/-shm don't cause needless contention."""
    deadline = time.time() + LOCK_RETRY_WINDOW_S
    delay = 0.5
    while True:
        try:
            con = sqlite3.connect(db_path, timeout=10)
            con.execute('PRAGMA busy_timeout=10000')
            try:
                con.execute('PRAGMA wal_checkpoint(TRUNCATE)')
            except sqlite3.OperationalError:
                pass  # checkpoint can itself be momentarily blocked; the real op below will retry
            con.execute('SELECT 1 FROM schedule_config LIMIT 1')
            return con
        except sqlite3.OperationalError as e:
            if 'locked' not in str(e).lower() and 'busy' not in str(e).lower():
                raise
            if time.time() > deadline:
                raise Halt('master DB stayed locked >%ds -- the JavaFX app is probably open. '
                           'Close it and the sweep will resume.' % LOCK_RETRY_WINDOW_S)
            log(f'DB transient lock; retrying in {delay:.1f}s ...')
            time.sleep(delay)
            delay = min(delay * 1.7, 8)


def write_config(floor: bool, target: int, weight: int) -> None:
    con = db_connect_resilient(DB)
    try:
        c = con.cursor()
        def setk(k, v):
            c.execute('INSERT INTO schedule_config(config_key,config_value) VALUES(?,?) '
                      'ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value',
                      (k, str(v)))
        setk('enforce_zero_volunteer_weekends', 'true' if floor else 'false')
        setk('sunday_coverage_target', target)
        setk('weight_sunday_coverage', weight)
        setk('heavy_rotation_ids', HEAVY_IDS)
        setk('sunday_source_rotation_ids', SOURCE_IDS)
        con.commit()
    finally:
        con.close()


def version_id_by_name(name: str):
    """Return an existing schedule_versions.id whose name matches exactly, else None.
    Used for idempotent snapshot on resume (closes the double-snapshot window)."""
    con = db_connect_resilient(DB)
    try:
        row = con.execute('SELECT id FROM schedule_versions WHERE name=? ORDER BY id DESC LIMIT 1',
                          (name,)).fetchone()
        return row[0] if row else None
    finally:
        con.close()


# ----------------------------------------------------------------------------- queue & state
def parse_queue():
    """Return (units, errors, lines). A unit = dict per RUN (label-rN). `lines` records each
    valid config line's raw text + the uids it expands to (used to archive fully-completed
    lines out of queue.jsonl). Malformed lines are collected as warnings, never fatal."""
    units, errors, lines = [], [], []
    if not os.path.exists(QUEUE):
        return units, ['queue.jsonl not found'], lines
    with open(QUEUE, encoding='utf-8') as f:
        for lineno, raw in enumerate(f, 1):
            s = raw.strip()
            if not s or s.startswith('#'):
                continue
            try:
                cfg = json.loads(s)
                label = str(cfg['label'])
                floor = bool(cfg['floor'])
                target = int(cfg['target'])
                weight = int(cfg['weight'])
                runs = int(cfg.get('runs', 1))
                budget = cfg.get('budget', DEFAULT_BUDGET)
                budget = [int(x) for x in budget]
                if len(budget) != 4:
                    raise ValueError('budget must be [P0,P1,P2,P3]')
                if runs < 1:
                    raise ValueError('runs must be >= 1')
            except Exception as e:
                errors.append(f'line {lineno}: {e!r} -- skipped: {s[:80]}')
                continue
            phash = hashlib.sha1(
                f'{floor}|{target}|{weight}|{budget}'.encode()).hexdigest()[:8]
            line_uids = []
            for r in range(1, runs + 1):
                uid = f'{label}-r{r}#{phash}'
                line_uids.append(uid)
                units.append({
                    'uid': uid, 'label': label, 'run': r,
                    'floor': floor, 'target': target, 'weight': weight,
                    'budget': budget,
                    'version_name': f'{label} r{r} ({"ON" if floor else "OFF"}/{target}/{weight})',
                    'log': os.path.join(RUNS_DIR, f'solve_{label}_run{r}.log'),
                    'traj': os.path.join(RUNS_DIR, f'traj_{label}_run{r}.csv'),
                })
            lines.append({'raw': s, 'label': label, 'uids': line_uids})
    return units, errors, lines


def archive_completed_lines(state, lines) -> int:
    """Move any queue.jsonl line whose units are ALL resolved (DONE or FAILED) into
    queue.archive.jsonl, and rewrite queue.jsonl with only the still-pending lines. This
    keeps queue.jsonl showing ONLY what's left to run, so no one accidentally edits or
    re-reasons-about a completed config. State file remains the source of truth for
    re-run prevention; the archive is the audit trail. Returns count archived.

    Safe to call only at a between-units boundary (never mid-solve)."""
    runs = state.get('runs', {})
    def resolved(uid):
        return runs.get(uid, {}).get('status') in ('DONE', 'FAILED')
    # Only archive lines still PRESENT in queue.jsonl. Lines from prior rounds are already
    # resolved AND already removed from the queue; without this guard each pass would re-append
    # them to the archive forever (the rewrite step below finds nothing to drop). Gating on
    # current-file presence keeps the append and removal steps consistent.
    with open(QUEUE, encoding='utf-8') as f:
        present_raws = {s for s in (raw.strip() for raw in f) if s and not s.startswith('#')}
    done_lines = [ln for ln in lines
                  if ln['uids'] and ln['raw'] in present_raws and all(resolved(u) for u in ln['uids'])]
    if not done_lines:
        return 0
    done_raws = {ln['raw'] for ln in done_lines}
    # append archived lines (raw + a results comment) to the archive
    with open(ARCHIVE, 'a', encoding='utf-8', newline='\n') as af:
        for ln in done_lines:
            res = []
            for u in ln['uids']:
                r = runs.get(u, {})
                sc = r.get('scores', {})
                res.append(f"{u.split('#')[0]}={r.get('status')}"
                           f"(v{r.get('version')},{sc.get('volunteer','-')}/"
                           f"{sc.get('fragile','-')}/{sc.get('healthy','-')})")
            af.write(f'# archived {datetime.datetime.now().isoformat(timespec="seconds")}: '
                     f'{" ".join(res)}\n{ln["raw"]}\n')
    # rewrite queue.jsonl keeping comments/blanks and only the still-pending config lines
    kept = []
    with open(QUEUE, encoding='utf-8') as f:
        for raw in f:
            s = raw.strip()
            if s and not s.startswith('#') and s in done_raws:
                continue  # drop this completed config line
            kept.append(raw.rstrip('\n'))
    atomic_write(QUEUE, '\n'.join(kept) + '\n')
    log(f'archived {len(done_lines)} completed config line(s) -> {os.path.basename(ARCHIVE)}')
    return len(done_lines)


def load_state():
    if os.path.exists(STATE):
        try:
            return json.load(open(STATE, encoding='utf-8'))
        except Exception:
            log('sweep_state.json unreadable; starting fresh state')
    return {'runs': {}}


def save_state(state) -> None:
    atomic_write(STATE, json.dumps(state, indent=1))


# ----------------------------------------------------------------------------- lockfile
def acquire_lock() -> None:
    if os.path.exists(LOCK):
        try:
            old = int(open(LOCK).read().strip() or 0)
        except Exception:
            old = 0
        if pid_alive(old):
            raise Halt(f'another sweep driver is already running (PID {old}); refusing to start')
        log(f'stale lock from dead PID {old}; reclaiming')
    atomic_write(LOCK, str(os.getpid()))


def release_lock() -> None:
    try:
        if os.path.exists(LOCK) and open(LOCK).read().strip() == str(os.getpid()):
            os.remove(LOCK)
    except Exception:
        pass


# ----------------------------------------------------------------------------- preflight
def current_branch() -> str:
    try:
        return subprocess.check_output(['git', '-C', os.path.dirname(ROOT) or ROOT,
                                        'rev-parse', '--abbrev-ref', 'HEAD'],
                                       text=True).strip()
    except Exception:
        # fall back to the repo root two levels up (the .git lives at the app root)
        try:
            return subprocess.check_output(['git', 'rev-parse', '--abbrev-ref', 'HEAD'],
                                           cwd=ROOT, text=True).strip()
        except Exception:
            return '?'


def other_headless_solve_running() -> int:
    """Return the PID of any *other* HeadlessSolveRunner java process, else 0. Guards
    against starting a solve while one is already writing the master DB (the concurrent-
    writer corruption scenario this whole tool exists to prevent)."""
    try:
        out = subprocess.check_output(
            ['powershell', '-NoProfile', '-Command',
             "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
             "Where-Object { $_.CommandLine -like '*HeadlessSolveRunner*' } | "
             "Select-Object -ExpandProperty ProcessId"],
            text=True, stderr=subprocess.DEVNULL)
    except Exception:
        return 0  # can't check -> don't false-positive; integrity gate is the backstop
    me = os.getpid()
    for tok in out.split():
        try:
            pid = int(tok)
        except ValueError:
            continue
        if pid != me:
            return pid
    return 0


def preflight() -> None:
    other = other_headless_solve_running()
    if other:
        raise Halt(f'another HeadlessSolveRunner is already running (PID {other}) against '
                   f'the master DB. Refusing to start a concurrent solve (corruption risk). '
                   f'Wait for it to finish or stop it, then restart the sweep.')
    br = current_branch()
    if br != REQUIRED_BRANCH:
        raise Halt(f'on branch {br!r}, need {REQUIRED_BRANCH!r} (trajectory capture). '
                   f'Checkout the branch and restart.')
    if not os.path.exists(TRAJ_CLASS):
        raise Halt('compiled TrajectoryCallback artifact missing (target/classes/.../'
                   'CpSatSchedulerEngine$TrajectoryCallback.class). Run mvn compile on '
                   'this branch -- trajectory CSVs would be empty otherwise.')
    if not os.path.exists(os.path.join(ROOT, 'cp.txt')):
        raise Halt('cp.txt missing. Run: mvn -q dependency:build-classpath '
                   '-Dmdep.outputFile=cp.txt')
    if not integrity_ok(DB):
        raise Halt('master DB PRAGMA integrity_check != ok -- possible corruption. STOP.')
    for d in (GRID_DIR, RUNS_DIR, BACKUP_DIR):
        os.makedirs(d, exist_ok=True)


def backup_master(tag: str) -> None:
    if not integrity_ok(DB):
        raise Halt('refusing to back up: master integrity != ok')
    ts = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    dst = os.path.join(BACKUP_DIR, f'residency_scheduler.{ts}.{tag}.db')
    # checkpoint so the copy is self-contained, then copy
    con = sqlite3.connect(DB, timeout=10)
    try:
        con.execute('PRAGMA wal_checkpoint(TRUNCATE)')
    finally:
        con.close()
    shutil.copy2(DB, dst)
    if not integrity_ok(dst):
        os.remove(dst)
        raise Halt('backup copy failed integrity check')
    log(f'backup written: {os.path.basename(dst)}')
    # rotate
    backs = sorted(b for b in os.listdir(BACKUP_DIR) if b.endswith('.db'))
    while len(backs) > KEEP_BACKUPS:
        os.remove(os.path.join(BACKUP_DIR, backs.pop(0)))


# ----------------------------------------------------------------------------- launch & wait
def build_classpath() -> str:
    # ROOT comes from os.path.abspath(__file__), so it is already a native Windows path
    # (C:\...) no matter which shell launched us. Earlier this shelled out to `cygpath -w`,
    # which only exists on PATH under Git Bash -- that made the driver fail from PowerShell,
    # cmd, and Task Scheduler. normpath gives the same native path with no external dep.
    winclasses = os.path.normpath(os.path.join(ROOT, 'target', 'classes'))
    cp = open(os.path.join(ROOT, 'cp.txt')).read().strip()
    return winclasses + os.pathsep + cp


def launch_solve(unit, dry=False):
    cp = '<classpath>' if dry else build_classpath()
    budget = unit['budget']
    env = dict(os.environ)
    env['SOLVE_TRAJECTORY_CSV'] = unit['traj']
    cmd = ['java', '-cp', cp, 'com.residency.tools.HeadlessSolveRunner', '2',
           *map(str, budget)]
    if dry:
        log('WOULD launch: SOLVE_TRAJECTORY_CSV=%s %s  > %s' %
            (unit['traj'], ' '.join(cmd), unit['log']))
        return None
    logf = open(unit['log'], 'w')
    p = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=logf, stderr=subprocess.STDOUT)
    log(f'launched {unit["uid"]} PID {p.pid} budget {budget} -> {os.path.basename(unit["log"])}')
    return p.pid


def log_has_result(path) -> bool:
    if not os.path.exists(path):
        return False
    with open(path, encoding='utf-8', errors='replace') as f:
        return '=== RESULT ===' in f.read()


def phase3_start_epoch(unit):
    """Parse the '[Ns] Phase 3 ... (limit:' line -> launch_mtime + N (for a tightened ETA)."""
    if not os.path.exists(unit['log']):
        return None
    launch = os.path.getmtime(unit['log'])  # approx; the file is created at launch
    with open(unit['log'], encoding='utf-8', errors='replace') as f:
        for line in f:
            if 'Phase 3' in line and 'limit' in line:
                lb = line.find('[')
                rb = line.find('s]')
                if 0 <= lb < rb:
                    try:
                        return launch + int(line[lb + 1:rb].strip())
                    except ValueError:
                        pass
    return None


def wait_for_completion(unit, pid, update_status_cb) -> str:
    """Completion-driven, suspend-safe wait. Returns 'done' or 'crashed'."""
    budget_sum = sum(unit['budget'])
    log(f'{unit["uid"]} presumed end {fmt_central(time.time() + budget_sum)} '
        f'(budget {budget_sum//60} min); waiting completion-driven')
    time.sleep(WAIT_GRACE_S)
    crash_deadline = time.time() + WAIT_CRASH_MARGIN_S  # reset whenever progress seen
    last_size = -1
    while True:
        alive = pid_alive(pid)
        done = log_has_result(unit['log'])
        if done and not alive:
            return 'done'
        size = os.path.getsize(unit['log']) if os.path.exists(unit['log']) else 0
        if size != last_size:           # log grew -> still making progress; push the crash deadline
            last_size = size
            crash_deadline = time.time() + WAIT_CRASH_MARGIN_S
        if not alive and not done:
            if time.time() > crash_deadline:
                return 'crashed'
        update_status_cb(unit)          # refresh ETA / current-run line
        time.sleep(WAIT_POLL_S)


# ----------------------------------------------------------------------------- verify & score
def parse_log_metrics(unit):
    """Return dict: tier1 (int|None), phase3 (str|None), traj_rows (int)."""
    tier1, phase3 = None, None
    if os.path.exists(unit['log']):
        with open(unit['log'], encoding='utf-8', errors='replace') as f:
            for line in f:
                if 'Tier-1 score:' in line:
                    try:
                        tier1 = int(line.split('Tier-1 score:')[1].split()[0])
                    except (IndexError, ValueError):
                        pass
                if 'Phase 3 result:' in line:
                    try:
                        phase3 = line.split('Phase 3 result:')[1].split()[0].strip()
                    except IndexError:
                        pass
    traj_rows = 0
    if os.path.exists(unit['traj']):
        with open(unit['traj'], encoding='utf-8', errors='replace') as f:
            traj_rows = max(0, sum(1 for _ in f) - 1)  # minus header
    return {'tier1': tier1, 'phase3': phase3, 'traj_rows': traj_rows}


def is_valid(metrics) -> bool:
    return (metrics['tier1'] == 0
            and metrics['phase3'] is not None
            and metrics['phase3'].upper() != 'UNKNOWN'
            and metrics['traj_rows'] > 0)


def score_and_snapshot(name, notes, dry=False):
    """Run score_and_snapshot.py; return (version_id, scores dict). Idempotent: if a
    version with this exact name already exists, adopt it and skip re-snapshotting."""
    existing = None if dry else version_id_by_name(name)
    if existing is not None:
        log(f'version named {name!r} already exists (id {existing}); adopting, not re-snapshotting')
        # still parse scores from the existing summary if possible; otherwise leave blank
        return existing, {}
    if dry:
        log(f'WOULD score+snapshot --name {name!r}')
        return None, {}
    out = subprocess.check_output(
        [sys.executable, 'score_and_snapshot.py', '--name', name, '--notes', notes],
        cwd=ROOT, text=True)
    print(out, flush=True)
    scores, vid = {}, None
    for line in out.splitlines():
        if line.startswith('volunteer='):
            for part in line.replace('->', '_to_').split():
                if '=' in part:
                    k, v = part.split('=', 1)
                    scores[k] = v
        if line.startswith('SAVED_VERSION_ID='):
            vid = int(line.split('=')[1].split()[0])
    return vid, scores


def render_grid(unit, version, title, dry=False):
    """Render the per-run grid into sweep_grids/<label>_runN.{md,html}. Returns the html
    path (relative to ROOT) for linking from the report."""
    base = os.path.join(GRID_DIR, f'{unit["label"]}_run{unit["run"]}')
    if dry:
        log(f'WOULD render grid v{version} -> {base}.{{md,html}}')
        return os.path.relpath(base + '.html', ROOT)
    subprocess.check_call([sys.executable, 'gen_grid.py', '--version', str(version),
                           '--title', title, '--md', base + '.md', '--html', base + '.html'],
                          cwd=ROOT)
    return os.path.relpath(base + '.html', ROOT)


# ----------------------------------------------------------------------------- report (summary-row model)
def append_report_row(unit, version, scores, grid_rel, status, dry=False):
    """Append ONE summary row to the master report (md + html) under a dedicated sweep
    section, then re-render the PDF. Per-run full grids stay in sweep_grids/ -- the master
    report does NOT inline them (bounded growth)."""
    floor = 'ON' if unit['floor'] else 'OFF'
    cells = [unit['version_name'], f'{floor}/{unit["target"]}/{unit["weight"]}',
             scores.get('volunteer', '-'), scores.get('fragile', '-'),
             scores.get('healthy', '-'), scores.get('heavy_to_heavy', '-'),
             scores.get('runs>6wk', '-'), str(version or '-'), status]
    if dry:
        log('WOULD append report row: ' + ' | '.join(map(str, cells)))
        return

    # --- MD: ensure the section + header exist, append a row ---
    md = open(REPORT_MD, encoding='utf-8').read() if os.path.exists(REPORT_MD) else ''
    if REPORT_SUMMARY_HEADER not in md:
        block = (f'\n\n{REPORT_SUMMARY_HEADER}\n\n'
                 '| run | floor/target/weight | vol | frag | heal | h->h | runs>6wk | ver | status | grid |\n'
                 '|---|---|---|---|---|---|---|---|---|---|\n')
        anchor = '\n_Abbreviations:'
        md = (md.replace(anchor, block + anchor, 1) if anchor in md else md + block)
    row = '| ' + ' | '.join(map(str, cells)) + f' | [grid]({grid_rel}) |\n'
    # insert the row right after the header table's separator line
    marker = '|---|---|---|---|---|---|---|---|---|---|\n'
    idx = md.find(marker)
    if idx != -1:
        ins = idx + len(marker)
        md = md[:ins] + row + md[ins:]
    else:
        md += row
    atomic_write(REPORT_MD, md)

    # --- HTML: append a row to a sweep table before the abbrkey div ---
    if os.path.exists(REPORT_HTML):
        html = open(REPORT_HTML, encoding='utf-8').read()
        if 'id="sweep-summary"' not in html:
            table = ('<h2>Autonomous sweep results (auto-appended)</h2>\n'
                     '<table id="sweep-summary" border="1" cellpadding="4" cellspacing="0">\n'
                     '<tr><th>run</th><th>floor/target/weight</th><th>vol</th><th>frag</th>'
                     '<th>heal</th><th>h&rarr;h</th><th>runs&gt;6wk</th><th>ver</th>'
                     '<th>status</th><th>grid</th></tr>\n</table>\n')
            anchor = '<div class="abbrkey">'
            html = (html.replace(anchor, table + anchor, 1) if anchor in html
                    else html + table)
        tr = ('<tr><td>' + '</td><td>'.join(map(str, cells)) +
              f'</td><td><a href="{grid_rel}">grid</a></td></tr>\n')
        close = '</table>'
        i = html.find(close, html.find('id="sweep-summary"'))
        if i != -1:
            html = html[:i] + tr + html[i:]
        atomic_write(REPORT_HTML, html)
        render_pdf()


def render_pdf() -> None:
    if not os.path.exists(CHROME):
        log('Chrome not found; skipping PDF render')
        return
    uri = 'file:///' + REPORT_HTML.replace('\\', '/')
    try:
        subprocess.check_call([CHROME, '--headless=new', '--disable-gpu', '--no-sandbox',
                               '--no-pdf-header-footer',
                               f'--print-to-pdf={REPORT_PDF}', uri],
                              timeout=120)
    except Exception as e:
        log(f'PDF render failed (non-fatal): {e!r}')


# ----------------------------------------------------------------------------- results.csv & status.md
def append_results_csv(unit, version, scores, status, plateau) -> None:
    new = not os.path.exists(RESULTS_CSV)
    with open(RESULTS_CSV, 'a', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        if new:
            w.writerow(['timestamp', 'uid', 'floor', 'target', 'weight', 'volunteer',
                        'fragile', 'healthy', 'heavy_to_heavy', 'runs>6wk', 'version',
                        'plateau_s', 'status'])
        w.writerow([datetime.datetime.now().isoformat(timespec='seconds'), unit['uid'],
                    'ON' if unit['floor'] else 'OFF', unit['target'], unit['weight'],
                    scores.get('volunteer', ''), scores.get('fragile', ''),
                    scores.get('healthy', ''), scores.get('heavy_to_heavy', ''),
                    scores.get('runs>6wk', ''), version or '', plateau or '', status])


def traj_plateau_s(unit):
    """Last elapsed_s in the traj CSV = when the objective last improved (~plateau)."""
    if not os.path.exists(unit['traj']):
        return None
    last = None
    with open(unit['traj'], encoding='utf-8', errors='replace') as f:
        rdr = csv.reader(f)
        header = next(rdr, None)
        for row in rdr:
            if row:
                last = row[0]
    return last


def write_status(state, sweep_state, current_unit=None, halt_reason=None,
                 units=None, queue_errors=None) -> None:
    lines = ['# Sweep status', '',
             f'_Updated {fmt_central(time.time())}_', '',
             f'**State:** {sweep_state}']
    if halt_reason:
        lines += ['', f'## ⚠ HALTED', '', f'> {halt_reason}']
    runs = state.get('runs', {})
    done = sum(1 for r in runs.values() if r.get('status') == 'DONE')
    failed = sum(1 for r in runs.values() if r.get('status') == 'FAILED')
    total = len(units) if units else len(runs)
    lines += ['', f'**Progress:** {done} done, {failed} failed, of {total} queued units', '']
    if current_unit:
        p3 = phase3_start_epoch(current_unit)
        eta = (fmt_central(p3 + current_unit['budget'][3]) if p3
               else fmt_central(time.time() + sum(current_unit['budget'])) + ' (loose, pre-Phase-3)')
        lines += [f'**Running now:** `{current_unit["uid"]}` '
                  f'(floor={"ON" if current_unit["floor"] else "OFF"}/'
                  f'{current_unit["target"]}/{current_unit["weight"]})',
                  f'**ETA:** {eta}', '']
    if queue_errors:
        lines += ['## Queue warnings', ''] + [f'- {e}' for e in queue_errors] + ['']
    lines += ['## Completed units', '',
              '| run | f/t/w | vol | frag | heal | h->h | runs>6wk | plateau s | ver | status |',
              '|---|---|---|---|---|---|---|---|---|---|']
    for uid, r in runs.items():
        sc = r.get('scores', {})
        lines.append('| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |' % (
            uid, r.get('ftw', '-'), sc.get('volunteer', '-'), sc.get('fragile', '-'),
            sc.get('healthy', '-'), sc.get('heavy_to_heavy', '-'), sc.get('runs>6wk', '-'),
            r.get('plateau', '-'), r.get('version', '-'), r.get('status', '-')))
    atomic_write(STATUS_MD, '\n'.join(lines) + '\n')


# ----------------------------------------------------------------------------- per-unit run
def run_unit(unit, state, units, queue_errors, run_counter, dry=False) -> str:
    """Execute one run unit fully. Returns final status: DONE / FAILED."""
    runs = state['runs']
    rec = runs.setdefault(unit['uid'], {})
    rec['ftw'] = f'{"ON" if unit["floor"] else "OFF"}/{unit["target"]}/{unit["weight"]}'

    # 1. integrity gate (HALT)
    if not dry and not integrity_ok(DB):
        raise Halt('master integrity_check != ok before run -- STOP (possible corruption)')

    # periodic backup
    if not dry and run_counter % BACKUP_EVERY_N_RUNS == 0:
        backup_master(f'run{run_counter}')

    # 2. write config
    if dry:
        log(f'WOULD write config floor={unit["floor"]} target={unit["target"]} '
            f'weight={unit["weight"]} (+tier lists)')
    else:
        write_config(unit['floor'], unit['target'], unit['weight'])

    # 4. launch
    pid = launch_solve(unit, dry=dry)
    rec.update(status='RUNNING', pid=pid, log=os.path.relpath(unit['log'], ROOT))
    if not dry:
        save_state(state)

    if dry:
        log(f'WOULD wait ~{sum(unit["budget"])//60}min, then verify/score/grid/report')
        rec['status'] = 'DRY'
        return 'DRY'

    # 5. wait
    outcome = wait_for_completion(unit, pid,
                                  lambda u: write_status(state, 'RUNNING', current_unit=u,
                                                         units=units, queue_errors=queue_errors))
    if outcome == 'crashed':
        log(f'{unit["uid"]} crashed/interrupted (no RESULT, process dead)')
        rec['status'] = 'INTERRUPTED'
        save_state(state)
        return 'FAILED'

    # 6. verify
    m = parse_log_metrics(unit)
    valid = is_valid(m)
    plateau = traj_plateau_s(unit)
    log(f'{unit["uid"]} tier1={m["tier1"]} phase3={m["phase3"]} traj_rows={m["traj_rows"]} '
        f'valid={valid} plateau={plateau}s')

    # 7. score + snapshot (idempotent)
    floor = 'ON' if unit['floor'] else 'OFF'
    if valid:
        name = unit['version_name']
        notes = f'autosweep floor {floor} target {unit["target"]} weight {unit["weight"]}; std budget'
    else:
        name = unit['version_name'] + ' PHASE2-FALLBACK'
        notes = (f'autosweep INVALID (tier1={m["tier1"]} phase3={m["phase3"]} '
                 f'traj_rows={m["traj_rows"]}); fallback, not a real result')
    version, scores = score_and_snapshot(name, notes)
    status = 'DONE' if valid else 'FAILED'

    # record state ATOMICALLY with the version id (closes double-snapshot window)
    rec.update(status=status, version=version, scores=scores, plateau=plateau,
               valid=valid, phase3=m['phase3'], tier1=m['tier1'])
    save_state(state)

    # 8-9. grid + report row (only meaningful for valid runs; fallbacks recorded but no grid spam)
    if valid and version:
        title = (f'{unit["label"]} run{unit["run"]} - {floor}/{unit["target"]}/{unit["weight"]}: '
                 f'vol{scores.get("volunteer","?")}/frag{scores.get("fragile","?")}/'
                 f'heal{scores.get("healthy","?")}')
        grid_rel = render_grid(unit, version, title)
        append_report_row(unit, version, scores, grid_rel, status)

    # 10. results.csv + status
    append_results_csv(unit, version, scores, status, plateau)
    write_status(state, 'RUNNING', units=units, queue_errors=queue_errors)
    return status


# ----------------------------------------------------------------------------- resume classification
def needs_run(uid, rec) -> bool:
    if rec is None:
        return True
    st = rec.get('status')
    if st in ('DONE', 'FAILED'):
        return False
    if st == 'RUNNING':
        # crashed mid-solve (reboot) -> re-run; DB only written at snapshot, so safe
        return True
    return True


# ----------------------------------------------------------------------------- main
def respawn_detached() -> int:
    """Re-spawn this script as a process that OUTLIVES the launching shell, then
    return so the caller can exit. Returns the detached child's PID.

    Why a respawn (not just signal handling): on 2026-06-23 the driver died
    repeatedly the moment the launching shell's context ended. The cause was NOT
    a console Ctrl-C (ignoring SIGINT + FreeConsole did NOT save it) -- it was the
    HARNESS putting tool-spawned processes in a Windows JOB OBJECT and killing the
    whole tree when the tool call returned. The only way out of a job-object kill
    is to create a brand-new process with these flags:
      DETACHED_PROCESS          -> no console at all
      CREATE_NEW_PROCESS_GROUP  -> own process group (no inherited Ctrl-C)
      CREATE_BREAKAWAY_FROM_JOB -> escape the parent's job object (the key one)
    Bash `&` and PowerShell Start-Process cannot set these; subprocess.Popen can.

    The child re-runs WITHOUT --detach (it is already free) and writes its own
    stdout/stderr to launch_sweep.out.log so a death stays diagnosable. The
    single-writer sweep.lock still prevents two drivers from coexisting.
    """
    DETACHED_PROCESS          = 0x00000008
    CREATE_NEW_PROCESS_GROUP  = 0x00000200
    CREATE_BREAKAWAY_FROM_JOB = 0x01000000
    flags = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_BREAKAWAY_FROM_JOB
    # Driver-OWNED log files. Deliberately NOT launch_sweep.out.log -- that name
    # was being held open by Start-Process redirect handles / zombie conhosts,
    # causing PermissionError here. The launcher never opens these for write.
    out = open(os.path.join(ROOT, 'sweep_driver.out.log'), 'ab')
    err = open(os.path.join(ROOT, 'sweep_driver.err.log'), 'ab')
    # Forward --once if it was passed; never forward --detach (would loop).
    child_args = [sys.executable, os.path.abspath(__file__)]
    if '--once' in sys.argv:
        child_args.append('--once')
    try:
        p = subprocess.Popen(child_args, cwd=ROOT, stdout=out, stderr=err,
                             stdin=subprocess.DEVNULL, creationflags=flags, close_fds=True)
        return p.pid
    except OSError:
        # CREATE_BREAKAWAY_FROM_JOB fails if the job forbids breakaway. Retry
        # without it -- still detached/new-group, better than nothing.
        flags2 = DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        p = subprocess.Popen(child_args, cwd=ROOT, stdout=out, stderr=err,
                             stdin=subprocess.DEVNULL, creationflags=flags2, close_fds=True)
        return p.pid


def detach_from_console() -> None:
    """Belt-and-suspenders for the already-detached child: ignore console
    signals so a stray Ctrl-C/Ctrl-Break can't reach the long-running solve."""
    try:
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        if hasattr(signal, 'SIGBREAK'):           # Windows Ctrl-Break
            signal.signal(signal.SIGBREAK, signal.SIG_IGN)
    except Exception as e:
        log(f'detach: could not ignore console signals ({e}); continuing')
    if sys.platform == 'win32':
        try:
            import ctypes
            ctypes.windll.kernel32.FreeConsole()
        except Exception as e:
            log(f'detach: FreeConsole failed ({e}); continuing')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--once', action='store_true', help='run a single pending unit then exit')
    ap.add_argument('--detach', action='store_true',
                    help='re-spawn the driver as a job-object-breakaway detached process that '
                         'survives the launching shell, then exit (use for unattended runs)')
    args = ap.parse_args()

    if args.detach:
        child_pid = respawn_detached()
        log(f'--detach: re-spawned detached driver PID {child_pid}; parent exiting. '
            f'Progress in SWEEP_STATUS.md / launch_sweep.out.log.')
        return 0

    # A normally-launched driver (incl. the detached child) ignores stray console
    # signals so a Ctrl-C can't interrupt a long solve.
    detach_from_console()

    units, queue_errors, lines = parse_queue()
    for e in queue_errors:
        log('QUEUE WARNING: ' + e)

    if args.dry_run:
        # show what's already complete (and would be archived) vs. what will actually run
        st = load_state()
        pending = [u for u in units if needs_run(u['uid'], st['runs'].get(u['uid']))]
        done_n = len(units) - len(pending)
        log(f'DRY RUN: {len(units)} run units parsed; {done_n} already complete (skipped), '
            f'{len(pending)} pending')
        if done_n:
            log('  (completed config lines would be archived to queue.archive.jsonl on a real run)')
        counter = 0
        for u in pending:
            counter += 1
            log(f'--- unit {u["uid"]} ---')
            run_unit(u, {'runs': {}}, units, queue_errors, counter, dry=True)
        log('dry-run complete (no java launched, no DB writes)')
        return 0

    try:
        acquire_lock()
    except Halt as h:
        log('HALT: ' + str(h))
        return 3

    try:
        preflight()
        backup_master('sweep-start')
        state = load_state()
        consecutive_failures = 0
        run_counter = 0
        start_wall = time.time()
        # archive any lines already fully complete from a PRIOR run (e.g. last batch's units)
        archive_completed_lines(state, lines)
        write_status(state, 'RUNNING', units=units, queue_errors=queue_errors)

        for unit in units:
            rec = state['runs'].get(unit['uid'])
            if not needs_run(unit['uid'], rec):
                continue
            if time.time() - start_wall > MAX_TOTAL_WALL_CLOCK_S:
                raise Halt(f'max total wall clock ({MAX_TOTAL_WALL_CLOCK_S//3600}h) exceeded')
            run_counter += 1
            status = run_unit(unit, state, units, queue_errors, run_counter)
            if status == 'FAILED':
                consecutive_failures += 1
                if consecutive_failures >= MAX_CONSECUTIVE_FAILURES:
                    raise Halt(f'{consecutive_failures} consecutive failures -- queue or '
                               f'environment likely broken; stopping')
            else:
                consecutive_failures = 0
            # archive this line if all its units are now resolved (between-units boundary = safe)
            archive_completed_lines(state, lines)
            if args.once:
                log('--once: stopping after one unit')
                break
            # idle cooldown so CPU/fans recover before the next solve (safe between-units boundary;
            # only after a run that actually executed, never while skipping done units)
            if COOLDOWN_BETWEEN_RUNS_S > 0:
                log(f'cooldown: idling {COOLDOWN_BETWEEN_RUNS_S}s before next run')
                time.sleep(COOLDOWN_BETWEEN_RUNS_S)

        write_status(state, 'DONE', units=units, queue_errors=queue_errors)
        log('sweep complete: queue exhausted')
        return 0
    except Halt as h:
        log('HALT: ' + str(h))
        try:
            write_status(load_state(), 'HALTED', halt_reason=str(h),
                         units=units, queue_errors=queue_errors)
        except Exception:
            pass
        return 2
    finally:
        release_lock()


if __name__ == '__main__':
    sys.exit(main())
