#!/usr/bin/env python3
"""
ONE-OFF retroactive backfill of phase0_collection_runs from data recovered this session.

Two sources, per the user's "save all data" decision:
  (1) Historical RUN TIMINGS (status + secs + cap) from the stepwise ladder Steps 3/4/4b — the only
      surviving record of past time-to-feasibility (the per-run CSV was overwritten each run; these
      values were captured in the build conversation). Includes the CAPPED (censored) runs, which the
      cap analysis needs. cap was 180s for all of these.
  (2) SEED-LEVEL rows from the pool DB's phase0_seed_stats (created_at) are NOT separately inserted —
      the feasible runs in (1) already represent seed-banking events. We attach new_seed_id to the
      feasible rows in creation order where counts allow; capped rows get NULL.

Idempotent-ish: refuses to run if phase0_collection_runs already has rows for the year (so it can't
double-backfill). Run against the POOL DB (which carries the pool + seed_stats). Year = 2.

Usage:  python backfill_collection_runs.py [db_path]   (default residency_scheduler.pool.db)
"""
import sys, sqlite3
from datetime import datetime, timezone

DB = sys.argv[1] if len(sys.argv) > 1 else "residency_scheduler.pool.db"
YEAR = 2
CAP = 180  # all ladder Steps 3/4/4b ran at a 180s cap

# (status, secs) for every run, in order, from Steps 3, 4, 4b (captured this session).
STEP3 = [("OPTIMAL",27.9),("UNKNOWN",181.0),("OPTIMAL",15.0),("OPTIMAL",67.8),("OPTIMAL",142.2),
         ("OPTIMAL",137.9),("OPTIMAL",40.0),("OPTIMAL",42.3),("OPTIMAL",61.9),("UNKNOWN",181.0)]
STEP4 = [("OPTIMAL",40.3),("OPTIMAL",7.2),("OPTIMAL",13.2),("OPTIMAL",40.6),("OPTIMAL",37.6),
         ("OPTIMAL",20.5),("UNKNOWN",181.1),("OPTIMAL",72.1),("OPTIMAL",39.1),("OPTIMAL",89.0),
         ("OPTIMAL",13.4),("OPTIMAL",12.9),("OPTIMAL",43.5),("OPTIMAL",9.1),("OPTIMAL",10.2),
         ("OPTIMAL",52.9),("UNKNOWN",181.0),("OPTIMAL",18.7),("OPTIMAL",71.6),("OPTIMAL",139.3),
         ("OPTIMAL",9.3),("OPTIMAL",143.1),("UNKNOWN",181.0),("OPTIMAL",145.6),("OPTIMAL",47.0)]
STEP4B = [("UNKNOWN",181.2),("OPTIMAL",30.4),("OPTIMAL",13.9),("UNKNOWN",181.0),("OPTIMAL",16.5),
          ("OPTIMAL",45.2),("OPTIMAL",70.9),("OPTIMAL",146.1),("OPTIMAL",49.4),("UNKNOWN",181.2),
          ("OPTIMAL",86.4),("OPTIMAL",61.9),("OPTIMAL",37.3),("UNKNOWN",181.1),("OPTIMAL",14.0),
          ("OPTIMAL",164.6),("OPTIMAL",33.0),("UNKNOWN",181.1),("OPTIMAL",13.9),("OPTIMAL",17.2)]
RUNS = STEP3 + STEP4 + STEP4B

def main():
    c = sqlite3.connect(DB)
    try:
        # Ensure the table exists (mirrors the Java migration) so this backfill is self-contained
        # even if the new engine code hasn't opened THIS db file yet. Schema must match DatabaseManager.
        c.execute("""
            CREATE TABLE IF NOT EXISTS phase0_collection_runs (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                year         INTEGER NOT NULL,
                run_at       TEXT NOT NULL,
                status       TEXT NOT NULL,
                secs         REAL,
                cap_secs     INTEGER,
                new_seed_id  TEXT,
                worker_count INTEGER
            )""")
        c.execute("CREATE INDEX IF NOT EXISTS idx_collruns_year ON phase0_collection_runs(year, run_at)")
        existing = c.execute("SELECT COUNT(*) FROM phase0_collection_runs WHERE year = ?", (YEAR,)).fetchone()[0]
        if existing > 0:
            sys.exit(f"REFUSING: phase0_collection_runs already has {existing} rows for year {YEAR} "
                     f"(backfill would double-count). Nothing done.")
        # seed_ids in creation order, to attach to feasible rows where available.
        seed_ids = [r[0] for r in c.execute(
            "SELECT seed_id FROM phase0_seed_stats WHERE year = ? ORDER BY ordinal ASC", (YEAR,)).fetchall()]
        # synthetic timestamps so run_at is ordered (we don't have the true per-run clock).
        base = datetime(2026, 6, 24, 14, 0, 0, tzinfo=timezone.utc)
        si = 0
        feas = sum(1 for s, _ in RUNS if s in ("OPTIMAL", "FEASIBLE"))
        print(f"backfilling {len(RUNS)} runs ({feas} feasible, {len(RUNS)-feas} capped); "
              f"{len(seed_ids)} seed_ids available to attach")
        for i, (status, secs) in enumerate(RUNS):
            # synthetic run_at, ~90s apart, so rows are time-ordered (true per-run clock wasn't saved).
            ts = base.timestamp() + i * 90
            run_at = datetime.fromtimestamp(ts, tz=timezone.utc).isoformat().replace("+00:00", "Z")
            sid = None
            if status in ("OPTIMAL", "FEASIBLE") and si < len(seed_ids):
                sid = seed_ids[si]; si += 1
            c.execute(
                "INSERT INTO phase0_collection_runs (year, run_at, status, secs, cap_secs, new_seed_id, worker_count) "
                "VALUES (?,?,?,?,?,?,?)",
                (YEAR, run_at, status, secs, CAP, sid, 16))  # 16 = fjportfolio worker count used
        c.commit()
        total = c.execute("SELECT COUNT(*) FROM phase0_collection_runs WHERE year = ?", (YEAR,)).fetchone()[0]
        print(f"done: phase0_collection_runs now has {total} rows for year {YEAR} "
              f"(attached {si} seed_ids to feasible runs)")
    finally:
        c.close()

if __name__ == "__main__":
    main()
