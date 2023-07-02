package loomdbtest;

import java.sql.Connection;

public interface ConnectionFunction<V> {
    V run(Connection connection) throws Exception;
}
