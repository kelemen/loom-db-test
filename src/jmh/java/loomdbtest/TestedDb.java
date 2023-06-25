package loomdbtest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.openjdk.jmh.infra.Blackhole;

public enum TestedDb {
    H2(
            connectionKeepAlive(),
            TestDbAction.DEFAULT,
            new JdbcConnectionInfo("jdbc:h2:mem:dbpooltest")
    ),
    HSQL(
            runCommandOnCloseKeepAlive("SHUTDOWN"),
            TestDbAction.DEFAULT,
            new JdbcConnectionInfo("jdbc:hsqldb:mem:dbpooltest")
    ),
    POSTGRES(
            noopKeepAlive(),
            TestDbAction.DEFAULT,
            new JdbcConnectionInfo(
                    "jdbc:postgresql://localhost:5432/loomdbtest",
                    new JdbcCredential("loomdbtest", "loomdbtest")
            )
    ),
    JAVA_DB(
            javaDbKeepAlive("loomdbtest"),
            TestDbAction.DEFAULT_SIMPLIFIED,
            new JdbcConnectionInfo(
                    "jdbc:derby:memory:loomdbtest;create=true"
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

    private final DbKeepAliveStarter keepAliveStarter;
    private final TestDbAction dbAction;
    private final JdbcConnectionInfo connectionInfo;

    TestedDb(
            DbKeepAliveStarter keepAliveStarter,
            TestDbAction dbAction,
            JdbcConnectionInfo connectionInfo
    ) {
        this.keepAliveStarter = keepAliveStarter;
        this.dbAction = dbAction;
        this.connectionInfo = connectionInfo;
    }

    public static TestedDb selectedTestedDb() {
        return TESTED_DB;
    }

    public DbKeepAliveReference keepAliveDb() throws SQLException {
        return keepAliveStarter.keepAliveDb(this);
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

    private static DbKeepAliveStarter javaDbKeepAlive(String dbName) {
        return db -> () -> {
            try {
                DriverManager
                        .getConnection("jdbc:derby:memory:" + dbName + ";drop=true")
                        .close();
            } catch (SQLException e) {
                // Java DB responds with an exception on success
                if (!e.getSQLState().equals("08006")) {
                    throw e;
                }
                return;
            }
            throw new SQLException("Java DB did not shutdown as expected");
        };
    }

    private static DbKeepAliveStarter connectionKeepAlive() {
        return db -> db.newConnection()::close;
    }

    private static DbKeepAliveStarter noopKeepAlive() {
        return db -> () -> { };
    }

    private static DbKeepAliveStarter runCommandOnCloseKeepAlive(String command) {
        return db -> {
            return () -> {
                try (Connection connection = db.newConnection();
                        Statement statement = connection.createStatement()
                ) {
                    if (statement.execute(command)) {
                        statement.getResultSet().close();
                    }
                }
            };
        };
    }

    private interface DbKeepAliveStarter {
        DbKeepAliveReference keepAliveDb(TestedDb db) throws SQLException;
    }
}
