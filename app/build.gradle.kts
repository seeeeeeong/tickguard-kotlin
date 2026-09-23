/**
 * The running process: configuration, lifecycle, metrics, the status page.
 * The only module that knows Spring exists.
 */
plugins {
    id("tickguard.kotlin-jvm")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":adapters"))
}
