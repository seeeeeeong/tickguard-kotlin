// Formatting and static analysis, applied to every module by tickguard.kotlin-jvm.
//
// Both run under `check`, so the build that CI runs is the build that fails.
plugins {
    id("com.diffplug.spotless")
    id("dev.detekt")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val ktlintVersion = libs.findVersion("ktlint").get().requiredVersion

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion)
    }
}

detekt {
    // Only what differs from the defaults is written down, so the config file
    // reads as a list of decisions rather than a copy of the manual.
    buildUponDefaultConfig.set(true)
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// The plain `detekt` task runs without type resolution, and every rule that
// needs types is skipped without a word: the BigDecimal and println bans among
// them. `check` runs the type-resolved tasks instead, and the plain one is off
// so nobody reads its clean result as meaningful.
tasks.named("detekt") { enabled = false }
tasks.named("check") { dependsOn("detektMain", "detektTest") }
