package loomdbtest;

public interface ScopedDataSource extends AutoCloseable {
    void withConnection(ConnectionAction action) throws Exception;

    @Override
    void close();
}
