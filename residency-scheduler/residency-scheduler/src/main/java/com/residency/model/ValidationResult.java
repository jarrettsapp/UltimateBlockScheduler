package com.residency.model;

import java.util.ArrayList;
import java.util.List;

public class ValidationResult {

    public enum Severity { WARNING, ERROR }

    public static class Issue {
        private final Severity severity;
        private final String message;
        private final String ruleType; // "MAX_CAPACITY", "MIN_BLOCKS", "MAX_BLOCKS", "PREREQUISITE"

        public Issue(Severity severity, String message, String ruleType) {
            this.severity = severity;
            this.message = message;
            this.ruleType = ruleType;
        }

        public Severity getSeverity() { return severity; }
        public String getMessage() { return message; }
        public String getRuleType() { return ruleType; }

        @Override
        public String toString() {
            return "[" + severity + "] " + message;
        }
    }

    private final List<Issue> issues = new ArrayList<>();

    public void addIssue(Severity severity, String message, String ruleType) {
        issues.add(new Issue(severity, message, ruleType));
    }

    public List<Issue> getIssues() { return issues; }

    public boolean hasIssues() { return !issues.isEmpty(); }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.getSeverity() == Severity.ERROR);
    }

    public boolean hasWarnings() {
        return issues.stream().anyMatch(i -> i.getSeverity() == Severity.WARNING);
    }
}
