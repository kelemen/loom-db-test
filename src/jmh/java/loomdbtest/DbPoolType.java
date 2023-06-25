package loomdbtest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.jtrim2.utils.ExceptionHelper;

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
    SEMAPHORE {
        @Override
        public ScopedDataSource newDataSource(int poolSize) {
            var dbLimiter = new Semaphore(poolSize);
            var dataSource = new FixedDataSource(poolSize, TestedDb.selectedTestedDb()::newConnection);
            return new ScopedDataSource() {
                @Override
                public void withConnection(ConnectionAction action) throws Exception {
                    dbLimiter.acquire();
                    try (Connection connection = dataSource.getConnection()) {
                        action.run(connection);
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
        return new ScopedDataSource() {
            @Override
            public void withConnection(ConnectionAction action) throws Exception {
                try (Connection connection = dataSource.getConnection()) {
                    action.run(connection);
                }
            }

            @Override
            public void close() {
                try {
                    dataSource.close();
                } catch (Exception e) {
                    throw ExceptionHelper.throwUnchecked(e);
                }
            }
        };
    }

    public abstract ScopedDataSource newDataSource(int poolSize);
}
