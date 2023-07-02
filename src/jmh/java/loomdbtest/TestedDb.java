package loomdbtest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum TestedDb {
    H2(
            connectionKeepAlive(),
            new JdbcConnectionInfo("jdbc:h2:mem:dbpooltest")
    ),
    HSQL(
            runCommandOnCloseKeepAlive("SHUTDOWN"),
            new JdbcConnectionInfo("jdbc:hsqldb:mem:dbpooltest")
    ),
    POSTGRES(
            noopKeepAlive(),
            new JdbcConnectionInfo(
                    "jdbc:postgresql://localhost:5432/loomdbtest",
                    JdbcCredential.DEFAULT
            )
    ),
    MARIA(
            noopKeepAlive(),
            new JdbcConnectionInfo(
                    "jdbc:mariadb://localhost:3306/loomdbtest",
                    JdbcCredential.DEFAULT
            )
    ),
    DERBY(
            javaDbKeepAlive("loomdbtest"),
            new JdbcConnectionInfo(
                    "jdbc:derby:memory:loomdbtest;create=true"
            )
    ),
    MSSQL(
            noopKeepAlive(),
            new JdbcConnectionInfo(
                    "jdbc:sqlserver://localhost:1433;encrypt=false;databaseName=loomdbtest;integratedSecurity=false;",
                    JdbcCredential.DEFAULT
            )
    ),
    ORACLE(
            noopKeepAlive(),
            new JdbcConnectionInfo(
                    "jdbc:oracle:thin:@localhost:1521/loomdbtest",
                    JdbcCredential.DEFAULT
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
    private final JdbcConnectionInfo connectionInfo;

    TestedDb(
            DbKeepAliveStarter keepAliveStarter,
            JdbcConnectionInfo connectionInfo
    ) {
        this.keepAliveStarter = keepAliveStarter;
        this.connectionInfo = connectionInfo;
    }

    private static String selectedTestDbSubtype() {
        return System.getProperty("loomdbtest.testedDbSubtype", "").trim().toUpperCase(Locale.ROOT);
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

    public BenchmarkConnectionAction initDb(Connection connection) throws SQLException {
        String dbName = name().toLowerCase(Locale.ROOT);
        boolean sleep = selectedTestDbSubtype().endsWith("SLEEP");

        List<String> initScripts;
        List<String> benchmarkScripts;
        try {
            initScripts = SqlScriptUtils.loadSqlScriptStatements(
                    new SqlScriptParameters(connection, sleep),
                    dbName,
                    "init"
            );
            printStatements("Init Statements", initScripts);

            benchmarkScripts = SqlScriptUtils.loadSqlScriptStatements(
                    new SqlScriptParameters(connection, sleep),
                    dbName,
                    "benchmark"
            );
            printStatements("Benchmark Statements", benchmarkScripts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        executeStatements(connection, initScripts);
        return toBenchmarkAction(benchmarkScripts);
    }

    private static void printStatements(String caption, List<String> statements) {
        System.out.println();
        System.out.println("## " + caption);
        statements.forEach(statement -> {
            System.out.println("<statement>");
            System.out.println(statement.trim());
            System.out.println("</statement>");
        });
    }

    private static BenchmarkConnectionAction toBenchmarkAction(
            List<String> actionScripts
    ) {
        return (connection, blackhole) -> {
            executeStatements(connection, actionScripts, resultSet -> {
                while (resultSet.next()) {
                    blackhole.consume(resultSet);
                }
            });
        };
    }

    private static void executeStatements(
            Connection connection,
            List<String> statements
    ) throws SQLException {
        executeStatements(connection, statements, resultSet -> { });
    }

    private static void executeStatements(
            Connection connection,
            List<String> statements,
            ResultSetAction resultSetAction
    ) throws SQLException {
        String actionId = "X" + Thread.currentThread().threadId();
        for (String statement : statements) {
            executeStatement(
                    connection,
                    statement.replace("@ACTION_ID@", actionId),
                    resultSetAction)
            ;
        }
    }

    private static void executeStatement(
            Connection connection,
            String statementStr,
            ResultSetAction resultSetAction
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (statement.execute(statementStr)) {
                try (ResultSet rows = statement.getResultSet()) {
                    resultSetAction.processResultSet(rows);
                }
            }
        }
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

    private interface ResultSetAction {
        void processResultSet(ResultSet resultSet) throws SQLException;
    }

    private interface DbKeepAliveStarter {
        DbKeepAliveReference keepAliveDb(TestedDb db) throws SQLException;
    }
}
