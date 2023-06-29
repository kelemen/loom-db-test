package loomdbtest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public enum TestDbSetup {
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
    },
    DEFAULT_DERBY {
        @Override
        public void initDb(Connection connection) throws SQLException {
            DEFAULT_SIMPLIFIED.initDb(connection);
            if (functionExists(connection, "SLEEP")) {
                runDdl(connection, "DROP FUNCTION SLEEP");
            }
            runDdl(connection, """
                    CREATE FUNCTION SLEEP(SECONDS DOUBLE) RETURNS INT
                    PARAMETER STYLE JAVA NO SQL LANGUAGE JAVA
                    EXTERNAL NAME""" + " '" + TestDbSetup.class.getName() + ".sleepSeconds'"
            );
        }
    },
    DEFAULT_MSSQL {
        @Override
        public void initDb(Connection connection) throws SQLException {
            DEFAULT_SIMPLIFIED.initDb(connection);
            runDdl(connection, "DROP PROCEDURE IF EXISTS SLEEP");
            runDdl(connection, """
                    CREATE PROCEDURE SLEEP
                    (
                      @delaystr nvarchar(12)
                    )
                    AS BEGIN
                      WAITFOR DELAY @delaystr;
                      SELECT 1;
                    END
                    """
            );
        }
    };

    public abstract void initDb(Connection connection) throws SQLException;

    public static Integer sleepSeconds(double seconds) {
        try {
            Thread.sleep(Math.round(seconds * 1000.0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet infoRows = connection
                .getMetaData()
                .getTables(null, connection.getSchema(), tableName, null)
        ) {
            return infoRows.next();
        }
    }

    private static boolean functionExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet infoRows = connection
                .getMetaData()
                .getFunctions(null, connection.getSchema(), tableName)
        ) {
            return infoRows.next();
        }
    }

    private static void createDummyTable(Connection connection) throws SQLException {
        runDdl(connection, """
                CREATE TABLE LOOM_DB_TEST_TABLE (
                    COL1 VARCHAR(64) NOT NULL
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
