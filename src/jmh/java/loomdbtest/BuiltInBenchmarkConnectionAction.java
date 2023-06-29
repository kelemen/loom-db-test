package loomdbtest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.openjdk.jmh.infra.Blackhole;

public enum BuiltInBenchmarkConnectionAction implements BenchmarkConnectionAction {
    DEFAULT_QUERY("SELECT COL1, RANDOM() AS R FROM LOOM_DB_TEST_TABLE"),
    DEFAULT_QUERY2("SELECT COL1, RAND() AS R FROM LOOM_DB_TEST_TABLE"),
    DEFAULT_SLEEP("SELECT SLEEP(0.06)"),
    DERBY_SLEEP("SELECT SLEEP(0.06) AS X FROM SYSIBM.SYSDUMMY1"),
    HSQL_SLEEP("SELECT SLEEP(0.06) AS X FROM INFORMATION_SCHEMA.SYSTEM_USERS"),
    MSSQL_SLEEP("{call dbo.SLEEP('00:00:00.06')}"),
    PG_SLEEP("SELECT pg_sleep(0.06)"),
    ORACLE_SLEEP((connection, blackhole) -> {
        execute(connection, "{call DBMS_SESSION.SLEEP(0.06)}");
    }),
    ORACLE_QUERY("SELECT COL1,dbms_random.value() AS R FROM LOOM_DB_TEST_TABLE"),
    INSERT_DELETE("") {
        @Override
        public void run(Connection connection, Blackhole blackhole) throws SQLException {
            String element = "X" + Thread.currentThread().threadId();
            execute(connection, "INSERT INTO LOOM_DB_TEST_TABLE (COL1) VALUES ('" + element +"')");
            execute(connection, "DELETE FROM LOOM_DB_TEST_TABLE WHERE COL1 = '" + element + "'");
        }
    };;

    private final BenchmarkConnectionAction action;

    BuiltInBenchmarkConnectionAction(String query) {
        this((connection, blackhole) -> {
            try (PreparedStatement statement = connection.prepareStatement(query);
                    ResultSet rows = statement.executeQuery()
            ) {
                while (rows.next()) {
                    blackhole.consume(rows);
                }
            }
        });
    }

    BuiltInBenchmarkConnectionAction(BenchmarkConnectionAction action) {
        this.action = action;
    }

    @Override
    public void run(Connection connection, Blackhole blackhole) throws Exception {
        action.run(connection, blackhole);
    }

    private static void execute(Connection connection, String ddl) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.execute();
        }
    }
}
