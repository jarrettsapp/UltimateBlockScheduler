package com.residency.db;

import com.residency.cpsat.ScheduleConfig;
import com.residency.model.Rotation;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips a rotation policy through SQLite to lock in the weeks-in-DB /
 * slots-in-memory convention (PROJECT.md Code review, M2/M4). The DAO stores allowed block
 * lengths in WEEKS but exposes them in 2-week slots; this test asserts both
 * the persisted column value and the converted-back in-memory value.
 *
 * Uses the application's real database file but cleans up its own rotation in a
 * finally block (the ON DELETE CASCADE removes the policy row with it).
 */
class RotationPolicyRoundTripTest {

    private RotationDAO rotationDAO;
    private ScheduleConfigDAO configDAO;
    private Integer rotationId;

    @BeforeEach
    void setUp() throws SQLException {
        rotationDAO = new RotationDAO();
        configDAO   = new ScheduleConfigDAO();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (rotationId != null) rotationDAO.delete(rotationId);
    }

    @Test
    void allowedBlockLengths_roundTripConvertsSlotsToWeeksAndBack() throws SQLException {
        Rotation r = new Rotation(0, "RoundTrip Test Rotation", "Test", 5, 0, 8, null);
        rotationId = rotationDAO.insert(r).getId();

        // In memory the policy is held in 2-week slots: 1 = half block, 2 = full block.
        ScheduleConfig.RotationPolicy policy = new ScheduleConfig.RotationPolicy(rotationId);
        policy.allowedBlockLengths = new int[]{1, 2};
        configDAO.saveRotationPolicy(policy);

        // The DB column must hold WEEKS (2 and 4), not the slot values.
        String stored = readAllowedBlockLengthsColumn(rotationId);
        assertEquals("2,4", stored,
            "DB column should store block lengths in weeks (slots x2)");

        // Loading back must convert weeks -> slots, recovering {1, 2}.
        ScheduleConfig.RotationPolicy loaded = configDAO.loadRotationPolicy(rotationId);
        assertArrayEquals(new int[]{1, 2}, loaded.allowedBlockLengths,
            "Loaded lengths should be back in slots: got " + Arrays.toString(loaded.allowedBlockLengths));
    }

    @Test
    void defaultPolicy_forUnconfiguredRotation_isOneFullBlock() throws SQLException {
        Rotation r = new Rotation(0, "Default Policy Rotation", "Test", 5, 0, 8, null);
        rotationId = rotationDAO.insert(r).getId();

        // No saveRotationPolicy call and no rotation_config row: loadRotationPolicy
        // returns a fresh RotationPolicy, whose in-memory default is one full block
        // ({2} slots). (This mirrors the DDL default of '4' weeks = 2 slots.)
        ScheduleConfig.RotationPolicy loaded = configDAO.loadRotationPolicy(rotationId);
        assertArrayEquals(new int[]{2}, loaded.allowedBlockLengths,
            "An unconfigured rotation should default to a single 2-slot full block");
    }

    private String readAllowedBlockLengthsColumn(int rotId) throws SQLException {
        Connection c = DatabaseManager.getInstance().getConnection();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT allowed_block_lengths FROM rotation_config WHERE rotation_id=?")) {
            ps.setInt(1, rotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
