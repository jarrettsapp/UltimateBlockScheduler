package com.residency.ui;

import com.residency.db.*;
import com.residency.export.ExportService;
import com.residency.model.*;
import com.residency.service.SchedulingService;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

public class ScheduleView extends BorderPane {

    private final ResidentDAO residentDAO;
    private final RotationDAO rotationDAO;
    private final BlockDAO blockDAO;
    private final AssignmentDAO assignmentDAO;
    private final SchedulingService schedulingService;

    private final ComboBox<Integer> yearPicker = new ComboBox<>();
    private final ScrollPane gridScroll = new ScrollPane();
    private int currentYear = -1;

    private List<Resident> residents = new ArrayList<>();
    private List<Block> blocks = new ArrayList<>();
    private List<Rotation> rotations = new ArrayList<>();
    // residentId -> blockId -> assignment
    private Map<Integer, Map<Integer, Assignment>> assignmentGrid = new HashMap<>();

    public ScheduleView() {
        try {
            residentDAO = new ResidentDAO();
            rotationDAO = new RotationDAO();
            blockDAO = new BlockDAO();
            assignmentDAO = new AssignmentDAO();
            schedulingService = new SchedulingService();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        buildUI();
    }

    private void buildUI() {
        // ── Top toolbar ───────────────────────────────────────────────────
        try {
            List<Integer> years = blockDAO.getDistinctYears();
            yearPicker.getItems().setAll(years);
            if (!years.isEmpty()) {
                yearPicker.setValue(years.get(0));
                currentYear = years.get(0);
                AppState.get().setSelectedYear(currentYear);
            }
        } catch (SQLException e) { showError(e.getMessage()); }

        yearPicker.setPromptText("Year...");
        yearPicker.setOnAction(e -> {
            if (yearPicker.getValue() != null) {
                currentYear = yearPicker.getValue();
                AppState.get().setSelectedYear(currentYear);
                loadGrid();
            }
        });

        TextField newYearField = new TextField();
        newYearField.setPromptText("e.g. 2025");
        newYearField.setPrefWidth(80);

        Button genYearBtn = new Button("Generate Year");
        genYearBtn.setOnAction(e -> {
            try {
                String txt = newYearField.getText().trim();
                if (txt.isEmpty()) { showError("Enter a year."); return; }
                int y = Integer.parseInt(txt);
                blockDAO.generateBlocksForYear(y);
                List<Integer> years = blockDAO.getDistinctYears();
                yearPicker.getItems().setAll(years);
                yearPicker.setValue(y);
                currentYear = y;
                AppState.get().setSelectedYear(currentYear);
                newYearField.clear();
                loadGrid();
            } catch (NumberFormatException ex) {
                showError("Enter a valid 4-digit year.");
            } catch (SQLException ex) {
                showError(ex.getMessage());
            }
        });

        Button deleteYearBtn = new Button("🗑 Delete Year");
        deleteYearBtn.setStyle("-fx-background-color:#8b1a1a;-fx-text-fill:white;");
        deleteYearBtn.setOnAction(e -> {
            if (currentYear < 0) { showError("No year selected."); return; }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete year " + currentYear + "?\n\nThis will permanently remove all blocks and assignments for this year.",
                ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Delete Year");
            confirm.setHeaderText("This cannot be undone.");
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.YES) {
                    try {
                        blockDAO.deleteYear(currentYear);
                        List<Integer> years = blockDAO.getDistinctYears();
                        yearPicker.getItems().setAll(years);
                        if (!years.isEmpty()) {
                            yearPicker.setValue(years.get(0));
                            currentYear = years.get(0);
                            AppState.get().setSelectedYear(currentYear);
                            loadGrid();
                        } else {
                            currentYear = -1;
                            AppState.get().setSelectedYear(-1);
                            gridScroll.setContent(new Label("Generate or select a year to view the schedule grid."));
                        }
                    } catch (SQLException ex) { showError(ex.getMessage()); }
                }
            });
        });

        Button exportPdfBtn = new Button("Export PDF");
        exportPdfBtn.setOnAction(e -> exportPdf());

        Button exportXlsBtn = new Button("Export Excel");
        exportXlsBtn.setOnAction(e -> exportExcel());

        Button refreshBtn = new Button("↻ Refresh");
        refreshBtn.setOnAction(e -> {
            try {
                Integer saved = currentYear > 0 ? currentYear : yearPicker.getValue();
                List<Integer> years = blockDAO.getDistinctYears();
                yearPicker.getItems().setAll(years);
                if (saved != null && years.contains(saved)) {
                    yearPicker.setValue(saved);
                    currentYear = saved;
                } else if (!years.isEmpty()) {
                    yearPicker.setValue(years.get(0));
                    currentYear = years.get(0);
                }
                AppState.get().setSelectedYear(currentYear);
            } catch (SQLException ex) { showError(ex.getMessage()); }
            loadGrid();
        });

        HBox toolbar = new HBox(10,
            new Label("Year:"), yearPicker,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            newYearField, genYearBtn,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            refreshBtn, deleteYearBtn,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            exportPdfBtn, exportXlsBtn
        );
        toolbar.setPadding(new Insets(8, 10, 8, 10));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("toolbar");

        // ── Grid area ─────────────────────────────────────────────────────
        gridScroll.setFitToWidth(false);
        gridScroll.setFitToHeight(false);

        Label hint = new Label("Generate or select a year to view the schedule grid.");
        hint.setStyle("-fx-font-size: 14px; -fx-text-fill: #888;");
        gridScroll.setContent(hint);

        setTop(toolbar);
        setCenter(gridScroll);
        setPadding(new Insets(5));

        if (currentYear > 0) loadGrid();
    }

    private void loadGrid() {
        if (yearPicker.getValue() != null) currentYear = yearPicker.getValue();
        if (currentYear < 0) return;
        try {
            residents = residentDAO.getAll();
            blocks = blockDAO.getByYear(currentYear);
            rotations = rotationDAO.getAll();

            List<Assignment> assignments = assignmentDAO.getByYear(currentYear);
            assignmentGrid.clear();
            for (Assignment a : assignments) {
                assignmentGrid
                    .computeIfAbsent(a.getResidentId(), k -> new HashMap<>())
                    .put(a.getBlockId(), a);
            }

            buildGrid();
        } catch (SQLException e) {
            showError(e.getMessage());
        }
    }

    private void buildGrid() {
        if (blocks.isEmpty()) {
            gridScroll.setContent(new Label("No blocks found for year " + currentYear + ". Click 'Generate Year'."));
            return;
        }

        // Outer grid: rows = residents, cols = blocks
        GridPane grid = new GridPane();
        grid.setHgap(2);
        grid.setVgap(2);
        grid.setPadding(new Insets(10));

        // ── Corner cell ───────────────────────────────────────────────────
        Label corner = new Label("Resident / Block");
        corner.setStyle("-fx-font-weight: bold; -fx-padding: 6 10; -fx-background-color: #1e3c72; -fx-text-fill: white; -fx-background-radius: 3;");
        corner.setMinWidth(180);
        grid.add(corner, 0, 0);

        // ── Block header row ──────────────────────────────────────────────
        for (int col = 0; col < blocks.size(); col++) {
            Block b = blocks.get(col);
            VBox header = new VBox(1);
            header.setAlignment(Pos.CENTER);
            header.setStyle("-fx-background-color: #1e3c72; -fx-padding: 4 6; -fx-background-radius: 3;");

            Label blkLbl = new Label(b.getLabel());
            blkLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: white; -fx-font-size: 11px;");

            Label dateLbl = new Label(b.getStartDate() != null
                ? b.getStartDate().getMonthValue() + "/" + b.getStartDate().getDayOfMonth()
                : "");
            dateLbl.setStyle("-fx-text-fill: #aac4ff; -fx-font-size: 9px;");

            header.getChildren().addAll(blkLbl, dateLbl);
            header.setMinWidth(110);
            grid.add(header, col + 1, 0);
        }

        // ── Resident rows ─────────────────────────────────────────────────
        for (int row = 0; row < residents.size(); row++) {
            Resident r = residents.get(row);
            boolean alt = row % 2 == 1;

            // Name cell
            VBox nameCell = new VBox(1);
            nameCell.setAlignment(Pos.CENTER_LEFT);
            nameCell.setPadding(new Insets(4, 8, 4, 8));
            nameCell.setStyle("-fx-background-color: " + (alt ? "#f0f4ff" : "#ffffff") + "; -fx-background-radius: 2;");
            nameCell.setMinWidth(180);

            Label nameLbl = new Label(r.getName());
            nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            Label pgyLbl = new Label("PGY-" + r.getPgyLevel());
            pgyLbl.setStyle("-fx-text-fill: #666; -fx-font-size: 10px;");
            nameCell.getChildren().addAll(nameLbl, pgyLbl);
            grid.add(nameCell, 0, row + 1);

            // Block cells
            Map<Integer, Assignment> residentAssignments = assignmentGrid.getOrDefault(r.getId(), Collections.emptyMap());

            for (int col = 0; col < blocks.size(); col++) {
                Block b = blocks.get(col);
                Assignment existing = residentAssignments.get(b.getId());

                VBox cell = buildAssignmentCell(r, b, existing, alt);
                grid.add(cell, col + 1, row + 1);
            }
        }

        if (residents.isEmpty()) {
            Label empty = new Label("No residents found. Add residents in the Residents tab.");
            empty.setStyle("-fx-font-size: 13px; -fx-text-fill: #888;");
            grid.add(empty, 0, 1, blocks.size() + 1, 1);
        }

        gridScroll.setContent(grid);
    }

    private VBox buildAssignmentCell(Resident resident, Block block, Assignment existing, boolean alt) {
        VBox cell = new VBox(2);
        cell.setAlignment(Pos.CENTER);
        cell.setMinWidth(110);
        cell.setMinHeight(52);
        cell.setPadding(new Insets(3, 5, 3, 5));
        cell.setStyle("-fx-background-color: " + (alt ? "#f8faff" : "#ffffff") + "; -fx-background-radius: 3; -fx-border-color: #e0e4ef; -fx-border-radius: 3;");

        ComboBox<Rotation> picker = new ComboBox<>();
        picker.getItems().add(null); // blank = unassigned
        picker.getItems().addAll(rotations);
        picker.setValue(existing != null
            ? rotations.stream().filter(rt -> rt.getId() == existing.getRotationId()).findFirst().orElse(null)
            : null);

        // Custom cell factory to show rotation name or "—"
        picker.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Rotation item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : (item == null ? "— Unassigned —" : item.getName()));
            }
        });
        picker.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(Rotation item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : (item == null ? "" : item.getName()));
                setStyle(item == null ? "-fx-text-fill: #bbb;" : "-fx-font-size: 10px;");
            }
        });
        picker.setPrefWidth(104);
        picker.setStyle("-fx-font-size: 10px;");

        // Warning indicator if override was used
        if (existing != null && existing.isOverrideWarning()) {
            Label warn = new Label("⚠ override");
            warn.setStyle("-fx-text-fill: #e07b00; -fx-font-size: 8px;");
            cell.getChildren().add(warn);
        }

        picker.setOnAction(e -> handleAssignmentChange(resident, block, picker.getValue(), picker));

        cell.getChildren().add(picker);
        return cell;
    }

    private void handleAssignmentChange(Resident resident, Block block, Rotation selectedRotation, ComboBox<Rotation> picker) {
        try {
            if (selectedRotation == null) {
                // Unassign
                Map<Integer, Assignment> resMap = assignmentGrid.getOrDefault(resident.getId(), Collections.emptyMap());
                Assignment existing = resMap.get(block.getId());
                if (existing != null) {
                    schedulingService.unassign(existing.getId());
                    resMap.remove(block.getId());
                }
                return;
            }

            // Validate
            ValidationResult result = schedulingService.validate(
                resident.getId(), selectedRotation.getId(), block.getId(), block.getBlockNumber(), currentYear);

            boolean proceed = true;
            boolean override = false;

            if (result.hasIssues()) {
                StringBuilder sb = new StringBuilder();
                sb.append("The following warnings were found:\n\n");
                for (ValidationResult.Issue issue : result.getIssues()) {
                    sb.append("• ").append(issue.getMessage()).append("\n");
                }
                sb.append("\nDo you want to proceed anyway?");

                Alert alert = new Alert(Alert.AlertType.WARNING, sb.toString(), ButtonType.YES, ButtonType.NO);
                alert.setTitle("Schedule Warning");
                alert.setHeaderText("Rule Violations Detected");
                Optional<ButtonType> response = alert.showAndWait();

                proceed = response.isPresent() && response.get() == ButtonType.YES;
                override = proceed;
            }

            if (proceed) {
                Assignment saved = schedulingService.assign(
                    resident.getId(), selectedRotation.getId(), block.getId(), override);
                saved.setRotationName(selectedRotation.getName());
                saved.setBlockNumber(block.getBlockNumber());
                saved.setScheduleYear(currentYear);
                saved.setResidentId(resident.getId());
                saved.setRotationId(selectedRotation.getId());
                saved.setBlockId(block.getId());
                assignmentGrid
                    .computeIfAbsent(resident.getId(), k -> new HashMap<>())
                    .put(block.getId(), saved);

                // Re-style the cell to show override flag if needed
                if (override) {
                    picker.setStyle("-fx-font-size: 10px; -fx-border-color: #e07b00; -fx-border-width: 1.5;");
                }
            } else {
                // Revert picker
                Map<Integer, Assignment> resMap = assignmentGrid.getOrDefault(resident.getId(), Collections.emptyMap());
                Assignment existing = resMap.get(block.getId());
                final Rotation revert = existing != null
                    ? rotations.stream().filter(rt -> rt.getId() == existing.getRotationId()).findFirst().orElse(null)
                    : null;
                picker.setValue(revert);
            }

        } catch (SQLException ex) {
            showError(ex.getMessage());
        }
    }

    private void exportPdf() {
        if (currentYear < 0) { showError("No year selected."); return; }
        FileChooser fc = new FileChooser();
        fc.setTitle("Save PDF");
        fc.setInitialFileName("schedule_" + currentYear + ".pdf");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        File file = fc.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        try {
            new ExportService().exportToPdf(currentYear, file.getAbsolutePath());
            new Alert(Alert.AlertType.INFORMATION, "PDF exported successfully.", ButtonType.OK).showAndWait();
        } catch (Exception e) { showError(e.getMessage()); }
    }

    private void exportExcel() {
        if (currentYear < 0) { showError("No year selected."); return; }
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Excel");
        fc.setInitialFileName("schedule_" + currentYear + ".xlsx");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Files", "*.xlsx"));
        File file = fc.showSaveDialog(getScene().getWindow());
        if (file == null) return;
        try {
            new ExportService().exportToExcel(currentYear, file.getAbsolutePath());
            new Alert(Alert.AlertType.INFORMATION, "Excel exported successfully.", ButtonType.OK).showAndWait();
        } catch (Exception e) { showError(e.getMessage()); }
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
}
