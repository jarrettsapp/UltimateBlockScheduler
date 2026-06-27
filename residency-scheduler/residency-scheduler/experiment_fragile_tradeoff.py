#!/usr/bin/env python3
"""Experiment: does ALLOWING fragile weekends buy more healthy weekends?

The production Phase-3 objective sets the fragile penalty Wf=1000, which dominates the healthy
reward (~10 + 3/extra). Result: every Phase-3 schedule drives fragile to 0, and the only thing left
to vary is a near 1-for-1 volunteer↔healthy trade (the convergence proof shows the best is the
(fragile 0, volunteer 3, healthy 22) corner, objective-limited at tier3≈65).

The open question (your idea): if we let the solver KEEP one (or more) fragile weekends, does it
free up MORE than one healthy weekend? i.e. is fragile=0 actually the best operating point, or is
there a better (fragile≥1, higher-healthy) corner the dominant penalty is hiding?

This re-runs Phase-3 on a few representative Phase-2 starters at a LOWERED fragile weight (TF_WF),
so the solver is ALLOWED to trade a fragile weekend for healthy gains when that trade is favorable.
It does NOT force exactly one fragile (a hard ==1 constraint risks INFEASIBLE); it lowers the price
of fragile so the solver reveals the trade curve itself. Compare the resulting (fragile, healthy)
against the fragile-0 ceiling.

The runs write normal timefold-opt versions to the DB (tagged in the log), so the regular evaluator
picks them up — but they are produced under a DIFFERENT objective, so DO NOT mix them into the
production convergence proof. Read the printed table here instead.

USAGE (from residency-scheduler/residency-scheduler):
    python experiment_fragile_tradeoff.py --versions 56,90,79 --wf 15 --budget 600
    python experiment_fragile_tradeoff.py --top 3 --wf-sweep 5,15,50,200 --budget 600
    python experiment_fragile_tradeoff.py --top 3 --wf 15 --budget 600 --dry

  --versions   explicit Phase-2 version ids to start from (the v<N> the harvest produced)
  --top K      instead of --versions, auto-pick the K best Phase-2 harvest starters (fragile ASC)
  --wf W       fragile weight to test (lower = fragile cheaper = more willing to trade). 1000=prod.
  --wf-sweep   comma list of fragile weights to test in sequence (maps the whole trade curve)
  --budget S   Phase-3 budget seconds per run (default 600)
  --dry        print the planned java commands without running
"""
from __future__ import annotations
import argparse, os, subprocess, sqlite3, sys, time

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

ROOT = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(ROOT, "residency_scheduler.db")

# Experiment runs use a DIFFERENT objective (lowered fragile weight). The Java runner names every
# output 'tf-opt-from-v<N>' and labels it 'timefold-opt' — IDENTICAL to production runs — so the
# production convergence proof / finalist set would silently fold them in and corrupt the proof.
# After each run we RENAME the version to this prefix so the evaluator's LIKE 'tf-opt-from-v%' and
# config_label='timefold-opt' filters skip it. We also stamp the tested Wf into the name.
EXP_PREFIX = "frag-exp"

try:
    import sweep_driver as sw  # reuse classpath + integrity helpers from the pipeline tooling
except Exception:
    sw = None


def ro():
    return sqlite3.connect(f"file:{DB}?mode=ro", uri=True)


def top_starters(k: int) -> list[int]:
    con = ro()
    try:
        rows = con.execute("""
            SELECT sr.version_id, m.fragile, m.healthy, m.volunteer
            FROM solve_runs sr JOIN solve_run_metrics m ON m.run_id = sr.id
            WHERE sr.run_status='PHASE2_FALLBACK' AND sr.year=2
              AND sr.version_id IS NOT NULL AND m.fragile IS NOT NULL
            ORDER BY m.fragile ASC, m.healthy DESC LIMIT ?
        """, (k,)).fetchall()
    finally:
        con.close()
    for vid, f, h, v in rows:
        print(f"  starter v{vid}: Phase-2 fragile={f} healthy={h} volunteer={v}")
    return [r[0] for r in rows]


def baseline_ceiling() -> str:
    """The production fragile-0 ceiling, for side-by-side comparison."""
    con = ro()
    try:
        row = con.execute("""
            SELECT sv.tier3_score, m.fragile, m.volunteer, m.healthy
            FROM schedule_versions sv
            JOIN solve_runs sr ON sr.version_id=sv.id
            JOIN solve_run_metrics m ON m.run_id=sr.id
            WHERE sv.name LIKE 'tf-opt-from-v%' AND sv.tier1_score=0 AND sv.tier3_score IS NOT NULL
            ORDER BY sv.tier3_score ASC LIMIT 1
        """).fetchone()
    finally:
        con.close()
    if not row:
        return "no production Phase-3 baseline found"
    return f"production ceiling: tier3={row[0]} fragile={row[1]} volunteer={row[2]} healthy={row[3]}"


def metrics_for_version(version_id: int) -> dict | None:
    """Metrics for one exact version id."""
    con = ro()
    try:
        row = con.execute("""
            SELECT sv.id, sv.tier3_score, m.fragile, m.volunteer, m.healthy
            FROM schedule_versions sv
            LEFT JOIN solve_runs sr ON sr.version_id=sv.id
            LEFT JOIN solve_run_metrics m ON m.run_id=sr.id
            WHERE sv.id=?
        """, (version_id,)).fetchone()
    finally:
        con.close()
    if not row:
        return None
    return {"p3_version": row[0], "tier3": row[1], "fragile": row[2],
            "volunteer": row[3], "healthy": row[4]}


