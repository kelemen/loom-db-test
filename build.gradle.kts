plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.1"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(20))
    }
}

val enableLoomJvmArgs = listOf(
        "--enable-preview",
        "--add-modules=jdk.incubator.concurrent"
)

tasks.jmhRunBytecodeGenerator.configure {
    jvmArgs.set(enableLoomJvmArgs)
}

val selectedDb = providers
        .gradleProperty("testedDb")
        .getOrElse("H2")
        .uppercase()

jmh {
    val parsedSelectedDb = selectedDb
            .indexOf('.')
            .takeIf { it >= 0 }
            ?.let { selectedDb.substring(0, it) to selectedDb.substring(it + 1) }
            ?: (selectedDb to "")

    val extraJvmArgs = listOf(
            "-Dloomdbtest.testedDb=${parsedSelectedDb.first}",
            "-Dloomdbtest.testedDbSubtype=${parsedSelectedDb.second}",
    )
    jvmArgsAppend.set(extraJvmArgs + enableLoomJvmArgs)

    val setBenchmarkParameter = { name: String ->
        val listValue = objects.listProperty<String>()
        listValue.set(providers
                .gradleProperty("benchmark.$name")
                .map { rawValue ->
                    rawValue.split(",").map { it.trim() }
                })
        if (listValue.isPresent) {
            benchmarkParameters.put(name, listValue)
        }
    }
    setBenchmarkParameter("poolSize")
    setBenchmarkParameter("connectionAction")
    setBenchmarkParameter("dbPoolType")
    setBenchmarkParameter("forkType")
    setBenchmarkParameter("cpuWork")
    setBenchmarkParameter("cpuSleepMs")
    setBenchmarkParameter("fullConcurrentTasks")
}

tasks.withType<JavaCompile>().configureEach {
    options.apply {
        encoding = "UTF-8"
        compilerArgs = compilerArgs + enableLoomJvmArgs
    }
}

dependencies {
    implementation("org.jtrim2:jtrim-stream:2.0.6")
    implementation("org.apache.commons:commons-dbcp2:2.9.0")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("com.mchange:c3p0:0.9.5.5")
    implementation("org.vibur:vibur-dbcp:25.0")

    when (selectedDb) {
        "H2" -> runtimeOnly("com.h2database:h2:2.1.214")
        "H2.SLEEP" -> runtimeOnly("com.github.kelemen.mods.h2.sleep:h2:2.1.214")
        "H2.NOSYNC", "H2.NOSYNC.SLEEP" -> runtimeOnly("com.github.kelemen.mods.h2.nosync:h2:2.1.214")
        "HSQL" -> runtimeOnly("org.hsqldb:hsqldb:2.7.2")
        "HSQL.SLEEP" -> runtimeOnly("com.github.kelemen.mods.hsqldb.sleep:hsqldb:2.7.2")
        "MARIA" -> runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.1.4")
        "POSTGRES" -> runtimeOnly("org.postgresql:postgresql:42.6.0")
        "POSTGRES.OLD" -> runtimeOnly("org.postgresql:postgresql:42.4.3")
        "DERBY", "DERBY.SLEEP" -> runtimeOnly("org.apache.derby:derby:10.16.1.1")
        "MSSQL", "MSSQL.SLEEP" -> runtimeOnly("com.microsoft.sqlserver:mssql-jdbc:12.2.0.jre11")
    }
}
