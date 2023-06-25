This projects intends to test DB connection pooling and JDBC drivers with virtual threads.

## How to run

`./gradlew jmh -PtestedDb=<DB_NAME>`

The currently supported databases are (*DB_NAME* is in bold):

- **H2**
- **HSQL**
- **POSTGRES**: Postgres with the new virtual thread aware JDBC driver.
- **POSTGRES.OLD**: Postgres with the old driver using synchronized blocks.
- **JAVA_DB**: Java DB (Derby).

You may set additional parameters for the benchmark by passing the `-Pbenchmark.<PARAMETER_NAME>=<PARAMETER_VALUE>`,
the supporter parameter names are:

- **poolSize**: The size of the connection pool. If non-positive, then it is relative to the number of threads.
  It can also be "*X", and in this case the pool size is the number of threads times X.
- **connectionAction**: The action to do with a connection retrieved from the pool. The possible values are:
  - DO_QUERY: Execute a simple query.
  - SLEEP: Sleeps for 60 ms.
  - PINNING_SLEEP: Sleeps for 60 ms, but pins the carrier thread while sleeping.
- **dbPoolType**: The type of the connection pool. The possible values are:
  - DBCP2: Uses `BasicDataSource` of DBCP2.
  - HIKARI: Uses `HikariDataSource` of HikariCP.
  - C3P0: Uses `ComboPooledDataSource` of C3P0.
  - VIBUR: Uses `ViburDBCPDataSource` of Vibur.
  - SEMAPHORE: Uses a semaphore to limit the number of connections.
- **forkType**: The way to fork new tasks. The possible values are:
  - VIRTUAL_THREADS: Uses `Thread.startVirtualThread`.
  - LIMITED_EXECUTOR: Uses an executor with as many threads as returned by `Runtime.getRuntime().availableProcessors()`.
- **cpuWork**: The amount of CPU work to do in tasks not using a connection. The value is an integer as defined 
  by the `Blackhole.consumeCPU` method.
- **cpuSleepMs**: The number of ms to sleep in tasks not using a connection.
- **fullConcurrentTasks**: Set it to *false* to run the tasks in a partially sequential manner. Otherwise
  all tasks will be just submitted to run concurrently. This is *true* by default.

If you want multiple values, then you can provide them as a comma separated list. For example:

`./gradlew jmh -PtestedDb=POSTGRES -Pbenchmark.poolSize=1,2`

Even more conveniently: You can just run the provided `jmh.sh` script,
but instead of writing `-Pbenchmark.<PARAMETER_NAME>=<PARAMETER_VALUE>`
you can just write `--<PARAMETER_NAME>=<PARAMETER_VALUE>`. Similarly for the `-PtestedDb=<DB_NAME>`,
you can just write `--testedDb=<DB_NAME>`. Also, `jmh.sh` supports multiple comma separate values for
the `testedDb` parameter (unlike the Gradle command). For example:

`./jmh.sh --testedDb=POSTGRES,POSTGRES.OLD --poolSize=1,2`
