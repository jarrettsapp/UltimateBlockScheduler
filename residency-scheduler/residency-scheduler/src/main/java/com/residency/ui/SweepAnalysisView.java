package com.residency.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sweep Analysis tab. Reads the files the autonomous sweep driver already writes
 * ({@code sweep_results.csv} for per-run outcomes, {@code sweep_runs/traj_*.csv} for
 * the objective-vs-time trajectories) and visualizes them. Read-only: it touches no
 * DB and no solver state, so it is always safe to open, even mid-sweep.
 *
 * <p>This is a <em>decision aid</em>, not a decision-maker. Its whole job is to make the
 * noise in the data visible — e.g. that a given weight produced fragile {4,4,7,7} is a
 * spread, not a lucky 4 — so config design (the {@code design-solver-batch} skill) isn't
 * fooled by a single draw. It is a 1–2 dimensional view; once we tune 3+ interacting
 * knobs at once it will run out of room, which is the same point a surrogate optimizer
 * starts to earn its keep.
 */
public class SweepAnalysisView extends BorderPane {

    // Paths are relative to the working directory (project root), matching DatabaseManager's
    // "jdbc:sqlite:residency_scheduler.db". That is where the sweep driver writes these files.
    private static final Path RESULTS_CSV = Paths.get("sweep_results.csv");
    private static final Path RUNS_DIR = Paths.get("sweep_runs");

    /** One row of sweep_results.csv, parsed. Public getters back the TableView columns. */
    public static final class Run {
        private final String uid, floor, status;
        private final int target, weight;
        private final Double volunteer, fragile, healthy, heavyToHeavy, runsOver6wk, plateauS;
        private final Integer version;

        Run(String uid, String floor, int target, int weight, Double volunteer, Double fragile,
            Double healthy, Double heavyToHeavy, Double runsOver6wk, Integer version,
            Double plateauS, String status) {
            this.uid = uid; this.floor = floor; this.target = target; this.weight = weight;
            this.volunteer = volunteer; this.fragile = fragile; this.healthy = healthy;
            this.heavyToHeavy = heavyToHeavy; this.runsOver6wk = runsOver6wk;
            this.version = version; this.plateauS = plateauS; this.status = status;
        }
        public String getUid() { return uid; }
        public String getFloor() { return floor; }
        public int getTarget() { return target; }
        public int getWeight() { return weight; }
        public Double getVolunteer() { return volunteer; }
        public Double getFragile() { return fragile; }
        public Double getHealthy() { return healthy; }
        public Double getHeavyToHeavy() { return heavyToHeavy; }
        public Double getRunsOver6wk() { return runsOver6wk; }
        public Integer getVersion() { return version; }
        public Double getPlateauS() { return plateauS; }
        public String getStatus() { return status; }
        boolean isDone() { return "DONE".equalsIgnoreCase(status); }
    }

    /** One per-weight aggregate row for the summary table. */
    public static final class Agg {
        private final int weight, n;
        private final String meanFragile, bestFragile, spreadFragile, meanHealthy, meanPlateau;
        Agg(int weight, int n, String meanFragile, String bestFragile, String spreadFragile,
            String meanHealthy, String meanPlateau) {
            this.weight = weight; this.n = n; this.meanFragile = meanFragile;
            this.bestFragile = bestFragile; this.spreadFragile = spreadFragile;
            this.meanHealthy = meanHealthy; this.meanPlateau = meanPlateau;
        }
        public int getWeight() { return weight; }
        public int getN() { return n; }
        public String getMeanFragile() { return meanFragile; }
        public String getBestFragile() { return bestFragile; }
        public String getSpreadFragile() { return spreadFragile; }
        public String getMeanHealthy() { return meanHealthy; }
        public String getMeanPlateau() { return meanPlateau; }
    }

    /** Selectable Y metrics for the outcome-vs-weight chart. */
    private enum Metric {
        FRAGILE("Fragile", Run::getFragile),
        HEALTHY("Healthy", Run::getHealthy),
        VOLUNTEER("Volunteer", Run::getVolunteer),
        HEAVY_TO_HEAVY("Heavy→Heavy", Run::getHeavyToHeavy),
        PLATEAU("Plateau (s)", Run::getPlateauS);
        final String label;
        final java.util.function.Function<Run, Double> extractor;
        Metric(String label, java.util.function.Function<Run, Double> extractor) {
            this.label = label; this.extractor = extractor;
        }
        @Override public String toString() { return label; }
    }

