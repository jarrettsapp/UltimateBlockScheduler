package com.residency.ui;

import com.residency.db.RotationDAO;
import com.residency.model.Rotation;
import com.residency.model.RotationType;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;

import java.sql.SQLException;

public class RotationView extends BorderPane {

    private final RotationDAO dao;
    private final ObservableList<Rotation> data = FXCollections.observableArrayList();
    private final TableView<Rotation> table = new TableView<>();

    public RotationView() {
        try {
            dao = new RotationDAO();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        buildUI();
        refresh();
    }

    private void buildUI() {
        TableColumn<Rotation, String> nameCol = new TableColumn<>("Rotation Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(180);

        TableColumn<Rotation, String> deptCol = new TableColumn<>("Department");
        deptCol.setCellValueFactory(new PropertyValueFactory<>("department"));
        deptCol.setPrefWidth(150);

        TableColumn<Rotation, Integer> maxCol = new TableColumn<>("Max Residents/Block");
        maxCol.setCellValueFactory(new PropertyValueFactory<>("maxResidentsPerBlock"));
        maxCol.setPrefWidth(130);

        TableColumn<Rotation, String> minBlkCol = new TableColumn<>("Min Weeks");
        minBlkCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(
            String.valueOf(cellData.getValue().getMinBlocksRequired())));
        minBlkCol.setPrefWidth(80);

        TableColumn<Rotation, String> maxBlkCol = new TableColumn<>("Max Weeks");
        maxBlkCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(
            String.valueOf(cellData.getValue().getMaxBlocksAllowed())));
        maxBlkCol.setPrefWidth(80);
        // Min/Max are stored and entered in WEEKS (multiples of 2): a half-block = 2 weeks,
        // a full block = 4 weeks. The solver converts weeks -> 2-week slots (ScheduleUnits).

        TableColumn<Rotation, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(
            cellData.getValue().getRotationType().toString()));
        typeCol.setPrefWidth(90);

        table.getColumns().addAll(nameCol, deptCol, maxCol, minBlkCol, maxBlkCol, typeCol);
        table.setItems(data);
        table.setPlaceholder(new Label("No rotations defined yet."));

        // Form fields
        TextField nameField = new TextField(); nameField.setPromptText("e.g. Internal Medicine");
        TextField deptField = new TextField(); deptField.setPromptText("e.g. Medicine");
        ComboBox<RotationType> typeBox = new ComboBox<>();
        typeBox.getItems().addAll(RotationType.values());
        typeBox.setValue(RotationType.UNSPECIFIED);
        Spinner<Integer> maxPerBlock = new Spinner<>(1, 50, 5); maxPerBlock.setEditable(true);
        Tooltip maxPerBlockTip = new Tooltip(
            "Maximum number of residents that can be assigned to this rotation\n" +
            "simultaneously during any single scheduling block (e.g. 5 means\n" +
            "no more than 5 residents can be on this rotation at the same time).");
        Tooltip.install(maxPerBlock, maxPerBlockTip);
        Spinner<Integer> minWeeks = new Spinner<>(2, 52, 4, 2); minWeeks.setEditable(true);
        Spinner<Integer> maxWeeks = new Spinner<>(2, 52, 8, 2); maxWeeks.setEditable(true);
        Tooltip weeksTip = new Tooltip(
            "Total weeks a resident must spend on this rotation across the year,\n" +
            "in multiples of 2. A half-block = 2 weeks, a full block = 4 weeks.\n" +
            "The solver converts weeks into 2-week scheduling slots automatically.");
        Tooltip.install(minWeeks, weeksTip);
        Tooltip.install(maxWeeks, weeksTip);
        TextArea descArea = new TextArea(); descArea.setPromptText("Optional description");
        descArea.setPrefRowCount(3); descArea.setWrapText(true);

        RotationConfigPanel configPanel = new RotationConfigPanel();

        Button addBtn = new Button("Add Rotation");
        Button editBtn = new Button("Update Selected");
        Button deleteBtn = new Button("Delete Selected");

