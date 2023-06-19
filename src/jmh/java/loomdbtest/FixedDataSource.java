package loomdbtest;

import java.io.PrintWriter;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.ShardingKey;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.ConnectionFactory;
import org.jtrim2.utils.ExceptionHelper;

public final class FixedDataSource implements DataSource, AutoCloseable {
    private static final int CLOSED_CONNECTION_INDEX = -2;

    private final ConnectionFactory connectionFactory;
    private final Lock connectionsLock;
    private final Connection[] connections;
    private int nextConnectionIndex;

    private volatile PrintWriter logWriter;

    public FixedDataSource(
            int connectionCount,
            ConnectionFactory connectionFactory
    ) {
        ExceptionHelper.checkArgumentInRange(connectionCount, 1, Integer.MAX_VALUE, "connectionCount");

        this.connectionsLock = new ReentrantLock();
        this.connections = new Connection[connectionCount];
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.nextConnectionIndex = 0;

        this.logWriter = null;
    }

    private void returnConnection(Connection connection) throws SQLException {
        String errorMessage = null;
        connectionsLock.lock();
        try {
            int currentIndex = nextConnectionIndex;
            if (currentIndex <= 0) {
                errorMessage = currentIndex == CLOSED_CONNECTION_INDEX
                        ? "Returned too many connections."
                        : "The data source is closed.";
            } else {
                int returnIndex = currentIndex - 1;
                connections[returnIndex] = connection;
                nextConnectionIndex = returnIndex;
            }
        } finally {
            connectionsLock.unlock();
        }

        if (errorMessage != null) {
            if (connection != null) {
                connection.close();
            }
            throw new IllegalStateException(errorMessage);
        }
    }