def tag_experiment_version(version_id: int, src_version: int, wf: int) -> None:
    """Rename the experiment's output version + relabel its solve_run so the production evaluator
    (which filters on name LIKE 'tf-opt-from-v%' and config_label='timefold-opt') EXCLUDES it."""
    con = sqlite3.connect(DB, timeout=30)
    try:
        con.execute("UPDATE schedule_versions SET name=? WHERE id=?",
                    (f"{EXP_PREFIX}-wf{wf}-from-v{src_version}-{version_id}", version_id))
        con.execute("UPDATE solve_runs SET config_label=? WHERE version_id=?",
                    (f"{EXP_PREFIX}-wf{wf}", version_id))
        con.commit()
    finally:
        con.close()


def run_one(version_id: int, wf: int, budget: int, dry: bool) -> int | None:
    """Run one Phase-3 at lowered fragile weight; return the new version id (tagged), or None."""
    cp = "<classpath>" if (dry or sw is None) else sw.build_classpath()
    cmd = ["java", "-cp", cp, "com.residency.tools.TimefoldOptimizeRunner",
           "2", str(version_id), str(budget)]
    env = dict(os.environ)
    env["TF_WF"] = str(wf)                 # the lever: lower fragile price
    env["TF_TIER"] = "1"                   # ensure tiered objective is ON
    if dry:
        print(f"  WOULD run: TF_WF={wf} TF_TIER=1  java ... TimefoldOptimizeRunner 2 {version_id} {budget}")
        return None
    if sw is not None and not sw.integrity_ok(DB):
        raise SystemExit("DB integrity_check != ok -- STOP")
    log_path = os.path.join(ROOT, f"frag_tradeoff_v{version_id}_wf{wf}.log")
    print(f"  running v{version_id} at Wf={wf}, budget={budget}s -> {os.path.basename(log_path)}")
    with open(log_path, "w") as lf:
        subprocess.run(cmd, cwd=ROOT, env=env, stdout=lf, stderr=subprocess.STDOUT, check=False)
    # parse the new version id from the log (the runner prints NEW_VERSION_ID=<id>)
    new_vid = None
    try:
        with open(log_path, encoding="utf-8", errors="replace") as lf:
            for line in lf:
                if line.startswith("NEW_VERSION_ID="):
                    new_vid = int(line.strip().split("=", 1)[1])
    except OSError:
        pass
    if new_vid is not None:
        tag_experiment_version(new_vid, version_id, wf)
    return new_vid


def main() -> int:
    ap = argparse.ArgumentParser(description="Fragile trade-off experiment")
    ap.add_argument("--versions", help="comma list of Phase-2 version ids to start from")
    ap.add_argument("--top", type=int, help="auto-pick the K best Phase-2 starters instead")
    ap.add_argument("--wf", type=int, default=15, help="fragile weight to test (1000=production)")
    ap.add_argument("--wf-sweep", help="comma list of fragile weights to test in sequence")
    ap.add_argument("--budget", type=int, default=600, help="Phase-3 budget seconds per run")
    ap.add_argument("--dry", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(DB):
        print(f"DB not found: {DB}", file=sys.stderr); return 2

    print(baseline_ceiling())
    print()

    if args.versions:
        versions = [int(x) for x in args.versions.split(",") if x.strip()]
    elif args.top:
        print("auto-selecting starters:")
        versions = top_starters(args.top)
    else:
        print("specify --versions or --top", file=sys.stderr); return 2

    weights = ([int(x) for x in args.wf_sweep.split(",")] if args.wf_sweep else [args.wf])
    print(f"\ntesting fragile weights {weights} on versions {versions} "
          f"(budget {args.budget}s){' [DRY]' if args.dry else ''}\n")

    results = []
    total = len(weights) * len(versions); done = 0
    for wf in weights:
        for vid in versions:
            done += 1
            print(f"[{done}/{total}]", end=" ")
            new_vid = run_one(vid, wf, args.budget, args.dry)
            if not args.dry and new_vid is not None:
                time.sleep(3)
                m = metrics_for_version(new_vid)
                if m:
                    results.append((wf, vid, m))
                    print(f"    -> Wf={wf} v{vid}: fragile={m['fragile']} volunteer={m['volunteer']} "
                          f"healthy={m['healthy']} (tier3={m['tier3']}, exp-version {new_vid})")

    if results:
        print("\n=== TRADE-OFF SUMMARY (vs production fragile-0 ceiling: frag0 vol3 heal22) ===")
        print(f"{'Wf':>5} {'fromV':>6} {'fragile':>8} {'volunteer':>10} {'healthy':>8} {'tier3':>7}")
        for wf, vid, m in sorted(results):
            print(f"{wf:>5} {vid:>6} {m['fragile']:>8} {m['volunteer']:>10} {m['healthy']:>8} "
                  f"{str(m['tier3']):>7}")
        print("\nRead: the fragile-0 ceiling is healthy=22. If a lower Wf yields fragile>=1 but "
              "healthy rises ABOVE 22 (more than 1 healthy gained per fragile allowed), the trade is "
              "favorable and fragile=0 is NOT the best operating point. If healthy stays <=22 or "
              "rises <=1 per fragile, fragile=0 stays best.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
