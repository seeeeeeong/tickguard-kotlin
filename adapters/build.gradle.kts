// Everything that talks to the outside: the Toss socket and REST API, the
// database, Slack, the model, news sources. Implements the ports core declares.
plugins {
    id("tickguard.kotlin-jvm")
}

dependencies {
    api(project(":core"))
    // One HTTP stack for the socket and REST alike: one set of timeouts, one
    // way a failure is classified, one test server for both.
    api(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    // In-process and one file to back up, as node:sqlite was for the original.
    implementation(libs.sqlite.jdbc)
    // The store once it outgrows one file: a job queue and a ledger need
    // transactions across processes, and backtests want SQL over the ticks.
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    testImplementation(libs.mockwebserver)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.testcontainers.postgresql)
}
