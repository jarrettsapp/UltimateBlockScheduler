---
name: run-full-pipeline
description: Run the complete end-to-end block schedule pipeline unattended — Phase-0 seed generation, Phase-1/2 harvest, and Timefold Phase-3 optimization — all in one command. Records every run into solve_runs, produces PIPELINE_REPORT.md when done, and alerts immediately on failure. Use when the user wants to run the full pipeline, generate a batch of finalists, or says "run the pipeline" / "run everything end to end" / "generate schedules". Triggers: "run the pipeline", "run everything", "generate schedules end to end", "full pipeline run", "run from seeds to finalists".
---

# Full Pipeline — end to end (seed gen → harvest → Timefold Phase-3)

Your job is small: gather four numbers, run ONE launch command, read what it prints, and tell the
user. You do NOT need to verify the database, the git branch, or the compiled code yourself — the
launcher and the driver check all of that automatically and STOP with a written reason if anything
is wrong. Do not re-implement those checks by hand.

Follow the steps IN ORDER. When a step says STOP, stop and tell the user the reason printed on
screen. Do not continue past a STOP.

The pipeline runs three stages automatically, in sequence, with no input from you. It works on
**fresh, never-used work only** — no duplicates at any stage:
- **Stage 1** — Phase-0 seed generation (top up so there are N *unused* seeds — seeds never yet harvested)
- **Stage 2** — Phase-1/2 harvest (harvest only those unused seeds, each one exactly once)
- **Stage 3** — Timefold Phase-3 (optimize only Phase-2 versions never sent to Timefold before)

> **"Run N seeds" means N UNUSED seeds.** A seed already consumed by a prior harvest does NOT count.
> So if the pool already holds N seeds but all of them were harvested before, Stage 1 generates N
> brand-new ones. Stage 2 harvests only the unused seeds (never re-harvests). Stage 3 sends only
> never-optimized versions to Timefold. The driver enforces all three from the DB + bookkeeping, so
> re-running never repeats work.

All commands run from this directory. Use it exactly:

```
C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler
```

> **Which tool to use:** every command block below is labeled `[PowerShell]` or `[Bash]`. Run it
> with that tool. Do not run a `[PowerShell]` block in Bash or a `[Bash]` block in PowerShell.

---

## STEP 0 — Get the four parameters

Ask the user for these. If they don't give a value, use the default.

**`seeds` is the one number that matters — `harvest` and `top-k` DEFAULT TO IT** (seeds = p2 = k).
So a request like "run 20 seeds" means seeds=20, harvest=20, top-k=20 unless the user overrides one.
Only `p3-budget` is independent.

| Parameter | Flag | Default | Meaning |
|---|---|---|---|
| **seeds** | `-Seeds` | 20 | Target number of *unused* (un-harvested) seeds after Stage 1. Stage 1 generates only the shortfall between the current unused count and this number. |
| **harvest** | `-Harvest` | = `seeds` | Max unused seeds to harvest (each exactly once). Defaults to the seed count. |
| **top-k** | `-TopK` | = `seeds` | How many of the best *never-optimized* harvest schedules to send to Timefold. Defaults to the seed count. |
| **p3-budget** | `-P3Budget` | 600 | Timefold seconds per run (600 = locked production multi-start budget; do not use 300 for a representative run). |

> **Why these defaults:** the locked Timefold production config is multi-start(10) × **600s** — always
> use 600 unless the user explicitly wants faster/cheaper feedback. Tying harvest and top-k to the seed
> count keeps a test batch proportional (each unused seed harvested once, all ranked). The user can
> still override any of the three independently.
>
> **No-duplicate guarantee (driver-enforced):** the driver computes "unused seeds" from the DB
> (`phase0_seed_stats` minus seeds with a `PHASE2_FALLBACK` harvest row) and pins each harvest to one
> unused seed via `PHASE0_SEED_ID`, so no seed is harvested twice. Phase-3 dedup reads
> `pipeline_state.json` + `phase3_lineage.csv`, so an already-optimized version is never re-sent to
> Timefold even if one of those records is missing. You do not need to manage any of this — just pass
> the numbers.

