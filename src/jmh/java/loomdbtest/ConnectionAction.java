package loomdbtest;

import java.sql.Connection;

public interface ConnectionAction {
    void run(Connection connection) throws Exception;
}
