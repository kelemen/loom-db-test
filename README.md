This projects intends to test DB connection pooling and JDBC drivers with virtual threads.

## How to run

`./gradlew jmh -PtestedDb=<DB_NAME>`

The currently supported databases are (*DB_NAME* is in bold):

- **H2**
- **H2.NOSYNC**: A modified version of H2 where intrinsic locks were replaced with jucl locks.
- **HSQL**
- **MARIA**: Maria DB.
- **POSTGRES**: Postgres with the new virtual thread aware JDBC driver.
- **POSTGRES.OLD**: Postgres with the old driver using synchronized blocks.
- **DERBY**: Derby aka. Java DB.
- **MSSQL**
- **ORACLE**
- **ORACLE.OLD**: Oracle with the old driver using synchronized blocks.

Optionally, all databases support a *.SLEEP* suffix where the *EXECUTE_SCRIPT* action just sleeps for 60 ms. For example,
you use *POSTGRES.SLEEP* instead of *POSTGRES* as the *DB_NAME*.

Note: Databases that are not run within the JVM are assumed to run on the localhost having a user "loomdbtest" with
password "loomdbtest", and full access to the "loomdbtest" database. Currently, these databases are the following:
MariaDB. Postgres and MsSQL.

You may set additional parameters for the benchmark by passing the `-Pbenchmark.<PARAMETER_NAME>=<PARAMETER_VALUE>`,
the supporter parameter names are:

- **poolSize**: The size of the connection pool. If non-positive, then it is relative to the number of threads.
  It can also be "*X", and in this case the pool size is the number of threads times X.
- **connectionAction**: The action to do with a connection retrieved from the pool. The possible values are:
  - EXECUTE_SCRIPT: Execute the benchmark.sql(.ftl) in the *sql-scripts* directory.
  - DO_NOTHING: Does nothing with the connection.
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

`./gradlew jmh -PtestedDb=POSTGRES -Pbenchmark.forkType=VIRTUAL_THREADS,LIMITED_EXECUTOR`

Even more conveniently: You can just run the provided `jmh.sh` script,
but instead of writing `-Pbenchmark.<PARAMETER_NAME>=<PARAMETER_VALUE>`
you can just write `--<PARAMETER_NAME>=<PARAMETER_VALUE>`. Similarly for the `-PtestedDb=<DB_NAME>`,
you can just write `--testedDb=<DB_NAME>`. Also, `jmh.sh` supports multiple comma separate values for
the `testedDb` parameter (unlike the Gradle command). For example:

`./jmh.sh --testedDb=POSTGRES,POSTGRES.OLD --forkType=VIRTUAL_THREADS,LIMITED_EXECUTOR`

### Custom scripts

The scripts run by the benchmark are in the *sql-scripts* directory. If you want to run a custom script,
you can either overwrite them, or pass the `-Ploomdbtest.sqlScriptDir=<REL_PATH>` parameter to the build
to replace its default value (which is "sql-scripts*). When calling `jmh.sh`, then the parameter is
`--sqlScriptDir=<REL_PATH>`.

The script files are processed by Freemarker. See the examples for the parameters available to the template.
