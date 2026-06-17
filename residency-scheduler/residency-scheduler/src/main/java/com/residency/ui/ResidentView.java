package com.residency.ui;

import com.residency.db.AuxFillerRotationDAO;
import com.residency.db.ResidentDAO;
import com.residency.db.RotationDAO;
import com.residency.model.Resident;
import com.residency.model.Rotation;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ResidentView extends BorderPane {

    private final ResidentDAO dao;
    private final RotationDAO rotationDAO;
    private final AuxFillerRotationDAO fillerDAO;

    // Residents tab
    private final ObservableList<Resident> residentData = FXCollections.observableArrayList();
    private final TableView<Resident> residentTable = new TableView<>();

    // Filler rotations tab
    private record FillerEntry(String group, int rotationId, String rotationName) {}
    private final ObservableList<FillerEntry> fillerData = FXCollections.observableArrayList();
    private final TableView<FillerEntry> fillerTable = new TableView<>();

    public ResidentView() {
        try {
            dao         = new ResidentDAO();
            rotationDAO = new RotationDAO();
            fillerDAO   = new AuxFillerRotationDAO();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
            new Tab("Residents",         buildResidentsPane()),
            new Tab("Filler Rotations",  buildFillerPane())
        );
        setCenter(tabs);
        setPadding(new Insets(10));
        refreshResidents();
        refreshFiller();
    }

    // ── Residents tab ──────────────────────────────────────────────────────

    private BorderPane buildResidentsPane() {
        TableColumn<Resident, String>  nameCol  = new TableColumn<>("Name");
        TableColumn<Resident, Integer> pgyCol   = new TableColumn<>("PGY");
        TableColumn<Resident, String>  emailCol = new TableColumn<>("Email");
        TableColumn<Resident, String>  auxCol   = new TableColumn<>("Type");
        TableColumn<Resident, String>  groupCol = new TableColumn<>("Group");

        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));   nameCol.setPrefWidth(200);
        pgyCol.setCellValueFactory(new PropertyValueFactory<>("pgyLevel")); pgyCol.setPrefWidth(60);
        emailCol.setCellValueFactory(new PropertyValueFactory<>("email"));  emailCol.setPrefWidth(220);
        auxCol.setCellValueFactory(cd -> new SimpleStringProperty(
            cd.getValue().isAuxiliary() ? "Auxiliary" : "Main"));          auxCol.setPrefWidth(80);
        groupCol.setCellValueFactory(cd -> new SimpleStringProperty(
            cd.getValue().getResidentGroup() != null
                ? cd.getValue().getResidentGroup() : ""));                 groupCol.setPrefWidth(80);

        residentTable.getColumns().addAll(nameCol, pgyCol, emailCol, auxCol, groupCol);
        residentTable.setItems(residentData);
        residentTable.setPlaceholder(new Label("No residents added yet."));

        // Form fields
        TextField    nameField  = new TextField();  nameField.setPromptText("Full Name");
        Spinner<Integer> pgySpinner = new Spinner<>(1, 7, 1); pgySpinner.setEditable(true);
        TextField    emailField = new TextField();  emailField.setPromptText("email@hospital.org");
        CheckBox     auxBox     = new CheckBox("Auxiliary (manually scheduled)"); auxBox.setWrapText(true);
        TextField    groupField = new TextField();  groupField.setPromptText("e.g. BMC, TY (blank for none)");

        Button addBtn    = new Button("Add");
        Button updateBtn = new Button("Update Selected");
        Button deleteBtn = new Button("Delete Selected");

        addBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { showError("Name is required."); return; }
            try {
                Resident r = new Resident(0, name, pgySpinner.getValue(), emailField.getText().trim());
                r.setAuxiliary(auxBox.isSelected());
                String grp = groupField.getText().trim();
                r.setResidentGroup(grp.isEmpty() ? null : grp);
                dao.insert(r);
                nameField.clear(); emailField.clear(); groupField.clear(); auxBox.setSelected(false);
                refreshResidents();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        updateBtn.setOnAction(e -> {
            Resident sel = residentTable.getSelectionModel().getSelectedItem();
            if (sel == null) { showError("Select a resident to update."); return; }
            try {
                sel.setName(nameField.getText().trim());
                sel.setPgyLevel(pgySpinner.getValue());
                sel.setEmail(emailField.getText().trim());
                sel.setAuxiliary(auxBox.isSelected());
                String grp = groupField.getText().trim();
                sel.setResidentGroup(grp.isEmpty() ? null : grp);
                dao.update(sel);
                refreshResidents();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        deleteBtn.setOnAction(e -> {
            Resident sel = residentTable.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + sel.getName() + "? This also removes all their assignments.",
                ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    try { dao.delete(sel.getId()); refreshResidents(); }
                    catch (SQLException ex) { showError(ex.getMessage()); }
                }
            });
        });

        residentTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel == null) return;
            nameField.setText(sel.getName());
            pgySpinner.getValueFactory().setValue(sel.getPgyLevel());
            emailField.setText(sel.getEmail() != null ? sel.getEmail() : "");
            auxBox.setSelected(sel.isAuxiliary());
            groupField.setText(sel.getResidentGroup() != null ? sel.getResidentGroup() : "");
        });

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8); form.setPadding(new Insets(10));
        form.add(new Label("Name:"),     0, 0); form.add(nameField,  1, 0);
        form.add(new Label("PGY:"),      0, 1); form.add(pgySpinner, 1, 1);
        form.add(new Label("Email:"),    0, 2); form.add(emailField, 1, 2);
        form.add(auxBox,                 0, 3, 2, 1);
        form.add(new Label("Group:"),    0, 4); form.add(groupField, 1, 4);

        VBox right = new VBox(10, new Label("Resident Details"), form,
            new HBox(10, addBtn, updateBtn, deleteBtn));
        right.setPadding(new Insets(10));
        right.setPrefWidth(380);
        right.getStyleClass().add("form-panel");

        BorderPane pane = new BorderPane();
        pane.setCenter(residentTable);
        pane.setRight(right);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ── Filler Rotations tab ───────────────────────────────────────────────

    private BorderPane buildFillerPane() {
        TableColumn<FillerEntry, String> grpCol  = new TableColumn<>("Group");
        TableColumn<FillerEntry, String> rotCol  = new TableColumn<>("Rotation");

        grpCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().group()));
        grpCol.setPrefWidth(120);
        rotCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().rotationName()));
        rotCol.setPrefWidth(300);

        fillerTable.getColumns().addAll(grpCol, rotCol);
        fillerTable.setItems(fillerData);
        fillerTable.setPlaceholder(new Label("No filler rotations configured."));

        // Form
        TextField groupInput = new TextField();
        groupInput.setPromptText("Group name, e.g. BMC");

        ComboBox<Rotation> rotationPicker = new ComboBox<>();
        rotationPicker.setPromptText("Select rotation…");
        rotationPicker.setPrefWidth(260);
        try {
            rotationPicker.getItems().setAll(rotationDAO.getAll());
        } catch (SQLException e) {
            showError(e.getMessage());
        }
        rotationPicker.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Rotation r, boolean empty) {
                super.updateItem(r, empty); setText(empty || r == null ? null : r.getName());
            }
        });
        rotationPicker.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Rotation r, boolean empty) {
                super.updateItem(r, empty); setText(empty || r == null ? null : r.getName());
            }
        });

        Button addBtn    = new Button("Add");
        Button removeBtn = new Button("Remove Selected");

        addBtn.setOnAction(e -> {
            String grp = groupInput.getText().trim();
            Rotation rot = rotationPicker.getValue();
            if (grp.isEmpty())  { showError("Group name is required."); return; }
            if (rot == null)    { showError("Select a rotation."); return; }
            try {
                fillerDAO.add(grp, rot.getId());
                groupInput.clear();
                rotationPicker.setValue(null);
                refreshFiller();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        removeBtn.setOnAction(e -> {
            FillerEntry sel = fillerTable.getSelectionModel().getSelectedItem();
            if (sel == null) { showError("Select a row to remove."); return; }
            try {
                fillerDAO.remove(sel.group(), sel.rotationId());
                refreshFiller();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        Label hint = new Label(
            "Filler rotations are excluded from the solver's pre-count. After solving, " +
            "residents of the specified group are auto-assigned to fill any blocks below minPerBlock.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11; -fx-text-fill: #555;");

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8); form.setPadding(new Insets(10));
        form.add(new Label("Group:"),    0, 0); form.add(groupInput,    1, 0);
        form.add(new Label("Rotation:"), 0, 1); form.add(rotationPicker, 1, 1);

        VBox right = new VBox(12, new Label("Add Filler Rotation"), form,
            new HBox(10, addBtn, removeBtn), hint);
        right.setPadding(new Insets(10));
        right.setPrefWidth(380);
        right.getStyleClass().add("form-panel");

        BorderPane pane = new BorderPane();
        pane.setCenter(fillerTable);
        pane.setRight(right);
        pane.setPadding(new Insets(8));
        return pane;
    }

    // ── Refresh helpers ────────────────────────────────────────────────────

    private void refreshResidents() {
        try { residentData.setAll(dao.getAll()); }
        catch (SQLException e) { showError(e.getMessage()); }
    }

    private void refreshFiller() {
        try {
            List<Rotation> rotations = rotationDAO.getAll();
            Map<Integer, String> rotNames = new java.util.HashMap<>();
            for (Rotation r : rotations) rotNames.put(r.getId(), r.getName());

            fillerData.clear();
            Map<String, Set<Integer>> all = fillerDAO.getAllFillerRotations();
            for (Map.Entry<String, Set<Integer>> entry : all.entrySet()) {
                for (int rotId : entry.getValue()) {
                    fillerData.add(new FillerEntry(
                        entry.getKey(), rotId,
                        rotNames.getOrDefault(rotId, "Rotation #" + rotId)));
                }
            }
        } catch (SQLException e) { showError(e.getMessage()); }
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
}
