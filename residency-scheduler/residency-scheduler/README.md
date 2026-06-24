# UltimateBlockScheduler

A JavaFX desktop application for medical residency programs. Given a set of residents, rotations, and scheduling rules, it automatically generates a one-year block schedule using constraint programming — then lets users view, edit, and export the result.

**Repository:** [github.com/jarrettsapp/UltimateBlockScheduler](https://github.com/jarrettsapp/UltimateBlockScheduler)

## Quick Start

```bash
git clone https://github.com/jarrettsapp/UltimateBlockScheduler.git
cd UltimateBlockScheduler/residency-scheduler/residency-scheduler
mvn javafx:run
```

The SQLite database is created automatically on first launch.

---

## Key Documentation

Documentation is consolidated into three master docs (each with its own table of contents):

| Document | Contents |
|----------|----------|
| [`RULES.md`](RULES.md) | **Domain master** — the authoritative rule set with enforcement tags (solver-hard / soft / operational), the zero-volunteer coverage-floor proof, the block↔call cross-project review, the encoded-vs-real rule review, and a hand-audit checklist |
| [`OPERATIONS.md`](OPERATIONS.md) | **Operator master** — how to run the solver: the one-command autonomous sweep, running a single config by hand, standard budgets, conventions, and troubleshooting |
| [`PROJECT.md`](PROJECT.md) | **Engineering master** — current status, key findings, open trackers, known bugs, planned work, the resolved code-review findings (H1–H3, M1–M4), and design assessments |
| [`SCHEDULE_ITERATION_REPORT.md`](SCHEDULE_ITERATION_REPORT.md) | Side-by-side comparison of the real-world schedule and app versions (auto-maintained by the sweep) |

---

## Block Structure

| Term | Definition |
|------|-----------|
| **Slot** | 2 calendar weeks — the atomic scheduling unit the solver indexes over |
| **Academic year** | 26 slots (labeled 1a, 1b, 2a, 2b, … 13a, 13b = 52 weeks) |
| **Full block** | a 4-week clinical block = 2 slots (e.g. 2a + 2b) |
| **Half block** | a 2-week clinical block = 1 slot |

Slot indices used internally are 0-based (0–25). Display labels are 1a–13b.

> **A note on the word "block."** Clinically, a *block* is 4 weeks and a
> *half-block* is 2 weeks. Internally the solver works in 2-week **slots** (what
> the labels 1a/1b/2a… refer to), so much of the code calls a slot a "block."
> All week↔slot conversions are centralised in `model/ScheduleUnits.java`; rotation
> duration fields ("Min/Max Weeks" on the Rotations tab) are entered in **weeks**.
> See `REVIEW.md` (findings H1/H2) for the history behind this.

---

## Requirements

- Java 17+
- Maven 3.8+
- **Windows x64** — the OR-Tools CP-SAT native library (`jniortools.dll`) is bundled for Windows only

Key dependencies (managed by Maven):

| Library | Version | Purpose |
|---------|---------|---------|
| JavaFX | 21.0.2 | Desktop UI |
| Google OR-Tools CP-SAT | 9.9.3963 | Primary constraint solver |
| Timefold Solver | 1.14.0 | Legacy solver (limited constraints) |
| SQLite JDBC | 3.45.1.0 | Embedded database |
| Apache POI | 5.2.5 | Excel export |
| iText | 5.5.13.3 | PDF export |
| Jackson | 2.17.1 | Config serialization |

---

## Build & Run

From the `residency-scheduler/residency-scheduler/` directory:

```bash
# Run directly with Maven
mvn javafx:run

# Or build a fat JAR
mvn package
java -jar target/residency-scheduler-1.0-SNAPSHOT.jar
```

The SQLite database (`residency_scheduler.db`) is created automatically on first launch.

---

## Contributing

1. **Fork** the repository on GitHub
2. **Create a feature branch** (`git checkout -b feature/your-feature`)
3. **Commit changes** with clear messages
4. **Push** to your fork and **open a Pull Request**

For major changes, please open an issue first to discuss your proposal.

---

## Application Tabs

### Schedule
- Visual block grid: residents as rows, blocks (2-week periods) as columns
- Assign rotations via dropdown per cell
- Rule violations shown as warning dialogs — user can override or cancel
- Override assignments highlighted with ⚠ indicator
- Generate a new academic year (26 blocks starting July 1)
- Export to PDF (landscape A3) or Excel (.xlsx)

### Residents
- Add, edit, delete residents
- Fields: Name, PGY Level (1–7), Email, Auxiliary flag, Group
- **Auxiliary residents** are pre-scheduled manually and fill coverage gaps post-solve via the Filler Rotations sub-tab

### Rotations
- Add, edit, delete rotations
- Fields: Name, Department, Type (INPATIENT / OUTPATIENT / UNSPECIFIED), Description, capacity limits
- **Rotation Config panel** exposes all CP-SAT scheduling policy options (see Constraint Reference)

### Rules
Three rule types:
1. **PGY-Level Requirements** — min/max blocks and required flag per (PGY level, rotation) pair
2. **Prerequisites** — "Rotation A must be completed before Rotation B", optionally scoped to a PGY level
3. **Sequence / Adjacency Rules** — `MUST_BE_AFTER` (temporal ordering) and `CANNOT_IMMEDIATELY_FOLLOW` (direct adjacency prevention), optionally scoped to a PGY level

### Compliance
- Run a full compliance check for any academic year
- Per-resident status cards: green (met), red (shortfall), orange (over max)
- Per-rotation breakdown with blocks completed vs. required
- Summary of all violations

### Auto-Scheduler
Runs the constraint-based solver to generate a schedule automatically.

**Solver engines (select via radio buttons):**

| Engine | Status | Notes |
|--------|--------|-------|
| CP-SAT (OR-Tools) | **Recommended** | Full 4-phase optimization, all constraints enforced |
| Timefold | Legacy | Fewer constraints; PGY-scoped prerequisites ignored |

**CP-SAT phase controls:** each phase has an independent time limit (seconds):
- Phase 0 — Feasibility (find any valid assignment)
- Phase 1 — Clinical quality (minimize weighted post-call and inpatient-transition penalties)
- Phase 2 — Schedule quality (minimize weighted coverage gaps, workload imbalance, PGY imbalance)
- Phase 3 — Pattern optimization (encourage 2-inpatient / 1-outpatient repeating cycle)

**Phase-limit presets:** Quick / Standard / Long / Overnight buttons set all four
phase time limits at once (the per-phase cap is 3600 s). Use Long or Overnight when
optimizing transitions under a hard coverage floor.

**Require 0 volunteer weekends (hard):** a checkbox that turns on the
`enforce_zero_volunteer_weekends` constraint — every back-end weekend must have at least
one eligible categorical coverer, eliminating volunteer Sundays. Default off; fully
reversible (uncheck and re-solve). The coverage floor was proven achievable — see
`COVERAGE_FLOOR_FINDINGS.md`.

**Versions…:** save the current schedule as a named "final production" snapshot in the
database and compare any two saved versions side-by-side on the same metrics (volunteer /
fragile / healthy weekends, heavy→heavy transitions, long runs, capacity compliance).
Snapshots survive block-row regeneration.

Other features: live progress log, undo/rollback via snapshots, delete-year capability.

### Constraints Viewer
- Lists all active constraints grouped by category
- Runs pre-solve feasibility analysis
- Highlights constraint conflicts with diagnostic detail

### Settings
Configure CP-SAT objective weights, post-call incompatibility rule sets, global workload bounds, solver parameters, and linked rotation sum rules. See Settings Reference below.

---

## Constraint Reference

### Hard Constraints (must be satisfied for any solution)

Block expansion and no-overlap are applied first (via `BlockExpansionService`), then:

| # | Constraint | Controlling field |
|---|-----------|-------------------|
| 1 | Coverage min/max per rotation per block | `minPerBlock`, `maxPerBlock` in Rotation Config |
| 2 | PGY-level block caps per rotation per block | `pgyMinMax` in Rotation Config |
| 3 | Global workload cap per resident per year | `globalMinWorkloadBlocks`, `globalMaxWorkloadBlocks` in Settings |
| 4 | Max total blocks per resident per rotation | `maxBlocksAllowed` on Rotation; PGY requirement `maxBlocks` |
| 5 | Prerequisite ordering | Rules tab → Prerequisites |
| 6 | Sequence rules (MUST_BE_AFTER, CANNOT_IMMEDIATELY_FOLLOW) | Rules tab → Sequence/Adjacency Rules |
| 7 | No back-to-back single-block segments | `noBackToBackHalfBlocks` in Rotation Config |
| 8 | Mutual non-adjacency between rotation pairs | `mutuallyNonAdjacentWith` list in Rotation Config |
| 9 | Max consecutive blocks on a rotation | `maxConsecutiveBlocks` in Rotation Config (0 = no limit) |
| 10 | Earliest allowed start block | `earliestStartBlock` in Rotation Config (0 = no restriction) |
| 11 | Require even-block starts (1A, 2A, 3A only) | `requireEvenBlockStart` in Rotation Config |
| 12 | Require break between segments | `requireBreakBetweenSegments` in Rotation Config |
| 13 | Requires consecutive (no gaps between assignments) | `requiresConsecutive` in Rotation Config |
| 14 | Linked rotation sum (rotA + rotB = N per resident) | Settings → Linked Rotation Sum Rules |
| 15 | Full-year coverage (must staff every block) | `optionalFullYearCoverage` in Rotation Config |
| 16 | Categorical-only cap per rotation per block (e.g. ICU ≤ 1, VA ≤ 2 categoricals) | `categoricalMaxPerBlock` in Rotation Config |
| 17 | Zero volunteer weekends (≥1 eligible coverer every weekend) — *optional, reversible* | `enforceZeroVolunteerWeekends` (Auto-Scheduler checkbox) |

### Soft Objectives (minimized in priority order)

**Tier 1 — Clinical quality** (Phase 1):

| Violation | Weight setting | Default |
|-----------|---------------|---------|
| Trigger rotation → mandatory-attendance next block | `weightPostCallHard` | 10 000 |
| Trigger rotation → discouraged rotation next block | `weightPostCallSoft` | 300 |
| Inpatient → different inpatient at block boundary | `weightInpatientSplit` | 15 |

Configure trigger/mandatory/discouraged rotation sets in Settings → Post-Call Incompatibilities.

**Tier 2 — Schedule quality** (Phase 2):

| Component | Weight setting | Default |
|-----------|---------------|---------|
| Undercoverage (actual < min per block) | `weightUndercoverage` | 100 |
| Overcoverage (actual > max per block) | `weightOvercoverage` | 20 |
| Workload variance across residents | `weightVariance` | 10 |
| PGY-level imbalance per block | `weightPgyImbalance` | 15 |
| Sunday call coverage below target (eligible coverers per weekend) | `weightSundayCoverage` / `sundayCoverageTarget` | 150 / 2 |

**Tier 3 — Pattern** (Phase 3):

| Component | Weight setting | Default |
|-----------|---------------|---------|
| 2-inpatient / 1-outpatient cycle deviation | `weightFourPlusTwo` | 30 |

---

## Settings Reference

| Setting | Default | Effect |
|---------|---------|--------|
| `weightUndercoverage` | 100 | Penalty per block-slot below rotation minimum (Tier 2) |
| `weightOvercoverage` | 20 | Penalty per block-slot above rotation maximum (Tier 2) |
| `weightVariance` | 10 | Penalty per unit of pairwise workload difference (Tier 2) |
| `weightPgyImbalance` | 15 | Penalty per unit of pairwise PGY-count difference per block (Tier 2) |
| `weightFourPlusTwo` | 30 | Penalty per block that violates 2-inpatient/1-outpatient pattern (Tier 3) |
| `weightPostCallHard` | 10 000 | Weighted penalty per trigger→mandatory post-call violation (Tier 1) |
| `weightPostCallSoft` | 300 | Weighted penalty per trigger→discouraged post-call violation (Tier 1) |
| `weightInpatientSplit` | 15 | Weighted penalty per inpatient→different-inpatient transition (Tier 1) |
| `globalMinWorkloadBlocks` | 0 | Minimum blocks per resident per year |
| `globalMaxWorkloadBlocks` | 24 | Maximum blocks per resident per year |
| `cpSatNumWorkers` | 4 | Solver thread count |

**Post-Call Incompatibilities** — three lists of rotation IDs:
- **Trigger rotations** — rotations that generate a post-call Monday (e.g. Night Float)
- **Mandatory-attendance rotations** — rotations that cannot immediately follow a trigger
- **Discouraged rotations** — rotations penalized (but allowed) after a trigger

**Linked Rotation Sum Rules** — enforce per-resident block totals:
`blocks(rotA) + blocks(rotB) = sumPerResident` (hard equality per resident)
Optional: total blocks of rotB across all residents = `globalTotalForRotB`

---

## Database Schema

| Table | Contents |
|-------|---------|
| `residents` | Name, PGY level, email, auxiliary flag, group |
| `rotations` | Name, department, type, capacity limits |
| `blocks` | 2-week periods tied to an academic year |
| `assignments` | resident × block → rotation mappings, override flag |
| `rotation_requirements` | Per-rotation, per-PGY-level min/max/required rules |
| `prerequisites` | Ordering rules between rotations, optional PGY scope |
| `rotation_sequence_rules` | MUST_BE_AFTER and CANNOT_IMMEDIATELY_FOLLOW rules |
| `rotation_config` | Per-rotation CP-SAT policy (block lengths, staffing, flags) |
| `rotation_pgy_caps` | Per-PGY-level weekly staffing caps per rotation |
| `rotation_link_rules` | Linked rotation sum constraints |
| `schedule_config` | Objective weights and solver parameters (key-value store) |
| `aux_filler_rotations` | Auxiliary resident filler group mappings |
| `solver_runs` | Solver run history for comparison panel |
| `schedule_versions` | Named "final production" schedule snapshots (metadata + solve scores) |
| `schedule_version_assignments` | Per-version assignments, stored by block number so they survive block-row regeneration |

---

## Project Structure

```
src/main/java/com/residency/
├── model/      # Data classes and enums (Resident, Rotation, Block, Assignment, …)
├── db/         # SQLite DAOs (one per table)
├── service/    # Manual validation (SchedulingService), schedule metrics
│               #   (ScheduleMetrics, ScheduleMetricsBuilder) + Timefold runner
├── export/     # PDF (iText) and Excel (POI) export
├── ui/         # JavaFX views (8 tabs + supporting panels)
├── cpsat/      # CP-SAT solver: engine, constraints, objectives, variables,
│               #   feasibility analysis, post-solve validation, scoring (12 classes)
├── solver/     # Timefold solver: ConstraintProvider, problem facts, score model
└── tools/      # Headless runners (HeadlessSolveRunner, CoverageFloorRunner,
                #   MetricsReportRunner) — command-line solve / proof / reporting
src/main/resources/
└── styles.css  # Application stylesheet
```