    public Connection getConnection() throws SQLException {
        Connection result;
        connectionsLock.lock();
        try {
            int currentIndex = nextConnectionIndex;
            if (currentIndex == CLOSED_CONNECTION_INDEX) {
                throw new IllegalStateException("The data source is closed.");
            }
            if (currentIndex >= connections.length) {
                throw new IllegalStateException("Requested too many connections.");
            }
            result = connections[currentIndex];
            connections[currentIndex] = null;
            nextConnectionIndex = currentIndex + 1;
        } finally {
            connectionsLock.unlock();
        }

        try {
            if (result == null) {
                result = connectionFactory.createConnection();
            }
            return new PooledConnection(result);
        } catch (Throwable e) {
            returnConnection(result);
            throw e;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public void close() throws SQLException {
        List<Connection> toClose = new ArrayList<>();
        connectionsLock.lock();
        try {
            if (nextConnectionIndex == CLOSED_CONNECTION_INDEX) {
                return;
            }
            for (int i = 0; i < connections.length; i++) {
                Connection connection = connections[i];
                connections[i] = null;
                if (connection != null) {
                    toClose.add(connection);
                }
            }
        } finally {
            connectionsLock.unlock();
        }
        for (Connection connection : toClose) {
            connection.close();
        }
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getLoginTimeout() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not supported.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isAssignableFrom(getClass());
    }

    private final class PooledConnection implements Connection {
        private final Connection wrapped;
        private final AtomicBoolean closed;

        public PooledConnection(Connection wrapped) {
            this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
            this.closed = new AtomicBoolean(false);
        }

        @Override
        public Statement createStatement() throws SQLException {
            return wrapped.createStatement();
        }

        @Override
        public PreparedStatement prepareStatement(String sql) throws SQLException {
            return wrapped.prepareStatement(sql);
        }

        @Override
        public CallableStatement prepareCall(String sql) throws SQLException {
            return wrapped.prepareCall(sql);
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return wrapped.nativeSQL(sql);
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            wrapped.setAutoCommit(autoCommit);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return wrapped.getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            wrapped.commit();
        }

        @Override
        public void rollback() throws SQLException {
            wrapped.rollback();
        }

        @Override
        public void close() throws SQLException {
            if (closed.compareAndSet(false, true)) {
                returnConnection(wrapped);
            }
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public DatabaseMetaData getMetaData() throws SQLException {
            return wrapped.getMetaData();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            wrapped.setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return wrapped.isReadOnly();
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
            wrapped.setCatalog(catalog);
        }

        @Override
        public String getCatalog() throws SQLException {
            return wrapped.getCatalog();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            wrapped.setTransactionIsolation(level);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return wrapped.getTransactionIsolation();
        }

        @Override
        public SQLWarning getWarnings() throws SQLException {
            return wrapped.getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            wrapped.clearWarnings();
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return wrapped.createStatement(resultSetType, resultSetConcurrency);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return wrapped.prepareStatement(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return wrapped.prepareCall(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public Map<String, Class<?>> getTypeMap() throws SQLException {
            return wrapped.getTypeMap();
        }

        @Override
        public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
            wrapped.setTypeMap(map);
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            wrapped.setHoldability(holdability);
        }

        @Override
        public int getHoldability() throws SQLException {
            return wrapped.getHoldability();
        }

        @Override
        public Savepoint setSavepoint() throws SQLException {
            return wrapped.setSavepoint();
        }

        @Override
        public Savepoint setSavepoint(String name) throws SQLException {
            return wrapped.setSavepoint(name);
        }

        @Override
        public void rollback(Savepoint savepoint) throws SQLException {
            wrapped.rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(Savepoint savepoint) throws SQLException {
            wrapped.releaseSavepoint(savepoint);
        }

        @Override
        public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return wrapped.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return wrapped.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return wrapped.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return wrapped.prepareStatement(sql, autoGeneratedKeys);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return wrapped.prepareStatement(sql, columnIndexes);
        }

        @Override
        public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return wrapped.prepareStatement(sql, columnNames);
        }

        @Override
        public Clob createClob() throws SQLException {
            return wrapped.createClob();
        }

        @Override
        public Blob createBlob() throws SQLException {
            return wrapped.createBlob();
        }

        @Override
        public NClob createNClob() throws SQLException {
            return wrapped.createNClob();
        }

        @Override
        public SQLXML createSQLXML() throws SQLException {
            return wrapped.createSQLXML();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return wrapped.isValid(timeout);
        }

        @Override
        public void setClientInfo(String name, String value) throws SQLClientInfoException {
            wrapped.setClientInfo(name, value);
        }

        @Override
        public void setClientInfo(Properties properties) throws SQLClientInfoException {
            wrapped.setClientInfo(properties);
        }

        @Override
        public String getClientInfo(String name) throws SQLException {
            return wrapped.getClientInfo(name);
        }

        @Override
        public Properties getClientInfo() throws SQLException {
            return wrapped.getClientInfo();
        }

        @Override
        public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return wrapped.createArrayOf(typeName, elements);
        }

        @Override
        public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return wrapped.createStruct(typeName, attributes);
        }

        @Override
        public void setSchema(String schema) throws SQLException {
            wrapped.setSchema(schema);
        }

        @Override
        public String getSchema() throws SQLException {
            return wrapped.getSchema();
        }

        @Override
        public void abort(Executor executor) throws SQLException {
            wrapped.abort(executor);
        }

        @Override
        public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            wrapped.setNetworkTimeout(executor, milliseconds);
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return wrapped.getNetworkTimeout();
        }

        @Override
        public void beginRequest() throws SQLException {
            wrapped.beginRequest();
        }

        @Override
        public void endRequest() throws SQLException {
            wrapped.endRequest();
        }

        @Override
        public boolean setShardingKeyIfValid(ShardingKey shardingKey, ShardingKey superShardingKey, int timeout) throws SQLException {
            return wrapped.setShardingKeyIfValid(shardingKey, superShardingKey, timeout);
        }

        @Override
        public boolean setShardingKeyIfValid(ShardingKey shardingKey, int timeout) throws SQLException {
            return wrapped.setShardingKeyIfValid(shardingKey, timeout);
        }

        @Override
        public void setShardingKey(ShardingKey shardingKey, ShardingKey superShardingKey) throws SQLException {
            wrapped.setShardingKey(shardingKey, superShardingKey);
        }

        @Override
        public void setShardingKey(ShardingKey shardingKey) throws SQLException {
            wrapped.setShardingKey(shardingKey);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return wrapped.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return wrapped.isWrapperFor(iface);
        }
    }
}
