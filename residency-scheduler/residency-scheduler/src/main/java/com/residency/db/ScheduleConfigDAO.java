package com.residency.db;

import com.residency.cpsat.ScheduleConfig;
import com.residency.model.ScheduleUnits;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists ScheduleConfig (global weights, solver params) and per-rotation
 * policies (block lengths, weekly staffing caps) to SQLite.
 */
public class ScheduleConfigDAO extends BaseDAO {

    public ScheduleConfigDAO() throws SQLException {
        super();
    }

    // ── Global config ──────────────────────────────────────────────────────

    public ScheduleConfig loadConfig() throws SQLException {
        ScheduleConfig cfg = new ScheduleConfig();
        String sql = "SELECT config_key, config_value FROM schedule_config";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String k = rs.getString("config_key");
                String v = rs.getString("config_value");
                applyConfigEntry(cfg, k, v);
            }
        }
        // Load per-rotation policies
        loadRotationPolicies(cfg);
        return cfg;
    }

    public void saveConfig(ScheduleConfig cfg) throws SQLException {
        upsertKey("weight_undercoverage",    String.valueOf(cfg.getWeightUndercoverage()));
        upsertKey("weight_overcoverage",     String.valueOf(cfg.getWeightOvercoverage()));
        upsertKey("weight_variance",         String.valueOf(cfg.getWeightVariance()));
        upsertKey("weight_pgy_imbalance",    String.valueOf(cfg.getWeightPgyImbalance()));
        upsertKey("weight_four_plus_two",    String.valueOf(cfg.getWeightFourPlusTwo()));
        upsertKey("weight_inpatient_split",  String.valueOf(cfg.getWeightInpatientSplit()));
        upsertKey("weight_post_call_hard",   String.valueOf(cfg.getWeightPostCallHard()));
        upsertKey("weight_post_call_soft",   String.valueOf(cfg.getWeightPostCallSoft()));
        upsertKey("post_call_trigger_ids",   joinIds(cfg.getPostCallTriggerRotationIds()));
        upsertKey("mandatory_attendance_ids",joinIds(cfg.getMandatoryAttendanceRotationIds()));
        upsertKey("discouraged_after_trigger_ids", joinIds(cfg.getDiscouragedAfterTriggerIds()));
        upsertKey("weight_sunday_coverage",  String.valueOf(cfg.getWeightSundayCoverage()));
        upsertKey("sunday_coverage_target",  String.valueOf(cfg.getSundayCoverageTarget()));
        upsertKey("heavy_rotation_ids",      joinIds(cfg.getHeavyRotationIds()));
        upsertKey("sunday_source_rotation_ids", joinIds(cfg.getSundaySourceRotationIds()));
        upsertKey("global_max_workload",    String.valueOf(cfg.getGlobalMaxWorkloadBlocks() * 2)); // DB stores in weeks
        upsertKey("global_min_workload",    String.valueOf(cfg.getGlobalMinWorkloadBlocks() * 2));
        upsertKey("cpsat_time_limit",       String.valueOf(cfg.getCpSatTimeLimitSeconds()));
        upsertKey("cpsat_num_workers",      String.valueOf(cfg.getCpSatNumWorkers()));
        upsertKey("cpsat_log_search",       String.valueOf(cfg.isCpSatLogSearch()));
        upsertKey("total_blocks",           String.valueOf(cfg.getTotalBlocks()));
        saveRotationPolicies(cfg);
    }

    // ── Rotation policies ──────────────────────────────────────────────────

    public ScheduleConfig.RotationPolicy loadRotationPolicy(int rotationId) throws SQLException {
        String sql = "SELECT * FROM rotation_config WHERE rotation_id = ?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, rotationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapPolicy(rs);
            }
        }
        return new ScheduleConfig.RotationPolicy(rotationId);
    }

    public void saveRotationPolicy(ScheduleConfig.RotationPolicy policy) throws SQLException {
        // allowedBlockLengths is held in 2-week slots in memory; the DB column is in
        // weeks. Convert slots -> weeks for storage. See ScheduleUnits / REVIEW.md M2.
        String lengths = Arrays.stream(policy.allowedBlockLengths)
            .mapToObj(l -> String.valueOf(ScheduleUnits.slotsToWeeks(l))).collect(Collectors.joining(","));
        String sql = """
            INSERT INTO rotation_config
                (rotation_id, allowed_block_lengths, requires_consecutive,
                 min_per_week, max_per_week, optional_full_year,
                 no_back_to_back_half_blocks, require_break_between_segments,
                 mutually_non_adjacent_with, max_consecutive_weeks, earliest_start_block,
                 require_even_block_start, categorical_max_per_block)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(rotation_id) DO UPDATE SET
                allowed_block_lengths=excluded.allowed_block_lengths,
                requires_consecutive=excluded.requires_consecutive,
                min_per_week=excluded.min_per_week,
                max_per_week=excluded.max_per_week,
                optional_full_year=excluded.optional_full_year,
                no_back_to_back_half_blocks=excluded.no_back_to_back_half_blocks,
                require_break_between_segments=excluded.require_break_between_segments,
                mutually_non_adjacent_with=excluded.mutually_non_adjacent_with,
                max_consecutive_weeks=excluded.max_consecutive_weeks,
                earliest_start_block=excluded.earliest_start_block,
                require_even_block_start=excluded.require_even_block_start,
                categorical_max_per_block=excluded.categorical_max_per_block
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, policy.rotationId);
            ps.setString(2, lengths);
            ps.setInt(3, policy.requiresConsecutive ? 1 : 0);
            ps.setInt(4, policy.minPerBlock);
            ps.setInt(5, policy.maxPerBlock);
            ps.setInt(6, policy.optionalFullYearCoverage ? 1 : 0);
            ps.setInt(7, policy.noBackToBackHalfBlocks ? 1 : 0);
            ps.setInt(8, policy.requireBreakBetweenSegments ? 1 : 0);
            ps.setString(9, policy.mutuallyNonAdjacentWith.stream()
                .map(String::valueOf).collect(Collectors.joining(",")));
            ps.setInt(10, policy.maxConsecutiveBlocks * 2); // DB stores in weeks
            ps.setInt(11, policy.earliestStartBlock);
            ps.setInt(12, policy.requireEvenBlockStart ? 1 : 0);
            ps.setInt(13, policy.categoricalMaxPerBlock);
            ps.executeUpdate();
        }
        // Save PGY caps
        for (var entry : policy.pgyMinMax.entrySet()) {
            upsertPgyCap(policy.rotationId, entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }
    }

    public void upsertPgyCap(int rotationId, int pgyLevel, int minPerWeek, int maxPerWeek)
            throws SQLException {
        String sql = """
            INSERT INTO rotation_pgy_caps (rotation_id, pgy_level, min_per_week, max_per_week)
            VALUES (?,?,?,?)
            ON CONFLICT(rotation_id, pgy_level) DO UPDATE SET
                min_per_week=excluded.min_per_week,
                max_per_week=excluded.max_per_week
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, rotationId);
            ps.setInt(2, pgyLevel);
            ps.setInt(3, minPerWeek);
            ps.setInt(4, maxPerWeek);
            ps.executeUpdate();
        }
    }

    public void saveSolverRun(int year, String engine, String status,
                               Integer hard, Integer medium, Integer soft,
                               long runtimeMs, boolean feasible, String summary)
            throws SQLException {
        String sql = """
            INSERT INTO solver_runs
                (schedule_year, engine, run_at, status, hard_score, medium_score,
                 soft_score, runtime_ms, feasible, summary)
            VALUES (?,?,datetime('now'),?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setString(2, engine);
            ps.setString(3, status);
            if (hard   != null) ps.setInt(4, hard);   else ps.setNull(4, Types.INTEGER);
            if (medium != null) ps.setInt(5, medium); else ps.setNull(5, Types.INTEGER);
            if (soft   != null) ps.setInt(6, soft);   else ps.setNull(6, Types.INTEGER);
            ps.setLong(7, runtimeMs);
            ps.setInt(8, feasible ? 1 : 0);
            ps.setString(9, summary);
            ps.executeUpdate();
        }
    }

    public ResultSet getRecentRuns(int year) throws SQLException {
        String sql = """
            SELECT * FROM solver_runs WHERE schedule_year=?
            ORDER BY run_at DESC LIMIT 20
            """;
        PreparedStatement ps = getConn().prepareStatement(sql);
        ps.setInt(1, year);
        return ps.executeQuery();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void applyConfigEntry(ScheduleConfig cfg, String key, String value) {
        try {
            switch (key) {
                case "weight_undercoverage"   -> cfg.setWeightUndercoverage(Integer.parseInt(value));
                case "weight_overcoverage"    -> cfg.setWeightOvercoverage(Integer.parseInt(value));
                case "weight_variance"        -> cfg.setWeightVariance(Integer.parseInt(value));
                case "weight_pgy_imbalance"   -> cfg.setWeightPgyImbalance(Integer.parseInt(value));
                case "weight_four_plus_two"   -> cfg.setWeightFourPlusTwo(Integer.parseInt(value));
                case "weight_inpatient_split" -> cfg.setWeightInpatientSplit(Integer.parseInt(value));
                case "weight_post_call_hard"  -> cfg.setWeightPostCallHard(Integer.parseInt(value));
                case "weight_post_call_soft"  -> cfg.setWeightPostCallSoft(Integer.parseInt(value));
                case "post_call_trigger_ids"  -> cfg.setPostCallTriggerRotationIds(parseIds(value));
                case "mandatory_attendance_ids" -> cfg.setMandatoryAttendanceRotationIds(parseIds(value));
                case "discouraged_after_trigger_ids" -> cfg.setDiscouragedAfterTriggerIds(parseIds(value));
                case "weight_sunday_coverage"  -> cfg.setWeightSundayCoverage(Integer.parseInt(value));
                case "sunday_coverage_target"  -> cfg.setSundayCoverageTarget(Integer.parseInt(value));
                case "heavy_rotation_ids"      -> cfg.setHeavyRotationIds(parseIds(value));
                case "sunday_source_rotation_ids" -> cfg.setSundaySourceRotationIds(parseIds(value));
                case "global_max_workload"   -> cfg.setGlobalMaxWorkloadBlocks(Math.max(1, Integer.parseInt(value) / 2)); // DB stored in weeks
                case "global_min_workload"   -> cfg.setGlobalMinWorkloadBlocks(Integer.parseInt(value) / 2);
                case "cpsat_time_limit"      -> cfg.setCpSatTimeLimitSeconds(Integer.parseInt(value));
                case "cpsat_num_workers"     -> cfg.setCpSatNumWorkers(Integer.parseInt(value));
                case "cpsat_log_search"      -> cfg.setCpSatLogSearch(Boolean.parseBoolean(value));
                case "total_weeks"           -> cfg.setTotalBlocks(Integer.parseInt(value) / 2); // legacy: weeks→blocks
                case "total_blocks"          -> cfg.setTotalBlocks(Integer.parseInt(value));
            }
        } catch (NumberFormatException ignored) {}
    }

    private void loadRotationPolicies(ScheduleConfig cfg) throws SQLException {
        String sql = "SELECT * FROM rotation_config";
        try (Statement stmt = getConn().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ScheduleConfig.RotationPolicy policy = mapPolicy(rs);
                // Load PGY caps
                loadPgyCaps(policy);
                cfg.getRotationPolicies().put(policy.rotationId, policy);
            }
        }
    }

    private void loadPgyCaps(ScheduleConfig.RotationPolicy policy) throws SQLException {
        String sql = "SELECT * FROM rotation_pgy_caps WHERE rotation_id=?";
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setInt(1, policy.rotationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int pgy = rs.getInt("pgy_level");
                    int min = rs.getInt("min_per_week");
                    int max = rs.getInt("max_per_week");
                    policy.pgyMinMax.put(pgy, new int[]{min, max});
                }
            }
        }
    }

    private void saveRotationPolicies(ScheduleConfig cfg) throws SQLException {
        for (ScheduleConfig.RotationPolicy policy : cfg.getRotationPolicies().values()) {
            saveRotationPolicy(policy);
        }
    }

    private ScheduleConfig.RotationPolicy mapPolicy(ResultSet rs) throws SQLException {
        ScheduleConfig.RotationPolicy p = new ScheduleConfig.RotationPolicy(rs.getInt("rotation_id"));
        String[] lengths = rs.getString("allowed_block_lengths").split(",");
        // DB stores lengths in weeks; convert to 2-week slots (min 1 slot).
        p.allowedBlockLengths = Arrays.stream(lengths)
            .mapToInt(s -> Math.max(1, ScheduleUnits.weeksToSlots(Integer.parseInt(s.trim())))).toArray();
        p.requiresConsecutive         = rs.getInt("requires_consecutive") == 1;
        p.minPerBlock                 = rs.getInt("min_per_week");   // column still named min_per_week in DB
        p.maxPerBlock                 = rs.getInt("max_per_week");
        p.optionalFullYearCoverage    = rs.getInt("optional_full_year") == 1;
        p.noBackToBackHalfBlocks      = rs.getInt("no_back_to_back_half_blocks") == 1;
        p.requireBreakBetweenSegments = rs.getInt("require_break_between_segments") == 1;
        String adjIds = rs.getString("mutually_non_adjacent_with");
        if (adjIds != null && !adjIds.isBlank()) {
            for (String s : adjIds.split(",")) {
                try { p.mutuallyNonAdjacentWith.add(Integer.parseInt(s.trim())); }
                catch (NumberFormatException ignored) {}
            }
        }
        // DB stores max_consecutive_weeks in weeks; convert to blocks (÷2, min 0).
        int rawConsec = rs.getInt("max_consecutive_weeks");
        p.maxConsecutiveBlocks = rawConsec > 0 ? Math.max(1, rawConsec / 2) : 0;
        try { p.earliestStartBlock = rs.getInt("earliest_start_block"); }
        catch (SQLException ignored) {}
        try { p.requireEvenBlockStart = rs.getInt("require_even_block_start") == 1; }
        catch (SQLException ignored) {}
        try { p.categoricalMaxPerBlock = rs.getInt("categorical_max_per_block"); }
        catch (SQLException ignored) {}
        return p;
    }

    private static String joinIds(Set<Integer> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Set<Integer> parseIds(String value) {
        Set<Integer> result = new HashSet<>();
        if (value == null || value.isBlank()) return result;
        for (String s : value.split(",")) {
            try { result.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private void upsertKey(String key, String value) throws SQLException {
        String sql = """
            INSERT INTO schedule_config (config_key, config_value) VALUES (?,?)
            ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value
            """;
        try (PreparedStatement ps = getConn().prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
