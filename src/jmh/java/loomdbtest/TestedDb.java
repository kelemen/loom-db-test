package loomdbtest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import org.openjdk.jmh.infra.Blackhole;

public enum TestedDb {
    H2(
            connectionKeepAlive(),
            TestDbSetup.DEFAULT,
            selectQuery(BuiltInBenchmarkConnectionAction.DEFAULT_SLEEP, BuiltInBenchmarkConnectionAction.INSERT_DELETE),
            new JdbcConnectionInfo("jdbc:h2:mem:dbpooltest")
    ),
    HSQL(
            runCommandOnCloseKeepAlive("SHUTDOWN"),
            TestDbSetup.DEFAULT,
            selectQuery(BuiltInBenchmarkConnectionAction.HSQL_SLEEP, BuiltInBenchmarkConnectionAction.INSERT_DELETE),
            new JdbcConnectionInfo("jdbc:hsqldb:mem:dbpooltest")
    ),
    POSTGRES(
            noopKeepAlive(),
            TestDbSetup.DEFAULT,
            selectQuery(BuiltInBenchmarkConnectionAction.PG_SLEEP, BuiltInBenchmarkConnectionAction.DEFAULT_QUERY),
            new JdbcConnectionInfo(
                    "jdbc:postgresql://localhost:5432/loomdbtest",
                    new JdbcCredential("loomdbtest", "loomdbtest")
            )
    ),
    MARIA(
            noopKeepAlive(),
            TestDbSetup.DEFAULT,
            selectQuery(BuiltInBenchmarkConnectionAction.DEFAULT_SLEEP, BuiltInBenchmarkConnectionAction.DEFAULT_QUERY2),
            new JdbcConnectionInfo(
                    "jdbc:mariadb://localhost:3306/loomdbtest",
                    new JdbcCredential("loomdbtest", "loomdbtest")
            )
    ),
    DERBY(
            javaDbKeepAlive("loomdbtest"),
            TestDbSetup.DEFAULT_DERBY,
            selectQuery(BuiltInBenchmarkConnectionAction.DERBY_SLEEP, BuiltInBenchmarkConnectionAction.DEFAULT_QUERY),
            new JdbcConnectionInfo(
                    "jdbc:derby:memory:loomdbtest;create=true"
            )
    ),
    MSSQL(
            noopKeepAlive(),
            TestDbSetup.DEFAULT_MSSQL,
            selectQuery(BuiltInBenchmarkConnectionAction.MSSQL_SLEEP, BuiltInBenchmarkConnectionAction.DEFAULT_QUERY2),
            new JdbcConnectionInfo(
                    "jdbc:sqlserver://localhost:1433;encrypt=false;databaseName=loomdbtest;integratedSecurity=false;",
                    new JdbcCredential("loomdbtest", "loomdbtest")
            )
    ),
    ORACLE(
            noopKeepAlive(),
            TestDbSetup.DEFAULT_ORACLE,
            selectQuery(BuiltInBenchmarkConnectionAction.ORACLE_SLEEP, BuiltInBenchmarkConnectionAction.DEFAULT_QUERY2),
            new JdbcConnectionInfo(
                    "jdbc:oracle:thin:@localhost:1521/loomdbtest",
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

    private final DbKeepAliveStarter keepAliveStarter;
    private final TestDbSetup testedDbSetup;
    private final BuiltInBenchmarkConnectionAction query;
    private final JdbcConnectionInfo connectionInfo;

    TestedDb(
            DbKeepAliveStarter keepAliveStarter,
            TestDbSetup testedDbSetup,
            BuiltInBenchmarkConnectionAction query,
            JdbcConnectionInfo connectionInfo
    ) {
        this.keepAliveStarter = keepAliveStarter;
        this.testedDbSetup = testedDbSetup;
        this.query = query;
        this.connectionInfo = connectionInfo;
    }

    private static String selectedTestDbSubtype() {
        return System.getProperty("loomdbtest.testedDbSubtype", "").trim().toUpperCase(Locale.ROOT);
    }

    private static BuiltInBenchmarkConnectionAction selectQuery(BuiltInBenchmarkConnectionAction sleepQuery, BuiltInBenchmarkConnectionAction normalQuery) {
        return selectedTestDbSubtype().endsWith("SLEEP") ? sleepQuery : normalQuery;
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
        testedDbSetup.initDb(connection);
    }

    public void testDbAction(Connection connection, Blackhole blackhole) throws Exception {
        query.run(connection, blackhole);
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
