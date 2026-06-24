# Seed Pool Viewer — UI Implementation Plan

Status: **AMENDED 2026-06-24 + IMPLEMENTED.** Originally design-only; amended to
account for the schema changes from `SEED_POOL_STATS_IMPLEMENTATION_PLAN.md` (PR3 + PR4:
`nn_dist_at_insert`, `best_run_tier1/2/3`, and the now-LOCKED lexicographic reward), then
implemented. See the "Schema changes since the original plan" section below.

> **What changed vs. the original design (read this first):**
> 1. **Reward metric is no longer "undecided" — it is LOCKED to lexicographic (Tier-1, then
>    Tier-2, then Tier-3)** (PR4). The metric picker still offers all tiers for exploration,
>    but the page now also surfaces a coherent **"best run"** and labels lexicographic order
>    as the canonical ranking.
> 2. **`best_tier1/2/3` are telemetry only** — each is an INDEPENDENT per-tier minimum that may
>    come from DIFFERENT runs, so the triple is NOT one schedule. The new **`best_run_tier1/2/3`**
>    columns are the single lexicographically-best real run; the page uses THOSE for "this seed's
>    best result," and visually distinguishes them from the per-tier minima.
> 3. **New saturation column `nn_dist_at_insert`** (PR3): each seed's nearest-neighbor Hamming
>    distance to the pool at the moment it was banked. Surfaced as a table column + a small
>    diversity-saturation read. NULL for pre-monitor seeds, -1 for the first seed — both render `—`.

## Goal

A new **read-only** JavaFX tab that interactively visualizes the Phase-0 cached-seed
pool (`phase0_seed_stats`) and its per-seed statistics: how often each seed has been
used to start a run, and (when available) the reward/quality of the schedules those
runs produced.

The page is a **decision aid for the seed-pool work** described in
`SEED_POOL_TRACKING_PLAN.md` — it makes the coverage-first usage distribution and the
(still-accumulating) reward record visible, so the deferred exploit/prune decision can
later be made on evidence, not a hunch. It is the read-only twin of the
`SweepAnalysisView` "Sweep Analysis" tab.

---

## Hard constraints (baked into this spec)

- **Read-only.** No solves, no DB writes, no schema changes triggered from this page.
  Only `SELECT` queries via new DAO methods.
- **Additive only.** New view class + new read-only DAO methods + one ported stats
  helper + one nav line in `MainApp`. **Do NOT** modify the engine, the seeding
  harnesses, the migration list in `DatabaseManager`, or any existing DAO method.
- **Reward metric is undecided.** Display *all three* tiers (best + avg for Tier-1/2/3).
  The scatter's Y axis is a **metric picker** — never hard-code a single "best."
- **Graceful empty/partial state.** Reward columns are currently almost all NULL
  (only Phase-0 collection has run; no full-run outcomes). Most seeds have
  `runs_scored = 0`. The page must show usage and leave quality blank (`—`),
  **never divide by zero**.

---

## Grounding: the patterns this plan follows

Confirmed by reading the codebase (branch `main`):

- **No FXML.** This app builds every UI page programmatically in Java. Each page is a
  class extending a JavaFX layout pane. Match that — **do not** introduce FXML.
  (`Glob **/*.fxml` → none.)
- **Nav = a `Tab` in `MainApp`.** `src/main/java/com/residency/ui/MainApp.java`
  constructs a `TabPane` and adds `new Tab("📈 Sweep Analysis", new SweepAnalysisView())`
  etc. (lines 20–31). A new page registers by adding one `Tab` there.
- **Closest template = `SweepAnalysisView`** (`src/main/java/com/residency/ui/SweepAnalysisView.java`).
  It is already a read-only, chart+table+help-box page (`extends BorderPane`, top bar +
  scrollable center + bottom status line). Reuse its idioms verbatim:
  - `setTop(topBar) / setCenter(scroll) / setBottom(statusLine)`.
  - Small static helpers `explainLabel()` and `sectionHeading(...)` (blue help boxes,
    bold section headings).
  - `ScatterChart<Number,Number>` with a per-point `installTooltip(...)`.
  - `TableView` with `PropertyValueFactory` columns and a `setPlaceholder(...)`.
  - A `↻ Refresh` button calling `reload()`.
  - Stable color `PALETTE` + `colorSeriesByWeight`-style styling (we adapt to color by
    "has reward data vs not").
