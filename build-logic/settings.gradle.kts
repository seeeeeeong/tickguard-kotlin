dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // The same catalog as the main build, so a version is written down once.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
