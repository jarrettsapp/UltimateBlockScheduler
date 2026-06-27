---
name: seed-pool-generate
description: Run more Phase-0 feasible-seed POOL generation (collection) for the residency block-schedule solver — launch N cold Phase-0 solves that each find a distinct feasible assignment and bank it into the persistent seed pool, then verify the run (pool grew, every seed feasible, DB intact, no zombie processes). Use whenever the user wants to grow/build the seed pool, collect more seeds, run more seed generations, or "seed the pool". Only two inputs are needed: the per-solve time limit (default or a number) and the number of runs. Triggers: "generate more seeds", "run more seed generations", "grow the seed pool", "collect N more seeds", "seed pool run".
---

# Phase-0 Seed-Pool Generation (collection)

Follow these steps IN ORDER. Do exactly what each step says. If a step says STOP, stop and tell the
user the reason — do not continue past a failed check. Run every command from this one directory:

```
c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler
```

This grows a saved pool of feasible "seed" schedules. The pool CONTINUES across runs (never wiped).

---

## STEP 0 — Get the two inputs from the user

Ask the user for these two values. Ask nothing else.

1. **time limit** (seconds per solve). If the user does not give one, use **180**.
   Do NOT use a value below 150 unless the user explicitly asks for it.
2. **number of runs** (how many solves). There is no default — if the user did not say, ASK.

Write down the two numbers. Below, replace `<RUNS>` with the number of runs and `<CAP>` with the
time limit everywhere they appear.

---

## STEP 1 — Preflight check (run this exact command)

```bash
cd "c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler"
ls .seed_pool.lock 2>/dev/null && echo "LOCK=PRESENT" || echo "LOCK=NONE"
python -c "import sqlite3;print('INTEGRITY='+sqlite3.connect('residency_scheduler.db').execute('PRAGMA integrity_check').fetchone()[0])"
python -c "import sqlite3;c=sqlite3.connect('residency_scheduler.pool.db');r=c.execute(\"SELECT config_value FROM schedule_config WHERE config_key='phase0_feasible_pool_2'\").fetchone();print('STARTPOOL='+str(sum(1 for x in r[0].split('␞') if x.strip()) if r and r[0] else 0))" 2>/dev/null || echo "STARTPOOL=0"
```

Read the output:
- If it shows `LOCK=PRESENT` → **STOP.** Tell the user: "A seed run is already in progress; I won't
  start a second one." Do not continue.
- If it shows anything other than `INTEGRITY=ok` → **STOP.** Tell the user the live database is
  corrupt and you need their go-ahead to restore it from a backup. Do not continue.
- Otherwise, note the `STARTPOOL=` number (the starting pool size) and go to STEP 2.

---

## STEP 2 — Launch the collection (run this exact command)

Replace `<RUNS>` and `<CAP>` with the user's two numbers, then run:

```bash
COOLDOWN_S=10 bash phase0_seed_pool.sh <RUNS> <CAP> 1000 > phase0_seed_$(date +%H%M).out 2>&1 &
```

Then tell the user it started, and give the projected finish time:
- Each run takes about **82 seconds** on average.
- Projected minutes = `<RUNS>` × 82 ÷ 60. State the projected end time in Central time.

This runs in the background. Do NOT run a second copy. Wait for it to finish before STEP 3.

---

## STEP 3 — Wait for the user to prompt a check-in

**Do NOT poll automatically.** Never run a status check on your own. Only check when the user
explicitly asks (e.g. "how's it going?", "is it done?", "check the status").

When the user prompts a check-in, run this command:

```bash
ls .seed_pool.lock >/dev/null 2>&1 && echo "STILL-RUNNING" || echo "DONE"
```

- If `STILL-RUNNING` → report that it is still running. Wait for the user to prompt again.
- If `DONE` → show the results table and go to STEP 4:

```bash
cat phase0_seed_results.csv
```

In that table: `delta=1` means a new seed was added (good); `delta=0` with `UNKNOWN` means that run
timed out and added nothing (normal, expected sometimes); `delta=0` with `OPTIMAL` means a duplicate
(should be rare — note it if you see it).

---

## STEP 4 — Verify the run (run this exact command)

```bash
echo "ZOMBIES=$(wmic process where \"name='java.exe'\" get CommandLine 2>/dev/null | grep -c HeadlessSolveRunner)"
ls .seed_pool.lock 2>/dev/null && echo "LOCK=PRESENT" || echo "LOCK=REMOVED"
python -c "import sqlite3;c=sqlite3.connect('residency_scheduler.db');print('INTEGRITY='+c.execute('PRAGMA integrity_check').fetchone()[0]);print('LEAKED='+str(c.execute(\"SELECT COUNT(*) FROM schedule_config WHERE config_key LIKE 'phase0%'\").fetchone()[0]))"
python -c "import sqlite3;c=sqlite3.connect('residency_scheduler.pool.db');r=c.execute(\"SELECT config_value FROM schedule_config WHERE config_key='phase0_feasible_pool_2'\").fetchone();print('ENDPOOL='+str(sum(1 for x in r[0].split('␞') if x.strip())))"
```

Check the output against these PASS conditions. ALL must be true:
- `ZOMBIES=0`
- `LOCK=REMOVED`
- `INTEGRITY=ok`
- `LEAKED=0`
- `ENDPOOL` number is **greater than or equal to** the `STARTPOOL` number from STEP 1.

If any condition fails → **STOP** and tell the user exactly which check failed. (Especially: if the
CSV contains a line with the word `FATAL` or `SHRANK`, the pool lost data — report it immediately.)

If all pass, go to STEP 5.

---

## STEP 5 — Feasibility audit (run this exact command)

This independently re-checks that every new seed is truly feasible. It works on a COPY, so it never
touches the live database.

```bash
rm -rf audit_workdir && mkdir -p audit_workdir && cp residency_scheduler.pool.db audit_workdir/residency_scheduler.db
cd audit_workdir && CP="../target/classes;$(sed -E 's#(^|;)target/classes#\1../target/classes#' ../cp.txt)"
java -cp "$CP" com.residency.tools.PoolAudit 2 2>&1 | grep -avE 'WARNING|SLF4J|Loading|native|Restricted|Unsafe|deprecat' | tail -6
cd .. && rm -rf audit_workdir
```

In the output:
- Every line should say `FEASIBLE`. The summary line must say `stale: 0`.
- The `diversity` line should show numbers roughly between 850 and 1000 with `0 identical pairs`.

If you see any `STALE` entry or `stale:` is not 0 → tell the user the model may have changed since
these seeds were collected (flag it; it is not fatal but they should know).

---

## STEP 6 — Report to the user

Give a short summary with these numbers:
- How many runs, how many found a seed (OPTIMAL) vs timed out (UNKNOWN).
- Pool grew from `STARTPOOL` to `ENDPOOL` (added the difference).
- All verification gates passed (zombies 0, lock removed, integrity ok, no leaked keys, pool grew).
- Audit: all seeds FEASIBLE, 0 stale, diversity range.

---

## Rules you must not break
- Use cap **180** by default; never below 150 unless the user asks.
- NEVER add `FRESH=1` to the launch command — that would erase the pool.
- NEVER start a second run while one is running (STEP 1's lock check prevents this — respect it).
- NEVER hand-edit `residency_scheduler.db` — the script backs it up and restores it automatically.
- If `mvn -q compile` has not been run since the code last changed and a command fails with a Java
  error, run `mvn -q compile` once from the project directory, then retry the step.
