package com.residency.ui;

import com.residency.db.Phase0SeedStatsDAO;
import com.residency.db.Phase0SeedStatsDAO.SeedStatRow;
import com.residency.stats.SeedStats;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

/**
 * 🌱 Seed Pool tab — a READ-ONLY visualization of the Phase-0 cached-seed pool
 * ({@code phase0_seed_stats}): per-seed usage (coverage-first telemetry), reward record (still
 * accumulating), and the diversity-saturation signal. The read-only twin of {@link SweepAnalysisView}.
 *
 * <p>It is a decision aid for the seed-pool work (SEED_POOL_TRACKING_PLAN.md): it makes the usage
 * distribution and the reward record visible so the deferred exploit/prune decision can later be made
 * on evidence. It issues only {@code SELECT}s through {@link Phase0SeedStatsDAO}; it never solves,
 * writes, or mutates seed stats.
 *
 * <p>Reward is LOCKED to lexicographic order (Tier-1, then Tier-2, then Tier-3). The metric picker
 * still offers all tiers for exploration, but "best result" means the coherent {@code best_run_*}
 * triple, not the independent per-tier minima ({@code best_*}, which are telemetry only).
 *
 * <p>Graceful empty/partial state: reward columns are mostly NULL until full 4-phase runs score
 * seeds (and only {@code PHASE0_FIX=cache} runs score them). Quality cells render {@code —}; usage
 * is always populated. No code path divides by {@code runs_scored} in Java.
 */
public class SeedPoolView extends BorderPane {

    private static final String DASH = "—";

    /** Backing row for the TableView. Public getters drive PropertyValueFactory columns. */
    public static final class SeedRow {
        private final SeedStatRow s;
        private final String shortId;
        private final String powerText;   // "powered" / "underpowered (need ~N)" / "—"
        private final String tier1CiText; // reserved for a success-criterion CI; "—" until defined

        SeedRow(SeedStatRow s) {
            this.s = s;
            this.shortId = s.seedId == null ? "" : s.seedId.substring(0, Math.min(8, s.seedId.length()));
            // Power flag: until a success criterion is defined we use the seed's mean Tier-1 as a
            // proxy p̂ (normalized to a fraction only for the powering count — purely a sample-size
            // gate "have we scored enough runs from this seed to trust its average?"). With no scored
            // runs, blank.
            if (s.runsScored > 0) {
                double pProxy = 0.5; // worst-case variance proxy (we have no success rate yet)
                int need = SeedStats.neededNForMargin(pProxy);
                this.powerText = s.runsScored >= need ? "powered" : "underpowered (need ~" + need + ")";
            } else {
                this.powerText = DASH;
            }
            this.tier1CiText = DASH; // wired; fills in when a Tier-1 success criterion is chosen
        }

        // --- usage ---
        public int getOrd() { return s.ordinal; }
        public String getId() { return shortId; }
        public String getFullId() { return s.seedId; }
        public int getStarted() { return s.timesStarted; }
        public String getLastUsed() { return s.lastUsedAt == null ? DASH : shortDateTime(s.lastUsedAt); }
        public int getRuns() { return s.runsScored; }
        // --- saturation ---
        public String getNnDist() { return (s.nnDistAtInsert == null || s.nnDistAtInsert < 0)
                ? DASH : String.valueOf(s.nnDistAtInsert); }
        // --- reward: per-tier minima (telemetry) ---
        public String getBestT1() { return fmtInt(s.bestTier1); }
        public String getBestT2() { return fmtInt(s.bestTier2); }
        public String getBestT3() { return fmtInt(s.bestTier3); }
        // --- reward: the coherent lexicographically-best run ---
        public String getBestRun() {
            if (s.bestRunTier1 == null) return DASH;
            return s.bestRunTier1 + " / " + fmtInt(s.bestRunTier2) + " / " + fmtInt(s.bestRunTier3);
        }
        // --- reward: averages ---
        public String getAvgT1() { return fmt1(s.avgTier1); }
        public String getAvgT2() { return fmt1(s.avgTier2); }
        public String getAvgT3() { return fmt1(s.avgTier3); }
        public String getPower() { return powerText; }
        public String getT1Ci() { return tier1CiText; }

