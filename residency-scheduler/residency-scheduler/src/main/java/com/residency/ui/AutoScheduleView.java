package com.residency.ui;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import com.residency.cpsat.CpSatSchedulerEngine;
import com.residency.cpsat.FeasibilityReport;
import com.residency.cpsat.ScheduleSolution;
import com.residency.db.BlockDAO;
import com.residency.model.ScheduleSnapshot;
import com.residency.service.TimefoldSchedulerService;
import com.residency.solver.RotationSchedule;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Auto-Scheduler tab.
 *
 * Supports two engines side-by-side:
 *   Left panel  — Timefold Solver (run-until-stop, live score stream)
 *   Right panel — Google OR-Tools CP-SAT (runs to completion or stop)
 *
 * Engine selector toggles between:
 *   • Run Timefold only
 *   • Run CP-SAT only
 *   • Run both (side-by-side comparison)
 */
public class AutoScheduleView extends BorderPane {

    // Services
    private TimefoldSchedulerService timefoldService;
    private CpSatSchedulerEngine     cpSatEngine;

    private final Deque<ScheduleSnapshot> undoStack = new ArrayDeque<>();

    // Controls
    private final ComboBox<Integer>  yearPicker     = new ComboBox<>();
    private final ToggleGroup        engineGroup    = new ToggleGroup();
    private final RadioButton        rbTimefold     = new RadioButton("Timefold");
    private final RadioButton        rbCpSat        = new RadioButton("CP-SAT (OR-Tools)");
    private final RadioButton        rbBoth         = new RadioButton("Both (compare)");
    private final Button             runBtn         = new Button("▶  Start");
    private final Button             stopBtn        = new Button("⏹  Stop & Commit");
    private final Button             undoBtn        = new Button("↩  Undo");

    // Timefold panel widgets
    private final Label  tfScore    = new Label("—");
    private final Label  tfHard     = new Label("Hard: —");
    private final Label  tfMedium   = new Label("Medium: —");
    private final Label  tfSoft     = new Label("Soft: —");
    private final Label  tfFeasible = new Label();
    private final Label  tfIter     = new Label("Updates: 0");
    private final VBox   tfLog      = new VBox(3);
    private final AtomicLong tfUpdates = new AtomicLong(0);

    // Score progress history
    private final VBox   tfProgressRows = new VBox(2);

    // CP-SAT panel widgets
    private final Label  csStatus   = new Label("—");
    private final Label  csObj      = new Label("Objective: —");
    private final Label  csConflicts= new Label("Search backtracks: —");
    private final Label  csBranches = new Label("Search branches: —");
    private final Label  csFeasible = new Label();
    private final VBox   csLog      = new VBox(3);

    // CP-SAT score progress history
    private final VBox   csProgressRows = new VBox(2);
    private volatile boolean csSolving = false;

    // Per-phase time limits (seconds; 0 = unlimited)
    private final Spinner<Integer> tier0Spinner = new Spinner<>(0, 600, 60,  15);
    private final Spinner<Integer> tier1Spinner = new Spinner<>(0, 600, 120, 30);
    private final Spinner<Integer> tier2Spinner = new Spinner<>(0, 600, 120, 30);
    private final Spinner<Integer> tier3Spinner = new Spinner<>(0, 600, 60,  15);
    private VBox feasibilityContent;
    private TitledPane feasibilityPane;

    private long solveStartMs = 0;

