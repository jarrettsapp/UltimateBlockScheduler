---
name: design-solver-batch
description: Design the next evidence-driven batch of residency block-schedule solver configs to test — study the prior runs, reason out which floor/target/weight/runs combinations to try next and why, then write a machine-readable queue.jsonl for the autonomous sweep driver. This is the ANALYTICAL/config-designer role; it does NOT run anything (the autonomous tool does). Use when the user wants to plan the next sweep batch, propose new configs, decide what to test next, or refill the queue. Triggers: "design the next batch", "what should we test next", "plan a sweep", "fill the queue", "propose configs".
---

# Design the next solver-tuning batch (analytical — no runs)

Project dir (all reads/writes here):
`c:/Users/Jarrett/Desktop/Block Schedule app/residency-scheduler/residency-scheduler`

You are the **config designer**. Your job is purely analytical: study the data from runs
already done and propose the next, evidence-driven batch of configs. **You do not run anything** —
a separate autonomous tool (`sweep_driver.py` reading `queue.jsonl`) runs the batch unattended.
Decide what to test and why, then hand off the queue. To actually execute a single config, that's
the sibling skill [[run-solver-config]] — different role.

## 1. Read for full context first (don't re-derive what's settled)
- `PROJECT.md` — living tracker + key findings (status, open work, known bugs).
- `SCHEDULE_ITERATION_REPORT.md` — per-config scores + auto-appended sweep table. **Source of truth
  for which labels already exist** (don't collide).
- `sweep_results.csv` — machine-readable results incl. `plateau_s` per run.
- `traj_*.csv` and `sweep_runs/traj_*.csv` — Phase-3 objective-vs-time curves (where each run plateaued).
- Memory: [[solver-tuning-experiment]], [[solver-trajectory-capture]], [[parallel-solver-runs]],
  [[weekend-coverage-math]], [[coverage-floor-model]].

## 2. What's established — don't re-litigate (verify against docs, don't re-test)
- Objective penalty = `max(0, target − eligibleCoverers)`, capped at target. At target=2 a volunteer
  costs exactly 1 more than fragile; the cap means 2 coverers == 5 coverers (no surplus reward).
- **Best family: floor OFF + target=2 + weight 75–120** → vol=1 / fragile=6 / healthy=18.
- **target=3 backfires** (over-stacks easy weekends) — don't propose more floor-OFF target=3.
- **Hard floor pins fragile ~11 regardless of weight** — floor-ON is a closed direction; don't spend
  runs re-confirming it unless the user explicitly wants a baseline.
- Practical fragile floor is ~3–5 (combinatorial bound — see [[weekend-coverage-math]]).
- **Plateau time, not branch count, is the quality signal.** Phase 3 ends FEASIBLE not OPTIMAL, so
  the same config converges to the same objective floor but a given DRAW may be cut off before
  reaching it. The floor-OFF/target=2/w=120 objective converges to ~1094; the 6/18-vs-7/17
  "variance" is the single final step, reached at 13–23 min (one run stalled at 1212 even at 37 min).
  ⇒ **Repeats with trajectory capture characterize variance better than new weight points.**

## 3. How to reason the batch (the cfgD/E/F way — no blind grids)
Look at the data and ask, in order:
1. **Where are the gaps?** Unfilled points in the weight curve at target=2; weights above the highest
   tested; anything the trajectory flags (a config that never plateaued).
2. **What's the binding lever right now?** If the config is converged, the open question is "does the
   draw reach plateau" → spend on **repeats** of the proven weights *with trajectory capture*.
3. **What does theory rule out?** Don't propose configs the data already kills (floor-ON gains,
   target=3, weights the curve shows are flat).
4. **One probe, not a sweep,** for an unexplored region (e.g. a single high-weight point to bound the
   useful range). Add a second only if the first could be ambiguous.
5. **Budget:** standard `[900,300,300,2400]` covers all observed plateaus. Only add a custom `budget`
   (e.g. P3=3600) for a deliberate insurance/late-plateau run, and justify it inline.

When a direction is genuinely ambiguous (how hard to push weight, repeats-vs-breadth, whether to
re-confirm a baseline), **use AskUserQuestion** rather than guessing — this batch must be evidence-driven.