    /** Trajectory panel modes. */
    private enum TrajView {
        OVERLAY("Overlay — all runs, colored by weight"),
        HEATMAP("Heatmap — when runs reach their best"),
        SINGLE("Single run");
        final String label;
        TrajView(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final ComboBox<Metric> metricPicker = new ComboBox<>();
    private final CheckBox doneOnly = new CheckBox("DONE runs only");
    private final ComboBox<TrajView> trajViewPicker = new ComboBox<>();
    private final ComboBox<String> trajPicker = new ComboBox<>();
    private final Label trajPickerLabel = new Label("Run:");
    private final ScatterChart<Number, Number> outcomeChart;
    private final LineChart<Number, Number> trajChart;
    private final TableView<Agg> summaryTable = new TableView<>();
    private final Label statusLine = new Label();

    // Plain-language "what am I looking at" boxes, refreshed with the current selection.
    private final Label outcomeHelp = explainLabel();
    private final Label tableHelp = explainLabel();
    private final Label trajHelp = explainLabel();

    private List<Run> runs = new ArrayList<>();

    public SweepAnalysisView() {
        // --- outcome-vs-weight scatter ---
        NumberAxis ox = new NumberAxis(); ox.setLabel("Sunday-coverage weight");
        ox.setForceZeroInRange(false);
        NumberAxis oy = new NumberAxis(); oy.setLabel("metric");
        outcomeChart = new ScatterChart<>(ox, oy);
        outcomeChart.setTitle("Outcome vs. weight (each dot is one run)");
        outcomeChart.setAnimated(false);
        outcomeChart.setPrefHeight(360);

        // --- trajectory line chart ---
        NumberAxis tx = new NumberAxis(); tx.setLabel("elapsed seconds"); tx.setForceZeroInRange(false);
        NumberAxis ty = new NumberAxis(); ty.setLabel("Phase-3 objective"); ty.setForceZeroInRange(false);
        trajChart = new LineChart<>(tx, ty);
        trajChart.setTitle("Phase-3 objective trajectory (lower = better)");
        trajChart.setAnimated(false);
        trajChart.setCreateSymbols(true);
        trajChart.setPrefHeight(320);

        buildSummaryTableColumns();

        // --- controls ---
        metricPicker.setItems(FXCollections.observableArrayList(Metric.values()));
        metricPicker.getSelectionModel().select(Metric.FRAGILE);
        metricPicker.setOnAction(e -> redrawOutcome());
        doneOnly.setSelected(true);
        doneOnly.setOnAction(e -> { redrawOutcome(); rebuildSummary(); redrawTrajectory(); });
        trajViewPicker.setItems(FXCollections.observableArrayList(TrajView.values()));
        trajViewPicker.getSelectionModel().select(TrajView.OVERLAY);
        trajViewPicker.setOnAction(e -> { updateTrajControls(); redrawTrajectory(); });
        trajPicker.setOnAction(e -> redrawTrajectory());
        Button refresh = new Button("↻ Refresh");
        refresh.setOnAction(e -> reload());

        HBox topBar = new HBox(12,
                new Label("Y metric:"), metricPicker,
                doneOnly,
                refresh);
        topBar.setPadding(new Insets(10));
        topBar.setStyle("-fx-alignment: center-left;");

        HBox trajBar = new HBox(10, new Label("Trajectory view:"), trajViewPicker,
                trajPickerLabel, trajPicker);
        trajBar.setPadding(new Insets(4, 0, 0, 0));
        trajBar.setStyle("-fx-alignment: center-left;");

        // --- center layout ---
        Label outcomeHeading = sectionHeading("① Outcome vs. weight — which weight gives the best schedules?");
        Label tableHeading = sectionHeading("② Per-weight summary — have we sampled each weight enough?");
        Label trajHeading = sectionHeading("③ Trajectory — how solves descend over time");

        VBox center = new VBox(6,
                outcomeHeading, outcomeChart, outcomeHelp,
                tableHeading, summaryTable, tableHelp,
                trajHeading, trajBar, trajChart, trajHelp);
        center.setPadding(new Insets(6));
        summaryTable.setPrefHeight(180);
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);
        updateTrajControls();

        statusLine.setPadding(new Insets(6, 10, 6, 10));

        setTop(topBar);
        setCenter(scroll);
        setBottom(statusLine);
        setPadding(new Insets(5));

        reload();
    }

