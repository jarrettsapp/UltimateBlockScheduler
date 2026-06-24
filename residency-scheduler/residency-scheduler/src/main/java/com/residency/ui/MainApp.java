package com.residency.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Residency Rotation Scheduler");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        AutoScheduleView autoScheduleView = new AutoScheduleView();

        Tab scheduleTab    = new Tab("📅 Schedule",       new ScheduleView());
        Tab residentsTab   = new Tab("👥 Residents",      new ResidentView());
        Tab rotationsTab   = new Tab("🏥 Rotations",      new RotationView());
        Tab rulesTab       = new Tab("📋 Rules",           new RulesView());
        Tab complianceTab  = new Tab("✅ Compliance",      new ComplianceView());
        Tab autoTab        = new Tab("🤖 Auto-Scheduler",  autoScheduleView);
        Tab constraintTab  = new Tab("🔍 Constraints",     new ConstraintViewerPanel());
        Tab sweepTab       = new Tab("📈 Sweep Analysis",  new SweepAnalysisView());
        Tab settingsTab    = new Tab("⚙️ Settings",         new ScheduleConfigView());

        tabPane.getTabs().addAll(scheduleTab, residentsTab, rotationsTab, rulesTab,
            complianceTab, autoTab, constraintTab, sweepTab, settingsTab);

        // Refresh the Auto-Scheduler year picker whenever the user switches to that tab,
        // so any year generated on the Schedule tab is immediately visible and selected.
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == autoTab) autoScheduleView.refreshYears();
        });

        BorderPane root = new BorderPane(tabPane);
        Scene scene = new Scene(root, 1280, 800);

        // Load CSS
        try {
            String css = getClass().getResource("/styles.css").toExternalForm();
            scene.getStylesheets().add(css);
        } catch (Exception e) {
            System.err.println("Could not load stylesheet: " + e.getMessage());
        }

        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
