package tickguard.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Test

/**
 * The rules applied to the real modules.
 *
 * Empty checks are allowed while core has no classes yet; an empty module
 * satisfies every rule trivially, and the fixtures in RulesTest show each rule
 * would fail on a violation.
 */
class ArchitectureTest {
    private fun classesOf(vararg modules: String): JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .withImportOption { location -> modules.any { location.contains("/$it/build/") } }
            .importClasspath()

    @Test
    fun `core depends on no framework`() {
        Rules.frameworkFree().allowEmptyShould(true).check(classesOf("core"))
    }

    @Test
    fun `features do not depend on each other in a cycle`() {
        Rules.featuresAcyclic("tickguard").allowEmptyShould(true).check(classesOf(*MODULES))
    }

    @Test
    fun `nothing but token issuance writes to Toss`() {
        Rules
            .readOnly("tickguard.toss", "tickguard.toss.auth")
            .allowEmptyShould(true)
            .check(classesOf(*MODULES))
    }

    private companion object {
        /** Every module that ships code. The architecture module holds only tests. */
        val MODULES = arrayOf("core", "adapters", "app", "tools")
    }
}
