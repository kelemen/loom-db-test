rootProject.name = "loom-db-test"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            setUrl(file("custom-dependencies-repo"))
            content {
                includeGroupByRegex("com[.]github[.]kelemen[.]mods[.].*")
            }
        }
        google()
        mavenCentral()
    }
}
