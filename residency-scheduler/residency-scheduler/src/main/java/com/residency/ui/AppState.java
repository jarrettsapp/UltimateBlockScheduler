package com.residency.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Application-wide shared state. Holds values that need to stay in sync
 * across multiple tabs (e.g. which schedule year is currently active).
 */
public class AppState {

    private static final AppState INSTANCE = new AppState();

    private final ObjectProperty<Integer> selectedYear = new SimpleObjectProperty<>(null);

    private AppState() {}

    public static AppState get() { return INSTANCE; }

    public ObjectProperty<Integer> selectedYearProperty() { return selectedYear; }
    public Integer getSelectedYear()          { return selectedYear.get(); }
    public void setSelectedYear(Integer year) { selectedYear.set(year); }
}