- **DAO pattern** (`Phase0SeedStatsDAO extends BaseDAO`): every method opens with
  `getConn()` (a fresh validated connection from `DatabaseManager`), uses a
  try-with-resources `PreparedStatement`, and is parameterized. Add the new read-only
  query methods here, alongside the existing `ensureSeed` / `pickRoundRobin` /
  `markUsed` / `recordOutcome` (do not alter those).
- **Year picker idiom** (`AutoScheduleView`): `ComboBox<Integer> yearPicker`, populated
  from a DAO list, defaulting via `AppState.get().getSelectedYear()` when present.
  We adapt: populate from the new `listYears()` DAO method and **default to year 2**
  (the populated one), falling back to the first available year.

### Table being visualized — `phase0_seed_stats`

(Confirmed in `DatabaseManager.java` lines 276–291 and `SEED_POOL_TRACKING_PLAN.md`.)

| column | meaning |
|---|---|
| `seed_id` TEXT PK | sha256 of occupancy fingerprint — stable content-based key |
| `ordinal` INT | human-friendly 1..N per year, display only |
| `year` INT | scheduling year |
| `created_at` TEXT | ISO instant first cached |
| `times_started` INT | # full runs begun from this seed (usage) |
| `last_used_at` TEXT | ISO instant, for LRU; NULL if never used |
| `best_tier1/2/3` INT | per-tier INDEPENDENT minimum (telemetry; **may span runs — NOT one schedule**); NULL until scored |
| `best_run_tier1/2/3` INT | the single LEXICOGRAPHICALLY-best run (T1→T2→T3); the three move together = one schedule; NULL until scored |
| `sum_tier1/2/3` INT | running totals (default 0) |
| `runs_scored` INT | # runs with a recorded Tier outcome (default 0) |
| `nn_dist_at_insert` INT | nearest-neighbor Hamming distance to the pool at insertion (saturation); NULL = pre-monitor, -1 = first seed |

Derived: `avg_tierN = sum_tierN / runs_scored` — **only defined when `runs_scored > 0`**.

### Schema changes since the original plan (PR3 + PR4)

Confirmed in `DatabaseManager.java` (CREATE + idempotent ALTERs) and `Phase0SeedStatsDAO.java`:

- **`nn_dist_at_insert`** (PR3) — display-only per-seed integer. The page shows it as a column and
  derives a one-line saturation read (is the distance trending down over `ordinal`?). The standalone
  CLI `analyze_seed_saturation.py` does the rigorous trend; the UI just surfaces the raw value + a
  light hint. NULL/-1 → `—`.
- **`best_run_tier1/2/3`** (PR4) — the coherent best run. **This is what "best result" means on the
  page now**; `best_tier1/2/3` are relabeled as "per-tier min (telemetry)" and shown separately (or
  in a tooltip) so nobody mistakes the three independent minima for one schedule.
- **Lexicographic reward LOCKED** — the metric picker keeps all-tier exploration, but the canonical
  "best" is lexicographic. The help text states this; no arbitrary composite weight is introduced.

---

## Page layout (approved)

New tab `🌱 Seed Pool`, placed after `📈 Sweep Analysis` in the `TabPane`.

```
┌─ TabPane: … 📈 Sweep │ 🌱 Seed Pool │ ⚙ Settings ─┐
│ Year: [2 ▾]   Sort: [ordinal ▾]   [↻ Refresh]      │  ← top bar
├────────────────────────────────────────────────────┤
│ ① Usage vs. quality — which seeds are good & under- │
│    used?                                            │
│   Y metric: [avg Tier-1 ▾]   x: times_started       │  ← metric picker
│   ┌──────────────────────────────────────────────┐  │
│   │   · ·    ·                                    │  │
│   │ ·    ·       ·   (seeds with no reward data   │  │
│   │     ·            sit on the x-axis baseline,  │  │
│   └──────────────────  shown as hollow markers)   │  │
│   [help box: good vs underused seeds]              │  │
├────────────────────────────────────────────────────┤
│ ② Seed table (sortable, all columns)               │
│  ord │ id…   │ started │ last used │ best T1/2/3 │…  │
│   1  │ a3f9… │   4     │ 06-24 …   │  — / — / —  │…  │
│   2  │ 9c1b… │   3     │ 06-24 …   │  — / — / —  │…  │
│   [help box: blanks = no scored runs yet]         │  │
├────────────────────────────────────────────────────┤
│ Loaded N seeds for year 2 • M with reward data     │  ← status line
└────────────────────────────────────────────────────┘
```

