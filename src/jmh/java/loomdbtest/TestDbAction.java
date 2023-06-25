package loomdbtest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.openjdk.jmh.infra.Blackhole;

public enum TestDbAction {
    DEFAULT {
        @Override
        public void initDb(Connection connection) throws SQLException {
            runDdl(connection, """
                    DROP TABLE IF EXISTS LOOM_DB_TEST_TABLE
                    """
            );
            createDummyTable(connection);
            insertDummyValues(connection);
        }
    },
    DEFAULT_SIMPLIFIED {
        @Override
        public void initDb(Connection connection) throws SQLException {
            if (tableExists(connection, "LOOM_DB_TEST_TABLE")) {
                runDdl(connection, """
                    DROP TABLE LOOM_DB_TEST_TABLE
                    """
                );
            }

            createDummyTable(connection);
            insertDummyValues(connection);
        }
    };

    public abstract void initDb(Connection connection) throws SQLException;

    public void run(Connection connection, Blackhole blackhole) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COL1 FROM LOOM_DB_TEST_TABLE");
                ResultSet rows = statement.executeQuery()
        ) {
            while (rows.next()) {
                blackhole.consume(rows);
            }
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet infoRows = connection
                .getMetaData()
                .getTables(null, connection.getSchema(), tableName, null)
        ) {
            return infoRows.next();
        }
    }

    private static void createDummyTable(Connection connection) throws SQLException {
        runDdl(connection, """
                CREATE TABLE LOOM_DB_TEST_TABLE (
                    COL1 VARCHAR(1) NOT NULL
                )
                """
        );
    }

    private static void insertDummyValues(Connection connection) throws SQLException {
        runDdl(connection, """
                INSERT INTO LOOM_DB_TEST_TABLE (COL1) VALUES ('A'), ('B'), ('C')
                """
        );
    }

    private static void runDdl(Connection connection, String ddl) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.execute();
        }
    }
}