    // ------------------------------------------------------------------ small UI helpers
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

    /** Show the per-run picker only in SINGLE mode (overlay/heatmap use all runs). */
    private void updateTrajControls() {
        boolean single = trajViewPicker.getValue() == TrajView.SINGLE;
        trajPickerLabel.setVisible(single); trajPickerLabel.setManaged(single);
        trajPicker.setVisible(single);      trajPicker.setManaged(single);
    }

    @SuppressWarnings("unchecked")
    private void buildSummaryTableColumns() {
        TableColumn<Agg, Integer> cW = new TableColumn<>("weight");
        cW.setCellValueFactory(new PropertyValueFactory<>("weight"));
        TableColumn<Agg, Integer> cN = new TableColumn<>("n runs");
        cN.setCellValueFactory(new PropertyValueFactory<>("n"));
        TableColumn<Agg, String> cMF = new TableColumn<>("mean fragile");
        cMF.setCellValueFactory(new PropertyValueFactory<>("meanFragile"));
        TableColumn<Agg, String> cBF = new TableColumn<>("best fragile");
        cBF.setCellValueFactory(new PropertyValueFactory<>("bestFragile"));
        TableColumn<Agg, String> cSF = new TableColumn<>("fragile spread");
        cSF.setCellValueFactory(new PropertyValueFactory<>("spreadFragile"));
        TableColumn<Agg, String> cMH = new TableColumn<>("mean healthy");
        cMH.setCellValueFactory(new PropertyValueFactory<>("meanHealthy"));
        TableColumn<Agg, String> cMP = new TableColumn<>("mean plateau (s)");
        cMP.setCellValueFactory(new PropertyValueFactory<>("meanPlateau"));
        summaryTable.getColumns().addAll(cW, cN, cMF, cBF, cSF, cMH, cMP);
        summaryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        summaryTable.setPlaceholder(new Label("No sweep_results.csv rows yet."));
    }

    // ------------------------------------------------------------------ loading
    private void reload() {
        runs = loadResults();
        // populate trajectory picker from files present on disk
        List<String> trajLabels = loadTrajLabels();
        String prev = trajPicker.getValue();
        trajPicker.setItems(FXCollections.observableArrayList(trajLabels));
        if (prev != null && trajLabels.contains(prev)) trajPicker.setValue(prev);
        else if (!trajLabels.isEmpty()) trajPicker.getSelectionModel().selectFirst();

        redrawOutcome();
        rebuildSummary();
        redrawTrajectory();

        long done = runs.stream().filter(Run::isDone).count();
        statusLine.setText(String.format(
                "Loaded %d run(s) from %s  (%d DONE, %d other)  •  %d trajectory file(s) in %s",
                runs.size(), RESULTS_CSV, done, runs.size() - done, trajLabels.size(), RUNS_DIR));
    }

