package loomdbtest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.jtrim2.cancel.Cancellation;
import org.jtrim2.concurrent.Tasks;
import org.jtrim2.concurrent.WaitableSignal;
import org.jtrim2.utils.ExceptionHelper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(1)
public class DbPoolTest {
    private static final TestedDb TESTED_DB = findTestedDb();
    private static final int PROCESSOR_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int DB_TASKS_PER_PROCESSOR = 4;

    /**
     * The maximum number of concurrent connections. If non-positive, then
     * it is relative to {@code Runtime.getRuntime().availableProcessors()}.
     */
    @Param({
            "1",
            "-1",
    })
    private int poolSize;

    @Param
    private ConnectionActionType connectionAction;

    @Param
    private DbPoolType dbPoolType;

    @Param
    private ForkType forkType;

    @Param({"0", "50000000"})
    private long cpuWork;

    @Param({"0", "60"})
    private long cpuSleepMs;

    private ScopedDataSource dataSource;
    private Connection keepAliveConnection;
    private ForkScope globalForkScope;

    private static TestedDb findTestedDb() {
        var testedDbName = System.getProperty("loomdbtest.testedDb");
        try {
            return TestedDb.valueOf(testedDbName);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(
                    "Invalid testedDb: " + testedDbName + ". Supported values are: " + Arrays.toString(TestedDb.values()),
                    e
            );
        }
    }

    private static int normalizePoolSize(int paramPoolSize) {
        if (paramPoolSize <= 0) {
            int result = PROCESSOR_COUNT + paramPoolSize;
            if (result <= 0) {
                throw new IllegalArgumentException(
                        "Pool size cannot be set to " + paramPoolSize
                                + ", because the number of available processors is " + PROCESSOR_COUNT
                );
            }
            return result;
        } else {
            return paramPoolSize;
        }
    }

    @Setup
    public void setup() throws Exception {
        dataSource = dbPoolType.newDataSource(normalizePoolSize(poolSize));
        if (TESTED_DB.requireKeepAlive()) {
            keepAliveConnection = TESTED_DB.newConnection();
        }

        dataSource.withConnection(TESTED_DB::initDb);

        globalForkScope = exceptionTracker(forkType.newForkScope());
    }

    @TearDown
    public void tearDown() {
        closeAll(globalForkScope, keepAliveConnection, dataSource);
    }

    private static void closeAll(AutoCloseable... resources) {
        Throwable toThrow = null;
        for (AutoCloseable resource : resources) {
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (Throwable e) {
                if (toThrow == null) toThrow = e;
                else toThrow.addSuppressed(e);
            }
        }
        ExceptionHelper.rethrowIfNotNull(toThrow);
    }

    private void doDbAction(Blackhole blackhole) throws Exception {
        dataSource.withConnection(connection -> {
            connectionAction.doDbAction(connection, blackhole);
        });
    }

    private void doCpuWork() throws Exception {
        Blackhole.consumeCPU(cpuWork);
        if (cpuSleepMs > 0) {
            Thread.sleep(cpuSleepMs);
        }
    }

    @Benchmark
    @Warmup(iterations = 3)
    public void testPools(Blackhole blackhole) {
        try (ForkScope forkScope = newChildScope(globalForkScope)) {
            UnsafeTask[] tasks = new UnsafeTask[]{
                    this::doCpuWork,
                    () -> doDbAction(blackhole),
                    this::doCpuWork,
                    () -> doDbAction(blackhole),
            };
            int loopCount = PROCESSOR_COUNT * DB_TASKS_PER_PROCESSOR / 2;
            for (int i = 0; i < loopCount; i++) {
                forkInSequence(forkScope, tasks);
            }
        }
    }

    private static void forkInSequence(ForkScope forkScope, UnsafeTask[] tasks) {
        forkInSequence(forkScope, 0, tasks);
    }

    private static void forkInSequence(ForkScope forkScope, int offset, UnsafeTask[] tasks) {
        if (offset >= tasks.length) {
            return;
        }

        forkScope.fork(() -> {
            tasks[offset].run();
            forkInSequence(forkScope, offset + 1, tasks);
        });
    }

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

