/**
 * What every module compiles and tests with, so no module can quietly opt out.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    // 21 is the LTS every target host already runs; nothing here needs later.
    jvmToolchain(21)
    compilerOptions {
        // A warning left in place is a warning read by nobody. The TypeScript
        // original failed on type errors for the same reason.
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
