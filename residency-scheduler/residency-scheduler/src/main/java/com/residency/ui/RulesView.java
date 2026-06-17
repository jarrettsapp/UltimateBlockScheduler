package com.residency.ui;

import com.residency.db.ResidentDAO;
import com.residency.db.RotationDAO;
import com.residency.db.RulesDAO;
import com.residency.model.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.util.List;

public class RulesView extends BorderPane {

    private final RulesDAO rulesDAO;
    private final RotationDAO rotationDAO;
    private final ResidentDAO residentDAO;

    private final ComboBox<Rotation> rotationPicker = new ComboBox<>();
    private final ObservableList<RotationRequirement> reqData = FXCollections.observableArrayList();
    private final TableView<RotationRequirement> reqTable = new TableView<>();
    private final ObservableList<Prerequisite> prereqData = FXCollections.observableArrayList();
    private final TableView<Prerequisite> prereqTable = new TableView<>();
    private final ObservableList<RotationSequenceRule> sequenceData = FXCollections.observableArrayList();
    private final TableView<RotationSequenceRule> sequenceTable = new TableView<>();

    // Prereq display helpers
    private List<Rotation> allRotations;

    public RulesView() {
        try {
            rulesDAO = new RulesDAO();
            rotationDAO = new RotationDAO();
            residentDAO = new ResidentDAO();
            allRotations = rotationDAO.getAll();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        buildUI();
    }

    private void buildUI() {
        // Top: rotation selector
        rotationPicker.setPromptText("Select a rotation to configure...");
        rotationPicker.setPrefWidth(320);
        try {
            rotationPicker.getItems().setAll(rotationDAO.getAll());
        } catch (SQLException e) { showError(e.getMessage()); }

        Button refreshRotationsBtn = new Button("↻");
        refreshRotationsBtn.setTooltip(new Tooltip("Reload rotation list"));
        refreshRotationsBtn.setOnAction(e -> {
            try {
                allRotations = rotationDAO.getAll();
                rotationPicker.getItems().setAll(allRotations);
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        rotationPicker.setOnAction(e -> loadRulesForSelected());

        HBox topBar = new HBox(10, new Label("Rotation:"), rotationPicker, refreshRotationsBtn);
        topBar.setPadding(new Insets(10));

        // ── PGY Requirements Table ─────────────────────────────────────────
        TableColumn<RotationRequirement, Integer> pgyCol = new TableColumn<>("PGY Level");
        pgyCol.setCellValueFactory(d -> new javafx.beans.property.SimpleIntegerProperty(d.getValue().getPgyLevel()).asObject());
        pgyCol.setPrefWidth(80);

        TableColumn<RotationRequirement, Double> minCol = new TableColumn<>("Min Blocks");
        minCol.setCellValueFactory(d -> new javafx.beans.property.SimpleDoubleProperty(d.getValue().getMinBlocks()).asObject());
        minCol.setPrefWidth(90);

        TableColumn<RotationRequirement, Double> maxCol = new TableColumn<>("Max Blocks");
        maxCol.setCellValueFactory(d -> new javafx.beans.property.SimpleDoubleProperty(d.getValue().getMaxBlocks()).asObject());
        maxCol.setPrefWidth(90);

        TableColumn<RotationRequirement, Boolean> reqCol = new TableColumn<>("Required?");
        reqCol.setCellValueFactory(d -> new javafx.beans.property.SimpleBooleanProperty(d.getValue().isRequired()));
        reqCol.setPrefWidth(80);

        reqTable.getColumns().addAll(pgyCol, minCol, maxCol, reqCol);
        reqTable.setItems(reqData);
        reqTable.setPlaceholder(new Label("No PGY rules defined for this rotation."));
        reqTable.setPrefHeight(200);

        // PGY Requirement form
        Spinner<Integer> pgySpinner = new Spinner<>(1, 7, 1); pgySpinner.setEditable(true);
        Spinner<Double> minSpinner = new Spinner<>(0.0, 52.0, 0.0, 0.5); minSpinner.setEditable(true);
        Spinner<Double> maxSpinner = new Spinner<>(0.0, 52.0, 4.0, 0.5); maxSpinner.setEditable(true);
        CheckBox requiredCheck = new CheckBox("Required for this PGY level");

        Button saveReqBtn = new Button("Save Requirement");
        Button deleteReqBtn = new Button("Delete Selected");

        saveReqBtn.setOnAction(e -> {
            Rotation rot = rotationPicker.getValue();
            if (rot == null) { showError("Select a rotation first."); return; }
            try {
                RotationRequirement r = new RotationRequirement(0, rot.getId(),
                    pgySpinner.getValue(), minSpinner.getValue(), maxSpinner.getValue(),
                    requiredCheck.isSelected());
                rulesDAO.upsertRequirement(r);
                loadRulesForSelected();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        deleteReqBtn.setOnAction(e -> {
            RotationRequirement sel = reqTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            try { rulesDAO.deleteRequirement(sel.getId()); loadRulesForSelected(); }
            catch (SQLException ex) { showError(ex.getMessage()); }
        });

        reqTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                pgySpinner.getValueFactory().setValue(sel.getPgyLevel());
                minSpinner.getValueFactory().setValue(sel.getMinBlocks());
                maxSpinner.getValueFactory().setValue(sel.getMaxBlocks());
                requiredCheck.setSelected(sel.isRequired());
            }
        });

        GridPane reqForm = new GridPane();
        reqForm.setHgap(10); reqForm.setVgap(6); reqForm.setPadding(new Insets(8));
        reqForm.add(new Label("PGY Level:"), 0, 0);   reqForm.add(pgySpinner, 1, 0);
        reqForm.add(new Label("Min Blocks:"), 0, 1);  reqForm.add(minSpinner, 1, 1);
        reqForm.add(new Label("Max Blocks:"), 0, 2);  reqForm.add(maxSpinner, 1, 2);
        reqForm.add(requiredCheck, 1, 3);

        HBox reqBtns = new HBox(10, saveReqBtn, deleteReqBtn);

        TitledPane reqPane = new TitledPane("PGY-Level Requirements",
            new VBox(8, reqTable, reqForm, reqBtns));
        reqPane.setCollapsible(false);

        // ── Prerequisites Table ────────────────────────────────────────────
        TableColumn<Prerequisite, String> prereqRotCol = new TableColumn<>("This Rotation Requires...");
        prereqRotCol.setCellValueFactory(d -> {
            int id = d.getValue().getPrerequisiteRotationId();
            String name = allRotations.stream().filter(r -> r.getId() == id)
                .findFirst().map(Rotation::getName).orElse("ID:" + id);
            return new javafx.beans.property.SimpleStringProperty(name);
        });
        prereqRotCol.setPrefWidth(220);

        TableColumn<Prerequisite, String> prereqPgyCol = new TableColumn<>("PGY Filter");
        prereqPgyCol.setCellValueFactory(d -> {
            Integer pgy = d.getValue().getPgyLevel();
            return new javafx.beans.property.SimpleStringProperty(pgy == null ? "All" : "PGY-" + pgy);
        });
        prereqPgyCol.setPrefWidth(80);

        prereqTable.getColumns().addAll(prereqRotCol, prereqPgyCol);
        prereqTable.setItems(prereqData);
        prereqTable.setPlaceholder(new Label("No prerequisites defined."));
        prereqTable.setPrefHeight(180);

        ComboBox<Rotation> prereqRotPicker = new ComboBox<>();
        prereqRotPicker.setPromptText("Must complete first...");
        prereqRotPicker.setPrefWidth(240);
        try { prereqRotPicker.getItems().setAll(rotationDAO.getAll()); }
        catch (SQLException ex) { showError(ex.getMessage()); }

        ComboBox<String> prereqPgyFilter = new ComboBox<>();
        prereqPgyFilter.getItems().addAll("All PGY Levels", "PGY-1", "PGY-2", "PGY-3", "PGY-4", "PGY-5");
        prereqPgyFilter.setValue("All PGY Levels");

        Button addPrereqBtn = new Button("Add Prerequisite");
        Button deletePrereqBtn = new Button("Delete Selected");

        addPrereqBtn.setOnAction(e -> {
            Rotation rot = rotationPicker.getValue();
            Rotation prereq = prereqRotPicker.getValue();
            if (rot == null || prereq == null) { showError("Select both rotations."); return; }
            if (rot.getId() == prereq.getId()) { showError("A rotation cannot be its own prerequisite."); return; }
            try {
                String pgyVal = prereqPgyFilter.getValue();
                Integer pgy = pgyVal.equals("All PGY Levels") ? null : Integer.parseInt(pgyVal.replace("PGY-", ""));
                Prerequisite p = new Prerequisite(0, rot.getId(), prereq.getId(), pgy);
                rulesDAO.insertPrerequisite(p);
                loadRulesForSelected();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        deletePrereqBtn.setOnAction(e -> {
            Prerequisite sel = prereqTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            try { rulesDAO.deletePrerequisite(sel.getId()); loadRulesForSelected(); }
            catch (SQLException ex) { showError(ex.getMessage()); }
        });

        GridPane prereqForm = new GridPane();
        prereqForm.setHgap(10); prereqForm.setVgap(6); prereqForm.setPadding(new Insets(8));
        prereqForm.add(new Label("Must complete:"), 0, 0); prereqForm.add(prereqRotPicker, 1, 0);
        prereqForm.add(new Label("PGY Filter:"), 0, 1);    prereqForm.add(prereqPgyFilter, 1, 1);

        HBox prereqBtns = new HBox(10, addPrereqBtn, deletePrereqBtn);

        TitledPane prereqPane = new TitledPane("Prerequisite Ordering (A must precede B)",
            new VBox(8, prereqTable, prereqForm, prereqBtns));
        prereqPane.setCollapsible(false);

        // —— Sequence / adjacency rules ————————————————————————————————————————
        TableColumn<RotationSequenceRule, String> seqTypeCol = new TableColumn<>("Rule");
        seqTypeCol.setCellValueFactory(d ->
            new javafx.beans.property.SimpleStringProperty(d.getValue().getRuleType().getDisplayName()));
        seqTypeCol.setPrefWidth(170);

        TableColumn<RotationSequenceRule, String> seqRotCol = new TableColumn<>("Related Rotation");
        seqRotCol.setCellValueFactory(d -> {
            int id = d.getValue().getRelatedRotationId();
            String name = allRotations.stream().filter(r -> r.getId() == id)
                .findFirst().map(Rotation::getName).orElse("ID:" + id);
            return new javafx.beans.property.SimpleStringProperty(name);
        });
        seqRotCol.setPrefWidth(220);

        TableColumn<RotationSequenceRule, String> seqPgyCol = new TableColumn<>("PGY Filter");
        seqPgyCol.setCellValueFactory(d -> {
            Integer pgy = d.getValue().getPgyLevel();
            return new javafx.beans.property.SimpleStringProperty(pgy == null ? "All" : "PGY-" + pgy);
        });
        seqPgyCol.setPrefWidth(80);

        sequenceTable.getColumns().addAll(seqTypeCol, seqRotCol, seqPgyCol);
        sequenceTable.setItems(sequenceData);
        sequenceTable.setPlaceholder(new Label("No sequence or adjacency rules defined."));
        sequenceTable.setPrefHeight(180);

        ComboBox<RotationSequenceRuleType> seqTypePicker = new ComboBox<>();
        seqTypePicker.getItems().setAll(RotationSequenceRuleType.values());
        seqTypePicker.setValue(RotationSequenceRuleType.CANNOT_IMMEDIATELY_FOLLOW);
        seqTypePicker.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(RotationSequenceRuleType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayName());
            }
        });
        seqTypePicker.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(RotationSequenceRuleType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayName());
            }
        });

        ComboBox<Rotation> seqRotPicker = new ComboBox<>();
        seqRotPicker.setPromptText("Related rotation...");
        seqRotPicker.setPrefWidth(240);
        seqRotPicker.getItems().setAll(allRotations);

        ComboBox<String> seqPgyFilter = new ComboBox<>();
        seqPgyFilter.getItems().addAll("All PGY Levels", "PGY-1", "PGY-2", "PGY-3", "PGY-4", "PGY-5");
        seqPgyFilter.setValue("All PGY Levels");

        Button addSeqBtn = new Button("Add Rule");
        Button deleteSeqBtn = new Button("Delete Selected");

        addSeqBtn.setOnAction(e -> {
            Rotation rot = rotationPicker.getValue();
            Rotation related = seqRotPicker.getValue();
            RotationSequenceRuleType type = seqTypePicker.getValue();
            if (rot == null || related == null || type == null) { showError("Select the rotation, rule type, and related rotation."); return; }
            if (rot.getId() == related.getId()) { showError("A rotation cannot reference itself."); return; }
            try {
                String pgyVal = seqPgyFilter.getValue();
                Integer pgy = pgyVal.equals("All PGY Levels") ? null : Integer.parseInt(pgyVal.replace("PGY-", ""));
                RotationSequenceRule rule = new RotationSequenceRule(0, rot.getId(), related.getId(), type, pgy);
                rulesDAO.insertSequenceRule(rule);
                loadRulesForSelected();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        deleteSeqBtn.setOnAction(e -> {
            RotationSequenceRule sel = sequenceTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            try { rulesDAO.deleteSequenceRule(sel.getId()); loadRulesForSelected(); }
            catch (SQLException ex) { showError(ex.getMessage()); }
        });

        GridPane seqForm = new GridPane();
        seqForm.setHgap(10); seqForm.setVgap(6); seqForm.setPadding(new Insets(8));
        seqForm.add(new Label("Rule Type:"), 0, 0);      seqForm.add(seqTypePicker, 1, 0);
        seqForm.add(new Label("Related Rotation:"), 0, 1); seqForm.add(seqRotPicker, 1, 1);
        seqForm.add(new Label("PGY Filter:"), 0, 2);     seqForm.add(seqPgyFilter, 1, 2);

        Label seqHelp = new Label(
            "Use 'Must be after' for order rules like \"Night Medicine must come after ICU if both occur\".\n" +
            "Use 'Cannot immediately follow' for adjacency rules like \"Night Medicine cannot directly follow GI\".");
        seqHelp.setWrapText(true);
        seqHelp.setStyle("-fx-text-fill:#555; -fx-font-size:11px;");

        TitledPane sequencePane = new TitledPane("Sequence / Adjacency Rules",
            new VBox(8, sequenceTable, seqHelp, seqForm, new HBox(10, addSeqBtn, deleteSeqBtn)));
        sequencePane.setCollapsible(false);

        VBox content = new VBox(12, reqPane, prereqPane, sequencePane);
        content.setPadding(new Insets(10));

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        setTop(topBar);
        setCenter(scroll);
        setPadding(new Insets(5));
    }

    private void loadRulesForSelected() {
        Rotation rot = rotationPicker.getValue();
        if (rot == null) return;
        try {
            reqData.setAll(rulesDAO.getRequirementsByRotation(rot.getId()));
            prereqData.setAll(rulesDAO.getPrerequisitesByRotation(rot.getId()));
            sequenceData.setAll(rulesDAO.getSequenceRulesByRotation(rot.getId()));
        } catch (SQLException e) { showError(e.getMessage()); }
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
}
