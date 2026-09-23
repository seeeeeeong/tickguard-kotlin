// Formats the build scripts that belong to no module: the root's and build-logic's.
plugins {
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

spotless {
    kotlinGradle {
        target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
        ktlint(libs.findVersion("ktlint").get().requiredVersion)
    }
}
