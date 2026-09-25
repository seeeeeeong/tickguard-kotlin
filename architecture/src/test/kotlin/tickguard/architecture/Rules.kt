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
     * Two Toss calls may write: token issuance, and order placement from the
     * execution module the user approved. Anything else that sends a body is an
     * order endpoint, and those hit a live account with real money. A client
     * that cannot express the call is a cheaper guarantee than remembering not
     * to make it.
     */
    fun readOnly(
        tossPackage: String,
        authPackage: String,
        executionPackage: String,
    ): ArchRule =
        noClasses()
            .that()
            .resideInAPackage("$tossPackage..")
            .and()
            .resideOutsideOfPackage("$authPackage..")
            .and()
            .resideOutsideOfPackage("$executionPackage..")
            .should()
            .callMethodWhere(writesARequest)
            .because("orders are placed only by the execution module")

    /** Every way to give a request a method that modifies or cancels rather than creates. */
    private val NOT_CREATE = setOf("put", "patch", "delete", "method")

    private val modifiesARequest =
        object : DescribedPredicate<JavaMethodCall>("put, patch, delete or method on okhttp3.Request.Builder") {
            override fun test(call: JavaMethodCall): Boolean =
                call.targetOwner.name == "okhttp3.Request\$Builder" && call.name in NOT_CREATE
        }

    /**
     * The execution module creates orders and nothing else: modifying and
     * cancelling stay forbidden, and a module that can only POST cannot reach
     * them by accident.
     */
    fun createsOnly(executionPackage: String): ArchRule =
        noClasses()
            .that()
            .resideInAPackage("$executionPackage..")
            .should()
            .callMethodWhere(modifiesARequest)
            .because("orders are never modified or cancelled by this project")
}