    private List<Run> loadResults() {
        List<Run> out = new ArrayList<>();
        if (!Files.exists(RESULTS_CSV)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(RESULTS_CSV);
        } catch (IOException e) {
            statusLine.setText("Could not read " + RESULTS_CSV + ": " + e.getMessage());
            return out;
        }
        if (lines.isEmpty()) return out;
        // header: timestamp,uid,floor,target,weight,volunteer,fragile,healthy,heavy_to_heavy,
        //         runs>6wk,version,plateau_s,status
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] f = line.split(",", -1);
            if (f.length < 13) continue;
            try {
                out.add(new Run(
                        f[1], f[2], parseInt(f[3], 0), parseInt(f[4], 0),
                        parseDbl(f[5]), parseDbl(f[6]), parseDbl(f[7]), parseDbl(f[8]),
                        parseDbl(f[9]), parseIntOrNull(f[10]), parseDbl(f[11]), f[12]));
            } catch (RuntimeException ex) {
                // skip a malformed row rather than fail the whole view
            }
        }
        return out;
    }

    private List<String> loadTrajLabels() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(RUNS_DIR)) return out;
        try (var s = Files.list(RUNS_DIR)) {
            s.map(p -> p.getFileName().toString())
             .filter(n -> n.startsWith("traj_") && n.endsWith(".csv"))
             .sorted()
             .forEach(out::add);
        } catch (IOException ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ outcome chart
    private void redrawOutcome() {
        outcomeChart.getData().clear();
        Metric m = metricPicker.getValue();
        if (m == null) return;
        ((NumberAxis) outcomeChart.getYAxis()).setLabel(m.label);

        List<Run> rows = filtered();

        // Series 1: individual runs (the raw dots — this is what shows the noise).
        XYChart.Series<Number, Number> pts = new XYChart.Series<>();
        pts.setName("runs");
        // Series 2: per-weight mean (connect-the-means is what eyeballing tries to do, made honest).
        XYChart.Series<Number, Number> means = new XYChart.Series<>();
        means.setName("mean per weight");

        Map<Integer, List<Double>> byWeight = new TreeMap<>();
        for (Run r : rows) {
            Double y = m.extractor.apply(r);
            if (y == null) continue;
            XYChart.Data<Number, Number> d = new XYChart.Data<>(r.weight, y);
            pts.getData().add(d);
            byWeight.computeIfAbsent(r.weight, k -> new ArrayList<>()).add(y);
            installTooltip(d, String.format("%s%nweight %d  •  %s = %s%nstatus %s",
                    r.uid, r.weight, m.label, fmtNum(y), r.status));
        }
        for (var e : byWeight.entrySet()) {
            double mu = mean(e.getValue());
            XYChart.Data<Number, Number> d = new XYChart.Data<>(e.getKey(), mu);
            means.getData().add(d);
            installTooltip(d, String.format("weight %d%nmean %s = %.1f  (n=%d)",
                    e.getKey(), m.label, mu, e.getValue().size()));
        }
        outcomeChart.getData().add(pts);
        outcomeChart.getData().add(means);

        updateOutcomeHelp(m, byWeight);
    }

    private void updateOutcomeHelp(Metric m, Map<Integer, List<Double>> byWeight) {
        boolean lowerBetter = m != Metric.HEALTHY && m != Metric.VOLUNTEER;
        String dir = lowerBetter ? "Lower is better" : "Higher is better";
        StringBuilder sb = new StringBuilder();
        sb.append("Each dot is one solve, placed at the weight it used. ")
          .append(dir).append(" for ").append(m.label).append(". ")
          .append("The connected line is the average per weight — but trust the SPREAD of dots, not the line: ")
          .append("a weight whose dots cluster tightly is reliable; scattered dots mean that weight is a gamble. ");
        // call out the best + any high-variance weight, computed live, so the read is concrete
        Integer bestW = null; double bestVal = lowerBetter ? Double.MAX_VALUE : -Double.MAX_VALUE;
        Integer noisyW = null; double noisySpread = -1;
        for (var e : byWeight.entrySet()) {
            double mu = mean(e.getValue());
            if ((lowerBetter && mu < bestVal) || (!lowerBetter && mu > bestVal)) { bestVal = mu; bestW = e.getKey(); }
            double lo = e.getValue().stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double hi = e.getValue().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (hi - lo > noisySpread) { noisySpread = hi - lo; noisyW = e.getKey(); }
        }
        if (bestW != null)
            sb.append(String.format("Right now weight %d has the best average %s (%.1f). ", bestW, m.label, bestVal));
        if (noisyW != null && noisySpread > 0)
            sb.append(String.format("Weight %d is the most variable (spread of %.0f) — needs more runs before you trust it.", noisyW, noisySpread));
        sb.append("  Hover any dot for its run id.");
        outcomeHelp.setText(sb.toString());
    }

    // ------------------------------------------------------------------ summary table
    private void rebuildSummary() {
        Map<Integer, List<Run>> byWeight = filtered().stream()
                .collect(Collectors.groupingBy(Run::getWeight, TreeMap::new, Collectors.toList()));
        ObservableList<Agg> rows = FXCollections.observableArrayList();
        for (var e : byWeight.entrySet()) {
            List<Double> frag = collect(e.getValue(), Run::getFragile);
            List<Double> heal = collect(e.getValue(), Run::getHealthy);
            List<Double> plat = collect(e.getValue(), Run::getPlateauS);
            rows.add(new Agg(
                    e.getKey(), e.getValue().size(),
                    fmtMean(frag), fmtBest(frag), fmtSpread(frag),
                    fmtMean(heal), fmtMean(plat)));
        }
        summaryTable.setItems(rows);
        tableHelp.setText(
            "One row per weight. 'n runs' is how many times we've solved at that weight — a conclusion "
          + "from n=1 is just a single draw, not evidence. 'best fragile' is the luckiest result; "
          + "'fragile spread' is low–high across its runs (a wide spread = unreliable). "
          + "'mean plateau (s)' is when the objective typically stopped improving — that's the number "
          + "you'll eventually use to decide how short the P3 budget can safely be. "
          + "Rule of thumb: don't trust a weight until n ≥ 3 and its spread is tight.");
    }

    // ------------------------------------------------------------------ trajectory chart
    /** One loaded trajectory: its points plus the weight it ran at (for coloring/grouping). */
    private static final class Traj {
        final String file; final Integer weight;
        final List<double[]> pts = new ArrayList<>();  // {elapsed_s, objective, best_bound}
        Traj(String file, Integer weight) { this.file = file; this.weight = weight; }
        double[] last() { return pts.isEmpty() ? null : pts.get(pts.size() - 1); }
    }

    /** Map a traj filename (traj_<label>_runN.csv) to the weight of that run, via the results rows. */
    private Integer weightForTrajFile(String fname) {
        // strip "traj_" and ".csv" -> "<label>_runN"; the uid is "<label>-rN#hash"
        String core = fname.substring(5, fname.length() - 4);   // <label>_runN
        int us = core.lastIndexOf("_run");
        if (us < 0) return null;
        String label = core.substring(0, us);
        String run = core.substring(us + 4);
        String prefix = label + "-r" + run;                     // matches uid before '#'
        for (Run r : runs) {
            String uidLabel = r.uid.contains("#") ? r.uid.substring(0, r.uid.indexOf('#')) : r.uid;
            if (uidLabel.equals(prefix)) return r.weight;
        }
        return null;
    }

    private Traj loadTraj(String fname) {
        Traj t = new Traj(fname, weightForTrajFile(fname));
        Path p = RUNS_DIR.resolve(fname);
        if (!Files.exists(p)) return t;
        try {
            List<String> lines = Files.readAllLines(p);
            for (int i = 1; i < lines.size(); i++) {
                String[] f = lines.get(i).trim().split(",", -1);
                if (f.length < 3) continue;
                Double x = parseDbl(f[0]), o = parseDbl(f[1]), b = parseDbl(f[2]);
                if (x != null && o != null) t.pts.add(new double[]{x, o, b == null ? Double.NaN : b});
            }
        } catch (IOException ignored) { }
        return t;
    }

    private void redrawTrajectory() {
        trajChart.getData().clear();
        TrajView view = trajViewPicker.getValue();
        if (view == null) view = TrajView.OVERLAY;
        switch (view) {
            case SINGLE  -> drawSingleTraj();
            case HEATMAP -> drawHeatmapTraj();
            case OVERLAY -> drawOverlayTraj();
        }
    }

    /** All runs on one chart, one line per run, colored/legended by weight. */
    private void drawOverlayTraj() {
        ((NumberAxis) trajChart.getYAxis()).setLabel("Phase-3 objective");
        ((NumberAxis) trajChart.getXAxis()).setLabel("elapsed seconds");
        trajChart.setCreateSymbols(false);
        List<String> files = doneFilteredTrajFiles();
        // group series by weight so the legend reads per-weight, not per-file
        Map<Integer, Boolean> weightLegended = new HashMap<>();
        int loaded = 0;
        for (String f : files) {
            Traj t = loadTraj(f);
            if (t.pts.isEmpty()) continue;
            loaded++;
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            String wlabel = t.weight == null ? "weight ?" : "weight " + t.weight;
            // only name the FIRST series of each weight so the legend isn't repeated
            s.setName(weightLegended.putIfAbsent(t.weight, true) == null ? wlabel : null);
            for (double[] pt : t.pts) {
                XYChart.Data<Number, Number> d = new XYChart.Data<>(pt[0], pt[1]);
                s.getData().add(d);
                installTooltip(d, String.format("%s (%s)%nt=%.0fs  objective=%.0f", f, wlabel, pt[0], pt[1]));
            }
            trajChart.getData().add(s);
            colorSeriesByWeight(s, t.weight);
        }
        trajChart.setTitle("All runs overlaid — colored by weight (lower & faster = better)");
        trajHelp.setText(
            "Every solve's objective-vs-time curve, on one chart, colored by weight. Read it for RELIABILITY: "
          + "if all curves of one color dive low and stay there, that weight dependably finds good schedules. "
          + "A lone curve that stalls high while its same-color siblings drop is an OUTLIER — a bad-luck run. "
          + "Curves that flatten early tell you the solve finished improving early (budget could be shorter); "
          + "curves still dropping at the right edge mean the budget cut them off. "
          + String.format("Showing %d run(s). Hover any line for its run id and time.", loaded));
    }

    /** Density of (time-to-best, best-objective) points — where runs TYPICALLY land, outliers visible. */
    private void drawHeatmapTraj() {
        ((NumberAxis) trajChart.getYAxis()).setLabel("final objective (best reached)");
        ((NumberAxis) trajChart.getXAxis()).setLabel("time to reach best (s)");
        trajChart.setCreateSymbols(true);
        // We approximate a heatmap with a scatter where each point is one run's (time-to-best, best-obj),
        // colored by weight. Clusters = the typical case; isolated points = outliers. (A true binned
        // heatmap isn't a built-in JavaFX chart; this scatter conveys the same "where do runs land".)
        List<String> files = doneFilteredTrajFiles();
        Map<Integer, XYChart.Series<Number, Number>> byWeight = new TreeMap<>();
        int n = 0;
        for (String f : files) {
            Traj t = loadTraj(f);
            double[] last = t.last();
            if (last == null) continue;
            // time-to-best = elapsed at the last improvement (objective stops dropping after this)
            double timeToBest = last[0], bestObj = last[1];
            XYChart.Series<Number, Number> s = byWeight.computeIfAbsent(t.weight, w -> {
                XYChart.Series<Number, Number> ns = new XYChart.Series<>();
                ns.setName(w == null ? "weight ?" : "weight " + w);
                return ns;
            });
            XYChart.Data<Number, Number> d = new XYChart.Data<>(timeToBest, bestObj);
            s.getData().add(d);
            installTooltip(d, String.format("%s%nreached best at t=%.0fs%nbest objective=%.0f",
                    f, timeToBest, bestObj));
            n++;
        }
        for (var e : byWeight.entrySet()) {
            trajChart.getData().add(e.getValue());
            colorSeriesByWeight(e.getValue(), e.getKey());
        }
        trajChart.setTitle("Where runs land: time-to-best vs. best objective (colored by weight)");
        trajHelp.setText(
            "Each point is one run: WHEN it reached its best objective (x) vs. HOW good that best was (y). "
          + "Down-and-left is ideal (great schedule, found fast). Look for the CLUSTER — that's the typical "
          + "outcome — and for stragglers far to the right (slow to converge) or high up (never got good). "
          + "This is the chart that tells you the budget question: if nearly every point reaches its best by, "
          + "say, ~1800s, then a P3 budget much beyond that is mostly wasted. "
          + String.format("Showing %d run(s).", n));
    }

    /** Original single-run view: objective vs. best-bound for one chosen file. */
    private void drawSingleTraj() {
        ((NumberAxis) trajChart.getYAxis()).setLabel("Phase-3 objective");
        ((NumberAxis) trajChart.getXAxis()).setLabel("elapsed seconds");
        trajChart.setCreateSymbols(true);
        String name = trajPicker.getValue();
        if (name == null) { trajHelp.setText("Pick a run above to see its single-run trajectory."); return; }
        Traj t = loadTraj(name);
        XYChart.Series<Number, Number> obj = new XYChart.Series<>(); obj.setName("objective");
        XYChart.Series<Number, Number> bound = new XYChart.Series<>(); bound.setName("best bound");
        for (double[] pt : t.pts) {
            XYChart.Data<Number, Number> od = new XYChart.Data<>(pt[0], pt[1]);
            obj.getData().add(od);
            installTooltip(od, String.format("t=%.0fs  objective=%.0f", pt[0], pt[1]));
            if (!Double.isNaN(pt[2])) {
                XYChart.Data<Number, Number> bd = new XYChart.Data<>(pt[0], pt[2]);
                bound.getData().add(bd);
                installTooltip(bd, String.format("t=%.0fs  best bound=%.0f", pt[0], pt[2]));
            }
        }
        trajChart.getData().add(obj);
        trajChart.getData().add(bound);
        trajChart.setTitle("Single run: " + name);
        trajHelp.setText(
            "One run. The upper line (objective) is the best schedule found so far; the lower line (best bound) "
          + "is the solver's proof of how low it could POSSIBLY go. When the two MEET, the answer is proven "
          + "optimal — more time can't help, so it's safe to cut. When a GAP remains at the right edge, the "
          + "solver might still have improved with more time — cutting there risks missing a better schedule. "
          + "This is your safety check before shortening any budget.");
    }

    /** Trajectory files, optionally restricted to runs that ended DONE (respecting the page toggle). */
    private List<String> doneFilteredTrajFiles() {
        List<String> all = loadTrajLabels();
        if (!doneOnly.isSelected()) return all;
        Set<String> doneLabels = new HashSet<>();
        for (Run r : runs) if (r.isDone()) {
            String uidLabel = r.uid.contains("#") ? r.uid.substring(0, r.uid.indexOf('#')) : r.uid;
            doneLabels.add(uidLabel);   // e.g. cfgR4-01-r2
        }
        List<String> out = new ArrayList<>();
        for (String f : all) {
            String core = f.substring(5, f.length() - 4);   // <label>_runN
            int us = core.lastIndexOf("_run");
            if (us < 0) { out.add(f); continue; }
            String prefix = core.substring(0, us) + "-r" + core.substring(us + 4);
            if (doneLabels.isEmpty() || doneLabels.contains(prefix)) out.add(f);
        }
        return out;
    }

    // ------------------------------------------------------------------ chart styling helpers
    /** Attach a hover tooltip to a chart point once its node exists. */
    private static void installTooltip(XYChart.Data<Number, Number> d, String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(javafx.util.Duration.millis(150));
        if (d.getNode() != null) {
            Tooltip.install(d.getNode(), tip);
        } else {
            // node is created by the chart after the data is added; install when it appears
            d.nodeProperty().addListener((obs, old, node) -> { if (node != null) Tooltip.install(node, tip); });
        }
    }

    // Stable palette so a given weight keeps its color across the overlay and heatmap views.
    private static final String[] PALETTE = {
        "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
        "#8c564b", "#e377c2", "#17becf", "#bcbd22", "#7f7f7f"
    };
    private final Map<Integer, String> weightColors = new HashMap<>();
    private String colorForWeight(Integer w) {
        if (w == null) return "#999999";
        return weightColors.computeIfAbsent(w, k -> PALETTE[weightColors.size() % PALETTE.length]);
    }
    private void colorSeriesByWeight(XYChart.Series<Number, Number> s, Integer weight) {
        String c = colorForWeight(weight);
        Runnable style = () -> {
            if (s.getNode() != null)
                s.getNode().setStyle("-fx-stroke: " + c + "; -fx-stroke-width: 1.6px;");
            for (XYChart.Data<Number, Number> d : s.getData())
                if (d.getNode() != null)
                    d.getNode().setStyle("-fx-background-color: " + c + ", white; -fx-background-radius: 6px;");
        };
        // nodes may not exist until laid out; apply now and again on the next pulse
        style.run();
        javafx.application.Platform.runLater(style);
    }

    private static String fmtNum(double v) {
        return v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.1f", v);
    }

    // ------------------------------------------------------------------ helpers
    private List<Run> filtered() {
        if (doneOnly.isSelected()) return runs.stream().filter(Run::isDone).collect(Collectors.toList());
        return runs;
    }

    private static List<Double> collect(List<Run> rs, java.util.function.Function<Run, Double> f) {
        List<Double> out = new ArrayList<>();
        for (Run r : rs) { Double v = f.apply(r); if (v != null) out.add(v); }
        return out;
    }
    private static double mean(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }
    private static String fmtMean(List<Double> xs) {
        return xs.isEmpty() ? "-" : String.format("%.1f", mean(xs));
    }
    private static String fmtBest(List<Double> xs) {
        return xs.isEmpty() ? "-" : String.format("%.0f", xs.stream().mapToDouble(Double::doubleValue).min().getAsDouble());
    }
    private static String fmtSpread(List<Double> xs) {
        if (xs.isEmpty()) return "-";
        double lo = xs.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
        double hi = xs.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
        return String.format("%.0f–%.0f", lo, hi);
    }
    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private static Integer parseIntOrNull(String s) {
        try { return Integer.valueOf(s.trim()); } catch (Exception e) { return null; }
    }
    private static Double parseDbl(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return Double.valueOf(t); } catch (Exception e) { return null; }
    }
}
