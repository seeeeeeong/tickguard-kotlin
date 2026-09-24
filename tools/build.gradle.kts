// Operator entry points: probe, backtest, verdict export and scoring. Plain
// mains that start in a second rather than booting the whole application, and
// the one place besides the runner allowed to print.
plugins {
    id("tickguard.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adapters"))
    // The verdict tools read the service's database directly, read-only.
    implementation(libs.sqlite.jdbc)
}

tasks.register<JavaExec>("probe") {
    group = "tickguard"
    description = "Checks the Toss API end to end: token, socket, subscription, ticks, pong."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.ProbeKt")
    // .env sits at the repository root, as it did for the original.
    workingDir = rootDir
}

tasks.register<JavaExec>("verdictExport") {
    group = "tickguard"
    description = "Writes stories to label, without the model's answers, to eval/verdict-labels.csv."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.VerdictExportKt")
    workingDir = rootDir
}

tasks.register<JavaExec>("verdictScore") {
    group = "tickguard"
    description = "Scores stored verdicts, or another model's, against the labels."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.VerdictScoreKt")
    workingDir = rootDir
}
