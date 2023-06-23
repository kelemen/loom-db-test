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
    val selectedDbName = selectedDb
            .indexOf('.')
            .takeIf { it >= 0 }
            ?.let { selectedDb.substring(0, it) }
            ?: selectedDb
    jvmArgsAppend.set(listOf("-Dloomdbtest.testedDb=${selectedDbName}") + enableLoomJvmArgs)

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

    when (selectedDb) {
        "H2" -> runtimeOnly("com.h2database:h2:2.1.214")
        "POSTGRES" -> runtimeOnly("org.postgresql:postgresql:42.6.0")
        "POSTGRES.OLD" -> runtimeOnly("org.postgresql:postgresql:42.4.3")
    }
}
