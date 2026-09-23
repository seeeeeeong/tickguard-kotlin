// Rules about the code rather than behaviour of it: which module may depend
// on what, and what no Toss call may do. Test-only; it depends on every module
// so the rules see all of them at once.
plugins {
    id("tickguard.kotlin-jvm")
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":adapters"))
    testImplementation(project(":app"))
    testImplementation(project(":tools"))
    testImplementation(libs.archunit)
    testImplementation(libs.konsist)
    // Only so a fixture can make the call the read-only rule forbids.
    testImplementation(libs.okhttp)
}