    public AutoScheduleView() {
        try {
            timefoldService = new TimefoldSchedulerService();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        buildUI();
        loadYears();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI construction
    // ══════════════════════════════════════════════════════════════════════

    private void buildUI() {
        // ── Engine selector ────────────────────────────────────────────────
        rbTimefold.setToggleGroup(engineGroup); rbTimefold.setSelected(true);
        rbCpSat.setToggleGroup(engineGroup);
        rbBoth.setToggleGroup(engineGroup);

        HBox engineSelector = new HBox(16, new Label("Engine:"), rbTimefold, rbCpSat, rbBoth);
        engineSelector.setAlignment(Pos.CENTER_LEFT);
        engineSelector.setPadding(new Insets(8, 12, 4, 12));

        // ── Year + buttons ─────────────────────────────────────────────────
        stopBtn.setDisable(true);
        undoBtn.setDisable(true);
        runBtn.setStyle("-fx-background-color:#1a6b2a;-fx-text-fill:white;-fx-font-size:13px;-fx-padding:6 18;");
        stopBtn.setStyle("-fx-background-color:#8b1a1a;-fx-text-fill:white;-fx-font-size:13px;-fx-padding:6 18;");
        undoBtn.setStyle("-fx-background-color:#555;-fx-text-fill:white;");
        runBtn.setOnAction(e  -> doStart());
        stopBtn.setOnAction(e -> doStop());
        undoBtn.setOnAction(e -> doUndo());

        Button deleteYearBtn = new Button("🗑 Delete Year");
        deleteYearBtn.setStyle("-fx-background-color:#8b1a1a;-fx-text-fill:white;");
        deleteYearBtn.setOnAction(e -> {
            Integer year = yearPicker.getValue();
            if (year == null) { showError("Select a year to delete."); return; }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete year " + year + "?\n\nThis will permanently remove all blocks and assignments for this year.",
                ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Delete Year");
            confirm.setHeaderText("This cannot be undone.");
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    try {
                        new BlockDAO().deleteYear(year);
                        refreshYears();
                    } catch (SQLException ex) { showError(ex.getMessage()); }
                }
            });
        });

        HBox controls = new HBox(12,
            new Label("Year:"), yearPicker, runBtn, stopBtn, undoBtn,
            new Separator(javafx.geometry.Orientation.VERTICAL), deleteYearBtn);
        controls.setPadding(new Insets(4, 12, 10, 12));
        controls.setAlignment(Pos.CENTER_LEFT);

        VBox topBar = new VBox(0, engineSelector, controls);
        topBar.setStyle("-fx-background-color:white;-fx-border-color:#dde3f0;-fx-border-width:0 0 1 0;");

        // ── Side-by-side panels ────────────────────────────────────────────
        VBox timefoldPanel = buildTimefoldPanel();
        VBox cpSatPanel    = buildCpSatPanel();
        timefoldPanel.setMaxWidth(Double.MAX_VALUE);
        cpSatPanel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(timefoldPanel, Priority.ALWAYS);
        HBox.setHgrow(cpSatPanel, Priority.ALWAYS);