Everything below the top bar lives in a `VBox` inside a `ScrollPane`
(`setFitToWidth(true)`), exactly as `SweepAnalysisView` does.

---

## New files to create

1. **`src/main/java/com/residency/ui/SeedPoolView.java`** — the page.
   `public class SeedPoolView extends BorderPane`. Mirrors `SweepAnalysisView`'s
   structure (top bar / scroll center / status line; `explainLabel`, `sectionHeading`,
   `installTooltip` helpers copied or factored). Holds:
   - inner record/class `SeedRow` (public getters back the `TableView` columns), with
     fields: `ordinal`, `seedId`, `shortId`, `timesStarted`, `lastUsedAt`,
     `bestTier1/2/3` (Integer, nullable), `avgTier1/2/3` (Double, nullable),
     `runsScored`, and a derived `tier1CiText` / `powerText` (String, blank when no data).
   - `ComboBox<Integer> yearPicker` (default 2), `ComboBox<Metric> metricPicker`,
     `ComboBox<SortKey> sortPicker`, `Button refresh`.
   - `ScatterChart<Number,Number> usageChart`, `TableView<SeedRow> seedTable`,
     `Label statusLine`, three `explainLabel()` help boxes.
   - `enum Metric` over the **six** reward extractors (avg T1/T2/T3, best T1/T2/T3),
     each `Function<SeedRow, Double>` returning `null` when undefined — so the picker
     never forces a single "best."
   - `reload()` → calls the DAO, rebuilds table + chart + status line.

2. **`src/main/java/com/residency/stats/SeedStats.java`** — the **ported statistics
   helper** (see "Stats to port" below). Pure functions, no DB, no UI. It lives in its
   own package (not inside `ui`) **only so the test can call it without starting JavaFX**
   — this is purely about *where the file sits*, not about who uses it. The UI uses this
   exact same class. See the "One source, two consumers" note below.

3. **`src/test/java/com/residency/stats/SeedStatsTest.java`** — JUnit test asserting the
   ported `wilson` / `neededNForMargin` match the Python reference on a couple of fixed
   inputs (e.g. `wilson(3,10)` and an extreme `wilson(0,5)`). This is the "prove the math"
   half; it does **not** require JavaFX because `SeedStats` is a plain class.

> **One source, two consumers (the decision locked with the user).** There is exactly
> **one** copy of the confidence-interval / power math — `SeedStats`. It is *both*:
> - **displayed in the UI** — `SeedPoolView` calls `SeedStats.wilson(...)` /
>   `SeedStats.neededNForMargin(...)` every time the page loads or refreshes, so the
>   table always shows the **current** CI / power values recomputed from whatever is in
>   the DB right then (nothing is cached or stale); and
> - **proven by the standalone test** — `SeedStatsTest` calls the same methods directly.
>
> These are not two versions and not a trade-off: the UI *displays* the intervals, the
> test *proves the math*, and both reference the identical `SeedStats` class. The page
> never needs a "test mode" of its own — testing the math through the UI would be slower
> and more fragile while checking the exact same `SeedStats` call. Do **not** copy the
> formulas into `SeedPoolView`; always reference `SeedStats`.

> No new FXML, no CSS changes required (the page reuses `/styles.css` already loaded by
> `MainApp`). No new migration. No change to `DatabaseManager`'s migration array.

---

## Read-only DAO methods to add to `Phase0SeedStatsDAO`

Append these to the existing `Phase0SeedStatsDAO` (do not touch existing methods).
All follow the `getConn()` + try-with-resources `PreparedStatement` pattern.

```java
/** Distinct years that have any pooled seeds, ascending. Drives the year picker. */
public List<Integer> listYears() throws SQLException {
    List<Integer> out = new ArrayList<>();
    try (PreparedStatement ps = getConn().prepareStatement(
            "SELECT DISTINCT year FROM phase0_seed_stats ORDER BY year ASC");
         ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getInt(1));
    }
    return out;
}

/**
 * All seeds for a year with usage + reward fields, ordered by ordinal. Read-only.
 * avg_tierN is computed in SQL but returned as NULL when runs_scored = 0 (no divide-by-zero):
 *   CASE WHEN runs_scored > 0 THEN 1.0*sum_tierN/runs_scored ELSE NULL END
 * best_tierN is returned as-is (already NULL until a run is scored). Callers must treat
 * the avg/best/CI fields as possibly-NULL.
 */
public List<SeedStatRow> listSeeds(int year) throws SQLException { … }
```

