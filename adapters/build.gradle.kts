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

    testImplementation(libs.mockwebserver)
    testImplementation(testFixtures(project(":core")))
}
