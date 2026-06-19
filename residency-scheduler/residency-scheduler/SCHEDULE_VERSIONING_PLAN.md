# Implementation Plan — Schedule Versioning, Comparison & Long-Solve Limits

_Drafted 2026-06-18 for review before building._

Three related features so you can solve repeatedly, keep multiple "final production"
schedules, and track improvements over time — all inside the app.

---

## Feature 1 — Named schedule snapshots (in the same DB)

**Goal:** after a solve, save the current schedule as a named version; later list, load,
or delete versions. Solving no longer silently destroys prior work.

### Data model
New table (added via the existing migrations list):
```sql
CREATE TABLE schedule_versions (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  schedule_year INTEGER NOT NULL,
  name          TEXT NOT NULL,
  created_at    TEXT NOT NULL,          -- ISO timestamp
  notes         TEXT,                   -- optional free-text
  -- snapshot of solve metadata at save time:
  tier1_score   INTEGER, tier2_score INTEGER, tier3_score INTEGER,
  feasible      INTEGER, summary TEXT,
  UNIQUE(schedule_year, name)
);
CREATE TABLE schedule_version_assignments (
  version_id  INTEGER NOT NULL REFERENCES schedule_versions(id) ON DELETE CASCADE,
  resident_id INTEGER NOT NULL,
  rotation_id INTEGER NOT NULL,
  block_number INTEGER NOT NULL        -- store by block_number, not block_id, so it's
                                        -- robust if blocks are regenerated
);
```
Storing assignments by `block_number` (1–26) rather than `block_id` keeps a version
loadable even if the year's block rows are rebuilt.

### DAO — `ScheduleVersionDAO`
- `saveVersion(year, name, notes, scores)` → reads current `assignments` for the year,
  copies them (resolving block_id→block_number) into the snapshot tables; returns id.
- `listVersions(year)` → `[{id, name, created_at, scores, feasible}]`.
- `loadVersion(id)` → **replaces** the live `assignments` for that year with the
  snapshot (block_number→block_id), so the schedule grid shows the chosen version.
- `deleteVersion(id)`.
- `getVersionAssignments(id)` → for the comparison view (no DB mutation).

### UI (in AutoScheduleView or ScheduleView)
- **"Save as version…"** button → prompts for a name (+ optional notes), calls
  `saveVersion`. Disabled if no schedule exists for the year.
- **"Versions…"** button → a dialog listing saved versions for the year with
  Load / Delete / Compare actions and their scores + timestamps.
- Loading a version asks for confirmation (it overwrites the live schedule), and offers
  to auto-save the current live schedule first if it isn't already a version.

---

## Feature 2 — Built-in metrics comparison

**Goal:** a side-by-side view of the tracked metrics across saved versions (the same
numbers as SCHEDULE_ITERATION_REPORT, but live).

### Metrics computed per version (read-only, from snapshot assignments)
Reuse the program's authoritative tiers (not `rotation_type`):
- **Capacity compliance:** ICU ≤1 cat, VA ≤2 cat, BMC ≤2 total, Y7 Days = 2/block,
  block 13 = 2 cats (pass/fail + offending blocks).
- **Call coverage (eligibility-based):** volunteer (0-coverer) Sundays, fragile
  (1-coverer) weekends, healthy (≥2) weekends.
- **Transitions:** direct heavy→different-heavy count; consecutive heavy+medium run
  histogram; runs > 6 weeks.
- **Saturday Y8-Pulm floor** (info only).

### Implementation
- New `ScheduleMetrics` service (pure function: assignments + tier config → metrics
  record). Single source of truth so the app and any report agree.
- New `VersionCompareView` (a tab or dialog): pick 2+ versions → table with one column
  per version, one row per metric, best values highlighted. Mirrors the iteration report
  layout.
- Bonus: an **"Export comparison"** button to write the same data to a markdown/HTML/PDF
  (so the reports become a UI feature, not something done by hand).

### Note on operational rules
Coverage eligibility and the BMC/TY composition are **operational** (the solver doesn't
own them). The metrics service applies the same eligibility model documented in
`SCHEDULING_RULES.md`, and the capacity checks include the BMC/TY-aware Y7 Days rule.

---

## Feature 3 — Long-solve time limits + presets

**Problem:** the four phase spinners are capped at **600 s**; the capacity-constrained
model needs Phase 1 ≈ 1200 s and Phase 3 ≈ 2700 s to reach good quality. Today that's
impossible from the app.

### Changes (AutoScheduleView)
- Raise each spinner's max from 600 → **3600** (1 hour/phase).
- Add a **preset dropdown** that sets all four phases at once:
  - **Quick** — 30 / 120 / 60 / 60 (fast feasibility check)
  - **Standard** — 60 / 300 / 120 / 300 (the current default-ish)
  - **Long** — 90 / 1200 / 180 / 1800 (what the capacity model needs)
  - **Overnight** — 120 / 1800 / 300 / 3600
  - **Custom** — leave the spinners as hand-set
- Persist the last-used limits (and chosen preset) so they survive app restarts.
- Keep the existing "Stop" button working for these long runs (it already commits the
  best result found so far at the phase boundary).

---

## Files touched
- `db/DatabaseManager.java` — 2 new tables in the migrations list.
- `db/ScheduleVersionDAO.java` (new) — save/list/load/delete/getAssignments.
- `service/ScheduleMetrics.java` (new) — metrics from assignments + tiers.
- `ui/AutoScheduleView.java` — Save-as-version + Versions buttons; raise spinner max;
  preset dropdown; persist limits.
- `ui/VersionCompareView.java` (new) — side-by-side metrics; export.
- Tests: `ScheduleVersionDAOTest` (round-trip save/load), `ScheduleMetricsTest`
  (capacity + coverage + transition counts on a known fixture).

## Open decisions for owner
1. **Where do the version buttons live** — on the Auto-Schedule (solve) screen, the
   Schedule (grid) screen, or both? _Recommend: Save on the solve screen; Versions/Compare
   reachable from the Schedule screen._
2. **Auto-snapshot before overwrite?** When a solve or a version-load is about to replace
   the live schedule, offer to auto-save it first (prevents accidental loss). _Recommend:
   yes, with a default name like "autosave-<timestamp>"._
3. **Comparison export format** — markdown only, or markdown + PDF (reusing the Chrome
   pipeline)? _Recommend: markdown now; PDF later if useful._