## 4. ALWAYS confirm batch size and per-run length with the user (AskUserQuestion)
Two knobs depend on how the user plans to babysit the run, and they CHANGE day to day — never assume.
Once you've reasoned the configs (§3), ask both before writing the queue:

1. **Total batch size / how long unattended.** Compute and state the runtime first (§7), then confirm:
   does the proposed N runs fit the window the user has? On a multi-day away period they may want
   MORE configs (deeper exploration while gone); on a day they're around they may want a SHORT batch.
   Adjust the number/repeats of configs to fit — don't just truncate.
2. **Per-run length = the Phase-3 budget.** P3 is the only phase that uses its full budget (others
   exit early on OPTIMAL), so "how long is each run" IS the P3 number. The tradeoff, from the data:
   - **Standard P3=2400s (~48 min/run total):** covers all observed plateaus (~1094 reached by 13–23
     min); the safe default.
   - **Longer P3=3000–3600s:** insurance for **multi-day unattended** runs the user won't check —
     guarantees even a slow draw reaches plateau; costs ~+10–20 min/run.
   - **Shorter P3=1200–1500s (~30–35 min/run):** for days the user wants quick turnaround / is
     watching — risks cutting a draw off one step above ~1094 (accept that it's a faster, slightly
     noisier draw). Don't go below ~1200s — Phase 0 alone can need ~2–8 min to seed.
   Apply the chosen length as the `budget` `[900,300,300,<P3>]` on the runs (omit only if standard).

Offer sensible defaults (standard length, the N you reasoned) as the recommended option so the user
can one-tap accept. Then recompute and re-state total runtime with their choices.

## 5. Label naming — `cfgR<round>-<NN>` (READ THIS — it's why the skill exists)
Single letters (cfgA…cfgZ) ran out at 26 and **don't sort or group by batch**. Going forward:

- **Format: `cfgR<round>-<NN>`** — e.g. `cfgR3-01`, `cfgR3-02`, … `cfgR4-01`. Round = one batch/queue
  you design; `NN` = zero-padded sequence within it (start at 01).
- **Sorts correctly forever** (R3 < R4, 01 < 02 < … < 10), **groups by batch**, never runs out.
- The **date** lives in the `sweep_results.csv`/report row, not the label — don't stuff it in.
- **Old `cfgA`–`cfgF` (and v1–v18) are historical** — leave them as-is; the new scheme starts at the
  first round you design under it. Round 3 was the first (2026-06-22).
- **Pick the round number** = highest existing `cfgR<n>-*` round in the report + 1 (or 3 if none yet).
- **Check the report for collisions** before finalizing labels.

## 6. Deliverable — two parts
1. **A readable table** for the user: one row per config — label, floor, target, weight, runs, budget
   (if non-standard), and a one-line rationale tied to the data (hypothesis → what we learn).
2. **`queue.jsonl`** — the seed file the autonomous driver consumes. Format (one object per line;
   blank lines and `#` comments ignored):
   ```
   {"label":"cfgR3-01","floor":false,"target":2,"weight":75,"runs":3}
   {"label":"cfgR3-05","floor":false,"target":2,"weight":120,"runs":1,"budget":[900,300,300,3600]}
   ```
   Fields: `label` (str, unique), `floor` (bool), `target` (int), `weight` (int), `runs` (int repeats),
   `budget` (optional `[P0,P1,P2,P3]` seconds; omit to use the standard `[900,300,300,2400]`).
   Keep a short comment header noting the round, the label scheme, and the batch rationale. Editing
   floor/target/weight/budget makes a line a NEW unit (param hash changes) so it re-runs; re-ordering
   or commenting-out already-DONE units is safe.

## 7. State the runtime cost
Standard run ≈ 48 min at 10 workers sequential (budget `900 300 300 2400`); a longer-P3 run adds
~10–20 min, a shorter-P3 run saves ~13–18 min. Per-run time tracks the chosen P3 length from §4.
**Report total estimated runtime** = sum of all runs × per-run time, in hours — state it BEFORE the
§4 confirmation (so the user can judge fit) and AGAIN after, recomputed with their chosen length/N.

## Constraints
- Propose only floor/target/weight/runs/budget combinations — **no Java, schema, or objective changes.**
- Don't collide with existing labels/versions in the report.
- You design and write the queue; you do **not** launch the driver or any solve.
