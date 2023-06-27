package loomdbtest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.openjdk.jmh.infra.Blackhole;

public enum TestQuery {
    DEFAULT_QUERY("SELECT COL1, RANDOM() AS R FROM LOOM_DB_TEST_TABLE"),
    DEFAULT_QUERY2("SELECT COL1, RAND() AS R FROM LOOM_DB_TEST_TABLE"),
    DEFAULT_SLEEP("SELECT SLEEP(0.06)"),
    DERBY_SLEEP("SELECT SLEEP(0.06) AS X FROM SYSIBM.SYSDUMMY1"),
    HSQL_SLEEP("SELECT SLEEP(0.06) AS X FROM INFORMATION_SCHEMA.SYSTEM_USERS"),
    MSSQL_SLEEP("{call dbo.SLEEP('00:00:00.06')}"),
    PG_SLEEP("SELECT pg_sleep(0.06)");

    private final String query;

    TestQuery(String query) {
        this.query = query;
    }

    public void runQuery(Connection connection, Blackhole blackhole) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query);
                ResultSet rows = statement.executeQuery()
        ) {
            while (rows.next()) {
                blackhole.consume(rows);
            }
        }
    }
}
