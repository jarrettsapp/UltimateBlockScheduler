---
name: babysit-pipeline
description: Read-only status check of the running full pipeline. Reports stage, progress, and whether it is running / done / halted — then asks when to check again. NEVER edits, launches, kills, or touches the database. Use to monitor a pipeline already started by run-full-pipeline. Triggers: "babysit the pipeline", "check on the pipeline", "watch the run", "is the pipeline ok".
---

# Babysit Pipeline — READ-ONLY status check

You are a watcher. Your ONLY job is to read three files, report what they say, and ask the
user when to check again. You take **no corrective action of any kind.**

## ABSOLUTE RULES — never break these

1. **READ-ONLY.** You may only run the two read commands in STEP 1. You must NOT:
   - edit, write, or delete ANY file (including `pipeline_state.json`, logs, the DB)
   - run the launcher, the driver, `mvn`, `git`, `Stop-Process`/`kill`, or any java/python solve
   - run anything that writes to the database or removes the lock
   - "fix", "resume", "relaunch", "unstick", or "clean up" anything
2. **If something looks wrong, you REPORT and ESCALATE — you do not act.** A halt, a crash, a
   stuck stage, a missing file: your response is to tell the user clearly and stop. Never try
   to resolve it yourself.
3. **Only use the exact commands below.** Do not improvise other commands, even read-only ones.
4. **Never claim it's "running fine" unless the checks below actually show that.** If a file is
   missing or a value is unexpected, say so plainly rather than guessing it's okay.

If the user asks you to fix, relaunch, or change anything: refuse and tell them to use the
`run-full-pipeline` skill (or a non-Haiku session) — that is outside a babysitter's job.

All commands run from:
```
C:\Users\Jarrett\Desktop\Block Schedule app\residency-scheduler\residency-scheduler
```

---

## STEP 1 — Read the status (the only two commands you may run)

Run BOTH. They only read files; they change nothing.

```bash
# [Bash]
python -c "import os,json; s=json.load(open('pipeline_state.json')) if os.path.exists('pipeline_state.json') else {}; d=lambda k:sum(1 for r in s.get(k,{}).values() if r.get('status')=='DONE'); print('Lock:', 'HELD' if os.path.exists('pipeline.lock') else 'RELEASED'); print('Stage:', s.get('stage','unknown')); print('Seeds done:', d('seeds')); print('Harvest done:', d('harvest')); print('Phase-3 done:', d('phase3'))"
```

```powershell
# [PowerShell]
if (Test-Path "PIPELINE_STATUS.md") { Get-Content "PIPELINE_STATUS.md" } else { Write-Host "PIPELINE_STATUS.md MISSING" }; Write-Host "----- driver log tail -----"; if (Test-Path "pipeline_driver.out.log") { Get-Content "pipeline_driver.out.log" -Tail 6 } else { Write-Host "driver log MISSING" }
```

---

## STEP 2 — Decide which of FOUR states it is, and report

Match against these. Report the matching one plainly; do nothing else.

- **RUNNING (healthy)** — `Lock: HELD`, `Stage:` is `seeds`/`harvest`/`phase3`, and
  `PIPELINE_STATUS.md` does NOT start with `# Pipeline HALTED`.
  → Report: current stage, the done counts, and the "Presumed end" line from the status file.

- **DONE** — `Lock: RELEASED` and `Stage: done`.
  → Report that it finished. Tell the user they can ask the `run-full-pipeline` skill (STEP 6)
    for the final report and ranked finalists. Do NOT pull results yourself beyond what STEP 1 printed.

- **HALTED** — `PIPELINE_STATUS.md` starts with `# Pipeline HALTED`.
  → Report the **Reason** line verbatim and the driver-log tail. Then STOP and tell the user:
    "The pipeline halted. I won't take any action — please use the `run-full-pipeline` skill or a
    non-Haiku session to diagnose and resume." Do not suggest or attempt a fix.

- **UNCERTAIN** — anything else: a file is missing, `Stage: unknown`, lock RELEASED but stage not
  `done`, or the values don't fit the patterns above.
  → Say exactly what you saw and that it does not match a known-good state. Recommend the user
    check with the `run-full-pipeline` skill. Do NOT guess that it's fine.

> Note: `PIPELINE_STATUS.md`'s "Running now" line can lag. Trust the STEP-1 python output (lock +
> stage + done counts) as the source of truth over the status file's prose.

---

## STEP 3 — Ask when to check again, then schedule it

After reporting, ask the user ONE question: **"How long until I check in again?"**
Offer a default of **2 hours** so nothing goes unwatched for too long, plus shorter options.

- If they give an interval (or accept the 2-hour default), tell them you'll check again then.
- This skill is intended to run under the `/loop` skill so the check repeats automatically. If you
  were invoked via `/loop`, the loop handles re-firing — just confirm the cadence. If not, suggest:
  "Run `/loop 2h /babysit-pipeline` to have me check every 2 hours automatically."

Never check more often than asked, and never go more than the agreed interval (default 2h) without
checking. If the state was HALTED or UNCERTAIN, recommend the user intervene now rather than waiting
for the next interval.

---

## What you must NEVER do (reminder)

- No edits. No launches. No kills. No git. No mvn. No DB writes. No lock removal.
- No "I'll just resume it" / "I'll clean up the state" / "I'll relaunch with different args."
- No pulling deep results, rendering grids, or running analysis scripts — that's the
  `run-full-pipeline` / analysis skills' job, not a babysitter's.
- When in doubt: report what you see, escalate to the user, and stop.
