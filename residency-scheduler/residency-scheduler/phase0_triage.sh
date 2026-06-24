#!/usr/bin/env bash
# Quick triage: K Phase-0-only solves IN PARALLEL on isolated DB copies, fewer workers
# each so they fit in 12 cores. Absolute classpath so it resolves from any cwd (the
# earlier per-run-dir failure was a relative cp.txt + ';' separator under a changed cwd).
# Not a precise benchmark (parallelism + worker count shift absolute time) — it answers
# the binary question: is Phase 0 ~80s or ~480s?
set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
K="${1:-3}"
WORKERS="${2:-4}"
# Build an ABSOLUTE classpath: convert ROOT/target/classes + each cp.txt entry is already absolute.
CP="$ROOT/target/classes;$(cat "$ROOT/cp.txt")"
OUT="$ROOT/phase0_triage_logs"
mkdir -p "$OUT"
pids=()
for i in $(seq 1 "$K"); do
  d="$OUT/run_$i"; rm -rf "$d"; mkdir -p "$d"
  cp "$ROOT/residency_scheduler.db" "$d/residency_scheduler.db"
  (
    cd "$d" || exit 1
    SOLVE_TRAJECTORY_CSV="$d/traj.csv" CPSAT_NUM_WORKERS="$WORKERS" \
      java -cp "$CP" com.residency.tools.HeadlessSolveRunner 2 900 1 1 1 > "$d/solve.log" 2>&1
  ) &
  pids+=($!)
  echo "launched triage run $i (pid $!) workers=$WORKERS"
done
echo "waiting for $K parallel runs…"
for p in "${pids[@]}"; do wait "$p"; done
echo "=== Phase 0 results ==="
for i in $(seq 1 "$K"); do
  grep -E 'Phase 0 result:' "$OUT/run_$i/solve.log" 2>/dev/null | tail -1 | tr -d '\r' | sed "s/^/run $i: /"
done