Also ask one yes/no question: **does the user want to skip a stage?**
- Skip seed generation (there are already ≥ N unused seeds to harvest) → add `-SkipSeeds`.
- Skip seeds + harvest (only want Phase-3 on existing never-optimized versions) → add `-SkipSeeds -SkipHarvest`.
- Otherwise → run all three stages (no skip flags).

> **Skip-stage caution under the no-duplicate rule:** if you `-SkipSeeds` but there are 0 unused
> seeds, Stage 2 will find nothing to harvest and do nothing. If you `-SkipSeeds -SkipHarvest` but
> every Phase-2 version was already optimized (FRESH_P2 = 0 in STEP 0.5), Stage 3 HALTs with "no
> un-optimized versions". The STEP 0.5 inventory tells you whether a skip is safe — read it first.

Write the four numbers down. You will paste them into the command in STEP 2.

---

## STEP 0.5 — Show the user the current inventory, then help them decide

Before launching, run this ONE command. It prints the inventory counts AND the exact Phase-2
candidates Stage 3 would pick next (with your top-k). Read the counts and the candidate list back to
the user so they can choose which stages to skip. Replace `TOPK` with the user's top-k from STEP 0
(defaults to the seed count).

```bash
# [Bash]
python -c "
import os, sqlite3, json, csv
TOPK = TOPK
c = sqlite3.connect('residency_scheduler.db', timeout=10)
pool = c.execute('SELECT COUNT(*) FROM phase0_seed_stats WHERE year=2 AND seed_id IS NOT NULL').fetchall()
pool_ids = {r[0][:8] for r in c.execute('SELECT seed_id FROM phase0_seed_stats WHERE year=2 AND seed_id IS NOT NULL').fetchall()}
# Seeds CONSUMED by a Phase-2 harvest (the DB record of 'used') -> unused = pool minus these
harvested = {r[0][:8] for r in c.execute('SELECT DISTINCT seed_id FROM solve_runs WHERE run_status=\"PHASE2_FALLBACK\" AND year=2 AND seed_id IS NOT NULL').fetchall()}
unused_ids = sorted(pool_ids - harvested)
rows = c.execute('''SELECT sr.version_id, sr.seed_id, srm.fragile, srm.healthy, srm.volunteer, srm.heavy_heavy
  FROM solve_runs sr JOIN solve_run_metrics srm ON srm.run_id=sr.id
  WHERE sr.run_status=\"PHASE2_FALLBACK\" AND sr.feasible=0 AND sr.year=2
    AND sr.version_id IS NOT NULL AND srm.fragile IS NOT NULL
  ORDER BY srm.fragile ASC, srm.healthy DESC, srm.heavy_heavy ASC''').fetchall()
tf = c.execute('SELECT COUNT(*) FROM solve_runs WHERE year=2 AND p3_status=\"TIMEFOLD\"').fetchone()[0]
c.close()
# Already-optimized Phase-2 sources: union of pipeline_state.json[phase3] (authoritative) + lineage CSV
used = set()
st = json.load(open('pipeline_state.json')) if os.path.exists('pipeline_state.json') else None
if st:
    for rec in (st.get('phase3') or {}).values():
        v = rec.get('src_version_id')
        if v not in (None, '') and str(v).isdigit(): used.add(int(v))
if os.path.exists('phase3_lineage.csv'):
    with open('phase3_lineage.csv', newline='', encoding='utf-8') as f:
        for r in csv.DictReader(f):
            v = (r.get('src_version_id') or '').strip()
            if v.isdigit(): used.add(int(v))
fresh = [r for r in rows if r[0] not in used]
print('SEEDS_IN_POOL   =', len(pool_ids), '  (all Phase-0 seeds in the pool)')
print('UNUSED_SEEDS    =', len(unused_ids), '  (seeds NEVER harvested — what \"run N seeds\" draws from; Stage 1 tops THIS up to -Seeds)')
print('QUALIFYING_P2   =', len(rows), '  (existing Phase-2 schedules in the DB)')
print('ALREADY_USED_P2 =', len(used), '  (Phase-2 versions already sent to Timefold; state.json + lineage CSV)')
print('FRESH_P2        =', len(fresh), '  (never-optimized Phase-2 left; Stage 3 picks top-k from THESE)')
print('TIMEFOLD_DONE   =', tf,  '  (Phase-3/Timefold runs ever completed in this DB)')
if st is None:
    print('PIPELINE_STATE  = none  (fresh run; nothing to resume)')
else:
    hd = sum(1 for r in st.get('harvest',{}).values() if r.get('status')=='DONE')
    p3 = sum(1 for r in st.get('phase3',{}).values()  if r.get('status')=='DONE')
    print(f'PIPELINE_STATE  = present (stage={st.get(\"stage\",\"?\")}; banked {hd} harvest + {p3} phase-3 runs; will RESUME)')
print()
print(f'NEXT {TOPK} PHASE-3 CANDIDATES (what Stage 3 would optimize now):')
for r in fresh[:TOPK]:
    print(f'  version={r[0]} seed={r[1][:8]} frag={r[2]} heal={r[3]} vol={r[4]} hh={r[5]}')
if not fresh:
    print('  (none left un-optimized — Stage 3 would HALT unless Stage 2 produces fresh harvest first)')
"
```

