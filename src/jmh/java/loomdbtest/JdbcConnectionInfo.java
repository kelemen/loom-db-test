package loomdbtest;

import java.util.Objects;

public record JdbcConnectionInfo(
        String jdbcUrl,
        JdbcCredential credential
) {
    public JdbcConnectionInfo {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    }

    public JdbcConnectionInfo(String jdbcUrl) {
        this(jdbcUrl, null);
    }
}
