package com.residency.db;

import java.sql.*;

public class DatabaseManager {

    private static final String DB_URL = "jdbc:sqlite:residency_scheduler.db";
    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() throws SQLException {
        connection = DriverManager.getConnection(DB_URL);
        connection.createStatement().execute("PRAGMA foreign_keys = ON");
        initializeSchema();
    }

    public static DatabaseManager getInstance() throws SQLException {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    /**
     * Get a guaranteed-valid connection. Call this each time you need a connection.
     * This method validates the connection and reconnects if necessary.
     */
    public synchronized Connection getValidConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            // Connection was closed; create a fresh one
            connection = DriverManager.getConnection(DB_URL);
            connection.createStatement().execute("PRAGMA foreign_keys = ON");
        } else {
            try {
                // Test if connection is still valid
                connection.createStatement().execute("SELECT 1");
            } catch (SQLException e) {
                // Connection went stale; reconnect
                try { connection.close(); } catch (Exception ignored) {}
                connection = DriverManager.getConnection(DB_URL);
                connection.createStatement().execute("PRAGMA foreign_keys = ON");
            }
        }
        return connection;
    }

    public Connection getConnection() throws SQLException {
        return getValidConnection();
    }

    private void initializeSchema() throws SQLException {
        String[] ddl = {
            // Residents
            """
            CREATE TABLE IF NOT EXISTS residents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                pgy_level INTEGER NOT NULL,
                email TEXT
            )
            """,
            // Rotations
            """
            CREATE TABLE IF NOT EXISTS rotations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                department TEXT,
                max_residents_per_block INTEGER NOT NULL DEFAULT 5,
                min_blocks_required INTEGER NOT NULL DEFAULT 1,
                max_blocks_allowed INTEGER NOT NULL DEFAULT 4,
                description TEXT
            )
            """,
            // Blocks
            """
            CREATE TABLE IF NOT EXISTS blocks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                block_number INTEGER NOT NULL,
                schedule_year INTEGER NOT NULL,
                start_date TEXT,
                end_date TEXT,
                UNIQUE(block_number, schedule_year)
            )
            """,
            // Assignments
            """
            CREATE TABLE IF NOT EXISTS assignments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                resident_id INTEGER NOT NULL REFERENCES residents(id) ON DELETE CASCADE,
                rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                block_id INTEGER NOT NULL REFERENCES blocks(id) ON DELETE CASCADE,
                override_warning INTEGER NOT NULL DEFAULT 0,
                UNIQUE(resident_id, block_id)
            )
            """,
            // Per-PGY rotation requirements
            """
            CREATE TABLE IF NOT EXISTS rotation_requirements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                pgy_level INTEGER NOT NULL,
                min_blocks INTEGER NOT NULL DEFAULT 0,
                max_blocks INTEGER NOT NULL DEFAULT 4,
                required INTEGER NOT NULL DEFAULT 0,
                UNIQUE(rotation_id, pgy_level)
            )
            """,
            // Prerequisites
            """
            CREATE TABLE IF NOT EXISTS prerequisites (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                prerequisite_rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                pgy_level INTEGER,
                UNIQUE(rotation_id, prerequisite_rotation_id, pgy_level)
            )
            """,
            // Sequence / adjacency rules
            """
            CREATE TABLE IF NOT EXISTS rotation_sequence_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                related_rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                rule_type TEXT NOT NULL,
                pgy_level INTEGER,
                UNIQUE(rotation_id, related_rotation_id, rule_type, pgy_level)
            )
            """,
            // Rotation extended config (block lengths, weekly staffing, PGY caps)
            """
            CREATE TABLE IF NOT EXISTS rotation_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE UNIQUE,
                allowed_block_lengths TEXT NOT NULL DEFAULT '4',
                requires_consecutive INTEGER NOT NULL DEFAULT 0,
                min_per_week INTEGER NOT NULL DEFAULT 1,
                max_per_week INTEGER NOT NULL DEFAULT 5,
                optional_full_year INTEGER NOT NULL DEFAULT 0
            )
            """,
            // Per-PGY weekly staffing caps per rotation
            """
            CREATE TABLE IF NOT EXISTS rotation_pgy_caps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                pgy_level INTEGER NOT NULL,
                min_per_week INTEGER NOT NULL DEFAULT 0,
                max_per_week INTEGER NOT NULL DEFAULT 99,
                UNIQUE(rotation_id, pgy_level)
            )
            """,
            // Solver run metadata (for comparison panel)
            """
            CREATE TABLE IF NOT EXISTS solver_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                schedule_year INTEGER NOT NULL,
                engine TEXT NOT NULL,
                run_at TEXT NOT NULL,
                status TEXT NOT NULL,
                hard_score INTEGER,
                medium_score INTEGER,
                soft_score INTEGER,
                runtime_ms INTEGER,
                feasible INTEGER NOT NULL DEFAULT 0,
                summary TEXT
            )
            """,
            // Schedule config (global weights + solver params)
            """
            CREATE TABLE IF NOT EXISTS schedule_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                config_key TEXT NOT NULL UNIQUE,
                config_value TEXT NOT NULL
            )
            """,
            // Linked rotation sum constraints (e.g. Y7N + Elective = 2 per resident)
            """
            CREATE TABLE IF NOT EXISTS rotation_link_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rot_a_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                rot_b_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                sum_per_resident INTEGER NOT NULL DEFAULT 2,
                global_total_for_rot_b INTEGER NOT NULL DEFAULT 0
            )
            """
        };

        try (Statement stmt = connection.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
        }

        // Schema migrations: add new columns to existing tables if not present.
        // ALTER TABLE IF NOT EXISTS is not supported in SQLite; ignore errors for existing columns.
        String[] migrations = {
            "ALTER TABLE rotation_config ADD COLUMN no_back_to_back_half_blocks INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE rotation_config ADD COLUMN require_break_between_segments INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE rotation_config ADD COLUMN mutually_non_adjacent_with TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE rotations ADD COLUMN rotation_type TEXT NOT NULL DEFAULT 'UNSPECIFIED'",
            "ALTER TABLE rotation_config ADD COLUMN max_consecutive_weeks INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE rotation_config ADD COLUMN earliest_start_block INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE residents ADD COLUMN is_auxiliary INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE rotation_config ADD COLUMN require_even_block_start INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE residents ADD COLUMN resident_group TEXT DEFAULT NULL",
            """
            CREATE TABLE IF NOT EXISTS aux_filler_rotations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                resident_group TEXT NOT NULL,
                rotation_id INTEGER NOT NULL REFERENCES rotations(id) ON DELETE CASCADE,
                UNIQUE(resident_group, rotation_id)
            )"""
        };
        try (Statement stmt = connection.createStatement()) {
            for (String sql : migrations) {
                try { stmt.execute(sql); }
                catch (SQLException ignored) {} // column already exists
            }
        }
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
