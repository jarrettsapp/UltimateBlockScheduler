package com.residency.ui;

import com.residency.cpsat.ScheduleConfig;
import com.residency.db.RotationDAO;
import com.residency.db.ScheduleConfigDAO;
import com.residency.model.Rotation;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sub-panel embedded in RotationView for editing CP-SAT rotation policy:
 *  - Allowed block lengths (2 / 4 / 6 week checkboxes)
 *  - Requires consecutive
 *  - Min/max residents per week
 *  - Optional full-year coverage
 *  - No back-to-back half-blocks
 *  - Require break between segments
 *  - Mutually non-adjacent with (cross-rotation adjacency prohibition)
 */
public class RotationConfigPanel extends VBox {

    private final ScheduleConfigDAO dao;
    private final RotationDAO rotationDAO;
    private int currentRotationId = -1;

    private final CheckBox cbHalf            = new CheckBox("Allow 1-block segments (2 weeks)");
    private final CheckBox cbFull            = new CheckBox("Allow 2-block segments (4 weeks)");
    private final CheckBox cbConsecutive     = new CheckBox("Requires consecutive blocks");
    private final CheckBox cbFullYear        = new CheckBox("Optional full-year coverage");
    private final CheckBox cbNoBackToBack    = new CheckBox(
        "No back-to-back half-blocks — when only 2-week assignments are used, prevent two from being placed consecutively");
    private final CheckBox cbRequireBreak    = new CheckBox(
        "Require a break between segments — a different rotation must occur between any two sessions on this rotation");
    private final CheckBox cbEvenBlockStart  = new CheckBox(
        "Force even-block starts — all segments must begin on block 0, 2, 4… (1A, 2A, 3A…); odd-block starts forbidden");

    private final Spinner<Integer> minWeekly          = new Spinner<>(0, 99, 1);
    private final Spinner<Integer> maxWeekly          = new Spinner<>(1, 99, 5);
    private final Spinner<Integer> maxConsecWeeks     = new Spinner<>(0, 26, 0);
    private final Spinner<Integer> earliestStartBlock = new Spinner<>(0, 25, 0);

    // Mutual non-adjacency: one CheckBox per rotation (populated at load time)
    private final VBox adjCheckBoxContainer  = new VBox(4);
    private final List<CheckBox> adjCheckBoxes = new ArrayList<>();
    private final List<Integer>  adjRotIds     = new ArrayList<>();

    private final Button saveBtn   = new Button("Save Rotation Policy");
    private final Label  statusLbl = new Label();

