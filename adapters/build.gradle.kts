/**
 * Everything that talks to the outside: the Toss socket and REST API, the
 * database, Slack, the model, news sources. Implements the ports core declares.
 */
plugins {
    id("tickguard.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
}
