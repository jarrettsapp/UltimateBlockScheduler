import java.sql.*;

public class QueryCaps {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection c = DriverManager.getConnection("jdbc:sqlite:residency_scheduler.db");
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT r.id, r.name, rc.categorical_max_per_block, rc.categorical_soft_cap, rc.min_per_week, rc.max_per_week FROM rotations r LEFT JOIN rotation_config rc ON r.id = rc.rotation_id ORDER BY r.id");
        System.out.println("ID | Name | cat_max | cat_soft | min_per_block | max_per_block");
        System.out.println("---+------+---------+----------+--------------+---------------");
        while (rs.next()) {
            int id = rs.getInt(1);
            String name = rs.getString(2);
            Object catMax = rs.wasNull() ? "null" : rs.getInt(3);
            Object catSoft = rs.wasNull() ? "null" : rs.getInt(4);
            Object minPerBlock = rs.wasNull() ? "null" : rs.getInt(5);
            Object maxPerBlock = rs.wasNull() ? "null" : rs.getInt(6);
            System.out.printf("%d | %-30s | %-7s | %-8s | %-12s | %-13s%n", id, name, catMax, catSoft, minPerBlock, maxPerBlock);
        }
        c.close();
    }
}
