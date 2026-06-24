#!/usr/bin/env bash
# Phase-0 feasible-assignment POOL SEEDING run (Option 1 dedicated collection).
#
# Builds up a pool of DISTINCT feasible Phase-0 assignments by running cold solves
# (PHASE0_CACHE_COLLECT=1 → no replay, fresh random seed each run, persist new finds).
# Reports, per run and in summary: pool size, NEW finds, DUPLICATE finds (dedup hit rate),
# caps, and the collection RATE (assignments/hour) so the user can choose the pool cap
# from data rather than guessing. Open-ended: runs until N solves complete (or you Ctrl-C;
# the pool persists in the working DB either way).
#
# Unlike phase0_fix.sh / phase0_decomp.sh, this is STATEFUL: it runs every solve against the
# SAME working DB so the pool accumulates. The LIVE DB is never touched — we seed into a copy.
#
# Usage:  ./phase0_seed_pool.sh [N] [P0_CAP] [POOL_MAX]
#   N         number of collection solves to run (default 30)
#   P0_CAP    Phase-0 time cap seconds per solve (default 300)
#   POOL_MAX  pool cap; set high to not bottleneck collection (default 100)
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1

N="${1:-30}"
P0_CAP="${2:-300}"
POOL_MAX="${3:-100}"

CP="target/classes;$(cat cp.txt)"
LIVE="residency_scheduler.db"
SAFE="residency_scheduler.seed-safe-$(date +%Y%m%d-%H%M%S).db"   # untouched backup of live
POOLDB="residency_scheduler.pool.db"                             # PERSISTENT working DB (accumulates)
OUT="$ROOT/phase0_seed_logs"
RESULTS="$ROOT/phase0_seed_results.csv"
mkdir -p "$OUT"

if [ ! -f "$LIVE" ]; then echo "FATAL: $LIVE not found"; exit 1; fi

# SINGLE-INSTANCE GUARD: refuse to start if another seeder is already running. Stopping a
# background seeder did NOT always reap its detached bash+java children, leaving ZOMBIE
# seeders that raced on the shared pool DB / CSV and corrupted both. This lock prevents a
# second seeder from ever overlapping a first. A clean exit removes the lock; a stale lock
# whose PID is dead is reclaimed.
LOCK="$ROOT/.seed_pool.lock"
if [ -f "$LOCK" ]; then
  oldpid="$(cat "$LOCK" 2>/dev/null)"
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    echo "FATAL: another seeder is running (pid $oldpid, lock $LOCK). Stop it first."; exit 1
  fi
  echo "[reclaiming stale lock from dead pid $oldpid]"
fi
echo "$$" > "$LOCK"

cp "$LIVE" "$SAFE" || { echo "FATAL: backup failed"; rm -f "$LOCK"; exit 1; }

# Pool keys can survive INSIDE the live DB (the engine writes them there), so a "deleted
# POOLDB file" is NOT a clean start — a stale pool baked into LIVE silently pre-seeds the
# count. FRESH=1 wipes any existing pool keys so the session starts genuinely empty; default
# (FRESH unset) preserves them so a session CONTINUES an existing pool.
if [ "${FRESH:-0}" = "1" ]; then
  python - "$LIVE" <<'PY'
import sys, sqlite3
c=sqlite3.connect(sys.argv[1])
c.execute("DELETE FROM schedule_config WHERE config_key LIKE 'phase0_feasible_pool%'")
c.commit(); c.execute("PRAGMA wal_checkpoint(TRUNCATE)"); c.commit(); c.close()
print("[FRESH: cleared all phase0_feasible_pool* keys from live DB]")
PY
  rm -f "$POOLDB" "$POOLDB-wal" "$POOLDB-shm"
fi

# Seed the persistent pool DB from a fresh copy of live ONLY if it doesn't already exist,
# so re-running this script CONTINUES growing an existing pool instead of wiping it.
if [ ! -f "$POOLDB" ]; then cp "$LIVE" "$POOLDB"; echo "[created fresh pool DB $POOLDB]"; else echo "[continuing existing pool DB $POOLDB]"; fi
# Track the current child solve PID so a stop/trap can kill it (not orphan it).
CHILD_PID=""
cleanup() {
  [ -n "$CHILD_PID" ] && kill "$CHILD_PID" 2>/dev/null
  cp "$SAFE" "$LIVE" 2>/dev/null
  rm -f "$LOCK"
  echo "[cleanup: killed child $CHILD_PID, restored live DB from $SAFE, removed lock; pool kept in $POOLDB]"
}
trap cleanup EXIT INT TERM

# WAL CHECKPOINT (critical): the app opens SQLite in WAL mode, so writes land in a
# <db>-wal sidecar, NOT the main .db file. A plain `cp <db>` copies only the main file and
# SILENTLY DROPS everything in the WAL — which corrupted the first seeding attempt (the pool
# oscillated/shrank instead of growing). Before copying a DB, force its WAL fully into the
# main file and empty the WAL with PRAGMA wal_checkpoint(TRUNCATE), so the single .db file is
# always complete and self-contained.
checkpoint() {
  python - "$1" <<'PY'
import sys, sqlite3
try:
    c=sqlite3.connect(sys.argv[1])
    c.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    c.commit(); c.close()
except Exception as e:
    sys.stderr.write("checkpoint failed: %s\n" % e)
PY
}
# Copy a DB safely = checkpoint source first, then copy main file + any remaining sidecars.
copydb() { checkpoint "$1"; cp "$1" "$2"; rm -f "$2-wal" "$2-shm"; }