Now explain to the user, in plain terms, what each count means and how the no-duplicate rule behaves:

- **UNUSED_SEEDS is the number that drives Stage 1.** "Run N seeds" generates `max(0, N − UNUSED_SEEDS)`
  new seeds. If UNUSED_SEEDS is already ≥ N, Stage 1 does nothing and `-SkipSeeds` is equivalent. If
  the pool is large but UNUSED_SEEDS is small (every existing seed was already harvested), Stage 1
  still generates fresh seeds — that is the point of the rule.
- **Stage 2 harvests only unused seeds, each exactly once.** It pins each harvest to one UNUSED seed,
  so it never re-harvests a seed already consumed. If UNUSED_SEEDS is 0 and you `-SkipSeeds`, Stage 2
  has nothing to do. Your existing Phase-2 runs are never touched.
- **Stage 3 sends only never-optimized versions.** It ranks the `QUALIFYING_P2` rows, drops the
  `ALREADY_USED_P2` set (from `pipeline_state.json` + `phase3_lineage.csv`), and sends the best
  `FRESH_P2` up to `-TopK` to Timefold. The "NEXT … CANDIDATES" list is exactly what it would pick.
  If `FRESH_P2` is 0, Stage 3 HALTs rather than redo work.

**Recommend a choice based on the numbers:**

| If the inventory shows… | Recommend | Why |
|---|---|---|
| `UNUSED_SEEDS` ≥ your `-Seeds` | `-SkipSeeds` | Enough fresh seeds already exist to harvest; no need to generate more. |
| `FRESH_P2` ≥ your `-TopK` and you don't need new harvest variety | `-SkipSeeds -SkipHarvest` | Straight to Phase-3 on never-optimized schedules — no seeds or harvest re-solved. |
| `UNUSED_SEEDS` < your `-Seeds` (need fresh seeds) | full run | Tops up unused seeds, harvests them, then ranks + optimizes the fresh results. |

Confirm the user's choice before STEP 2.

---

## STEP 1 — Make sure the scheduling app is closed

The JavaFX scheduling app locks the database, so the pipeline cannot run while it is open. Check:

```powershell
# [PowerShell]
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'com\.residency\.ui\.MainApp' -or $_.CommandLine -match 'javafx:run' } |
  Select-Object ProcessId, CommandLine
```

- If this prints **nothing** → good, continue to STEP 2.
- If it prints a process → **STOP.** Tell the user: "The scheduling app is open and locks the
  database. Please close it, then run this skill again."