    private interface ScopedDataSource extends AutoCloseable {
        void withConnection(ConnectionAction action) throws Exception;

        @Override
        void close();
    }

    private static ForkScope exceptionTracker(ForkScope scope) {
        AtomicReference<Throwable> firstErrorRef = new AtomicReference<>();
        return new ForkScope() {
            @Override
            public void fork(UnsafeTask task) {
                try {
                    scope.fork(task);
                } catch (Throwable e) {
                    firstErrorRef.compareAndSet(null, e);
                }
            }

            @Override
            public void close() {
                scope.close();
                ExceptionHelper.rethrowIfNotNull(firstErrorRef.get());
            }
        };
    }

    private static ForkScope newChildScope(ForkScope scope) {
        WaitableSignal doneSignal = new WaitableSignal();
        AtomicInteger outstandingTasks = new AtomicInteger(1);
        Runnable decTask = () -> {
            if (outstandingTasks.decrementAndGet() == 0) {
                doneSignal.signal();
            }
        };
        Runnable closeDecTask = Tasks.runOnceTask(decTask);
        AtomicInteger completedCount = new AtomicInteger(0);

        return new ForkScope() {
            @Override
            public void fork(UnsafeTask task) {
                outstandingTasks.incrementAndGet();
                scope.fork(() -> {
                    try {
                        task.run();
                    } finally {
                        completedCount.incrementAndGet();
                        decTask.run();
                    }
                });
            }

            @Override
            public void close() {
                closeDecTask.run();
                doneSignal.waitSignal(Cancellation.UNCANCELABLE_TOKEN);
            }
        };
    }

    private static ForkScope newVirtualThreadForkScope() {
        return new ForkScope() {
            @Override
            public void fork(UnsafeTask task) {
                Thread.startVirtualThread(task.toRunnable());
            }

            @Override
            public void close() {
            }
        };
    }

    private static ForkScope newExecutorForkScope(int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        return new ForkScope() {
            @Override
            public void fork(UnsafeTask task) {
                executor.execute(task.toRunnable());
            }

            @Override
            public void close() {
                try {
                    executor.shutdown();
                    if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
                        throw new IllegalStateException("Executor did not terminate in a reasonable time.");
                    }
                } catch (InterruptedException e) {
                    throw ExceptionHelper.throwUnchecked(e);
                }
            }
        };
    }

    private interface ForkScope extends AutoCloseable {
        void fork(UnsafeTask task);

        @Override
        void close();
    }

    private interface UnsafeTask {
        void run() throws Exception;

        default Runnable toRunnable() {
            return () -> {
                try {
                    run();
                } catch (Exception e) {
                    throw ExceptionHelper.throwUnchecked(e);
                }
            };
        }
    }

    private interface ConnectionAction {
        void run(Connection connection) throws Exception;
    }

    public enum ConnectionActionType {
        DO_QUERY {
            @Override
            public void doDbAction(Connection connection, Blackhole blackhole) throws Exception {
                TESTED_DB.testDbAction(connection, blackhole);
            }
        },
        SLEEP {
            @Override
            public void doDbAction(Connection connection, Blackhole blackhole) throws Exception {
                Thread.sleep(60);
            }
        };

        public abstract void doDbAction(Connection connection, Blackhole blackhole) throws Exception;
    }

    public enum ForkType {
        LIMITED_EXECUTOR {
            @Override
            public ForkScope newForkScope() {
                return newExecutorForkScope(PROCESSOR_COUNT);
            }
        },
        VIRTUAL_THREADS {
            @Override
            public ForkScope newForkScope() {
                return newVirtualThreadForkScope();
            }
        };

        public abstract ForkScope newForkScope();
    }

    public enum DbPoolType {
        DBCP2 {
            @Override
            public ScopedDataSource newDataSource(int poolSize) {
                var dataSource = new BasicDataSource();
                JdbcConnectionInfo connectionInfo = TESTED_DB.connectionInfo();
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
                var dataSource = new FixedDataSource(poolSize, TESTED_DB::newConnection);
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

        public abstract ScopedDataSource newDataSource(int poolSize);
    }
}
