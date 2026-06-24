#!/usr/bin/env bash
# Phase-0 FIX-options test harness (post-decomp investigation).
#
# Tests the STATELESS Phase-0 acceleration options that attack the cold first-feasibility
# search directly (instead of decomposing the problem, which dead-ended). Runs
# HeadlessSolveRunner N times per requested PHASE0_FIX mode (plus a monolithic baseline),
# P0 cap 300s, Phase-0 isolated (P1/P2/P3 = 1s). Sequential for honest timing.
#
# Options covered here (each independent of run history):
#   mono         monolithic baseline (PHASE0_FIX unset)
#   fjportfolio  Option 2: feasibility_jump-heavy worker portfolio
#   multiseed    Option 3: short fresh-seed attempts, stop on first feasible
#   lns          Option 4: hard-fix a greedy core, repair the free margin (single-shot LNS)
#   presolve0    Option 5: presolve disabled (search-first); presolveN caps at N iterations
#
# NOTE: the cache POOL option (Option 1, PHASE0_FIX=cache) is STATEFUL — it accumulates a
# pool across runs — so it is NOT tested here (the per-run DB reset would wipe the pool).
# Use phase0_seed_pool.sh for that.
#
# DB SAFETY: identical to phase0_decomp.sh — back up the live DB once, run every solve
# against a fresh COPY at the hard-coded path, restore the live DB on exit (trap).
#
# Usage:  ./phase0_fix.sh [N] [mode1 mode2 ...]
#   N      runs per mode (default 5 — smoke triage; promote survivors to 10)
#   modes  any of: mono fjportfolio multiseed lns presolve0 presolve2
#          (default: mono fjportfolio multiseed lns presolve0)
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1

N="${1:-5}"; shift || true
MODES=("$@"); [ ${#MODES[@]} -eq 0 ] && MODES=(mono fjportfolio multiseed lns presolve0)

CP="target/classes;$(cat cp.txt)"
LIVE="residency_scheduler.db"
SAFE="residency_scheduler.fix-safe-$(date +%Y%m%d-%H%M%S).db"   # untouched backup of live
WORK="residency_scheduler.fix-work.db"                          # fresh copy per run
OUT="$ROOT/phase0_fix_logs"
RESULTS="$ROOT/phase0_fix_results.csv"
mkdir -p "$OUT"

if [ ! -f "$LIVE" ]; then echo "FATAL: $LIVE not found"; exit 1; fi
cp "$LIVE" "$SAFE" || { echo "FATAL: backup failed"; exit 1; }
restore() { cp "$SAFE" "$LIVE" 2>/dev/null; rm -f "$WORK"; echo "[restored live DB from $SAFE]"; }
trap restore EXIT

echo "mode,run,phase0_status,phase0_secs" > "$RESULTS"
echo "Phase-0 FIX test: N=$N per mode; modes=${MODES[*]}; P0 cap 300s; Phase-0 isolated"
echo "Live DB backed up to $SAFE (restored on exit)."
echo "Started: $(date '+%Y-%m-%d %H:%M:%S %Z')"

for mode in "${MODES[@]}"; do
  for i in $(seq 1 "$N"); do
    cp "$SAFE" "$WORK" && cp "$WORK" "$LIVE"   # fresh, identical DB for every run
    log="$OUT/${mode}_run_$i.log"
    case "$mode" in
      mono)
        java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1 > "$log" 2>&1 ;;
      *)
        PHASE0_FIX="$mode" java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1 > "$log" 2>&1 ;;
    esac
    # Final Phase-0 result line: "Phase 0 result: STATUS  (NNN.Ns)..."
    pline="$(grep -E 'Phase 0 result:' "$log" | tail -1 | tr -d '\r')"
    pstatus="$(echo "$pline" | sed -E 's/.*result: *([A-Z_]+).*/\1/')"
    psecs="$(echo "$pline" | sed -E 's/.*\(([0-9.]+)s\).*/\1/')"
    [ -z "$pline" ] && { pstatus="NA"; psecs="NA"; }
    printf '%s,%s,%s,%s\n' "$mode" "$i" "$pstatus" "$psecs" | tee -a "$RESULTS"
  done
done

echo
echo "Finished: $(date '+%Y-%m-%d %H:%M:%S %Z')"
echo "=== summary (caps + median Phase-0 secs per mode) ==="
python - "$RESULTS" <<'PY'
import sys, csv, statistics
from collections import defaultdict
rows=list(csv.DictReader(open(sys.argv[1])))
bymode=defaultdict(list)
for r in rows:
    if r.get('mode') and r.get('phase0_status'): bymode[r['mode']].append(r)
for mode, rs in bymode.items():
    n=len(rs)
    caps=sum(1 for r in rs if r['phase0_status'] in ('UNKNOWN','NA','INFEASIBLE'))
    secs=[float(r['phase0_secs']) for r in rs if (r.get('phase0_secs') or 'NA') not in ('NA','')]
    med=statistics.median(secs) if secs else float('nan')
    mn=min(secs) if secs else float('nan'); mx=max(secs) if secs else float('nan')
    print(f"  {mode:12s}: caps {caps}/{n}  median {med:6.1f}s  (min {mn:.1f}/max {mx:.1f})")
PY
