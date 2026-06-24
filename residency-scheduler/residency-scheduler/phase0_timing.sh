#!/usr/bin/env bash
# Phase-0 timing validation: run HeadlessSolveRunner N times, measure ONLY Phase 0.
# P0=900 (never truncate Phase 0); P1=P2=P3=1s so phases 1-3 finish near-instantly.
# Runs in-place from ROOT (classpath + ortools natives resolve here; the sweep driver
# does the same). Master DB is already backed up; extra saved versions are harmless.
# SEQUENTIAL so CPU contention doesn't distort per-run Phase-0 wall time.
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1
N="${1:-10}"
CP="target/classes;$(cat cp.txt)"
RESULTS="$ROOT/phase0_timing_results.csv"
LOGDIR="$ROOT/phase0_timing_logs"
mkdir -p "$LOGDIR"
echo "run,phase0_status,phase0_secs" > "$RESULTS"

echo "Phase-0 timing: $N runs, config R6-95 (floor off / target 2 / weight 95)"
for i in $(seq 1 "$N"); do
  log="$LOGDIR/run_$i.log"
  SOLVE_TRAJECTORY_CSV="$LOGDIR/traj_$i.csv" \
    java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 900 1 1 1 > "$log" 2>&1
  line="$(grep -E 'Phase 0 result:' "$log" | tail -1 | tr -d '\r')"
  status="$(echo "$line" | sed -E 's/.*result: *([A-Z_]+).*/\1/')"
  secs="$(echo "$line" | sed -E 's/.*\(([0-9.]+)s\).*/\1/')"
  if [ -z "$line" ]; then status="NO_RESULT"; secs="NA"; fi
  echo "$i,$status,$secs" | tee -a "$RESULTS"
done

echo "=== summary ==="
python - "$RESULTS" <<'PY'
import sys, csv, statistics as st
rows=[r for r in csv.DictReader(open(sys.argv[1])) if r['phase0_secs'] not in ('NA','')]
vals=sorted(float(r['phase0_secs']) for r in rows)
if not vals:
    print("no valid Phase-0 timings"); sys.exit(0)
def pct(p):
    k=(len(vals)-1)*p; f=int(k); return vals[f] if f+1>=len(vals) else vals[f]+(vals[f+1]-vals[f])*(k-f)
print(f"n={len(vals)}  min={vals[0]:.1f}  med={st.median(vals):.1f}  "
      f"p90={pct(0.9):.1f}  max={vals[-1]:.1f}  mean={st.mean(vals):.1f}")
print("all:", ", ".join(f"{v:.1f}" for v in vals))
PY
