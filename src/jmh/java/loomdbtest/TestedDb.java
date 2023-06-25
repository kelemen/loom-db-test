package loomdbtest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import org.openjdk.jmh.infra.Blackhole;

public enum TestedDb {
    H2(
            true,
            TestDbAction.DEFAULT,
            new JdbcConnectionInfo("jdbc:h2:mem:dbpooltest")
    ),
    POSTGRES(
            false,
            TestDbAction.DEFAULT,
            new JdbcConnectionInfo(
                    "jdbc:postgresql://localhost:5432/loomdbtest",
                    new JdbcCredential("loomdbtest", "loomdbtest")
            )
    );

    private static final TestedDb TESTED_DB;

    static {
        var testedDbName = System.getProperty("loomdbtest.testedDb");
        try {
            TESTED_DB = TestedDb.valueOf(testedDbName);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(
                    "Invalid testedDb: " + testedDbName + ". Supported values are: " + Arrays.toString(TestedDb.values()),
                    e
            );
        }
    }

    private final boolean requireKeepAlive;
    private final TestDbAction dbAction;
    private final JdbcConnectionInfo connectionInfo;

    TestedDb(boolean requireKeepAlive, TestDbAction dbAction, JdbcConnectionInfo connectionInfo) {
        this.requireKeepAlive = requireKeepAlive;
        this.dbAction = dbAction;
        this.connectionInfo = connectionInfo;
    }

    public static TestedDb selectedTestedDb() {
        return TESTED_DB;
    }

    public boolean requireKeepAlive() {
        return requireKeepAlive;
    }

    public JdbcConnectionInfo connectionInfo() {
        return connectionInfo;
    }

    public Connection newConnection() throws SQLException {
        JdbcCredential credential = connectionInfo.credential();
        if (credential != null) {
            return DriverManager.getConnection(
                    connectionInfo.jdbcUrl(),
                    credential.username(),
                    credential.password()
            );
        } else {
            return DriverManager.getConnection(connectionInfo.jdbcUrl());
        }
    }

    public void initDb(Connection connection) throws SQLException {
        dbAction.initDb(connection);
    }

    public void testDbAction(Connection connection, Blackhole blackhole) throws SQLException {
        dbAction.run(connection, blackhole);
    }
}
