/**
 * Domain logic: rules, price windows, the subscription set, the pipeline.
 *
 * Nothing here may know about Spring, OkHttp or JDBC. That is kept true by
 * none of them being on this module's classpath, which the compiler enforces;
 * a convention only a reviewer enforces does not survive a busy week.
 */
plugins {
    id("tickguard.kotlin-jvm")
}
