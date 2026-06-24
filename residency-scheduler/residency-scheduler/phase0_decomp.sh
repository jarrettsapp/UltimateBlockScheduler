#!/usr/bin/env bash
# Phase-0 decomposition A/B test harness (Approach #1: hard-fix complement + backtrack).
#
# Runs HeadlessSolveRunner N times for each requested PHASE0_DECOMP mode (plus a monolithic
# baseline with PHASE0_DECOMP unset), P0 cap 300s, Phase-0 isolated (P1/P2/P3 = 1s), and
# records per run: final Phase-0 status, Phase-0 wall seconds, #windows, #backtracks, and the
# worst per-window status. Sequential for honest timing.
#
# DB SAFETY: the DB path is hard-coded to residency_scheduler.db. We back up the live DB once,
# run every solve against a fresh COPY of it placed at that path, and RESTORE the live DB on
# exit (trap). The live DB is never mutated by a solve here (Phase-0-only runs don't persist a
# schedule anyway, but we copy+restore to be certain).
#
# Usage:  ./phase0_decomp.sh [N] [mode1 mode2 ...]
#   N      runs per mode (default 10)
#   modes  any of: mono hardroll3 hardroll1 roll3 roll1   (default: mono hardroll3)
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1

N="${1:-10}"; shift || true
MODES=("$@"); [ ${#MODES[@]} -eq 0 ] && MODES=(mono hardroll3)

CP="target/classes;$(cat cp.txt)"
LIVE="residency_scheduler.db"
SAFE="residency_scheduler.decomp-safe-$(date +%Y%m%d-%H%M%S).db"   # untouched backup of live
WORK="residency_scheduler.decomp-work.db"                          # fresh copy per run
OUT="$ROOT/phase0_decomp_logs"
RESULTS="$ROOT/phase0_decomp_results.csv"
mkdir -p "$OUT"

if [ ! -f "$LIVE" ]; then echo "FATAL: $LIVE not found"; exit 1; fi
cp "$LIVE" "$SAFE" || { echo "FATAL: backup failed"; exit 1; }
restore() { cp "$SAFE" "$LIVE" 2>/dev/null; rm -f "$WORK"; echo "[restored live DB from $SAFE]"; }
trap restore EXIT

echo "mode,run,phase0_status,phase0_secs,n_windows,backtracks,worst_window_status" > "$RESULTS"
echo "Phase-0 decomp test: N=$N per mode; modes=${MODES[*]}; P0 cap 300s; Phase-0 isolated"
echo "Live DB backed up to $SAFE (restored on exit)."

for mode in "${MODES[@]}"; do
  for i in $(seq 1 "$N"); do
    cp "$SAFE" "$WORK" && cp "$WORK" "$LIVE"   # fresh, identical DB for every run
    log="$OUT/${mode}_run_$i.log"
    case "$mode" in
      mono)
        java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1 > "$log" 2>&1 ;;
      monoC)   # monolithic with PHASE0_MODE=C (stop-after-first + probing off + polarity false)
        PHASE0_MODE=C java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1 > "$log" 2>&1 ;;
      monoB)   # monolithic with PHASE0_MODE=B (greedy seed hints)
        PHASE0_MODE=B java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1 > "$log" 2>&1 ;;
      *)
        PHASE0_DECOMP="$mode" java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1 > "$log" 2>&1 ;;
    esac
    # Final Phase-0 result line from the solver log: "Phase 0 result: STATUS  (NNN.Ns)..."
    pline="$(grep -E 'Phase 0 result:' "$log" | tail -1 | tr -d '\r')"
    pstatus="$(echo "$pline" | sed -E 's/.*result: *([A-Z_]+).*/\1/')"
    psecs="$(echo "$pline" | sed -E 's/.*\(([0-9.]+)s\).*/\1/')"
    [ -z "$pline" ] && { pstatus="NA"; psecs="NA"; }
    # Per-window diagnostics (decomp modes only). The window log line is:
    #   "  decomp=MODE window N/M -> STATUS  (frozen=K slots, V vars fixed, T s total)"
    # The arrow char may render oddly under the console codepage, so match on "frozen=" which
    # is reliably ASCII. Pipe through `wc -l`/`tr -d` so a no-match yields a clean "0" (grep -c
    # alone exits non-zero on no match and would double-count with `|| echo 0`).
    nwin="$(grep -E 'decomp='"$mode"' window [0-9]+/[0-9]+ .*frozen=' "$log" 2>/dev/null | wc -l | tr -d ' \r\n')"
    nbt="$(grep -E 'backtrack #' "$log" 2>/dev/null | wc -l | tr -d ' \r\n')"
    [ -z "$nwin" ] && nwin=0; [ -z "$nbt" ] && nbt=0
    # worst window status: INFEASIBLE > UNKNOWN > FEASIBLE > OPTIMAL (corner severity)
    worst="-"
    if [ "$mode" != "mono" ]; then
      if   grep -qE 'window [0-9]+/[0-9]+ .*INFEASIBLE.*frozen=' "$log"; then worst="INFEASIBLE"
      elif grep -qE 'window [0-9]+/[0-9]+ .*UNKNOWN.*frozen='    "$log"; then worst="UNKNOWN"
      elif grep -qE 'window [0-9]+/[0-9]+ .*FEASIBLE.*frozen='   "$log"; then worst="FEASIBLE"
      elif grep -qE 'window [0-9]+/[0-9]+ .*OPTIMAL.*frozen='    "$log"; then worst="OPTIMAL"
      fi
    fi
    printf '%s,%s,%s,%s,%s,%s,%s\n' "$mode" "$i" "$pstatus" "$psecs" "$nwin" "$nbt" "$worst" | tee -a "$RESULTS"
  done
done

echo
echo "=== summary (caps + median Phase-0 secs per mode) ==="
python - "$RESULTS" <<'PY'
import sys, csv, statistics
from collections import defaultdict
rows=list(csv.DictReader(open(sys.argv[1])))
bymode=defaultdict(list)
# skip any malformed row (e.g. a column that came through as None/empty)
for r in rows:
    if r.get('mode') and r.get('phase0_status'): bymode[r['mode']].append(r)
for mode, rs in bymode.items():
    n=len(rs)
    caps=sum(1 for r in rs if r['phase0_status'] in ('UNKNOWN','NA','INFEASIBLE'))
    secs=[float(r['phase0_secs']) for r in rs if (r.get('phase0_secs') or 'NA') not in ('NA','')]
    bt=[int(r['backtracks']) for r in rs if (r.get('backtracks') or '').isdigit()]
    med=statistics.median(secs) if secs else float('nan')
    mn=min(secs) if secs else float('nan'); mx=max(secs) if secs else float('nan')
    btsum=sum(bt) if bt else 0
    print(f"  {mode:10s}: caps {caps}/{n}  median {med:6.1f}s  (min {mn:.1f}/max {mx:.1f})  total_backtracks={btsum}")
PY
