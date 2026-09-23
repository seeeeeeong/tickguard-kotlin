package tickguard.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

/**
 * The rules, apart from the classes they are checked against, so each can be
 * shown to fail on a deliberate violation before it is trusted to pass.
 */
internal object Rules {
    /** Every way to give an OkHttp request a body, and so a method other than GET. */
    private val WRITE_METHODS = setOf("post", "put", "patch", "delete", "method")

    private val writesARequest =
        object : DescribedPredicate<JavaMethodCall>("a write method on okhttp3.Request.Builder") {
            override fun test(call: JavaMethodCall): Boolean =
                call.targetOwner.name == "okhttp3.Request\$Builder" && call.name in WRITE_METHODS
        }

    /** Core is the domain; I/O belongs to adapters and the running process belongs to app. */
    fun frameworkFree(): ArchRule =
        noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "okhttp3..", "java.sql..", "javax.sql..")
            .because("core holds the domain, and a framework on its classpath is how I/O leaks into it")

    /** Features are packages, and two that need each other are one feature split badly. */
    fun featuresAcyclic(root: String): ArchRule = slices().matching("$root.(*)..").should().beFreeOfCycles()

    /**
     * The one Toss call allowed to write is token issuance. Anything else that
     * sends a body is an order endpoint, and those hit a live account with real
     * money. A client that cannot express the call is a cheaper guarantee than
     * remembering not to make it.
     */
    fun readOnly(
        tossPackage: String,
        authPackage: String,
    ): ArchRule =
        noClasses()
            .that()
            .resideInAPackage("$tossPackage..")
            .and()
            .resideOutsideOfPackage("$authPackage..")
            .should()
            .callMethodWhere(writesARequest)
            .because("this project never places, modifies or cancels orders")
}