That is the only manual check you need. Everything else (git branch, compiled classes, `cp.txt`,
database integrity, and refusing a double-launch) is checked by the launcher and driver in STEP 2.
If any of those is wrong, the launch will not run and STEP 3 tells you exactly what to do.

---

## STEP 2 — Launch the pipeline (one command)

Take the command below, replace the four ALL-CAPS placeholders with the numbers from STEP 0, and
add any skip flags the user asked for. Then run it.

**Full pipeline (all three stages):**
```powershell
# [PowerShell]
powershell -ExecutionPolicy Bypass -File "C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler\launch_pipeline.ps1" -Seeds SEEDS -Harvest HARVEST -TopK TOPK -P3Budget P3BUDGET
```

**Skipping seed generation** — add `-SkipSeeds` to the end of the same command.
**Skipping seeds and harvest** — add `-SkipSeeds -SkipHarvest` to the end.

> Example with values seeds=20, harvest=20, top-k=20, budget=600 (seeds=p2=k, production budget), no skips:
> ```powershell
> # [PowerShell]
> powershell -ExecutionPolicy Bypass -File "C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler\launch_pipeline.ps1" -Seeds 20 -Harvest 20 -TopK 20 -P3Budget 600
> ```

The launcher prints a CONFIGURATION block and its own time estimate, then a HEALTH CHECK block.
Read that estimate and the health-check verdict — you will relay them in STEP 3. Do not compute the
estimate yourself; use the one the launcher prints.

---

## STEP 3 — Read the launcher verdict and tell the user

Look at the last colored line the launcher printed. Match the FIRST WORD:

- Starts with **`RUNNING`** → launch succeeded; the driver and a java solve are both active. Tell
  the user it's running, then go to STEP 4.
- Starts with **`STARTING`** → the driver is up but hasn't spawned its first java solve yet (normal
  during Stage 1 startup, or whenever a stage is skipped). This is fine. To confirm it advanced,
  re-run the **same launch command** from STEP 2 after ~30 seconds — it will report `ALREADY RUNNING`
  with a java PID once it's working. Then go to STEP 4.
- Starts with **`ALREADY RUNNING`** → a pipeline was already going (or your re-run found the one you
  just launched). Nothing to do; go to STEP 4.
- Starts with **`BLOCKED`** → the JavaFX app is open. STOP. Tell the user to close it and re-run.
- Starts with **`NOT RUNNING`** → the driver exited during startup, almost always a preflight HALT.
  The launcher prints the tail of the log right below this line. Read it, apply the matching fix in
  STEP 6, then re-run STEP 2.

---

## STEP 4 — Tell the user what to watch, then wait

Tell the user they can safely close this session — the pipeline runs detached. Progress is written to
these files in the project directory (they can open them anytime):

- `PIPELINE_STATUS.md` — current stage and ETA; on failure its top line becomes `# Pipeline HALTED` with a **Reason**.
- `PIPELINE_REPORT.md` — results table, updated throughout and finalized at the end.
- `pipeline_results.csv` — raw per-run log (one row per seed/harvest/phase-3 unit).
- `phase3_lineage.csv` — one row per Timefold run linking the source Phase-2 version → new version,
  with before/after frag/heal/vol/hh and the delta. This is the record of which Phase-2 schedules
  were optimized (the DB does not store that link).
- `pipeline_driver.out.log` — full driver output.

Every solve is also recorded into the database itself: harvest runs and Phase-3 runs each write a
`solve_runs` row plus `solve_run_metrics` (and the weekend coverage vector), so the standard analysis
skills (solve-stats, seed-pool-stats) pick them up automatically.

Failures surface within seconds in `PIPELINE_STATUS.md` — no need to wait hours to learn something broke.

**Do NOT poll on a timer.** Wait for the user to ask for a status check (STEP 5) or the final report (STEP 6).

---

## STEP 5 — When the user asks "how is it going?"

Run both blocks and report the numbers plainly.

