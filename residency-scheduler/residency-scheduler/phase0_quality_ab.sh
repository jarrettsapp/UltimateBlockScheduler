#!/usr/bin/env bash
# Phase-0 cache QUALITY A/B: does starting Phase 1 from a cached feasible pool draw HURT
# final schedule quality or Phase-1 time vs a cold start? Runs FULL 4-phase solves in two
# arms and compares final Tier-1/2/3 scores + Phase-1 wall time.
#
#   COLD (control)   : PHASE0_FIX unset  → cold Phase-0 search, then Phases 1-3.
#   POOL (treatment) : PHASE0_FIX=cache  → warm-start Phase 0 from a RANDOM pool draw, 1-3.
#
# Requires a pre-seeded pool DB (residency_scheduler.pool.db, built by phase0_seed_pool.sh).
# Both arms run against a COPY of the pool DB so the pool itself isn't mutated by collection
# (cache replay does not collect; only COLLECT mode appends).
#
# Usage:  ./phase0_quality_ab.sh [N] [P0 P1 P2 P3]
#   N              runs per arm (default 5)
#   P0..P3         tier budgets (default 300 900 300 2400 — full-quality solve)
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1

N="${1:-5}"; shift || true
P0="${1:-300}"; P1="${2:-900}"; P2="${3:-300}"; P3="${4:-2400}"

CP="target/classes;$(cat cp.txt)"
LIVE="residency_scheduler.db"
POOLDB="residency_scheduler.pool.db"
SAFE="residency_scheduler.ab-safe-$(date +%Y%m%d-%H%M%S).db"
OUT="$ROOT/phase0_ab_logs"
RESULTS="$ROOT/phase0_ab_results.csv"
mkdir -p "$OUT"

if [ ! -f "$LIVE" ]; then echo "FATAL: $LIVE not found"; exit 1; fi
if [ ! -f "$POOLDB" ]; then echo "FATAL: $POOLDB not found — seed the pool first (phase0_seed_pool.sh)"; exit 1; fi
cp "$LIVE" "$SAFE" || { echo "FATAL: backup failed"; exit 1; }
restore() { cp "$SAFE" "$LIVE" 2>/dev/null; echo "[restored live DB from $SAFE]"; }
trap restore EXIT

echo "arm,run,phase0_status,phase0_secs,phase1_secs,tier1,tier2,tier3,total_secs" > "$RESULTS"
echo "Phase-0 QUALITY A/B: N=$N per arm; budgets ${P0}/${P1}/${P2}/${P3}s"
echo "Pool DB: $POOLDB.  Live DB backed up to $SAFE (restored on exit)."
echo "Started: $(date '+%Y-%m-%d %H:%M:%S %Z')"

run_one() {  # $1=arm  $2=run-index
  local arm="$1" i="$2" log="$OUT/${1}_run_${2}.log"
  cp "$POOLDB" "$LIVE"   # fresh copy of the seeded pool for every solve
  if [ "$arm" = "pool" ]; then
    PHASE0_FIX=cache java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 "$P0" "$P1" "$P2" "$P3" > "$log" 2>&1
  else
    java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 "$P0" "$P1" "$P2" "$P3" > "$log" 2>&1
  fi
  local pline pstatus psecs t1 t2 t3 p1secs total
  pline="$(grep -aE 'Phase 0 result:' "$log" | tail -1 | tr -d '\r')"
  pstatus="$(echo "$pline" | sed -E 's/.*result: *([A-Z_]+).*/\1/')"
  psecs="$(echo "$pline" | sed -E 's/.*\(([0-9.]+)s\).*/\1/')"
  t1="$(grep -aE 'Tier-1 score:' "$log" | tail -1 | sed -E 's/.*Tier-1 score: *([0-9]+).*/\1/')"
  t2="$(grep -aE 'Tier-2 score:' "$log" | tail -1 | sed -E 's/.*Tier-2 score: *([0-9]+).*/\1/')"
  t3="$(grep -aE 'Tier-3 score:' "$log" | tail -1 | sed -E 's/.*Tier-3 score: *([0-9]+).*/\1/')"
  # Phase 1 wall: first "[ Ns] Phase 1 ..." entered to "Phase 2 ..." entered (approx).
  total="$(grep -aE 'runtime ms' "$log" | tail -1 | sed -E 's/.*: *([0-9]+).*/\1/')"
  [ -z "$pstatus" ] && pstatus=NA; [ -z "$psecs" ] && psecs=NA
  [ -z "$t1" ] && t1=NA; [ -z "$t2" ] && t2=NA; [ -z "$t3" ] && t3=NA; [ -z "$total" ] && total=NA
  p1secs=NA
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$arm" "$i" "$pstatus" "$psecs" "$p1secs" "$t1" "$t2" "$t3" "$total" | tee -a "$RESULTS"
}

for i in $(seq 1 "$N"); do run_one cold "$i"; done
for i in $(seq 1 "$N"); do run_one pool "$i"; done

echo
echo "Finished: $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "=== A/B summary (median Tier scores + Phase-0 secs per arm) ==="
python - "$RESULTS" <<'PY'
import sys, csv, statistics
from collections import defaultdict
rows=list(csv.DictReader(open(sys.argv[1])))
by=defaultdict(list)
for r in rows:
    if r.get('arm'): by[r['arm']].append(r)
def med(rs,k):
    v=[float(r[k]) for r in rs if (r.get(k) or 'NA') not in ('NA','')]
    return statistics.median(v) if v else float('nan')
for arm,rs in by.items():
    caps=sum(1 for r in rs if r['phase0_status'] in ('UNKNOWN','NA','INFEASIBLE'))
    print(f"  {arm:5s}: n={len(rs)} caps={caps}  P0_med={med(rs,'phase0_secs'):.1f}s  "
          f"Tier1_med={med(rs,'tier1'):.0f}  Tier2_med={med(rs,'tier2'):.0f}  "
          f"Tier3_med={med(rs,'tier3'):.0f}  total_med={med(rs,'total_secs')/1000:.0f}s")
print()
print("  Interpretation: POOL should match or BEAT cold on Tier scores (warm start helps,")
print("  or at worst is neutral) AND cut Phase-0 caps to ~0. If POOL Tier scores are WORSE,")
print("  the cached starts are biasing Phase 1 into poorer basins — investigate diversity.")
PY
