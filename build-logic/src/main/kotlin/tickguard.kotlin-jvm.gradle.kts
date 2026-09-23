// What every module compiles and tests with, so no module can quietly opt out.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("tickguard.quality")
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
    "testImplementation"(libs.findLibrary("coroutines-test").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

// Tests drive virtual time with runCurrent and advanceTimeBy, which the
// coroutines test library still marks experimental. Production code may not.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
