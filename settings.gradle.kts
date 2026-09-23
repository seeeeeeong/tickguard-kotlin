pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Lets a machine without JDK 21 build anyway: the toolchain is downloaded
    // rather than whatever `java` happens to be on the PATH being used.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // One place declares where dependencies come from. A module adding its own
    // repository is how an unreviewed artifact source slips in.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "tickguard"

include("core", "adapters", "app", "tools")
