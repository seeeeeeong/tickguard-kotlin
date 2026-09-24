// The running process: configuration, lifecycle, metrics, the status page.
// The only module that knows Spring exists.
plugins {
    id("tickguard.kotlin-jvm")
    id("org.springframework.boot")
    id("org.jetbrains.kotlin.plugin.spring")
}

dependencies {
    // Spring's versions, for Spring's libraries only. Where it pins something
    // this build also names (Kotlin, coroutines, sqlite-jdbc, JUnit), the newer
    // version is resolved, which is the one the other modules are built with.
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":core"))
    implementation(project(":adapters"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.micrometer.prometheus)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.mockwebserver)
    testImplementation(testFixtures(project(":core")))
}

// .env and tickguard.db sit at the repository root, as they did for the original.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootDir
}