        // raw accessors for the scatter (nullable)
        Double avg(int tier) { return tier == 1 ? s.avgTier1 : tier == 2 ? s.avgTier2 : s.avgTier3; }
        Double best(int tier) { Integer b = tier == 1 ? s.bestTier1 : tier == 2 ? s.bestTier2 : s.bestTier3;
            return b == null ? null : b.doubleValue(); }
        int started() { return s.timesStarted; }
        int runsScored() { return s.runsScored; }
        String fullId() { return s.seedId; }
        int ordinal() { return s.ordinal; }
    }

    /** Selectable Y metric for the usage-vs-quality scatter. Returns null when undefined for a seed. */
    private enum Metric {
        AVG_T1("avg Tier-1", r -> r.avg(1)),
        AVG_T2("avg Tier-2", r -> r.avg(2)),
        AVG_T3("avg Tier-3", r -> r.avg(3)),
        BEST_T1("best Tier-1", r -> r.best(1)),
        BEST_T2("best Tier-2", r -> r.best(2)),
        BEST_T3("best Tier-3", r -> r.best(3));
        final String label;
        final Function<SeedRow, Double> extractor;
        Metric(String label, Function<SeedRow, Double> extractor) { this.label = label; this.extractor = extractor; }
        @Override public String toString() { return label; }
    }

    private final ComboBox<Integer> yearPicker = new ComboBox<>();
    private final ComboBox<Metric> metricPicker = new ComboBox<>();
    private final ScatterChart<Number, Number> usageChart;
    private final TableView<SeedRow> seedTable = new TableView<>();
    private final Label statusLine = new Label();
    private final Label usageHelp = explainLabel();
    private final Label tableHelp = explainLabel();
    private final Label saturationHelp = explainLabel();

    private Phase0SeedStatsDAO dao;
    private List<SeedRow> rows = new ArrayList<>();

    public SeedPoolView() {
        NumberAxis x = new NumberAxis(); x.setLabel("times started (usage)"); x.setForceZeroInRange(true);
        NumberAxis y = new NumberAxis(); y.setLabel("avg Tier-1 (lower is better)"); y.setForceZeroInRange(false);
        usageChart = new ScatterChart<>(x, y);
        usageChart.setTitle("Usage vs. quality (each dot is one seed)");
        usageChart.setAnimated(false);
        usageChart.setPrefHeight(360);

        buildTableColumns();
        seedTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        seedTable.setPlaceholder(new Label("No seeds in the pool for this year yet."));
        seedTable.setPrefHeight(320);

        try {
            dao = new Phase0SeedStatsDAO();
        } catch (SQLException e) {
            statusLine.setText("Could not open seed-stats DAO: " + e.getMessage());
        }

        yearPicker.setOnAction(e -> reload());
        metricPicker.setItems(FXCollections.observableArrayList(Metric.values()));
        metricPicker.getSelectionModel().select(Metric.AVG_T1);
        metricPicker.setOnAction(e -> redrawUsage());
        Button refresh = new Button("↻ Refresh");
        refresh.setOnAction(e -> { refreshYears(); reload(); });

        HBox topBar = new HBox(12,
                new Label("Year:"), yearPicker,
                new Label("Y metric:"), metricPicker,
                refresh);
        topBar.setPadding(new Insets(10));
        topBar.setStyle("-fx-alignment: center-left;");

        Label usageHeading = sectionHeading("① Usage vs. quality — which seeds are good and under-used?");
        Label tableHeading = sectionHeading("② Seed table — usage, reward record, and saturation");
        Label satHeading = sectionHeading("③ Diversity saturation — are new seeds landing too close?");

        VBox center = new VBox(6,
                usageHeading, usageChart, usageHelp,
                tableHeading, seedTable, tableHelp,
                satHeading, saturationHelp);
        center.setPadding(new Insets(6));
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);