# pool size = number of records in the cache blob (records separated by ␞ = U+241E).
pool_size() {
  python - "$1" <<'PY'
import sys, sqlite3
try:
    c=sqlite3.connect(sys.argv[1]); cur=c.cursor()
    # Match the POOL blob only — NOT the fingerprint key (phase0_feasible_pool_fp_<year>),
    # which the bare LIKE '..._%' would also catch and miscount as 1.
    cur.execute("SELECT config_value FROM schedule_config "
                "WHERE config_key LIKE 'phase0_feasible_pool_%' "
                "AND config_key NOT LIKE 'phase0_feasible_pool_fp_%'")
    row=cur.fetchone()
    if not row or not row[0]: print(0)
    else: print(sum(1 for r in row[0].split('␞') if r.strip()))
except Exception:
    print(0)
PY
}

echo "run,phase0_status,phase0_secs,pool_size,delta" > "$RESULTS"
echo "Phase-0 POOL SEEDING: N=$N solves; P0 cap ${P0_CAP}s; POOL_MAX=$POOL_MAX"
echo "Live DB backed up to $SAFE (restored on exit). Pool persists in $POOLDB."
START_EPOCH=$(date +%s)
echo "Started: $(date '+%Y-%m-%d %H:%M:%S %Z')"

prev=$(pool_size "$POOLDB")
echo "  starting pool size: $prev"

for i in $(seq 1 "$N"); do
  copydb "$POOLDB" "$LIVE"                    # solve runs against the live path (WAL-safe copy)...
  log="$OUT/seed_run_$i.log"
  # Collect with the feasibility_jump portfolio (PHASE0_PORTFOLIO=fj): it reaches feasibility
  # ~10× faster than cold monolithic, so each session fills the pool far quicker. Speed at
  # collect time is pure throughput — production runs replay from the pool, they don't re-solve.
  PHASE0_FIX=cache PHASE0_CACHE_COLLECT=1 PHASE0_PORTFOLIO=fj PHASE0_CACHE_POOL_MAX="$POOL_MAX" \
    java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 "$P0_CAP" 1 1 1 > "$log" 2>&1 &
  CHILD_PID=$!
  wait "$CHILD_PID"; CHILD_PID=""    # foreground-wait but track PID so a stop can kill it
  copydb "$LIVE" "$POOLDB"                    # ...then commit the updated pool back (WAL-safe)

  pline="$(grep -E 'Phase 0 result:' "$log" | tail -1 | tr -d '\r')"
  pstatus="$(echo "$pline" | sed -E 's/.*result: *([A-Z_]+).*/\1/')"
  psecs="$(echo "$pline" | sed -E 's/.*\(([0-9.]+)s\).*/\1/')"
  [ -z "$pline" ] && { pstatus="NA"; psecs="NA"; }
  cur=$(pool_size "$POOLDB")
  # MONOTONIC GUARD: the pool must never SHRINK. A shrink means a write was lost (the WAL bug)
  # — abort immediately rather than silently collect into a leaky bucket.
  if [ "$cur" -lt "$prev" ]; then
    echo "FATAL: pool SHRANK ($prev → $cur) at run $i — write lost (WAL/copy bug). Aborting." | tee -a "$RESULTS"
    exit 2
  fi
  delta=$((cur - prev)); prev=$cur
  printf '%s,%s,%s,%s,%s\n' "$i" "$pstatus" "$psecs" "$cur" "$delta" | tee -a "$RESULTS"
  # Optional cooldown between collect solves so CPU/fans recover over a long seeding session
  # (see sweep-cooldown finding). Default 15s; set COOLDOWN_S=0 to disable.
  if [ "$i" -lt "$N" ] && [ "${COOLDOWN_S:-15}" -gt 0 ]; then sleep "${COOLDOWN_S:-15}"; fi
done

END_EPOCH=$(date +%s); ELAPSED=$((END_EPOCH - START_EPOCH))
echo
echo "Finished: $(date '+%Y-%m-%d %H:%M:%S %Z')  (elapsed ${ELAPSED}s)"
echo "=== pool seeding summary ==="
python - "$RESULTS" "$ELAPSED" <<'PY'
import sys, csv
rows=[r for r in csv.DictReader(open(sys.argv[1])) if r.get('run')]
elapsed=float(sys.argv[2])
n=len(rows)
caps=sum(1 for r in rows if r['phase0_status'] in ('UNKNOWN','NA','INFEASIBLE'))
feas=[r for r in rows if r['phase0_status'] in ('FEASIBLE','OPTIMAL')]
new=sum(1 for r in feas if r.get('delta','0') not in ('0','') and int(r['delta'])>0)
dup=len(feas)-new
final=rows[-1]['pool_size'] if rows else '0'
rate_per_hr=(new/elapsed*3600) if elapsed>0 else float('nan')
print(f"  solves           : {n}  (feasible {len(feas)}, capped {caps})")
print(f"  pool size (final): {final}")
print(f"  NEW distinct adds: {new}")
print(f"  duplicate finds  : {dup}   (dedup hit rate {100*dup/len(feas):.0f}% of feasible)" if feas else "  duplicate finds  : 0")
print(f"  collection RATE  : {rate_per_hr:.1f} new assignments/hour  ({elapsed/60:.1f} min total)")
print()
print("  → Use the rate + dedup trend to choose the pool cap. Rising dedup %% = the")
print("    space is being saturated; flat dedup with steady new adds = keep collecting.")
PY
