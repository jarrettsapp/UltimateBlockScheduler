package com.residency.ui;

import com.residency.db.AssignmentDAO;
import com.residency.db.BlockDAO;
import com.residency.db.ResidentDAO;
import com.residency.db.RotationDAO;
import com.residency.db.RulesDAO;
import com.residency.model.*;
import com.residency.service.SchedulingService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.sql.SQLException;
import java.util.*;

public class ComplianceView extends BorderPane {

    private final ResidentDAO residentDAO;
    private final RotationDAO rotationDAO;
    private final RulesDAO rulesDAO;
    private final AssignmentDAO assignmentDAO;
    private final SchedulingService schedulingService;

    private final ComboBox<Integer> yearPicker = new ComboBox<>();
    private final VBox reportBox = new VBox(8);

    public ComplianceView() {
        try {
            residentDAO = new ResidentDAO();
            rotationDAO = new RotationDAO();
            rulesDAO = new RulesDAO();
            assignmentDAO = new AssignmentDAO();
            schedulingService = new SchedulingService();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        buildUI();
    }

    private void buildUI() {
        // Year picker
        try {
            BlockDAO blockDAO = new BlockDAO();
            List<Integer> years = blockDAO.getDistinctYears();
            yearPicker.getItems().setAll(years);
            if (!years.isEmpty()) yearPicker.setValue(years.get(0));
        } catch (SQLException e) { showError(e.getMessage()); }

        yearPicker.setPromptText("Select year...");
        Button runBtn = new Button("Run Compliance Check");
        runBtn.setOnAction(e -> runCheck());

        HBox topBar = new HBox(10, new Label("Academic Year:"), yearPicker, runBtn);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);

        reportBox.setPadding(new Insets(10));
        ScrollPane scroll = new ScrollPane(reportBox);
        scroll.setFitToWidth(true);

        setTop(topBar);
        setCenter(scroll);
        setPadding(new Insets(5));
    }

    private void runCheck() {
        Integer year = yearPicker.getValue();
        if (year == null) { showError("Select a year first."); return; }

        reportBox.getChildren().clear();

        try {
            List<Resident> residents = residentDAO.getAll();
            List<Rotation> rotations = rotationDAO.getAll();
            List<RotationRequirement> allReqs = rulesDAO.getAllRequirements();
            List<Prerequisite> allPrereqs = rulesDAO.getAllPrerequisites();

            if (residents.isEmpty()) {
                reportBox.getChildren().add(new Label("No residents found."));
                return;
            }

            int totalIssues = 0;

            for (Resident resident : residents) {
                List<String> issues = schedulingService.getComplianceIssues(resident.getId(), year);

                // Also check prerequisites
                List<Integer> completed = assignmentDAO.getCompletedRotationIds(resident.getId());
                for (Prerequisite prereq : allPrereqs) {
                    if (prereq.getPgyLevel() != null && prereq.getPgyLevel() != resident.getPgyLevel()) continue;
                    if (completed.contains(prereq.getRotationId()) && !completed.contains(prereq.getPrerequisiteRotationId())) {
                        Rotation rot = rotationDAO.getById(prereq.getRotationId());
                        Rotation pre = rotationDAO.getById(prereq.getPrerequisiteRotationId());
                        if (rot != null && pre != null) {
                            issues.add("Completed '" + rot.getName() + "' without completing prerequisite '" + pre.getName() + "'");
                        }
                    }
                }

                // Build card
                TitledPane card = buildResidentCard(resident, rotations, allReqs, issues, year);
                reportBox.getChildren().add(card);
                totalIssues += issues.size();
            }

            // Summary banner
            Label summary = new Label(totalIssues == 0
                ? "✅ All residents are compliant for " + year + "-" + (year + 1)
                : "⚠️ " + totalIssues + " compliance issue(s) found across " + residents.size() + " residents");
            summary.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 8 12; -fx-background-radius: 4;");
            summary.setStyle(summary.getStyle() + (totalIssues == 0
                ? "-fx-background-color: #d4edda; -fx-text-fill: #155724;"
                : "-fx-background-color: #fff3cd; -fx-text-fill: #856404;"));

            reportBox.getChildren().add(0, summary);

        } catch (SQLException e) {
            showError(e.getMessage());
        }
    }

    private TitledPane buildResidentCard(Resident resident, List<Rotation> rotations,
            List<RotationRequirement> allReqs, List<String> issues, int year) throws SQLException {

        VBox content = new VBox(4);
        content.setPadding(new Insets(8));

        // Per-rotation status rows
        for (Rotation rot : rotations) {
            RotationRequirement req = allReqs.stream()
                .filter(r -> r.getRotationId() == rot.getId() && r.getPgyLevel() == resident.getPgyLevel())
                .findFirst().orElse(null);
            if (req == null) continue;

            int done = assignmentDAO.countBlocksByResidentAndRotation(resident.getId(), rot.getId());

            boolean ok = !req.isRequired() || done >= req.getMinBlocks();
            boolean overMax = done > req.getMaxBlocks();

            Circle dot = new Circle(5);
            dot.setFill(overMax ? Color.ORANGE : (ok ? Color.LIMEGREEN : Color.TOMATO));

            String status;
            if (overMax) status = done + " blocks (exceeds max of " + req.getMaxBlocks() + ")";
            else if (req.isRequired()) status = done + " / " + req.getMinBlocks() + " required blocks";
            else status = done + " block(s) (optional)";

            Label rotLabel = new Label(rot.getName() + ": " + status);
            rotLabel.setStyle("-fx-font-size: 11px;");

            HBox row = new HBox(8, dot, rotLabel);
            row.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().add(row);
        }

        if (content.getChildren().isEmpty()) {
            content.getChildren().add(new Label("No PGY-" + resident.getPgyLevel() + " rules defined."));
        }

        // Issues list
        if (!issues.isEmpty()) {
            Separator sep = new Separator();
            content.getChildren().add(sep);
            for (String issue : issues) {
                Label lbl = new Label("⚠ " + issue);
                lbl.setStyle("-fx-text-fill: #b94a00; -fx-font-size: 11px;");
                lbl.setWrapText(true);
                content.getChildren().add(lbl);
            }
        }

        String titleText = resident.getName() + "  (PGY-" + resident.getPgyLevel() + ")"
            + (issues.isEmpty() ? "  ✅" : "  ⚠ " + issues.size() + " issue(s)");

        TitledPane card = new TitledPane(titleText, content);
        card.setExpanded(!issues.isEmpty());
        return card;
    }

    private void showError(String msg) {
        new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait();
    }
}
