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
    // The migration compares row counts on both sides over plain JDBC.
    implementation(libs.postgresql)

    testImplementation(libs.testcontainers.postgresql)
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

tasks.register<JavaExec>("backtest") {
    group = "tickguard"
    description = "Replays recorded ticks through a rule and reports what followed each fire."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.BacktestKt")
    workingDir = rootDir
}

tasks.register<JavaExec>("parity") {
    group = "tickguard"
    description = "Compares the Kotlin replay of a database with the original's golden, byte for byte."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.ParityKt")
    workingDir = rootDir
}

tasks.register<JavaExec>("migrateToPostgres") {
    group = "tickguard"
    description = "Copies a SQLite database into an empty Postgres schema and compares row counts."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.MigrateToPostgresKt")
    workingDir = rootDir
}

tasks.register<JavaExec>("backfillBars") {
    group = "tickguard"
    description = "Fetches daily bars for backtests into the store. Stop the service first: it issues a token."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.BackfillBarsKt")
    workingDir = rootDir
}

tasks.register<JavaExec>("strategyReport") {
    group = "tickguard"
    description =
        "Compares the candidate strategies with holding on stored daily bars, over the whole span and each half."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("tickguard.tools.StrategyReportKt")
    workingDir = rootDir
}