        HBox panels = new HBox(1, timefoldPanel, cpSatPanel);
        panels.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(panels);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);

        setTop(topBar);
        setCenter(scroll);

        // Toggle panel visibility based on engine selection
        engineGroup.selectedToggleProperty().addListener((obs, old, sel) -> updatePanelVisibility(timefoldPanel, cpSatPanel));
        updatePanelVisibility(timefoldPanel, cpSatPanel);
    }

    private void updatePanelVisibility(VBox tfPanel, VBox csPanel) {
        boolean showTf = rbTimefold.isSelected() || rbBoth.isSelected();
        boolean showCs = rbCpSat.isSelected()    || rbBoth.isSelected();
        tfPanel.setVisible(showTf); tfPanel.setManaged(showTf);
        csPanel.setVisible(showCs); csPanel.setManaged(showCs);
    }

    private VBox buildTimefoldPanel() {
        tfScore.setFont(Font.font("Monospaced", FontWeight.BOLD, 16));
        tfScore.setTextFill(Color.web("#1e3c72"));

        Label tfLegacyNote = new Label(
            "Legacy engine — fewer constraints than CP-SAT. PGY-scoped prerequisites are not enforced.");
        tfLegacyNote.setStyle("-fx-font-size:10px;-fx-text-fill:#8b4513;-fx-font-style:italic;");

        VBox scoreBox = new VBox(5,
            headerLabel("Timefold Solver", "#2a5298"),
            tfLegacyNote,
            tfScore,
            new HBox(16, tfHard, tfMedium, tfSoft),
            new HBox(16, tfIter, tfFeasible));
        scoreBox.setPadding(new Insets(10));
        scoreBox.setStyle("-fx-background-color:#f0f4ff;-fx-border-color:#c0cbdf;-fx-border-radius:5;-fx-background-radius:5;");

        // Score progress history panel
        Label progressHeader = new Label("  Time       Elapsed   Hard      Medium    Soft      Status");
        progressHeader.setStyle("-fx-font-family:monospace;-fx-font-size:10px;-fx-font-weight:bold;-fx-text-fill:#333;");
        tfProgressRows.setPadding(new Insets(4, 4, 4, 4));
        ScrollPane progressScroll = new ScrollPane(new VBox(0, progressHeader, tfProgressRows));
        progressScroll.setFitToWidth(true);
        progressScroll.setPrefHeight(160);
        progressScroll.setStyle("-fx-background-color:#fafbff;");
        TitledPane progressPane = new TitledPane("Score Progress", progressScroll);
        progressPane.setExpanded(true);

        tfLog.setPadding(new Insets(6));
        ScrollPane logScroll = new ScrollPane(tfLog);
        logScroll.setFitToWidth(true); logScroll.setPrefHeight(200);

        TitledPane logPane = new TitledPane("Solver Log", logScroll);
        logPane.setExpanded(false);

        TitledPane constraintPane = buildConstraintInfoPane();

        VBox panel = new VBox(10, scoreBox, progressPane, constraintPane, logPane);
        panel.setPadding(new Insets(6));
        return panel;
    }

    private VBox buildCpSatPanel() {
        csStatus.setFont(Font.font("Monospaced", FontWeight.BOLD, 13));
        csStatus.setTextFill(Color.web("#1e3c72"));

        VBox statusBox = new VBox(5,
            headerLabel("CP-SAT / OR-Tools", "#1a6b2a"),
            csStatus,
            csObj,
            new HBox(16, csConflicts, csBranches),
            csFeasible);
        statusBox.setPadding(new Insets(10));
        statusBox.setStyle("-fx-background-color:#f0fff4;-fx-border-color:#b0d8c0;-fx-border-radius:5;-fx-background-radius:5;");

        // Phase time-limit controls
        for (Spinner<Integer> sp : List.of(tier0Spinner, tier1Spinner, tier2Spinner, tier3Spinner)) {
            sp.setPrefWidth(72);
            sp.setEditable(true);
        }
        HBox timeLimits = new HBox(8,
            new Label("Phase limits (s):"),
            new Label("0-Feasibility"), tier0Spinner,
            new Label("1-Clinical"),    tier1Spinner,
            new Label("2-Quality"),     tier2Spinner,
            new Label("3-Pattern"),     tier3Spinner
        );
        timeLimits.setAlignment(Pos.CENTER_LEFT);
        timeLimits.setPadding(new Insets(4, 0, 4, 0));
        timeLimits.setStyle("-fx-font-size:10px;");

        // Score progress history panel
        Label progressHeader = new Label("  Time       Elapsed    Backtracks   Branches     Objective");
        progressHeader.setStyle("-fx-font-family:monospace;-fx-font-size:10px;-fx-font-weight:bold;-fx-text-fill:#333;");
        csProgressRows.setPadding(new Insets(4, 4, 4, 4));
        ScrollPane progressScroll = new ScrollPane(new VBox(0, progressHeader, csProgressRows));
        progressScroll.setFitToWidth(true);
        progressScroll.setPrefHeight(160);
        progressScroll.setStyle("-fx-background-color:#f5fff8;");
        TitledPane progressPane = new TitledPane("Score Progress", progressScroll);
        progressPane.setExpanded(true);

        csLog.setPadding(new Insets(6));
        ScrollPane logScroll = new ScrollPane(csLog);
        logScroll.setFitToWidth(true); logScroll.setPrefHeight(200);

        TitledPane logPane    = new TitledPane("Solver Log", logScroll);
        logPane.setExpanded(false);
        TitledPane diagPane   = buildFeasibilityPane();

        VBox panel = new VBox(10, statusBox, timeLimits, progressPane, diagPane, logPane);
        panel.setPadding(new Insets(6));
        return panel;
    }

    private Label headerLabel(String text, String color) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-weight:bold;-fx-font-size:14px;-fx-text-fill:" + color + ";");
        return lbl;
    }

    private TitledPane buildConstraintInfoPane() {
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(4); grid.setPadding(new Insets(8));
        String[][] rows = {
            {"HARD",   "Rotation capacity",       "Max N residents/rotation/block"},
            {"HARD",   "Max blocks per resident",  "PGY max blocks respected"},
            {"HARD",   "Prerequisite ordering",    "A before B enforced"},
            {"HARD",   "Sequence / adjacency",     "Order and no-direct-follow rules enforced"},
            {"HARD",   "Eligible rotations only",  "PGY eligibility enforced"},
            {"MEDIUM", "Required min blocks",      "Required rotations fulfilled"},
            {"SOFT",   "Unassigned penalty",       "Fill as many slots as possible"},
            {"SOFT",   "Workload balance",         "Minimise load variance"},
            {"SOFT",   "Prefer earlier blocks",    "Earlier placement preferred"},
        };
        for (int i = 0; i < rows.length; i++) {
            String tier = rows[i][0];
            String bg = "HARD".equals(tier) ? "#fde8e8" : "MEDIUM".equals(tier) ? "#fff3cd" : "#d4edda";
            String fg = "HARD".equals(tier) ? "#c0392b" : "MEDIUM".equals(tier) ? "#856404" : "#155724";
            Label t = new Label(tier);
            t.setStyle("-fx-font-weight:bold;-fx-font-size:9px;-fx-padding:1 4;-fx-background-radius:3;"
                + "-fx-background-color:" + bg + ";-fx-text-fill:" + fg + ";");
            Label n = new Label(rows[i][1]);  n.setStyle("-fx-font-weight:bold;-fx-font-size:10px;");
            Label d = new Label(rows[i][2]);  d.setStyle("-fx-font-size:10px;-fx-text-fill:#555;");
            grid.add(t, 0, i); grid.add(n, 1, i); grid.add(d, 2, i);
        }
        TitledPane p = new TitledPane("Timefold Constraints", grid);
        p.setExpanded(false);
        return p;
    }

    private TitledPane buildFeasibilityPane() {
        Label hint = new Label("Run CP-SAT to see feasibility diagnostics.");
        hint.setStyle("-fx-text-fill:#888;-fx-font-size:11px;");
        feasibilityContent = new VBox(4, hint);
        feasibilityContent.setPadding(new Insets(6));
        feasibilityPane = new TitledPane("Feasibility Diagnostics", feasibilityContent);
        feasibilityPane.setExpanded(false);
        return feasibilityPane;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Actions
    // ══════════════════════════════════════════════════════════════════════

    private void doStart() {
        Integer year = yearPicker.getValue();
        if (year == null) { showError("Select a year."); return; }

        runBtn.setDisable(true);
        stopBtn.setDisable(false);
        undoBtn.setDisable(true);
        solveStartMs = System.currentTimeMillis();

        try {
            // Snapshot for undo
            undoStack.push(timefoldService.takeSnapshot(year, "Before auto-schedule " + year));

            if (rbTimefold.isSelected() || rbBoth.isSelected()) startTimefold(year);
            if (rbCpSat.isSelected()    || rbBoth.isSelected()) startCpSat(year);

        } catch (SQLException e) { showError(e.getMessage()); }
    }

    private void doStop() {
        stopBtn.setDisable(true);
        csSolving = false;
        if (rbTimefold.isSelected() || rbBoth.isSelected()) timefoldService.stopSolving();
        if (rbCpSat.isSelected()    || rbBoth.isSelected()) {
            if (cpSatEngine != null) cpSatEngine.requestStop();
        }
        tfLogLine("Stop requested…");
        csLogLine("Stop requested…");
    }

    private void doUndo() {
        if (undoStack.isEmpty()) return;
        ScheduleSnapshot snap = undoStack.pop();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "Restore to: " + snap + "\n\nThis overwrites current assignments.",
            ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.YES) {
                try {
                    timefoldService.restoreSnapshot(snap);
                    tfLogLine("↩ Restored: " + snap.getLabel());
                    csLogLine("↩ Restored: " + snap.getLabel());
                    if (undoStack.isEmpty()) undoBtn.setDisable(true);
                } catch (SQLException e) { showError(e.getMessage()); }
            }
        });
    }

    // ── Timefold ───────────────────────────────────────────────────────────

    private void startTimefold(int year) throws SQLException {
        tfUpdates.set(0);
        tfProgressRows.getChildren().clear();
        tfLogLine("Timefold solver started for year " + year);

        timefoldService.startSolving(year,
            sol -> Platform.runLater(() -> updateTimefoldScore(sol)),
            sol -> Platform.runLater(() -> {
                onTimefoldFinished(sol, year);
                checkBothDone();
            }),
            (msg, ex) -> Platform.runLater(() -> {
                tfLogLine("❌ Solver error: " + msg);
                if (ex != null) tfLogLine("   " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                runBtn.setDisable(false);
                stopBtn.setDisable(true);
                undoBtn.setDisable(undoStack.isEmpty());
            }),
            msg -> Platform.runLater(() -> tfLogLine("ℹ " + msg)));
    }

    private void updateTimefoldScore(RotationSchedule sol) {
        HardMediumSoftScore s = sol.getScore();
        if (s == null) {
            tfLogLine("Solver initializing — waiting for first scored solution…");
            return;
        }
        long count = tfUpdates.incrementAndGet();
        long elapsed = System.currentTimeMillis() - solveStartMs;
        tfScore.setText(sol.scoreSummary());
        tfHard.setText("Hard: " + s.hardScore());
        tfMedium.setText("Medium: " + s.mediumScore());
        tfSoft.setText("Soft: " + s.softScore());
        tfIter.setText(String.format("Updates: %d  |  %.1fs", count, elapsed / 1000.0));
        boolean ok = sol.isFeasible();
        tfFeasible.setText(ok ? "✅ FEASIBLE" : "⚠ INFEASIBLE");
        tfFeasible.setStyle("-fx-font-weight:bold;-fx-text-fill:" + (ok ? "#155724" : "#8b1a1a") + ";");
        tfHard.setStyle("-fx-font-size:12px;-fx-text-fill:" + (s.hardScore() == 0 ? "#27ae60" : "#c0392b") + ";");
        tfMedium.setStyle("-fx-font-size:12px;-fx-text-fill:" + (s.mediumScore() == 0 ? "#27ae60" : "#e67e22") + ";");

        // Append a row to the score progress history
        String row = String.format("  %-10s %-9s %-9d %-9d %-9d %s",
            java.time.LocalTime.now().toString().substring(0, 8),
            String.format("%.1fs", elapsed / 1000.0),
            s.hardScore(), s.mediumScore(), s.softScore(),
            ok ? "FEASIBLE" : "infeasible");
        Label rowLbl = new Label(row);
        rowLbl.setStyle("-fx-font-family:monospace;-fx-font-size:10px;-fx-text-fill:"
            + (ok ? "#155724" : (s.hardScore() == 0 ? "#856404" : "#555")) + ";");
        tfProgressRows.getChildren().add(rowLbl);
        if (tfProgressRows.getChildren().size() > 100) tfProgressRows.getChildren().remove(0);
    }

    private void onTimefoldFinished(RotationSchedule sol, int year) {
        long elapsed = System.currentTimeMillis() - solveStartMs;
        tfLogLine(String.format("Done — %.1fs | Final: %s", elapsed / 1000.0, sol.scoreSummary()));
        updateTimefoldScore(sol);
        try {
            timefoldService.commitBestSolution(year);
            tfLogLine("✅ Solution committed.");
        } catch (SQLException e) { tfLogLine("❌ Commit failed: " + e.getMessage()); }
    }

    // ── CP-SAT ─────────────────────────────────────────────────────────────

    private void startCpSat(int year) {
        csLogLine("CP-SAT solver starting for year " + year + "…");
        csStatus.setText("Running…");
        csProgressRows.getChildren().clear();
        csSolving = true;

        // Polling thread — adds a row every 10 s so there's always visible activity,
        // even when the solver hasn't found a feasible solution yet.
        Thread poller = new Thread(() -> {
            while (csSolving) {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { break; }
                if (!csSolving) break;
                long elapsed = System.currentTimeMillis() - solveStartMs;
                Platform.runLater(() -> {
                    String row = String.format("  %-10s %-10s %-12s %-12s %s",
                        java.time.LocalTime.now().toString().substring(0, 8),
                        String.format("%.1fs", elapsed / 1000.0),
                        "—", "—", "searching…");
                    Label lbl = new Label(row);
                    lbl.setStyle("-fx-font-family:monospace;-fx-font-size:10px;-fx-text-fill:#888;");
                    csProgressRows.getChildren().add(lbl);
                    if (csProgressRows.getChildren().size() > 100) csProgressRows.getChildren().remove(0);
                });
            }
        }, "cpsat-poller");
        poller.setDaemon(true);
        poller.start();

        Thread worker = new Thread(() -> {
            try {
                cpSatEngine = new CpSatSchedulerEngine();
                int t0 = tier0Spinner.getValue();
                int t1 = tier1Spinner.getValue();
                int t2 = tier2Spinner.getValue();
                int t3 = tier3Spinner.getValue();
                ScheduleSolution sol = cpSatEngine.solve(year, msg ->
                    Platform.runLater(() -> {
                        if (msg.startsWith("PROGRESS:")) {
                            addCpSatProgressRow(msg);
                        } else {
                            csLogLine(msg);
                            csStatus.setText(msg.length() > 60 ? msg.substring(0, 60) + "…" : msg);
                        }
                    }), t0, t1, t2, t3);
                csSolving = false;
                Platform.runLater(() -> {
                    onCpSatFinished(sol);
                    checkBothDone();
                });
            } catch (Throwable ex) {
                csSolving = false;
                Platform.runLater(() -> {
                    Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
                    String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
                    csLogLine("❌ " + cause.getClass().getSimpleName() + ": " + msg);
                    if (cause instanceof UnsatisfiedLinkError || ex instanceof UnsatisfiedLinkError) {
                        csLogLine("   Fix: run  mvn javafx:run  from the project directory,");
                        csLogLine("   or rebuild with  mvn package  before running the JAR.");
                    }
                    csStatus.setText("Failed");
                    runBtn.setDisable(false);
                    stopBtn.setDisable(true);
                    undoBtn.setDisable(undoStack.isEmpty());
                    checkBothDone();
                });
            }
        }, "cpsat-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void onCpSatFinished(ScheduleSolution sol) {
        long elapsed = System.currentTimeMillis() - solveStartMs;
        csStatus.setText(String.format("[%s] obj=%.1f  %.1fs",
            sol.getStatus(), sol.getObjectiveValue(), elapsed / 1000.0));
        csObj.setText("Objective: " + String.format("%.2f", sol.getObjectiveValue()));
        csConflicts.setText("Search backtracks: " + sol.getNumConflicts());
        csBranches.setText("Search branches: " + sol.getNumBranches());

        boolean ok = sol.isFeasible();
        csFeasible.setText(ok ? "✅ FEASIBLE" : "⚠ INFEASIBLE");
        csFeasible.setStyle("-fx-font-weight:bold;-fx-text-fill:" + (ok ? "#155724" : "#8b1a1a") + ";");

        // Populate feasibility diagnostics pane with full solver log
        feasibilityContent.getChildren().clear();
        if (sol.getSolverLog() != null && !sol.getSolverLog().isBlank()) {
            TextArea ta = new TextArea(sol.getSolverLog());
            ta.setEditable(false);
            ta.setWrapText(false);
            ta.setStyle("-fx-font-family:monospace;-fx-font-size:11px;");
            ta.setPrefHeight(300);
            feasibilityContent.getChildren().add(ta);
        }
        if (sol.getFeasibilityReport() != null && sol.getFeasibilityReport().hasIssues()) {
            for (FeasibilityReport.Issue issue : sol.getFeasibilityReport().getIssues()) {
                Label lbl = new Label(String.format("[%s] %s: %s\n  → %s",
                    issue.type, issue.rotationName, issue.description, issue.suggestion));
                lbl.setWrapText(true);
                lbl.setStyle("-fx-font-size:11px;-fx-text-fill:#8b1a1a;");
                feasibilityContent.getChildren().add(lbl);
            }
        }
        feasibilityPane.setExpanded(true);

        // Add a final summary row to the progress table
        String finalRow = String.format("  %-10s %-10s %-12d %-12d %.1f  ← FINAL [%s]",
            java.time.LocalTime.now().toString().substring(0, 8),
            String.format("%.1fs", elapsed / 1000.0),
            sol.getNumConflicts(), sol.getNumBranches(),
            sol.getObjectiveValue(), sol.getStatus());
        Label finalLbl = new Label(finalRow);
        finalLbl.setStyle("-fx-font-family:monospace;-fx-font-size:10px;-fx-font-weight:bold;-fx-text-fill:"
            + (ok ? "#155724" : "#8b1a1a") + ";");
        csProgressRows.getChildren().add(finalLbl);

        csLogLine(ok ? "✅ Feasible solution committed to DB." : "⚠ " + sol.getStatus() + " — partial/empty result committed. Check schedule page.");
        csLogLine(sol.statusSummary());
    }

    private void addCpSatProgressRow(String msg) {
        // Parse: PROGRESS:conflicts=123,branches=456,obj=-789.0,elapsed=12.3
        try {
            Map<String, String> parts = new HashMap<>();
            for (String kv : msg.substring("PROGRESS:".length()).split(",")) {
                String[] pair = kv.split("=", 2);
                if (pair.length == 2) parts.put(pair[0], pair[1]);
            }
            long conflicts = Long.parseLong(parts.getOrDefault("conflicts", "0"));
            long branches  = Long.parseLong(parts.getOrDefault("branches",  "0"));
            double obj     = Double.parseDouble(parts.getOrDefault("obj",    "0"));
            double elapsed = Double.parseDouble(parts.getOrDefault("elapsed","0"));

            // Update the live summary labels too
            csConflicts.setText("Search backtracks: " + conflicts);
            csBranches.setText("Search branches: " + branches);
            csObj.setText(String.format("Objective: %.2f", obj));

            String row = String.format("  %-10s %-10s %-12d %-12d %.1f",
                java.time.LocalTime.now().toString().substring(0, 8),
                String.format("%.1fs", elapsed),
                conflicts, branches, obj);
            Label rowLbl = new Label(row);
            rowLbl.setStyle("-fx-font-family:monospace;-fx-font-size:10px;-fx-text-fill:#1a4a2a;");
            csProgressRows.getChildren().add(rowLbl);
            if (csProgressRows.getChildren().size() > 100) csProgressRows.getChildren().remove(0);
        } catch (Exception ignored) {
            csLogLine(msg); // fallback: treat as regular log line
        }
    }

    // ── Both done check ────────────────────────────────────────────────────

    private void checkBothDone() {
        boolean tfRunning = timefoldService.isSolving();
        if (!tfRunning) {
            runBtn.setDisable(false);
            stopBtn.setDisable(true);
            undoBtn.setDisable(undoStack.isEmpty());
        }
    }

    // ── Log helpers ────────────────────────────────────────────────────────

    private void tfLogLine(String msg) { addLogLine(tfLog, msg); }
    private void csLogLine(String msg) { addLogLine(csLog, msg); }

    private void addLogLine(VBox log, String msg) {
        Label lbl = new Label("[" + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        lbl.setStyle("-fx-font-size:11px;-fx-font-family:monospace;");
        lbl.setWrapText(true);
        log.getChildren().add(0, lbl);
        if (log.getChildren().size() > 200) log.getChildren().remove(200);
    }

    /** Called on construction and whenever the user switches to this tab. */
    public void refreshYears() {
        try {
            List<Integer> years = new BlockDAO().getDistinctYears();
            yearPicker.getItems().setAll(years);

            // Prefer whatever year the Schedule tab has active; fall back to first in list.
            Integer shared = AppState.get().getSelectedYear();
            if (shared != null && years.contains(shared)) {
                yearPicker.setValue(shared);
            } else if (!years.isEmpty()) {
                yearPicker.setValue(years.get(0));
            }
        } catch (SQLException e) { showError(e.getMessage()); }
    }

    private void loadYears() {
        refreshYears();
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
}
