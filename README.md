This projects intends to test DB connection pooling and JDBC drivers with virtual threads.

## How to run

`./gradlew jmh -PtestedDb=<DB_NAME>`

The currently supported databases are (*DB_NAME* is in bold):

- **H2**
- **POSTGRES**: Postgres with the new virtual thread aware JDBC driver.
- **POSTGRES.OLD**: Postgres with the old driver using synchronized blocks.

You may set additional parameters for the benchmark by passing the `-Pbenchmark.<PARAMETER_NAME>=<PARAMETER_VALUE>`,
the supporter parameter names are:

- **poolSize**: The size of the connection pool. If non-positive, then it is relative to the number of threads.
- **connectionAction**: The action to do with a connection retrieved from the pool. The possible values are:
  - DO_QUERY: Execute a simple query.
  - SLEEP: Sleeps for 60 ms.
  - PINNING_SLEEP: Sleeps for 60 ms, but pins the carrier thread while sleeping.
- **dbPoolType**: The type of the connection pool. The possible values are:
  - DBCP2: Uses `BasicDataSource` of DBCP2.
  - SEMAPHORE: Uses a semaphore to limit the number of connections.
- **forkType**: The way to fork new tasks. The possible values are:
  - VIRTUAL_THREADS: Uses `Thread.startVirtualThread`.
  - LIMITED_EXECUTOR: Uses an executor with as many threads as returned by `Runtime.getRuntime().availableProcessors()`.
- **cpuWork**: The amount of CPU work to do in tasks not using a connection. The value is an integer as defined 
  by the `Blackhole.consumeCPU` method.
- **cpuSleepMs**: The number of ms to sleep in tasks not using a connection.
- **fullConcurrentTasks**: Set it to *true*, if you want all tasks to run concurrently. Otherwise, the tasks
  will run in a partially sequential manner. This is *false* by default.

If you want multiple values, then you can provide them as a comma separated list. For example:

`./gradlew jmh -PtestedDb=POSTGRES -Pbenchmark.poolSize=1,2`
