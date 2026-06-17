package com.residency.ui;

import com.residency.cpsat.ScheduleConfig;
import com.residency.cpsat.ScheduleConfig.RotationLinkRule;
import com.residency.db.BlockDAO;
import com.residency.db.RotationDAO;
import com.residency.db.RotationLinkRuleDAO;
import com.residency.db.ScheduleConfigDAO;
import com.residency.model.Rotation;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings tab: global objective weights, solver parameters, post-call incompatibility
 * configuration, and a history table of all previous solver runs for comparison.
 */
public class ScheduleConfigView extends BorderPane {

    private final ScheduleConfigDAO   dao;
    private final RotationDAO         rotationDAO;
    private final RotationLinkRuleDAO linkRuleDAO;

    // Objective weights
    private final Spinner<Integer> spUnder        = new Spinner<>(0, 9999, 100);
    private final Spinner<Integer> spOver         = new Spinner<>(0, 9999, 20);
    private final Spinner<Integer> spVar          = new Spinner<>(0, 9999, 10);
    private final Spinner<Integer> spPgy          = new Spinner<>(0, 9999, 15);
    private final Spinner<Integer> spFourPlusTwo  = new Spinner<>(0, 9999, 30);
    private final Spinner<Integer> spInpSplit     = new Spinner<>(0, 9999, 50);
    private final Spinner<Integer> spPostCallHard = new Spinner<>(0, 99999, 10000);
    private final Spinner<Integer> spPostCallSoft = new Spinner<>(0, 9999, 300);

    // Workload
    private final Spinner<Integer> spMinLoad = new Spinner<>(0, 26, 0);
    private final Spinner<Integer> spMaxLoad = new Spinner<>(0, 26, 24);

    // CP-SAT params
    private final Spinner<Integer> spWorkers   = new Spinner<>(1, 32, 4);
    private final Spinner<Integer> spTimeLimit = new Spinner<>(0, 3600, 0);
    private final CheckBox         cbLog       = new CheckBox("Log CP-SAT search to console");

    // Post-call incompatibility — one CheckBox per rotation in each group
    private final VBox triggerBox     = new VBox(4);
    private final VBox mandatoryBox   = new VBox(4);
    private final VBox discouragedBox = new VBox(4);

    private final List<CheckBox> triggerCbs     = new ArrayList<>();
    private final List<CheckBox> mandatoryCbs   = new ArrayList<>();
    private final List<CheckBox> discouragedCbs = new ArrayList<>();
    private final List<Integer>  allRotIds      = new ArrayList<>();

    // Linked rotation sum rules
    private final ComboBox<Rotation>  linkRotA        = new ComboBox<>();
    private final ComboBox<Rotation>  linkRotB        = new ComboBox<>();
    private final Spinner<Integer>    linkSum         = new Spinner<>(1, 26, 2);
    private final Spinner<Integer>    linkGlobalTotal = new Spinner<>(0, 99, 0);
    private final ListView<String>    linkRulesList   = new ListView<>();
    private final List<RotationLinkRule> loadedLinkRules = new ArrayList<>();

    // Run history
    private final ComboBox<Integer> historyYear = new ComboBox<>();
    private final TableView<RunRecord> runsTable = new TableView<>();

