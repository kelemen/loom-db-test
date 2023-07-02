package loomdbtest;

import java.util.Objects;

public record JdbcCredential(
        String username,
        String password
) {
    public JdbcCredential {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }

    public static JdbcCredential DEFAULT = new JdbcCredential("loomdbtest", "loomdbtest");
}