        addBtn.setOnAction(e -> {
            if (nameField.getText().trim().isEmpty()) { showError("Name required."); return; }
            try {
                Rotation r = new Rotation(0, nameField.getText().trim(), deptField.getText().trim(),
                    maxPerBlock.getValue(), minWeeks.getValue(), maxWeeks.getValue(), descArea.getText());
                r.setRotationType(typeBox.getValue() != null ? typeBox.getValue() : RotationType.UNSPECIFIED);
                dao.insert(r);
                clearForm(nameField, deptField, descArea);
                refresh();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        editBtn.setOnAction(e -> {
            Rotation sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) { showError("Select a rotation first."); return; }
            try {
                sel.setName(nameField.getText().trim());
                sel.setDepartment(deptField.getText().trim());
                sel.setMaxResidentsPerBlock(maxPerBlock.getValue());
                sel.setMinBlocksRequired(minWeeks.getValue());
                sel.setMaxBlocksAllowed(maxWeeks.getValue());
                sel.setDescription(descArea.getText());
                sel.setRotationType(typeBox.getValue() != null ? typeBox.getValue() : RotationType.UNSPECIFIED);
                dao.update(sel);
                refresh();
            } catch (SQLException ex) { showError(ex.getMessage()); }
        });

        deleteBtn.setOnAction(e -> {
            Rotation sel = table.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete rotation '" + sel.getName() + "'? All assignments will be removed.",
                ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    try { dao.delete(sel.getId()); refresh(); }
                    catch (SQLException ex) { showError(ex.getMessage()); }
                }
            });
        });

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                nameField.setText(sel.getName());
                deptField.setText(sel.getDepartment() != null ? sel.getDepartment() : "");
                maxPerBlock.getValueFactory().setValue(sel.getMaxResidentsPerBlock());
                minWeeks.getValueFactory().setValue(sel.getMinBlocksRequired());
                maxWeeks.getValueFactory().setValue(sel.getMaxBlocksAllowed());
                descArea.setText(sel.getDescription() != null ? sel.getDescription() : "");
                typeBox.setValue(sel.getRotationType());
                configPanel.loadFor(sel.getId());
            }
        });

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(8); form.setPadding(new Insets(10));

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(Region.USE_PREF_SIZE);
        col1.setPrefWidth(120);
        col1.setMaxWidth(Region.USE_PREF_SIZE);

        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);

        form.getColumnConstraints().addAll(col1, col2);

        form.add(new Label("Name:"), 0, 0);         form.add(nameField, 1, 0);
        form.add(new Label("Department:"), 0, 1);   form.add(deptField, 1, 1);
        form.add(new Label("Type:"), 0, 2);         form.add(typeBox, 1, 2);
        Label maxPerBlockLabel = new Label("Max Residents per Block:");
        Tooltip.install(maxPerBlockLabel, new Tooltip(
            "How many residents can be on this rotation at the same time.\n" +
            "Hover over the number field for more detail."));
        form.add(maxPerBlockLabel, 0, 3); form.add(maxPerBlock, 1, 3);
        form.add(new Label("Min Weeks Required:"), 0, 4);   form.add(minWeeks, 1, 4);
        form.add(new Label("Max Weeks Required:"), 0, 5);   form.add(maxWeeks, 1, 5);
        form.add(new Label("Description:"), 0, 6);  form.add(descArea, 1, 6);

        HBox buttons = new HBox(10, addBtn, editBtn, deleteBtn);
        buttons.setPadding(new Insets(0, 0, 0, 10));

        VBox right = new VBox(10, new Label("Rotation Details"), form, buttons, configPanel);
        right.setPadding(new Insets(10));
        right.setPrefWidth(500);
        right.getStyleClass().add("form-panel");

        setCenter(table);
        setRight(right);
        setPadding(new Insets(10));
    }

    private void clearForm(TextField name, TextField dept, TextArea desc) {
        name.clear(); dept.clear(); desc.clear();
    }

    private void refresh() {
        try { data.setAll(dao.getAll()); }
        catch (SQLException e) { showError(e.getMessage()); }
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
}
