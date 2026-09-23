// Operator entry points: probe, backtest, verdict export and scoring. Plain
// mains that start in a second rather than booting the whole application, and
// the one place besides the runner allowed to print.
plugins {
    id("tickguard.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adapters"))
}

tasks.register<JavaExec>("probe") {
    group = "tickguard"
    description = "Checks the Toss API end to end: token, socket, subscription, ticks, pong."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.ProbeKt")
    // .env sits at the repository root, as it did for the original.
    workingDir = rootDir
}
