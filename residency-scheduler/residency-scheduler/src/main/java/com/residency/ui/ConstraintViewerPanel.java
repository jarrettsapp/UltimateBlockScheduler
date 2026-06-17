package com.residency.ui;

import com.residency.cpsat.FeasibilityReport;
import com.residency.cpsat.ScheduleConfig;
import com.residency.db.RotationDAO;
import com.residency.db.RulesDAO;
import com.residency.db.ScheduleConfigDAO;
import com.residency.model.*;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.sql.SQLException;
import java.util.*;

/**
 * ConstraintViewerPanel — shows all active scheduling constraints,
 * highlights conflicts detected by FeasibilityAnalyzer,
 * and provides a "Validate" button to run pre-solve diagnostics.
 */
public class ConstraintViewerPanel extends BorderPane {

    private final RotationDAO       rotationDAO;
    private final RulesDAO          rulesDAO;
    private final ScheduleConfigDAO configDAO;

    private final VBox constraintList = new VBox(6);
    private final VBox diagBox        = new VBox(6);
    private final Label summaryLabel  = new Label("Click Validate to check constraints.");
    private final Button validateBtn  = new Button("🔍  Validate Constraints");

    private FeasibilityReport lastReport;

    public ConstraintViewerPanel() {
        try {
            rotationDAO = new RotationDAO();
            rulesDAO    = new RulesDAO();
            configDAO   = new ScheduleConfigDAO();
        } catch (SQLException e) { throw new RuntimeException(e); }
        buildUI();
        refresh();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI construction
    // ══════════════════════════════════════════════════════════════════════

    private void buildUI() {
        validateBtn.setStyle("-fx-background-color:#1e3c72;-fx-text-fill:white;-fx-padding:6 16;");
        validateBtn.setOnAction(e -> runValidation());

        summaryLabel.setStyle("-fx-font-style:italic;-fx-text-fill:#555;");

        HBox topBar = new HBox(12, validateBtn, summaryLabel);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color:white;-fx-border-color:#dde3f0;-fx-border-width:0 0 1 0;");

        // Left: constraint list
        constraintList.setPadding(new Insets(10));
        ScrollPane leftScroll = new ScrollPane(constraintList);
        leftScroll.setFitToWidth(true);
        TitledPane leftPane = new TitledPane("Active Constraints", leftScroll);
        leftPane.setCollapsible(false);

        // Right: diagnostics output
        diagBox.setPadding(new Insets(10));
        ScrollPane rightScroll = new ScrollPane(diagBox);
        rightScroll.setFitToWidth(true);
        TitledPane rightPane = new TitledPane("Feasibility Diagnostics", rightScroll);
        rightPane.setCollapsible(false);

        SplitPane split = new SplitPane(leftPane, rightPane);
        split.setDividerPositions(0.55);

        setTop(topBar);
        setCenter(split);
        setPadding(new Insets(5));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Constraint display
    // ══════════════════════════════════════════════════════════════════════

    public void refresh() {
        constraintList.getChildren().clear();
        try {
            List<Rotation>            rotations = rotationDAO.getAll();
            List<RotationRequirement> reqs      = rulesDAO.getAllRequirements();
            List<Prerequisite>        prereqs   = rulesDAO.getAllPrerequisites();
            List<RotationSequenceRule> sequenceRules = rulesDAO.getAllSequenceRules();
            ScheduleConfig            config    = configDAO.loadConfig();

            // Collect conflicts from last report for highlighting
            Set<String> conflictedRotationNames = new HashSet<>();
            if (lastReport != null) {
                for (FeasibilityReport.Issue issue : lastReport.getIssues()) {
                    conflictedRotationNames.add(issue.rotationName);
                }
            }

            // Section: Global constraints
            constraintList.getChildren().add(sectionHeader("Global Constraints"));
            constraintList.getChildren().add(constraintRow(
                "HARD", "One rotation per resident per week",
                "A resident cannot be assigned to two rotations simultaneously.", false));
            constraintList.getChildren().add(constraintRow(
                "HARD", "Contiguous block scheduling",
                String.format("Blocks must be 1 or 2 consecutive blocks. Min workload: %d blocks, Max: %d blocks.",
                    config.getGlobalMinWorkloadBlocks(), config.getGlobalMaxWorkloadBlocks()), false));
            constraintList.getChildren().add(constraintRow(
                "SOFT", "Workload balance",
                String.format("Variance weight: %d, PGY imbalance weight: %d",
                    config.getWeightVariance(), config.getWeightPgyImbalance()), false));

            // Section: Per-rotation constraints
            if (!rotations.isEmpty()) {
                constraintList.getChildren().add(sectionHeader("Per-Rotation Constraints"));
                for (Rotation rot : rotations) {
                    boolean conflicted = conflictedRotationNames.contains(rot.getName());
                    ScheduleConfig.RotationPolicy policy = config.getPolicyFor(rot.getId());

                    String blockLens = Arrays.stream(policy.allowedBlockLengths)
                        .mapToObj(l -> l + "-blk")
                        .collect(java.util.stream.Collectors.joining(", "));

                    String desc = String.format(
                        "Lengths: [%s]  Consecutive: %s  Min/blk: %d  Max/blk: %d  Cap/block: %d",
                        blockLens,
                        policy.requiresConsecutive ? "yes" : "no",
                        policy.minPerBlock,
                        policy.maxPerBlock,
                        rot.getMaxResidentsPerBlock());

                    constraintList.getChildren().add(
                        constraintRow("HARD", rot.getName(), desc, conflicted));

                    // PGY caps
                    if (!policy.pgyMinMax.isEmpty()) {
                        for (var entry : policy.pgyMinMax.entrySet()) {
                            constraintList.getChildren().add(
                                constraintRow("HARD",
                                    "  PGY-" + entry.getKey() + " cap",
                                    String.format("Min %d / Max %d residents per week",
                                        entry.getValue()[0], entry.getValue()[1]),
                                    conflicted));
                        }
                    }
                }
            }

            // Section: PGY requirements
            if (!reqs.isEmpty()) {
                constraintList.getChildren().add(sectionHeader("PGY-Level Requirements"));
                Map<Integer, String> rotNames = new HashMap<>();
                for (Rotation r : rotations) rotNames.put(r.getId(), r.getName());

                for (RotationRequirement req : reqs) {
                    String rotName = rotNames.getOrDefault(req.getRotationId(), "ID:" + req.getRotationId());
                    boolean conflicted = conflictedRotationNames.contains(rotName);
                    String tier = req.isRequired() ? "MEDIUM" : "SOFT";
                    String desc = String.format(
                        "PGY-%d: %s  Min blocks: %.1f  Max blocks: %.1f",
                        req.getPgyLevel(),
                        req.isRequired() ? "REQUIRED" : "optional",
                        req.getMinBlocks(), req.getMaxBlocks());
                    constraintList.getChildren().add(
                        constraintRow(tier, rotName, desc, conflicted));
                }
            }

            // Section: Prerequisites
            if (!prereqs.isEmpty()) {
                constraintList.getChildren().add(sectionHeader("Prerequisite Ordering"));
                Map<Integer, String> rotNames = new HashMap<>();
                for (Rotation r : rotations) rotNames.put(r.getId(), r.getName());

                for (Prerequisite p : prereqs) {
                    String rotName  = rotNames.getOrDefault(p.getRotationId(), "ID:" + p.getRotationId());
                    String prereqName = rotNames.getOrDefault(p.getPrerequisiteRotationId(), "ID:" + p.getPrerequisiteRotationId());
                    boolean conflicted = conflictedRotationNames.contains(rotName);
                    String pgyNote = p.getPgyLevel() == null ? "all PGY" : "PGY-" + p.getPgyLevel();
                    constraintList.getChildren().add(
                        constraintRow("HARD", rotName,
                            String.format("Requires '%s' first (%s)", prereqName, pgyNote),
                            conflicted));
                }
            }

            if (!sequenceRules.isEmpty()) {
                constraintList.getChildren().add(sectionHeader("Sequence / Adjacency Rules"));
                Map<Integer, String> rotNames = new HashMap<>();
                for (Rotation r : rotations) rotNames.put(r.getId(), r.getName());

                for (RotationSequenceRule rule : sequenceRules) {
                    String rotName = rotNames.getOrDefault(rule.getRotationId(), "ID:" + rule.getRotationId());
                    String relatedName = rotNames.getOrDefault(rule.getRelatedRotationId(), "ID:" + rule.getRelatedRotationId());
                    boolean conflicted = conflictedRotationNames.contains(rotName);
                    String pgyNote = rule.getPgyLevel() == null ? "all PGY" : "PGY-" + rule.getPgyLevel();
                    String desc = switch (rule.getRuleType()) {
                        case MUST_BE_AFTER -> String.format("Must be after '%s' (%s)", relatedName, pgyNote);
                        case CANNOT_IMMEDIATELY_FOLLOW -> String.format("Cannot immediately follow '%s' (%s)", relatedName, pgyNote);
                    };
                    constraintList.getChildren().add(constraintRow("HARD", rotName, desc, conflicted));
                }
            }

        } catch (SQLException e) {
            constraintList.getChildren().add(new Label("Error loading constraints: " + e.getMessage()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Validation
    // ══════════════════════════════════════════════════════════════════════

    private void runValidation() {
        diagBox.getChildren().clear();
        validateBtn.setDisable(true);
        summaryLabel.setText("Running validation…");

        // Run in background
        Thread worker = new Thread(() -> {
            try {
                com.residency.db.ResidentDAO residentDAO = new com.residency.db.ResidentDAO();
                List<Resident>            residents    = residentDAO.getAll();
                List<Rotation>            rotations    = rotationDAO.getAll();
                List<RotationRequirement> reqs         = rulesDAO.getAllRequirements();
                List<Prerequisite>        prereqs      = rulesDAO.getAllPrerequisites();
                ScheduleConfig            config       = configDAO.loadConfig();

                // Build eligibility map
                Map<Integer, Map<Integer, RotationRequirement>> reqMap = new HashMap<>();
                for (RotationRequirement r : reqs)
                    reqMap.computeIfAbsent(r.getRotationId(), k -> new HashMap<>())
                          .put(r.getPgyLevel(), r);

                Map<Integer, Set<Integer>> eligible = new HashMap<>();
                for (Rotation s : rotations) {
                    Set<Integer> pool = new HashSet<>();
                    Map<Integer, RotationRequirement> byPgy = reqMap.getOrDefault(s.getId(), Map.of());
                    for (Resident r : residents) {
                        if (byPgy.isEmpty() || byPgy.containsKey(r.getPgyLevel()))
                            pool.add(r.getId());
                    }
                    eligible.put(s.getId(), pool);
                }

                int totalWeeks = config.getTotalBlocks();
                if (totalWeeks == 0) totalWeeks = 26;

                com.residency.cpsat.FeasibilityAnalyzer analyzer =
                    new com.residency.cpsat.FeasibilityAnalyzer(config, totalWeeks);
                FeasibilityReport report = analyzer.analyze(
                    residents, rotations, reqs, prereqs, eligible);

                lastReport = report;

                final FeasibilityReport finalReport = report;
                final int finalWeeks = totalWeeks;
                javafx.application.Platform.runLater(() -> {
                    showDiagnostics(finalReport, residents.size(), rotations.size(), finalWeeks);
                    refresh(); // re-render with highlights
                    validateBtn.setDisable(false);
                    summaryLabel.setText(finalReport.hasIssues()
                        ? "⚠ " + finalReport.getIssues().size() + " issue(s) found."
                        : "✅ No feasibility issues detected.");
                });

            } catch (SQLException e) {
                javafx.application.Platform.runLater(() -> {
                    diagBox.getChildren().add(new Label("Error: " + e.getMessage()));
                    validateBtn.setDisable(false);
                });
            }
        }, "constraint-validator");
        worker.setDaemon(true);
        worker.start();
    }

    private void showDiagnostics(FeasibilityReport report, int numResidents,
                                  int numRotations, int totalWeeks) {
        diagBox.getChildren().clear();

        // Summary stats
        GridPane stats = new GridPane();
        stats.setHgap(20); stats.setVgap(4); stats.setPadding(new Insets(8, 0, 12, 0));
        addStat(stats, "Residents", String.valueOf(numResidents), 0);
        addStat(stats, "Rotations", String.valueOf(numRotations), 1);
        addStat(stats, "Total Weeks", String.valueOf(totalWeeks), 2);
        addStat(stats, "Resident-Wks Available",
            String.valueOf(report.getTotalResidentWeeksAvailable()), 3);
        addStat(stats, "Resident-Wks Required",
            String.valueOf(report.getTotalResidentWeeksRequired()), 4);
        diagBox.getChildren().add(stats);
        diagBox.getChildren().add(new Separator());

        if (!report.hasIssues()) {
            Label ok = new Label("✅ All constraints appear satisfiable. Safe to run solver.");
            ok.setStyle("-fx-text-fill:#155724;-fx-font-size:13px;-fx-font-weight:bold;");
            diagBox.getChildren().add(ok);
            return;
        }

        for (FeasibilityReport.Issue issue : report.getIssues()) {
            VBox card = new VBox(4);
            card.setPadding(new Insets(8));
            card.setStyle("-fx-background-color:#fff3cd;-fx-border-color:#ffc107;"
                + "-fx-border-radius:4;-fx-background-radius:4;");

            Label typeLbl = new Label("[" + issue.type + "]  " + issue.rotationName);
            typeLbl.setStyle("-fx-font-weight:bold;-fx-text-fill:#856404;-fx-font-size:11px;");

            Label descLbl = new Label(issue.description);
            descLbl.setWrapText(true);
            descLbl.setStyle("-fx-font-size:11px;");

            Label suggLbl = new Label("💡 " + issue.suggestion);
            suggLbl.setWrapText(true);
            suggLbl.setStyle("-fx-font-size:11px;-fx-text-fill:#155724;-fx-font-style:italic;");

            card.getChildren().addAll(typeLbl, descLbl, suggLbl);
            diagBox.getChildren().add(card);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private HBox constraintRow(String tier, String name, String description, boolean conflicted) {
        String bg   = "HARD".equals(tier) ? "#fde8e8" : "MEDIUM".equals(tier) ? "#fff3cd" : "#d4edda";
        String fg   = "HARD".equals(tier) ? "#c0392b" : "MEDIUM".equals(tier) ? "#856404" : "#155724";

        Label tierLbl = new Label(tier);
        tierLbl.setStyle("-fx-font-weight:bold;-fx-font-size:9px;-fx-padding:1 5;"
            + "-fx-background-radius:3;-fx-background-color:" + bg + ";-fx-text-fill:" + fg + ";");
        tierLbl.setMinWidth(55);

        Label nameLbl = new Label(name);
        nameLbl.setStyle("-fx-font-weight:bold;-fx-font-size:11px;" +
            (conflicted ? "-fx-text-fill:#c0392b;" : ""));
        nameLbl.setMinWidth(160);

        Label descLbl = new Label(description);
        descLbl.setStyle("-fx-font-size:10px;-fx-text-fill:#555;");
        descLbl.setWrapText(true);
        HBox.setHgrow(descLbl, Priority.ALWAYS);

        HBox row = new HBox(10, tierLbl, nameLbl, descLbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3, 6, 3, 6));

        if (conflicted) {
            Circle dot = new Circle(5, Color.TOMATO);
            row.getChildren().add(0, dot);
            row.setStyle("-fx-background-color:#fff0f0;-fx-border-color:#ffcccc;"
                + "-fx-border-radius:3;-fx-background-radius:3;");
        }

        return row;
    }

    private Label sectionHeader(String title) {
        Label lbl = new Label(title);
        lbl.setStyle("-fx-font-weight:bold;-fx-font-size:12px;-fx-text-fill:#1e3c72;"
            + "-fx-padding:10 0 2 0;");
        return lbl;
    }

    private void addStat(GridPane grid, String label, String value, int col) {
        VBox box = new VBox(2);
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill:#666;-fx-font-size:10px;");
        Label val = new Label(value);
        val.setStyle("-fx-font-weight:bold;-fx-font-size:14px;-fx-text-fill:#1e3c72;");
        box.getChildren().addAll(lbl, val);
        grid.add(box, col, 0);
    }
}
