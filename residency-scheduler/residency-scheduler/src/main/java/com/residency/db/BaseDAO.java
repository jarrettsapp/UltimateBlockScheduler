package com.residency.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Base class for all DAOs to ensure they always get fresh, validated database connections.
 * This prevents "connection closed" errors that occur when connections become stale.
 */
public abstract class BaseDAO {

    protected final DatabaseManager dbMgr;

    protected BaseDAO() throws SQLException {
        this.dbMgr = DatabaseManager.getInstance();
    }

    /**
     * Get a fresh, validated connection from the DatabaseManager.
     * Call this at the start of each DAO method to ensure a valid connection.
     */
    protected Connection getConn() throws SQLException {
        return dbMgr.getValidConnection();
    }
}
