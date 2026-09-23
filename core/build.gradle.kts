// Domain logic: rules, price windows, the subscription set, the pipeline.
//
// Nothing here may know about Spring, OkHttp or JDBC. That is kept true by
// none of them being on this module's classpath, which the compiler enforces;
// a convention only a reviewer enforces does not survive a busy week.
plugins {
    id("tickguard.kotlin-jvm")
    `java-test-fixtures`
}

dependencies {
    // Pure libraries with no I/O of their own: concurrency, and reading the
    // JSON the server sends and writing the declarations it expects.
    api(libs.coroutines.core)
    api(libs.serialization.json)

    testFixturesApi(libs.coroutines.core)
    testFixturesApi(libs.coroutines.test)
    // The store contract is a test suite each adapter runs against itself.
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.assertj.core)
}
