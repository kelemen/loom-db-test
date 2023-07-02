package loomdbtest;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.jtrim2.utils.ExceptionHelper;
import org.vibur.dbcp.ViburDBCPDataSource;

public enum DbPoolType {
    DBCP2 {
        @Override
        public ScopedDataSource newDataSource(int poolSize) {
            var dataSource = new BasicDataSource();
            JdbcConnectionInfo connectionInfo = TestedDb.selectedTestedDb().connectionInfo();
            dataSource.setUrl(connectionInfo.jdbcUrl());
            JdbcCredential credential = connectionInfo.credential();
            if (credential != null) {
                dataSource.setUsername(credential.username());
                dataSource.setPassword(credential.password());
            }

            dataSource.setMaxIdle(poolSize);
            dataSource.setMaxTotal(poolSize);
            return fromDataSource(dataSource);
        }
    },
    HIKARI {
        @Override
        public ScopedDataSource newDataSource(int poolSize) {
            var config = new HikariConfig();
            JdbcConnectionInfo connectionInfo = TestedDb.selectedTestedDb().connectionInfo();
            config.setJdbcUrl(connectionInfo.jdbcUrl());
            JdbcCredential credential = connectionInfo.credential();
            if (credential != null) {
                config.setUsername(credential.username());
                config.setPassword(credential.password());
            }

            config.setMaximumPoolSize(poolSize);
            config.setConnectionTimeout(Long.MAX_VALUE);
            return fromDataSource(new HikariDataSource(config));
        }
    },
    C3P0 {
        @Override
        public ScopedDataSource newDataSource(int poolSize) {
            var dataSource = new ComboPooledDataSource();
            JdbcConnectionInfo connectionInfo = TestedDb.selectedTestedDb().connectionInfo();
            dataSource.setJdbcUrl(connectionInfo.jdbcUrl());
            JdbcCredential credential = connectionInfo.credential();
            if (credential != null) {
                dataSource.setUser(credential.username());
                dataSource.setPassword(credential.password());
            }

            dataSource.setMaxIdleTime(poolSize);
            dataSource.setMaxPoolSize(poolSize);
            return fromDataSource(dataSource, dataSource::close);
        }
    },
    VIBUR {
        @Override
        public ScopedDataSource newDataSource(int poolSize) {
            var dataSource = new ViburDBCPDataSource();
            JdbcConnectionInfo connectionInfo = TestedDb.selectedTestedDb().connectionInfo();
            dataSource.setJdbcUrl(connectionInfo.jdbcUrl());
            JdbcCredential credential = connectionInfo.credential();
            if (credential != null) {
                dataSource.setUsername(credential.username());
                dataSource.setPassword(credential.password());
            } else {
                dataSource.setUsername("");
                dataSource.setPassword("");
            }

            dataSource.setPoolMaxSize(poolSize);

            dataSource.start();
            return fromDataSource(dataSource);
        }
    },
    SEMAPHORE {
        @Override
        public ScopedDataSource newDataSource(int poolSize) {
            var dbLimiter = new Semaphore(poolSize);
            var dataSource = new FixedDataSource(poolSize, TestedDb.selectedTestedDb()::newConnection);
            return new ScopedDataSource() {
                @Override
                public <V> V withConnectionAndGet(ConnectionFunction<V> function) throws Exception {
                    dbLimiter.acquire();
                    try (Connection connection = dataSource.getConnection()) {
                        return function.run(connection);
                    } finally {
                        dbLimiter.release();
                    }
                }

                @Override
                public void close() {
                    try {
                        dataSource.close();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }
    };

    private static <T extends DataSource & AutoCloseable> ScopedDataSource fromDataSource(
            T dataSource
    ) {
        return fromDataSource(dataSource, dataSource);
    }

    private static ScopedDataSource fromDataSource(
            DataSource dataSource,
            AutoCloseable closeMethod
    ) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(closeMethod, "closeMethod");

        return new ScopedDataSource() {
            @Override
            public <V> V withConnectionAndGet(ConnectionFunction<V> function) throws Exception {
                try (Connection connection = dataSource.getConnection()) {
                    return function.run(connection);
                }
            }

            @Override
            public void close() {
                try {
                    closeMethod.close();
                } catch (Exception e) {
                    throw ExceptionHelper.throwUnchecked(e);
                }
            }
        };
    }

    public abstract ScopedDataSource newDataSource(int poolSize);
}
