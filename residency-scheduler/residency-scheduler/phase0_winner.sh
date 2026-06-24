#!/usr/bin/env bash
# Which subsolver actually finds Phase 0's first feasible solution?
# Runs DIAG mode (search-progress log on) N times, P0 cap 300s, Phase-0 only, and for
# each run records: the FIRST "#1 <time>s <subsolver>(...)" progress line (the winning
# subsolver + when it found feasibility), plus the final Phase 0 status/time.
# Purpose: confirm feasibility_jump is CONSISTENTLY the finder before we bias the
# portfolio toward it / prune the LP subsolvers. Sequential for honest timing.
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT" || exit 1
N="${1:-9}"
CP="target/classes;$(cat cp.txt)"
OUT="$ROOT/phase0_winner_logs"
RESULTS="$ROOT/phase0_winner_results.csv"
mkdir -p "$OUT"
echo "run,first_solution_subsolver,first_solution_secs,phase0_status,phase0_secs" > "$RESULTS"

echo "Phase-0 winner test: $N runs, DIAG on, P0 cap 300s"
for i in $(seq 1 "$N"); do
  log="$OUT/run_$i.log"
  PHASE0_DIAG=1 java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 300 1 1 1 > "$log" 2>&1
  # First feasible: e.g. "#1      89.39s fj_short_default(batch:...)"
  fline="$(grep -E '^#1 ' "$log" | head -1 | tr -d '\r')"
  fsubsolver="$(echo "$fline" | sed -E 's/^#1[[:space:]]+[0-9.]+s[[:space:]]+([a-zA-Z0-9_]+).*/\1/')"
  fsecs="$(echo "$fline" | sed -E 's/^#1[[:space:]]+([0-9.]+)s.*/\1/')"
  [ -z "$fline" ] && { fsubsolver="NONE"; fsecs="NA"; }
  pline="$(grep -E 'Phase 0 result:' "$log" | tail -1 | tr -d '\r')"
  pstatus="$(echo "$pline" | sed -E 's/.*result: *([A-Z_]+).*/\1/')"
  psecs="$(echo "$pline" | sed -E 's/.*\(([0-9.]+)s\).*/\1/')"
  echo "$i,$fsubsolver,$fsecs,$pstatus,$psecs" | tee -a "$RESULTS"
done

echo
echo "=== winning-subsolver tally ==="
python - "$RESULTS" <<'PY'
import sys, csv
from collections import Counter
rows=list(csv.DictReader(open(sys.argv[1])))
c=Counter(r['first_solution_subsolver'] for r in rows)
for k,v in c.most_common():
    print(f"  {k}: {v}")
times=[float(r['first_solution_secs']) for r in rows if r['first_solution_secs'] not in ('NA','')]
if times:
    print(f"first-solution time over {len(times)} runs that found one: "
          f"min={min(times):.1f} max={max(times):.1f} avg={sum(times)/len(times):.1f}")
caps=sum(1 for r in rows if r['phase0_status']=='UNKNOWN')
print(f"capped (no feasible in 300s): {caps}/{len(rows)}")
PY