Where `listSeeds` returns a small DTO (define as `Phase0SeedStatsDAO.SeedStatRow`, a
public static record/class) carrying every raw column plus the SQL-computed
`avgTier1/2/3` as `Double` (null when `runs_scored = 0`). The DAO reads each column with
the null-aware pattern: read the value, then `if (rs.wasNull()) field = null;` for the
nullable `best_*`/`avg_*` columns and for `last_used_at` (which is `getString`, naturally
null). The view maps `SeedStatRow` → `SeedRow` (adding short id, CI text, power text).

Recommended SQL for `listSeeds`:

```sql
SELECT seed_id, ordinal, year, created_at, times_started, last_used_at,
       best_tier1, best_tier2, best_tier3,
       best_run_tier1, best_run_tier2, best_run_tier3,
       CASE WHEN runs_scored > 0 THEN 1.0*sum_tier1/runs_scored ELSE NULL END AS avg_tier1,
       CASE WHEN runs_scored > 0 THEN 1.0*sum_tier2/runs_scored ELSE NULL END AS avg_tier2,
       CASE WHEN runs_scored > 0 THEN 1.0*sum_tier3/runs_scored ELSE NULL END AS avg_tier3,
       sum_tier1, sum_tier2, sum_tier3, runs_scored, nn_dist_at_insert
FROM phase0_seed_stats
WHERE year = ?
ORDER BY ordinal ASC
```

All three new columns (`best_run_*`, `nn_dist_at_insert`) are nullable; read with the
`rs.getInt(...); if (rs.wasNull()) field = null;` pattern like the other nullable columns.

Sorting is then done **in-view** on the `TableView` (column-click sort + the `Sort:`
combo just selects the default sort column) so we don't need a SQL variant per sort key.
This keeps the DAO to two read-only methods.

> Optional convenience (only if a quick header count is wanted without iterating):
> `public int countSeeds(int year)` and `public int countScored(int year)`
> (`… WHERE year=? AND runs_scored>0`). The status line can also derive both from the
> already-loaded list, so these are optional.

---

## Seed table — columns & rendering

Sortable `TableView<SeedRow>` (JavaFX columns are click-sortable for free). Columns:

| col | source | rendering |
|---|---|---|
| `ord` | `ordinal` | integer |
| `id` | `seedId` | **first 8 hex chars** in the cell; **full 64-char id in tooltip** (cell factory installs a `Tooltip` with the full id), mirroring how `SweepAnalysisView` truncates uids and tooltips the full value |
| `started` | `timesStarted` | integer |
| `last used` | `lastUsedAt` | short date-time; `—` when null (never used yet) |
| `runs` | `runsScored` | integer |
| `best T1 / T2 / T3` | `bestTier1/2/3` | integer, or `—` when null |
| `avg T1 / T2 / T3` | `avgTier1/2/3` | 1-decimal, or `—` when `runsScored = 0` |
| `T1 95% CI` | derived | Wilson CI text (see below); blank `—` when `runsScored = 0` |
| `power` | derived | `underpowered (need ~N)` or `powered`; blank `—` when `runsScored = 0` |

Empty-state handling:
- `seedTable.setPlaceholder(new Label("No seeds in the pool for this year yet."))`.
- A `formatTier(Integer/Double)` helper returns the literal string `"—"` for null, so
  every quality cell renders a dash rather than `null`, `0`, or a crash. This is the
  single choke point that guarantees "show usage, leave quality blank, never divide by
  zero."

---

## Usage-vs-quality scatter

`ScatterChart<Number,Number>`: **x = `times_started`**, **y = the picked Metric**
(`avg Tier-1` default; picker offers avg T1/T2/T3 and best T1/T2/T3).

- One series of **seeds that have reward data** (`runsScored > 0`): plotted at their
  `(times_started, metric)`, solid markers.
