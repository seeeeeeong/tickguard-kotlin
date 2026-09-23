/**
 * Operator entry points: probe, backtest, verdict export and scoring. Plain
 * mains that start in a second rather than booting the whole application, and
 * the one place besides the runner allowed to print.
 */
plugins {
    id("tickguard.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adapters"))
}
