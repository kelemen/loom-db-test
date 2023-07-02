package loomdbtest;

public interface ScopedDataSource extends AutoCloseable {
    <V> V withConnectionAndGet(ConnectionFunction<V> function) throws Exception;

    default void withConnection(ConnectionAction action) throws Exception {
        withConnectionAndGet(connection -> {
            action.run(connection);
            return null;
        });
    }

    @Override
    void close();
}