```bash
# [Bash]
python -c "import os,json; s=json.load(open('pipeline_state.json')) if os.path.exists('pipeline_state.json') else {}; d=lambda k:sum(1 for r in s.get(k,{}).values() if r.get('status')=='DONE'); print('Lock:', 'HELD' if os.path.exists('pipeline.lock') else 'RELEASED'); print('Stage:', s.get('stage','unknown')); print('Seeds done:', d('seeds')); print('Harvest done:', d('harvest')); print('Phase-3 done:', d('phase3'))"
```

```powershell
# [PowerShell]
Get-Content "PIPELINE_STATUS.md"; Write-Host "----- driver log tail -----"; Get-Content "pipeline_driver.out.log" -Tail 10
```

- If `PIPELINE_STATUS.md` starts with `# Pipeline HALTED` → the pipeline stopped. Read the **Reason**, then go to STEP 6's fix table.
- If `Lock: RELEASED` and `Stage: done` → it finished. Go to the report below.

---

## STEP 6 — When it finishes (or you need to fix a HALT)

**When it finished** (`Stage: done`), show the user the final report and the ranked finalists:

```powershell
# [PowerShell]
Get-Content "PIPELINE_REPORT.md"
```

```bash
# [Bash]
python -c "import os,json; s=json.load(open('pipeline_state.json')) if os.path.exists('pipeline_state.json') else {}; p3=[(u,r) for u,r in s.get('phase3',{}).items() if r.get('status')=='DONE']; p3.sort(key=lambda x:(int(x[1].get('frag',99) or 99), -int(x[1].get('heal',0) or 0))); print(f'Phase-3 finalists ({len(p3)}):'); [print(f'  {u}: frag={r.get(\"frag\",\"?\")} vol={r.get(\"vol\",\"?\")} heal={r.get(\"heal\",\"?\")} version={r.get(\"new_version_id\",\"?\")}') for u,r in p3]"
```

To inspect or render any finalist version:
```bash
# [Bash]
python score_and_snapshot.py --no-save --version VERSION_ID
python gen_grid.py --version VERSION_ID --html finalist_grid.html
```

**If it HALTED**, the **Reason** in `PIPELINE_STATUS.md` tells you which fix applies:

| Reason contains | Fix |
|---|---|
| `DB integrity_check != ok` | **STOP.** Tell the user the database may be corrupt — restore from `backups/`. Do not relaunch. |
| `another pipeline driver is already running` | A pipeline is already going. Do nothing; check `PIPELINE_STATUS.md` for its progress. |
| `consecutive ... failures` | A stage kept failing. Check the per-unit logs in `pipeline_runs/`. For harvest, run `python pipeline_select_candidates.py` to see if any valid harvest data exists. |
| `no valid Phase-2 harvest versions found` | Stage 2 produced nothing usable. Check `pipeline_runs/harvest-*.log`; a longer harvest budget may be needed. |
| `compiled TrajectoryCallback artifact missing` | Run `mvn -q compile` (in the project dir), then re-run STEP 2. |
| `cp.txt missing` | Run `mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt`, then re-run STEP 2. |
| `on branch ..., need ...` | Run `git checkout main` then `mvn -q compile`, then re-run STEP 2. |

**Resuming:** the driver is fully resumable. After fixing the cause, re-run the SAME launch command
from STEP 2 with the SAME parameters. It reads `pipeline_state.json` and skips already-completed
units. Only start over (by deleting `pipeline_state.json`) if you specifically want to discard all
prior progress.

---

## Rules you must not break

- Always do STEP 1 (confirm the JavaFX app is closed) before launching.
- Never run two pipelines at once — they write the same database. The launcher refuses a
  double-launch; trust it, don't force around it.
- Never hand-edit `pipeline_state.json` — the driver manages it atomically.
- Do not poll on a timer; respond to status checks when the user asks.
- The launcher and driver do all preflighting. If a launch is refused, read the printed reason and
  fix THAT — don't guess or skip checks.
