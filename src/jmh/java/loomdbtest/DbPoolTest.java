package loomdbtest;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final int PROCESSOR_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int DB_TASKS_PER_PROCESSOR = 4;

    /**
     * The maximum number of concurrent connections. If non-positive, then
     * it is relative to {@code Runtime.getRuntime().availableProcessors()}.
     * Or it can be "*X", then it will be X times the number of available
     * processors.
     */
    @Param("*4")
    private String poolSize;

    @Param
    private ConnectionActionType connectionAction;

    @Param
    private DbPoolType dbPoolType;

    @Param
    private ForkType forkType;

    @Param("0")
    private long cpuWork;

    @Param("0")
    private long cpuSleepMs;

    @Param("true")
    private boolean fullConcurrentTasks;

    private BenchmarkConnectionAction benchmarkConnectionAction;
    private ScopedDataSource dataSource;
    private DbKeepAliveReference keepAliveReference;
    private ForkScope globalForkScope;

    private static int normalizePoolSize(String paramPoolSize) {
        if (paramPoolSize.startsWith("*")) {
            int multiplier = Integer.parseInt(paramPoolSize.substring(1));
            return PROCESSOR_COUNT * multiplier;
        }
        int paramPoolSizeInt = Integer.parseInt(paramPoolSize);
        if (paramPoolSizeInt <= 0) {
            int result = PROCESSOR_COUNT + paramPoolSizeInt;
            if (result <= 0) {
                throw new IllegalArgumentException(
                        "Pool size cannot be set to " + paramPoolSizeInt
                                + ", because the number of available processors is " + PROCESSOR_COUNT
                );
            }
            return result;
        } else {
            return paramPoolSizeInt;
        }
    }

    private static void preopenConnections(int count, ScopedDataSource dataSource) throws Exception {
        if (count <= 0) {
            return;
        }

        dataSource.withConnection(connection -> {
            preopenConnections(count - 1, dataSource);
        });
    }

    @Setup
    public void setup() throws Exception {
        int actualPoolSize = normalizePoolSize(poolSize);
        benchmarkConnectionAction = connectionAction.createAction(actualPoolSize);
        dataSource = dbPoolType.newDataSource(actualPoolSize);

        var testedDb = TestedDb.selectedTestedDb();
        keepAliveReference = testedDb.keepAliveDb();

        dataSource.withConnection(testedDb::initDb);
        preopenConnections(actualPoolSize, dataSource);

        globalForkScope = exceptionTracker(forkType.newForkScope());
    }

    @TearDown
    public void tearDown() {
        closeAll(globalForkScope, keepAliveReference, dataSource);
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
            benchmarkConnectionAction.run(connection, blackhole);
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
                if (fullConcurrentTasks) {
                    for (UnsafeTask task : tasks) {
                        forkScope.fork(task);
                    }
                } else {
                    forkInSequence(forkScope, tasks);
                }
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

    public enum ConnectionActionType {
        DO_QUERY {
            @Override
            public BenchmarkConnectionAction createAction(int poolSize) {
                return TestedDb.selectedTestedDb()::testDbAction;
            }
        },
        SLEEP {
            @Override
            public BenchmarkConnectionAction createAction(int poolSize) {
                return (connection, blackhole) -> Thread.sleep(60);
            }
        },
        PINNING_SLEEP {
            @Override
            public BenchmarkConnectionAction createAction(int poolSize) {
                var lockQueue = new ArrayBlockingQueue<>(poolSize);
                for (int i = 0; i < poolSize; i++) {
                    lockQueue.add(new Object());
                }
                return (connection, blackhole) -> {
                    Object lock = lockQueue.poll();
                    if (lock == null) {
                        throw new IllegalStateException("Lock queue is empty.");
                    }
                    try {
                        blackhole.consume(lock);
                        synchronized (lock) {
                            Thread.sleep(60);
                        }
                    } finally {
                        lockQueue.add(lock);
                    }
                };
            }
        };

        public abstract BenchmarkConnectionAction createAction(int poolSize);
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
}
