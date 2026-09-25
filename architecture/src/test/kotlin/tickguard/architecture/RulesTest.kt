package tickguard.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Each rule, against fixtures that break it, so a passing check means something. */
class RulesTest {
    private fun fixtures(pkg: String) = ClassFileImporter().importPackages(pkg)

    @Test
    fun `the framework rule catches a JDBC dependency`() {
        val result = Rules.frameworkFree().evaluate(fixtures("fixtures.framework"))

        assertThat(result.hasViolation()).isTrue()
        assertThat(result.failureReport.details).anyMatch { it.contains("java.sql.Connection") }
    }

    @Test
    fun `the cycle rule catches two features that import each other`() {
        val result = Rules.featuresAcyclic("fixtures.cycle").evaluate(fixtures("fixtures.cycle"))

        assertThat(result.hasViolation()).isTrue()
    }

    @Test
    fun `the read-only rule catches a request with a body outside token issuance`() {
        val result =
            Rules
                .readOnly(
                    "fixtures.toss",
                    "fixtures.toss.auth",
                    "fixtures.toss.execution",
                ).evaluate(fixtures("fixtures.toss"))

        assertThat(result.hasViolation()).isTrue()
        assertThat(result.failureReport.details)
            .anyMatch { it.contains("PlaceOrder") }
            .noneMatch { it.contains("IssueToken") }
            .noneMatch { it.contains("CreateOrder") }
    }

    @Test
    fun `the creates-only rule catches the execution module modifying or cancelling an order`() {
        val result = Rules.createsOnly("fixtures.toss.execution").evaluate(fixtures("fixtures.toss.execution"))

        assertThat(result.hasViolation()).isTrue()
        assertThat(result.failureReport.details)
            .anyMatch { it.contains("CancelOrder") }
            .noneMatch { it.contains("CreateOrder") }
    }
}