    public RotationConfigPanel() {
        try {
            dao         = new ScheduleConfigDAO();
            rotationDAO = new RotationDAO();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        buildUI();
    }

    private void buildUI() {
        setPadding(new Insets(10));
        setSpacing(8);
        setStyle("-fx-background-color:#f8faff;-fx-border-color:#dde3f0;-fx-border-radius:4;-fx-background-radius:4;");

        cbFull.setSelected(true);
        minWeekly.setEditable(true);          minWeekly.setPrefWidth(70);
        maxWeekly.setEditable(true);          maxWeekly.setPrefWidth(70);
        maxConsecWeeks.setEditable(true);     maxConsecWeeks.setPrefWidth(70);
        earliestStartBlock.setEditable(true); earliestStartBlock.setPrefWidth(70);

        HBox blockLengths = new HBox(12, new Label("Allowed assignment types:"), cbHalf, cbFull);

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(6);
        grid.add(new Label("Min residents/block:"), 0, 0); grid.add(minWeekly, 1, 0);
        grid.add(new Label("Max residents/block:"), 0, 1); grid.add(maxWeekly, 1, 1);
        Label maxConsecLabel = new Label("Max consecutive blocks (0 = unlimited):");
        grid.add(maxConsecLabel, 0, 2); grid.add(maxConsecWeeks, 1, 2);
        grid.add(new Label("Earliest allowed start block (0 = no restriction):"), 0, 3);
        grid.add(earliestStartBlock, 1, 3);

        statusLbl.setStyle("-fx-text-fill:#27ae60;-fx-font-size:11px;");
        saveBtn.setOnAction(e -> save());

        Label explanation = new Label(
            "Each resident must complete a fixed total of weeks for this rotation (set below).\n" +
            "They can fulfill it using combinations of full blocks (4 weeks) and/or half-blocks (2 weeks)\n" +
            "based on what types are allowed above.");
        explanation.setStyle("-fx-font-size:10px; -fx-text-fill:#666;");
        explanation.setWrapText(true);

        cbNoBackToBack.setWrapText(true);
        cbRequireBreak.setWrapText(true);
        cbEvenBlockStart.setWrapText(true);

        // Mutual non-adjacency section
        Label adjLabel = new Label("Must not be adjacent to:");
        adjLabel.setStyle("-fx-font-weight:bold;");
        Label adjHint = new Label(
            "The solver will prohibit this rotation from immediately preceding or following any checked rotation.");
        adjHint.setStyle("-fx-font-size:10px;-fx-text-fill:#666;");
        adjHint.setWrapText(true);

        ScrollPane adjScroll = new ScrollPane(adjCheckBoxContainer);
        adjScroll.setFitToWidth(true);
        adjScroll.setPrefHeight(100);
        adjScroll.setStyle("-fx-background-color:transparent;");

        getChildren().addAll(
            new Label("CP-SAT Scheduling Policy"),
            blockLengths,
            explanation,
            grid,
            cbConsecutive,
            cbNoBackToBack,
            cbRequireBreak,
            cbEvenBlockStart,
            cbFullYear,
            adjLabel,
            adjHint,
            adjScroll,
            saveBtn,
            statusLbl
        );
    }

    public void loadFor(int rotationId) {
        this.currentRotationId = rotationId;
        try {
            ScheduleConfig.RotationPolicy policy = dao.loadRotationPolicy(rotationId);
            cbHalf.setSelected(containsLength(policy.allowedBlockLengths, 1)); // 1 block = 2 weeks (half)
            cbFull.setSelected(containsLength(policy.allowedBlockLengths, 2)); // 2 blocks = 4 weeks (full)
            cbConsecutive.setSelected(policy.requiresConsecutive);
            cbFullYear.setSelected(policy.optionalFullYearCoverage);
            cbNoBackToBack.setSelected(policy.noBackToBackHalfBlocks);
            cbRequireBreak.setSelected(policy.requireBreakBetweenSegments);
            cbEvenBlockStart.setSelected(policy.requireEvenBlockStart);
            minWeekly.getValueFactory().setValue(policy.minPerBlock);
            maxWeekly.getValueFactory().setValue(policy.maxPerBlock);
            maxConsecWeeks.getValueFactory().setValue(policy.maxConsecutiveBlocks);
            earliestStartBlock.getValueFactory().setValue(policy.earliestStartBlock);
            statusLbl.setText("");

            rebuildAdjacencyCheckBoxes(rotationId, policy.mutuallyNonAdjacentWith);
        } catch (SQLException e) {
            statusLbl.setText("Load error: " + e.getMessage());
        }
    }

    /** Rebuilds the non-adjacency checkbox list, excluding the current rotation. */
    private void rebuildAdjacencyCheckBoxes(int currentId, List<Integer> selectedIds)
            throws SQLException {
        adjCheckBoxContainer.getChildren().clear();
        adjCheckBoxes.clear();
        adjRotIds.clear();

        List<Rotation> allRotations = rotationDAO.getAll();
        for (Rotation rot : allRotations) {
            if (rot.getId() == currentId) continue;
            CheckBox cb = new CheckBox(rot.getName());
            cb.setSelected(selectedIds.contains(rot.getId()));
            adjCheckBoxes.add(cb);
            adjRotIds.add(rot.getId());
            adjCheckBoxContainer.getChildren().add(cb);
        }
    }

    private void save() {
        if (currentRotationId < 0) return;
        try {
            ScheduleConfig.RotationPolicy policy = new ScheduleConfig.RotationPolicy(currentRotationId);
            List<Integer> lengths = new ArrayList<>();
            if (cbHalf.isSelected()) lengths.add(1); // 1 block = 2 weeks
            if (cbFull.isSelected()) lengths.add(2); // 2 blocks = 4 weeks
            if (lengths.isEmpty()) lengths.add(2);
            policy.allowedBlockLengths        = lengths.stream().mapToInt(i -> i).toArray();
            policy.requiresConsecutive        = cbConsecutive.isSelected();
            policy.optionalFullYearCoverage   = cbFullYear.isSelected();
            policy.noBackToBackHalfBlocks     = cbNoBackToBack.isSelected();
            policy.requireBreakBetweenSegments = cbRequireBreak.isSelected();
            policy.requireEvenBlockStart      = cbEvenBlockStart.isSelected();
            policy.minPerBlock                = minWeekly.getValue();
            policy.maxPerBlock                = maxWeekly.getValue();
            policy.maxConsecutiveBlocks       = maxConsecWeeks.getValue();
            policy.earliestStartBlock         = earliestStartBlock.getValue();

            // Collect selected non-adjacent rotation IDs
            policy.mutuallyNonAdjacentWith = new ArrayList<>();
            for (int i = 0; i < adjCheckBoxes.size(); i++) {
                if (adjCheckBoxes.get(i).isSelected()) {
                    policy.mutuallyNonAdjacentWith.add(adjRotIds.get(i));
                }
            }

            dao.saveRotationPolicy(policy);
            statusLbl.setText("Saved.");
        } catch (SQLException e) {
            statusLbl.setText("Error: " + e.getMessage());
        }
    }

    private boolean containsLength(int[] arr, int val) {
        for (int v : arr) if (v == val) return true;
        return false;
    }
}
