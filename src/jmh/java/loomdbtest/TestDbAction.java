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
            runDdl(connection, """
                    CREATE TABLE LOOM_DB_TEST_TABLE (
                        COL1 VARCHAR(1) NOT NULL
                    )
                    """
            );
            runDdl(connection, """
                    INSERT INTO LOOM_DB_TEST_TABLE (COL1) VALUES ('A'), ('B'), ('C')
                    """
            );
        }

        @Override
        public void run(Connection connection, Blackhole blackhole) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COL1 FROM LOOM_DB_TEST_TABLE");
                 ResultSet rows = statement.executeQuery()
            ) {
                while (rows.next()) {
                    blackhole.consume(rows);
                }
            }
        }
    };
    public abstract void initDb(Connection connection) throws SQLException;
    public abstract void run(Connection connection, Blackhole blackhole) throws SQLException;

    private static void runDdl(Connection connection, String ddl) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.execute();
        }
    }
}