        statusLine.setPadding(new Insets(6, 10, 6, 10));

        setTop(topBar);
        setCenter(scroll);
        setBottom(statusLine);
        setPadding(new Insets(5));

        refreshYears();
        reload();
    }

    /** Repopulate the year picker from the DB, defaulting to year 2 (the populated one) if present. */
    public void refreshYears() {
        if (dao == null) return;
        try {
            List<Integer> years = dao.listYears();
            Integer prev = yearPicker.getValue();
            yearPicker.setItems(FXCollections.observableArrayList(years));
            if (prev != null && years.contains(prev)) yearPicker.setValue(prev);
            else if (years.contains(2)) yearPicker.setValue(2);
            else if (!years.isEmpty()) yearPicker.setValue(years.get(0));
        } catch (SQLException e) {
            statusLine.setText("Could not list years: " + e.getMessage());
        }
    }

    /** Re-query the selected year and rebuild table + chart + status. Read-only. */
    public void reload() {
        rows = new ArrayList<>();
        Integer year = yearPicker.getValue();
        if (dao != null && year != null) {
            try {
                for (SeedStatRow s : dao.listSeeds(year)) rows.add(new SeedRow(s));
            } catch (SQLException e) {
                statusLine.setText("Could not load seeds: " + e.getMessage());
            }
        }
        seedTable.setItems(FXCollections.observableArrayList(rows));
        redrawUsage();
        updateSaturationHelp();
        updateTableHelp();

        long scored = rows.stream().filter(r -> r.runsScored() > 0).count();
        StringBuilder sb = new StringBuilder(String.format(
                "Loaded %d seed(s) for year %s • %d with reward data",
                rows.size(), year == null ? "—" : year, scored));
        if (scored == 0) sb.append(" (no full-run outcomes scored yet — quality columns pending; "
                + "only PHASE0_FIX=cache runs score seeds)");
        statusLine.setText(sb.toString());
    }

    // ------------------------------------------------------------------ table
    @SuppressWarnings("unchecked")
    private void buildTableColumns() {
        TableColumn<SeedRow, Integer> cOrd = col("ord", "ord");
        TableColumn<SeedRow, String> cId = col("id", "id");
        // full id in a tooltip on the short-id cell
        cId.setCellFactory(tc -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setTooltip(null); return; }
                setText(item);
                SeedRow r = getTableRow() == null ? null : getTableRow().getItem();
                if (r != null && r.getFullId() != null) setTooltip(new Tooltip(r.getFullId()));
            }
        });
        TableColumn<SeedRow, Integer> cStarted = col("started", "started");
        TableColumn<SeedRow, String> cLast = col("last used", "lastUsed");
        TableColumn<SeedRow, Integer> cRuns = col("runs", "runs");
        TableColumn<SeedRow, String> cNn = col("nn-dist", "nnDist");
        TableColumn<SeedRow, String> cBestRun = col("best run (T1/T2/T3)", "bestRun");
        TableColumn<SeedRow, String> cBt1 = col("min T1", "bestT1");
        TableColumn<SeedRow, String> cBt2 = col("min T2", "bestT2");
        TableColumn<SeedRow, String> cBt3 = col("min T3", "bestT3");
        TableColumn<SeedRow, String> cAt1 = col("avg T1", "avgT1");
        TableColumn<SeedRow, String> cAt2 = col("avg T2", "avgT2");
        TableColumn<SeedRow, String> cAt3 = col("avg T3", "avgT3");
        TableColumn<SeedRow, String> cCi = col("T1 95% CI", "t1Ci");
        TableColumn<SeedRow, String> cPow = col("power", "power");

        seedTable.getColumns().addAll(cOrd, cId, cStarted, cLast, cRuns, cNn,
                cBestRun, cBt1, cBt2, cBt3, cAt1, cAt2, cAt3, cCi, cPow);
    }

    private static <T> TableColumn<SeedRow, T> col(String title, String prop) {
        TableColumn<SeedRow, T> c = new TableColumn<>(title);
        c.setCellValueFactory(new PropertyValueFactory<>(prop));
        return c;
    }

    private void updateTableHelp() {
        tableHelp.setText(
            "One row per cached seed. 'started' = how many full runs warm-started from it; 'runs' = how "
          + "many of those got a scored Tier outcome. 'best run (T1/T2/T3)' is the single "
          + "LEXICOGRAPHICALLY-best run from this seed (compare T1, then T2, then T3) — one real "
          + "schedule. The separate 'min T1/T2/T3' are each tier's independent lowest value (telemetry) "
          + "and may come from DIFFERENT runs, so don't read the three together as one schedule. "
          + "'nn-dist' is how far this seed was from its nearest pool neighbor when banked (lower = more "
          + "similar). Quality cells show " + DASH + " until a seed has scored runs. 'power' flags when a "
          + "seed has too few scored runs to trust its average — don't favor a seed on underpowered data.");
    }

    // ------------------------------------------------------------------ usage scatter
    private void redrawUsage() {
        usageChart.getData().clear();
        Metric m = metricPicker.getValue();
        if (m == null) return;
        ((NumberAxis) usageChart.getYAxis()).setLabel(m.label + " (lower is better)");

        XYChart.Series<Number, Number> scored = new XYChart.Series<>();
        scored.setName("scored seeds");
        XYChart.Series<Number, Number> unscored = new XYChart.Series<>();
        unscored.setName("no reward data yet");

        int nScored = 0, nUnscored = 0;
        for (SeedRow r : rows) {
            Double yv = m.extractor.apply(r);
            if (yv != null && r.runsScored() > 0) {
                XYChart.Data<Number, Number> d = new XYChart.Data<>(r.started(), yv);
                scored.getData().add(d);
                installTooltip(d, String.format("%s%nordinal %d • started %d • runs %d%n%s = %s",
                        r.fullId(), r.ordinal(), r.started(), r.runsScored(), m.label, fmtNum(yv)));
                nScored++;
            } else {
                // metric undefined → baseline marker so usage spread is still visible (NOT "quality 0")
                XYChart.Data<Number, Number> d = new XYChart.Data<>(r.started(), 0);
                unscored.getData().add(d);
                installTooltip(d, String.format("%s%nordinal %d • started %d%nno reward data yet",
                        r.fullId(), r.ordinal(), r.started()));
                nUnscored++;
            }
        }
        usageChart.getData().add(unscored);
        usageChart.getData().add(scored);
        styleSeries(scored, "#1f77b4", true);
        styleSeries(unscored, "#bbbbbb", false);

        usageHelp.setText(String.format(
            "Each dot is one seed. X = how many runs started from it; Y = its schedule quality "
          + "(lower Tier is better). Dots low-and-left are GOOD but UNDER-USED — candidates to run more. "
          + "Hollow grey markers on the baseline have NO scored runs yet, so their quality is unknown "
          + "(they're shown there only to reveal the usage spread, not as 'quality 0'). Don't trust a "
          + "single scored run — check the 'power' / CI columns. Showing %d scored, %d unscored seed(s).",
          nScored, nUnscored));
    }

    private void updateSaturationHelp() {
        // Light surfacing of the saturation signal; the rigorous trend is analyze_seed_saturation.py.
        List<int[]> pts = new ArrayList<>(); // {ordinal, nnDist}
        for (SeedRow r : rows) {
            Integer d = r.s.nnDistAtInsert;
            if (d != null && d >= 0) pts.add(new int[]{r.ordinal(), d});
        }
        if (pts.size() < 3) {
            saturationHelp.setText(
                "Each seed records its nearest-neighbor Hamming distance to the pool when it was banked "
              + "(the 'nn-dist' column). As the pool fills, that distance SHRINKING means new feasible "
              + "seeds are landing close to ones we already have = diversity saturation (the real "
              + "scaling limit, not solve time). Not enough monitored seeds yet to read a trend — this "
              + "fills in as more seeds are banked after the monitor went live. (Run "
              + "analyze_seed_saturation.py for the rigorous trend.)");
            return;
        }
        pts.sort(Comparator.comparingInt(a -> a[0]));
        int third = Math.max(1, pts.size() / 3);
        double earlyMed = median(pts.subList(0, third));
        double lateMed = median(pts.subList(pts.size() - third, pts.size()));
        String trend = lateMed < earlyMed * 0.85 ? "FALLING (possible saturation onset)"
                     : lateMed > earlyMed * 1.15 ? "RISING (space still vast — no saturation)"
                     : "roughly FLAT (no clear saturation yet)";
        saturationHelp.setText(String.format(
            "Nearest-neighbor distance at insertion, early vs. late: early-third median %.0f → "
          + "late-third median %.0f → trend is %s. A falling trend means new seeds are crowding into "
          + "already-covered basins; the lever then is seed-EXCLUSION constraints at collection time, "
          + "NOT a longer cap — and not before the quality (ICC) question says seed identity even "
          + "matters. (Run analyze_seed_saturation.py for the rigorous OLS-slope trend.)",
          earlyMed, lateMed, trend));
    }

    private static double median(List<int[]> pts) {
        List<Integer> vs = new ArrayList<>();
        for (int[] p : pts) vs.add(p[1]);
        Collections.sort(vs);
        int n = vs.size();
        return n == 0 ? 0 : (n % 2 == 1 ? vs.get(n / 2) : (vs.get(n / 2 - 1) + vs.get(n / 2)) / 2.0);
    }

    // ------------------------------------------------------------------ helpers (mirrors SweepAnalysisView)
    private static Label explainLabel() {
        Label l = new Label();
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-background-color: #eef3fb; -fx-border-color: #c5d6f0; "
                 + "-fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 8; "
                 + "-fx-font-size: 11.5px; -fx-text-fill: #243b5e;");
        return l;
    }
    private static Label sectionHeading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 10 0 0 0;");
        return l;
    }
    private static void installTooltip(XYChart.Data<Number, Number> d, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(javafx.util.Duration.millis(150));
        if (d.getNode() != null) Tooltip.install(d.getNode(), tip);
        else d.nodeProperty().addListener((obs, old, node) -> { if (node != null) Tooltip.install(node, tip); });
    }
    private static void styleSeries(XYChart.Series<Number, Number> s, String color, boolean filled) {
        Runnable style = () -> {
            for (XYChart.Data<Number, Number> d : s.getData()) {
                if (d.getNode() != null) {
                    String bg = filled ? color + ", white" : "white, " + color;
                    d.getNode().setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 6px;");
                }
            }
        };
        style.run();
        javafx.application.Platform.runLater(style);
    }

    // ------------------------------------------------------------------ formatting (the null→— choke point)
    private static String fmtInt(Integer v) { return v == null ? DASH : String.valueOf(v); }
    private static String fmt1(Double v) { return v == null ? DASH : String.format("%.1f", v); }
    private static String fmtNum(double v) {
        return v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.1f", v);
    }
    private static String shortDateTime(String iso) {
        // ISO instant like 2026-06-24T15:04:05.123Z → "06-24 15:04"
        try {
            String date = iso.substring(5, 10);   // MM-DD
            String time = iso.substring(11, 16);  // HH:mm
            return date + " " + time;
        } catch (RuntimeException e) {
            return iso;
        }
    }
}
