#!/usr/bin/env bash
# Phase-0 acceleration data collection.
# 5 modes x N runs, Phase 0 capped at 300s, phases 1-3 clamped to 1s (Phase 0 only).
# Sequential (honest per-run timing, no CPU contention). Writes each row immediately
# so a crash/interrupt loses at most one run. Summary table at the end.
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1
N="${1:-10}"
CAP="${2:-300}"
CP="target/classes;$(cat cp.txt)"
RESULTS="$ROOT/phase0_matrix_results.csv"
LOGDIR="$ROOT/phase0_matrix_logs"
mkdir -p "$LOGDIR"
echo "mode,run,phase0_status,phase0_secs" > "$RESULTS"

MODES="A B C BC AC"
echo "Phase-0 matrix: modes=[$MODES] x $N runs, P0 cap=${CAP}s, config R6-95"
for mode in $MODES; do
  for i in $(seq 1 "$N"); do
    log="$LOGDIR/${mode}_run${i}.log"
    PHASE0_MODE="$mode" SOLVE_TRAJECTORY_CSV="$LOGDIR/${mode}_run${i}_traj.csv" \
      java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 "$CAP" 1 1 1 > "$log" 2>&1
    line="$(grep -E 'Phase 0 result:' "$log" | tail -1 | tr -d '\r')"
    status="$(echo "$line" | sed -E 's/.*result: *([A-Z_]+).*/\1/')"
    secs="$(echo "$line" | sed -E 's/.*\(([0-9.]+)s\).*/\1/')"
    [ -z "$line" ] && { status="NO_RESULT"; secs="NA"; }
    echo "$mode,$i,$status,$secs" | tee -a "$RESULTS"
  done
done

echo
echo "=== SUMMARY (min / max / avg over runs with a numeric Phase-0 time) ==="
python - "$RESULTS" <<'PY'
import sys, csv
from collections import defaultdict
rows=list(csv.DictReader(open(sys.argv[1])))
by=defaultdict(list); status=defaultdict(list)
for r in rows:
    status[r['mode']].append(r['phase0_status'])
    if r['phase0_secs'] not in ('NA',''):
        by[r['mode']].append(float(r['phase0_secs']))
labels={'A':'A only','B':'B only','C':'C only','BC':'B+C','AC':'A+C'}
order=['A','B','C','BC','AC']
print(f"{'mode':<8}{'n':>4}{'min':>9}{'max':>9}{'avg':>9}  statuses")
for m in order:
    v=by.get(m,[])
    st=status.get(m,[])
    capped=sum(1 for s in st if s in ('FEASIBLE','UNKNOWN'))
    if not v:
        print(f"{labels[m]:<8}{0:>4}{'-':>9}{'-':>9}{'-':>9}  {dict((s,st.count(s)) for s in set(st))}")
        continue
    print(f"{labels[m]:<8}{len(v):>4}{min(v):>9.1f}{max(v):>9.1f}{sum(v)/len(v):>9.1f}"
          f"  capped(F/UNK)={capped}")
PY