    public ScheduleConfigView() {
        try {
            dao         = new ScheduleConfigDAO();
            rotationDAO = new RotationDAO();
            linkRuleDAO = new RotationLinkRuleDAO();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        buildUI();
        loadRotationsForPostCallPanel();
        loadConfig();
        loadYears();
        loadLinkRules();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════════════════

    private void buildUI() {
        for (Spinner<?> sp : List.of(spUnder, spOver, spVar, spPgy, spFourPlusTwo, spInpSplit,
                spPostCallHard, spPostCallSoft, spMinLoad, spMaxLoad, spWorkers, spTimeLimit)) {
            sp.setEditable(true);
            sp.setPrefWidth(95);
        }
        cbLog.setSelected(true);

        // ── Objective weights ──────────────────────────────────────────────
        GridPane weights = new GridPane();
        weights.setHgap(12); weights.setVgap(8); weights.setPadding(new Insets(10));
        addRow(weights, "α  Undercoverage penalty:",           spUnder,        0);
        addRow(weights, "β  Overcoverage penalty:",            spOver,         1);
        addRow(weights, "γ  Workload variance:",               spVar,          2);
        addRow(weights, "δ  PGY imbalance:",                   spPgy,          3);
        addRow(weights, "ε  4+2 pattern (in/outpatient):",     spFourPlusTwo,  4);
        addRow(weights, "ζ  Inpatient-to-inpatient split:",    spInpSplit,     5);
        addRow(weights, "η  Post-call hard penalty:",          spPostCallHard, 6);
        addRow(weights, "η  Post-call soft (discouraged):",    spPostCallSoft, 7);

        Label weightNote = new Label(
            "η_hard fires when a trigger rotation (ID/GI) is immediately followed by a mandatory-attendance\n" +
            "rotation (ICU, VA, etc.).  η_soft fires for the discouraged (but feasible) sequence.\n" +
            "Keep η_hard >> α (undercoverage) so the solver avoids post-call violations even at cost of coverage.");
        weightNote.setStyle("-fx-font-size:10px;-fx-text-fill:#555;");
        weightNote.setWrapText(true);

        // ── Post-call incompatibility ──────────────────────────────────────
        VBox postCallContent = buildPostCallPanel();

        // ── Workload ───────────────────────────────────────────────────────
        GridPane workload = new GridPane();
        workload.setHgap(12); workload.setVgap(8); workload.setPadding(new Insets(10));
        addRow(workload, "Min workload blocks/year:", spMinLoad, 0);
        addRow(workload, "Max workload blocks/year:", spMaxLoad, 1);

        // ── CP-SAT params ──────────────────────────────────────────────────
        GridPane cpsat = new GridPane();
        cpsat.setHgap(12); cpsat.setVgap(8); cpsat.setPadding(new Insets(10));
        addRow(cpsat, "CP-SAT worker threads:",    spWorkers,   0);
        addRow(cpsat, "Time limit (sec, 0=none):", spTimeLimit, 1);
        cpsat.add(cbLog, 0, 2, 2, 1);

        Button saveBtn = new Button("Save Settings");
        saveBtn.setStyle("-fx-background-color:#1e3c72;-fx-text-fill:white;-fx-padding:6 18;");
        saveBtn.setOnAction(e -> saveConfig());

        VBox weightBox = new VBox(6, weights, weightNote);
        weightBox.setPadding(new Insets(10));

        TitledPane weightPane   = titledPane("Objective Weights", weightBox);
        TitledPane postPane     = titledPane("Post-Call Incompatibility Rules", postCallContent);
        TitledPane workloadPane = titledPane("Workload Bounds", workload);
        TitledPane cpsatPane    = titledPane("CP-SAT Solver Parameters", cpsat);
        TitledPane linkPane     = buildLinkRulesPane();

        VBox form = new VBox(10, weightPane, postPane, workloadPane, cpsatPane, linkPane,
            new HBox(10, saveBtn));
        form.setPadding(new Insets(12));
        form.setPrefWidth(420);

        // ── Run history ────────────────────────────────────────────────────
        buildRunsTable();

        HBox histBar = new HBox(10, new Label("Year:"), historyYear,
            new Button("Load History") {{ setOnAction(e -> loadHistory()); }});
        histBar.setPadding(new Insets(8));
        histBar.setAlignment(Pos.CENTER_LEFT);

        VBox histBox = new VBox(0, histBar, runsTable);
        VBox.setVgrow(runsTable, Priority.ALWAYS);
        TitledPane histPane = titledPane("Solver Run History (Comparison)", histBox);
        histPane.setCollapsible(false);
        histPane.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(histPane, Priority.ALWAYS);

        VBox right = new VBox(histPane);
        right.setPadding(new Insets(12, 12, 12, 0));
        VBox.setVgrow(right, Priority.ALWAYS);

        SplitPane split = new SplitPane(
            new ScrollPane(form) {{ setFitToWidth(true); }},
            right);
        split.setDividerPositions(0.42);

        setCenter(split);
        setPadding(new Insets(5));
    }

    private VBox buildPostCallPanel() {
        Label intro = new Label(
            "Configure which rotations generate a post-call Monday obligation and which rotations\n" +
            "cannot absorb one.  The solver uses these to penalise or prohibit problematic sequences.");
        intro.setStyle("-fx-font-size:10px;-fx-text-fill:#444;");
        intro.setWrapText(true);

        // Trigger rotations
        Label trigLabel = new Label("Trigger rotations (generate post-call Monday):");
        trigLabel.setStyle("-fx-font-weight:bold;");
        Label trigHint = new Label("e.g. Infectious Disease, Inpatient GI");
        trigHint.setStyle("-fx-font-size:10px;-fx-text-fill:#666;");
        ScrollPane trigScroll = scrollPane(triggerBox);

        // Mandatory-attendance rotations
        Label mandLabel = new Label("Mandatory-attendance rotations (cannot absorb post-call Monday):");
        mandLabel.setStyle("-fx-font-weight:bold;");
        Label mandHint = new Label("e.g. ICU, Younker 7 Days, Broadlawns, VA, Younker 8 Pulmonology");
        mandHint.setStyle("-fx-font-size:10px;-fx-text-fill:#666;");
        ScrollPane mandScroll = scrollPane(mandatoryBox);

        // Discouraged rotations
        Label discLabel = new Label("Discouraged (but feasible) rotations after a trigger:");
        discLabel.setStyle("-fx-font-weight:bold;");
        Label discHint = new Label("e.g. Younker 7 Nights — night-to-night is workable but not ideal");
        discHint.setStyle("-fx-font-size:10px;-fx-text-fill:#666;");
        ScrollPane discScroll = scrollPane(discouragedBox);

        VBox content = new VBox(6,
            intro,
            trigLabel, trigHint, trigScroll,
            mandLabel, mandHint, mandScroll,
            discLabel, discHint, discScroll);
        content.setPadding(new Insets(10));
        return content;
    }

    private static ScrollPane scrollPane(VBox box) {
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setPrefHeight(90);
        sp.setStyle("-fx-background-color:transparent;");
        return sp;
    }

    /** Populates the three checkbox groups with all rotations. Called once at startup. */
    private void loadRotationsForPostCallPanel() {
        try {
            List<Rotation> rotations = rotationDAO.getAll();
            allRotIds.clear();
            triggerCbs.clear();
            mandatoryCbs.clear();
            discouragedCbs.clear();
            triggerBox.getChildren().clear();
            mandatoryBox.getChildren().clear();
            discouragedBox.getChildren().clear();

            for (Rotation rot : rotations) {
                allRotIds.add(rot.getId());

                CheckBox cb1 = new CheckBox(rot.getName());
                CheckBox cb2 = new CheckBox(rot.getName());
                CheckBox cb3 = new CheckBox(rot.getName());
                triggerCbs.add(cb1);
                mandatoryCbs.add(cb2);
                discouragedCbs.add(cb3);
                triggerBox.getChildren().add(cb1);
                mandatoryBox.getChildren().add(cb2);
                discouragedBox.getChildren().add(cb3);
            }
        } catch (SQLException e) {
            // Non-fatal — checkboxes just stay empty
        }
    }

    @SuppressWarnings("unchecked")
    private void buildRunsTable() {
        TableColumn<RunRecord, String> engineCol = new TableColumn<>("Engine");
        engineCol.setCellValueFactory(new PropertyValueFactory<>("engine"));
        engineCol.setPrefWidth(110);

        TableColumn<RunRecord, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setPrefWidth(90);

        TableColumn<RunRecord, String> feasCol = new TableColumn<>("Feasible");
        feasCol.setCellValueFactory(new PropertyValueFactory<>("feasible"));
        feasCol.setPrefWidth(70);

        TableColumn<RunRecord, String> scoreCol = new TableColumn<>("Score");
        scoreCol.setCellValueFactory(new PropertyValueFactory<>("score"));
        scoreCol.setPrefWidth(180);

        TableColumn<RunRecord, String> runtimeCol = new TableColumn<>("Runtime");
        runtimeCol.setCellValueFactory(new PropertyValueFactory<>("runtime"));
        runtimeCol.setPrefWidth(80);

        TableColumn<RunRecord, String> timeCol = new TableColumn<>("Run At");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("runAt"));
        timeCol.setPrefWidth(150);

        runsTable.getColumns().addAll(engineCol, statusCol, feasCol, scoreCol, runtimeCol, timeCol);
        runsTable.setPlaceholder(new Label("No solver runs recorded yet."));
        runsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        runsTable.setRowFactory(tv -> new TableRow<>() {
            @Override protected void updateItem(RunRecord item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) {
                    setStyle("");
                } else if ("✅".equals(item.getFeasible())) {
                    setStyle("-fx-background-color:#d4edda;");
                } else {
                    setStyle("-fx-background-color:#fff3cd;");
                }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Data loading / saving
    // ══════════════════════════════════════════════════════════════════════

    private void loadConfig() {
        try {
            ScheduleConfig cfg = dao.loadConfig();
            spUnder.getValueFactory().setValue(cfg.getWeightUndercoverage());
            spOver.getValueFactory().setValue(cfg.getWeightOvercoverage());
            spVar.getValueFactory().setValue(cfg.getWeightVariance());
            spPgy.getValueFactory().setValue(cfg.getWeightPgyImbalance());
            spFourPlusTwo.getValueFactory().setValue(cfg.getWeightFourPlusTwo());
            spInpSplit.getValueFactory().setValue(cfg.getWeightInpatientSplit());
            spPostCallHard.getValueFactory().setValue(cfg.getWeightPostCallHard());
            spPostCallSoft.getValueFactory().setValue(cfg.getWeightPostCallSoft());
            spMinLoad.getValueFactory().setValue(cfg.getGlobalMinWorkloadBlocks());
            spMaxLoad.getValueFactory().setValue(cfg.getGlobalMaxWorkloadBlocks());
            spWorkers.getValueFactory().setValue(cfg.getCpSatNumWorkers());
            spTimeLimit.getValueFactory().setValue(cfg.getCpSatTimeLimitSeconds());
            cbLog.setSelected(cfg.isCpSatLogSearch());

            // Apply post-call ID sets to checkboxes
            applyIdSetToCheckBoxes(cfg.getPostCallTriggerRotationIds(),     triggerCbs);
            applyIdSetToCheckBoxes(cfg.getMandatoryAttendanceRotationIds(), mandatoryCbs);
            applyIdSetToCheckBoxes(cfg.getDiscouragedAfterTriggerIds(),     discouragedCbs);
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
        }
    }

    private void applyIdSetToCheckBoxes(Set<Integer> ids, List<CheckBox> cbs) {
        for (int i = 0; i < cbs.size() && i < allRotIds.size(); i++) {
            cbs.get(i).setSelected(ids.contains(allRotIds.get(i)));
        }
    }

    private void saveConfig() {
        try {
            ScheduleConfig cfg = new ScheduleConfig();
            cfg.setWeightUndercoverage(spUnder.getValue());
            cfg.setWeightOvercoverage(spOver.getValue());
            cfg.setWeightVariance(spVar.getValue());
            cfg.setWeightPgyImbalance(spPgy.getValue());
            cfg.setWeightFourPlusTwo(spFourPlusTwo.getValue());
            cfg.setWeightInpatientSplit(spInpSplit.getValue());
            cfg.setWeightPostCallHard(spPostCallHard.getValue());
            cfg.setWeightPostCallSoft(spPostCallSoft.getValue());
            cfg.setGlobalMinWorkloadBlocks(spMinLoad.getValue());
            cfg.setGlobalMaxWorkloadBlocks(spMaxLoad.getValue());
            cfg.setCpSatNumWorkers(spWorkers.getValue());
            cfg.setCpSatTimeLimitSeconds(spTimeLimit.getValue());
            cfg.setCpSatLogSearch(cbLog.isSelected());
            cfg.setPostCallTriggerRotationIds(collectCheckedIds(triggerCbs));
            cfg.setMandatoryAttendanceRotationIds(collectCheckedIds(mandatoryCbs));
            cfg.setDiscouragedAfterTriggerIds(collectCheckedIds(discouragedCbs));
            dao.saveConfig(cfg);
            new Alert(Alert.AlertType.INFORMATION, "Settings saved.", ButtonType.OK).showAndWait();
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
        }
    }

    private Set<Integer> collectCheckedIds(List<CheckBox> cbs) {
        Set<Integer> result = new HashSet<>();
        for (int i = 0; i < cbs.size() && i < allRotIds.size(); i++) {
            if (cbs.get(i).isSelected()) result.add(allRotIds.get(i));
        }
        return result;
    }

    private void loadYears() {
        try {
            List<Integer> years = new BlockDAO().getDistinctYears();
            historyYear.getItems().setAll(years);
            if (!years.isEmpty()) historyYear.setValue(years.get(0));
        } catch (SQLException e) { /* ignore */ }
    }

    private void loadHistory() {
        Integer year = historyYear.getValue();
        if (year == null) return;
        try {
            List<RunRecord> records = new ArrayList<>();
            ResultSet rs = dao.getRecentRuns(year);
            while (rs.next()) {
                String hard   = rs.getObject("hard_score")   == null ? "—" : String.valueOf(rs.getInt("hard_score"));
                String medium = rs.getObject("medium_score") == null ? "—" : String.valueOf(rs.getInt("medium_score"));
                String soft   = rs.getObject("soft_score")   == null ? "—" : String.valueOf(rs.getInt("soft_score"));
                String score   = "H:" + hard + "  M:" + medium + "  S:" + soft;
                String runtime = rs.getInt("runtime_ms") + "ms";
                records.add(new RunRecord(
                    rs.getString("engine"),
                    rs.getString("status"),
                    rs.getInt("feasible") == 1 ? "✅" : "⚠",
                    score, runtime,
                    rs.getString("run_at")));
            }
            runsTable.setItems(FXCollections.observableArrayList(records));
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Linked rotation sum rules panel
    // ══════════════════════════════════════════════════════════════════════

    private TitledPane buildLinkRulesPane() {
        Label intro = new Label(
            "For each rule: every resident's total blocks on Rotation A + Rotation B must equal the sum.\n" +
            "Example: Y7N + Elective = 2 per resident, with exactly 2 total elective blocks across all residents.");
        intro.setStyle("-fx-font-size:10px;-fx-text-fill:#444;");
        intro.setWrapText(true);

        linkRotA.setPromptText("Rotation A");
        linkRotB.setPromptText("Rotation B");
        linkRotA.setPrefWidth(160);
        linkRotB.setPrefWidth(160);
        linkSum.setEditable(true);         linkSum.setPrefWidth(65);
        linkGlobalTotal.setEditable(true); linkGlobalTotal.setPrefWidth(65);

        linkRotA.setConverter(rotationStringConverter());
        linkRotB.setConverter(rotationStringConverter());

        Button addBtn = new Button("Add Rule");
        addBtn.setStyle("-fx-background-color:#1e3c72;-fx-text-fill:white;");
        addBtn.setOnAction(e -> addLinkRule());

        Button delBtn = new Button("Delete Selected");
        delBtn.setStyle("-fx-background-color:#8b1a1a;-fx-text-fill:white;");
        delBtn.setOnAction(e -> deleteLinkRule());

        GridPane addGrid = new GridPane();
        addGrid.setHgap(8); addGrid.setVgap(6); addGrid.setPadding(new Insets(6, 0, 6, 0));
        addGrid.add(new Label("Rotation A:"), 0, 0);      addGrid.add(linkRotA, 1, 0);
        addGrid.add(new Label("Rotation B:"), 2, 0);      addGrid.add(linkRotB, 3, 0);
        addGrid.add(new Label("Sum/resident:"), 0, 1);    addGrid.add(linkSum, 1, 1);
        addGrid.add(new Label("Global total B (0=off):"), 2, 1); addGrid.add(linkGlobalTotal, 3, 1);

        linkRulesList.setPrefHeight(90);
        linkRulesList.setPlaceholder(new Label("No linked rules defined."));

        VBox content = new VBox(8, intro, addGrid,
            new HBox(8, addBtn, delBtn), linkRulesList);
        content.setPadding(new Insets(10));

        TitledPane pane = new TitledPane("Linked Rotation Sum Rules", content);
        pane.setCollapsible(true);
        pane.setExpanded(false);
        return pane;
    }

    private javafx.util.StringConverter<Rotation> rotationStringConverter() {
        return new javafx.util.StringConverter<>() {
            @Override public String toString(Rotation r) { return r == null ? "" : r.getName(); }
            @Override public Rotation fromString(String s) { return null; }
        };
    }

    private void loadLinkRules() {
        try {
            List<Rotation> rotations = rotationDAO.getAll();
            linkRotA.getItems().setAll(rotations);
            linkRotB.getItems().setAll(rotations);

            loadedLinkRules.clear();
            loadedLinkRules.addAll(linkRuleDAO.getAll());
            refreshLinkRulesList(rotations);
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
        }
    }

    private void refreshLinkRulesList(List<Rotation> rotations) {
        java.util.Map<Integer, String> nameMap = new java.util.HashMap<>();
        for (Rotation r : rotations) nameMap.put(r.getId(), r.getName());
        linkRulesList.getItems().clear();
        for (RotationLinkRule rule : loadedLinkRules) {
            String a = nameMap.getOrDefault(rule.rotAId, "rot#" + rule.rotAId);
            String b = nameMap.getOrDefault(rule.rotBId, "rot#" + rule.rotBId);
            String global = rule.globalTotalForRotB > 0 ? "  |  total " + b + " = " + rule.globalTotalForRotB : "";
            linkRulesList.getItems().add(String.format("[%d] %s + %s = %d/resident%s",
                rule.id, a, b, rule.sumPerResident, global));
        }
    }

    private void addLinkRule() {
        Rotation rotA = linkRotA.getValue();
        Rotation rotB = linkRotB.getValue();
        if (rotA == null || rotB == null || rotA.getId() == rotB.getId()) {
            new Alert(Alert.AlertType.WARNING, "Select two different rotations.").showAndWait();
            return;
        }
        try {
            RotationLinkRule rule = new RotationLinkRule(
                rotA.getId(), rotB.getId(), linkSum.getValue(), linkGlobalTotal.getValue());
            linkRuleDAO.insert(rule);
            loadLinkRules();
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
        }
    }

    private void deleteLinkRule() {
        int idx = linkRulesList.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= loadedLinkRules.size()) return;
        RotationLinkRule rule = loadedLinkRules.get(idx);
        try {
            linkRuleDAO.delete(rule.id);
            loadLinkRules();
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private void addRow(GridPane grid, String label, Control ctrl, int row) {
        grid.add(new Label(label), 0, row);
        grid.add(ctrl, 1, row);
    }

    private TitledPane titledPane(String title, Region content) {
        TitledPane p = new TitledPane(title, content);
        p.setCollapsible(false);
        return p;
    }

    // ── RunRecord (TableView row model) ────────────────────────────────────
    public static class RunRecord {
        private final String engine, status, feasible, score, runtime, runAt;

        public RunRecord(String engine, String status, String feasible,
                         String score, String runtime, String runAt) {
            this.engine = engine; this.status = status;
            this.feasible = feasible; this.score = score;
            this.runtime = runtime; this.runAt = runAt;
        }

        public String getEngine()   { return engine; }
        public String getStatus()   { return status; }
        public String getFeasible() { return feasible; }
        public String getScore()    { return score; }
        public String getRuntime()  { return runtime; }
        public String getRunAt()    { return runAt; }
    }
}
