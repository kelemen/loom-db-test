package loomdbtest;

import java.sql.SQLException;

public interface DbKeepAliveReference extends AutoCloseable {
    @Override
    void close() throws SQLException;
}