- One series of **un-scored seeds** (`runsScored = 0`, the current majority): the metric
  is undefined, so plot them on the **x-axis baseline (y = 0)** as **hollow / greyed
  markers**, OR omit them from the scatter and note the count in the help box. Either way
  they are not allowed to read as "quality 0." (Recommended: baseline hollow markers, so
  their usage spread is still visible — this is the "underused vs overused" read the page
  is for.) Reuse a stable two-color scheme (scored vs un-scored), adapting
  `SweepAnalysisView`'s `PALETTE`/`colorSeriesByWeight` styling helper.
- Per-point tooltip (`installTooltip`, copied from `SweepAnalysisView`): full 64-char
  `seed_id`, ordinal, `times_started`, the metric value (or "no reward data yet"),
  `runs_scored`.
- Axis labels update with the metric (`y` label = metric name), like
  `redrawOutcome()` does.
- Help box (`explainLabel`): "Each dot is one seed. X = how many runs started from it;
  Y = its schedule quality (lower Tier is better). Dots low-and-left of the cloud are
  **good but underused** — candidates to run more. Hollow markers on the baseline have
  **no scored runs yet**, so their quality is unknown. Don't read a single scored run as
  proof — check the CI column."

When the whole year has zero scored seeds (today's state), the scored series is empty
and the chart shows only baseline markers + the help text explaining quality is pending.

---

## Stats to port from `analyze_collection_cap.py`

Port these two pure functions into `SeedStats.java` (Java), verbatim in behavior:

1. **`wilson(k, n, z=1.96)`** → `double[] wilson(int k, int n)` returning
   `{p, lo, hi}` (the 95% Wilson score interval). Same formula:
   `denom = 1 + z²/n; centre = (p + z²/2n)/denom; half = z·√(p(1-p)/n + z²/4n²)/denom`,
   clamped to `[0,1]`, returning `{0,0,0}` when `n == 0`.
2. **`needed_n_for_margin(p, margin=0.10, z=1.96)`** → `int neededNForMargin(double p)`:
   `ceil(z²·p(1-p)/margin²)`, with `p` clamped to `[1e-3, 1-1e-3]`. Drives the
   "underpowered — need ~N runs" flag.

*(`NNT = 1/p` and `cost = NNT × mean_seconds` from the Python are about Phase-0
**collection** cost, not seed reward — leave them out of this view; they belong to the
collection-cap analysis, not the per-seed quality display. Note that in a comment so a
later reader doesn't think they were forgotten.)*

**Where they apply on this page:** the **reward** record per seed is a small-sample
proportion problem in exactly the Python's spirit — the CI column expresses "we've only
scored `runs_scored` runs from this seed; here's the uncertainty." Two honest ways to
surface it, pick per the data once it exists (spec both, implement the simpler first):

- **Power flag (always safe):** `neededNForMargin(p̂)` vs `runs_scored` → label a seed
  `underpowered (need ~N)` until it has enough scored runs to trust its average. This
  needs only `runs_scored` and any `p̂` proxy, so it works the moment reward data starts
  arriving. **Implement this.**
- **Tier-1 success-rate CI (when a success criterion is defined):** if/when "best" is
  decided (e.g. "Tier-1 ≤ threshold = success"), compute `k = #runs meeting it`,
  `wilson(k, runs_scored)` → a `(lo–hi)` interval in the `T1 95% CI` column. Until the
  reward metric is locked (`SEED_POOL_TRACKING_PLAN.md` leaves it TBD), this column
  renders `—`. Wiring it now (blank) means zero rework when the metric is chosen.

This keeps the clinical-trial discipline from `analyze_collection_cap.py` —
*"don't favor a seed on underpowered data"* — visible in the UI from day one, while the
data is still mostly NULL.

The CI / power values are **always displayed live in the UI**: `SeedPoolView` calls
`SeedStats` on every load/refresh and renders the result for the seeds that have data
(`—` for those that don't). Nothing is precomputed or cached — the displayed interval is
always the current one for whatever is in the DB at that moment. The standalone
`SeedStatsTest` proves those same `SeedStats` methods are mathematically correct; it does
not drive or duplicate the display. One class, displayed by the UI and proven by the test.

---

## Empty / partial reward-data rendering (today's actual state)

Because only Phase-0 collection has run, expect: many seeds, `times_started` ranging
0..several, **`runs_scored = 0` and all `best_*` NULL for nearly all of them**. The page
must look correct and useful in that state:

- **Table:** all quality/CI/power cells show `—`. Usage columns (`ord`, `id`, `started`,
  `last used`, `runs`) are fully populated and sortable — that's the value today.
- **Scatter:** scored series empty; un-scored seeds shown as baseline hollow markers so
  the **usage distribution** (the coverage-first telemetry) is still readable. Help box
  states quality is pending.
- **Status line:** `Loaded N seed(s) for year Y • M with reward data` — and when `M = 0`,
  append `(no full-run outcomes scored yet — quality columns pending)`.
- **No divide-by-zero:** averages come from the SQL `CASE … runs_scored > 0 … ELSE NULL`,
  and every quality formatter dashes on null. There is no code path that divides by
  `runs_scored` in Java.

---

## Nav registration (exact change)

In `src/main/java/com/residency/ui/MainApp.java`, alongside the existing tab
construction (lines 20–31):

```java
Tab seedPoolTab = new Tab("🌱 Seed Pool", new SeedPoolView());
```

and add `seedPoolTab` to the `tabPane.getTabs().addAll(…)` call — placed **after**
`sweepTab`, before `settingsTab`. No listener needed (the page loads on construction and
has its own `↻ Refresh`); optionally add a `selectedItemProperty` listener to call
`seedPoolView.reload()` on tab-select (mirroring the `autoScheduleView.refreshYears()`
pattern) so it picks up new seeds written by a concurrent run — recommended, since a
seeding session may be writing the DB while this tab is open.

---

## Read-only / concurrency safety notes for the implementer

- A concurrent session may be **writing** `phase0_seed_stats` (live seeding). This page
  only issues `SELECT`s through `BaseDAO.getConn()`. The DB is WAL-mode (per the seeding
  work), so reads don't block the writer. Refresh re-queries; never cache stale rows as
  truth — the `↻ Refresh` / tab-select reload is the intended way to see new seeds.
- Do **not** open the `.db` file directly or copy it; go through `DatabaseManager` like
  every other DAO.
- Nothing on this page calls the engine, a solver, `markUsed`, `recordOutcome`, or any
  mutation. If a future enhancement wants "run N from this seed," that is a **separate,
  explicitly-authorized** change — out of scope here.

---

## Ordered implementation checklist

1. **`SeedStats.java`** — port `wilson(k,n)` → `{p,lo,hi}` and `neededNForMargin(p)`
   from `analyze_collection_cap.py`. Pure, no deps. *(+ `SeedStatsTest` asserting they
   match the Python on fixed inputs.)*
2. **`Phase0SeedStatsDAO`** — add read-only `listYears()` and `listSeeds(int year)`
   (with the public `SeedStatRow` DTO and the `CASE … ELSE NULL` avg columns). Do not
   alter existing methods. *(optional `countSeeds`/`countScored`.)*
3. **`SeedPoolView.java`** skeleton — `extends BorderPane`; top bar (year picker default
   **2**, sort combo, refresh), empty scroll center, status line. Wire `reload()` to call
   `listSeeds` and populate a placeholder table. Verify it opens with mostly-`—` data.
4. **Seed table** — all columns + the `formatTier` null→`—` choke point + short-id cell
   with full-id tooltip + `setPlaceholder`. Confirm sorting by clicking headers.
5. **Usage-vs-quality scatter** — x=`times_started`, y=Metric picker (6 extractors),
   scored vs un-scored series, baseline hollow markers for un-scored, `installTooltip`
   with full id. Confirm it renders sanely with zero scored seeds.
6. **CI / power column** — fill `power` via `neededNForMargin` vs `runs_scored`; leave
   `T1 95% CI` blank until the reward-success metric is decided (wired, dashes for now).
7. **Help boxes + status line** — the three `explainLabel()` texts and the
   `Loaded N • M with reward data (… pending)` status line.
8. **Nav** — add the `🌱 Seed Pool` `Tab` to `MainApp` after `sweepTab`; optional
   reload-on-select listener.
9. **Verify read-only** — grep the new view for any write/solve call; confirm only
   `SELECT`s. Build (`mvn -q compile`) and, when a session is NOT mid-seeding, launch and
   eyeball the empty-reward state. Do not launch while the seeding session holds the DB.
```
