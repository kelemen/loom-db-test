package loomdbtest;

import java.sql.Connection;

public record SqlScriptParameters(
        Connection connection,
        boolean sleep
) {
}
