package de.fayard.refreshVersions

import de.fayard.refreshVersions.BundledDependenciesTest.Files.dependencyMappingDescription
import de.fayard.refreshVersions.BundledDependenciesTest.Files.existingKeys
import de.fayard.refreshVersions.BundledDependenciesTest.Files.receivedKeys
import de.fayard.refreshVersions.BundledDependenciesTest.Files.removalsRevisionsHistoryFile
import de.fayard.refreshVersions.BundledDependenciesTest.Files.removeKeysDescription
import de.fayard.refreshVersions.BundledDependenciesTest.Files.validateMappingDescription
import de.fayard.refreshVersions.BundledDependenciesTest.Files.validatedDependencyMappingFile
import de.fayard.refreshVersions.BundledDependenciesTest.Files.versionKeysDescription
import de.fayard.refreshVersions.core.AbstractDependencyGroup
import de.fayard.refreshVersions.core.ModuleId.Maven
import de.fayard.refreshVersions.core.Version
import de.fayard.refreshVersions.core.internal.ArtifactVersionKeyReader
import de.fayard.refreshVersions.core.internal.DependencyMapping
import de.fayard.refreshVersions.internal.getArtifactNameToConstantMapping
import dependencies.ALL_DEPENDENCIES_NOTATIONS
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.haveSize
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.opentest4j.TestAbortedException
import testutils.getVersionCandidates
import testutils.isInCi
import testutils.parseRemovedDependencyNotations

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BundledDependenciesTest {

    private object Files {
        val rulesDir = mainResources.resolve("refreshVersions-rules")

        // We update the rules from a DependencyGroup(rawRules = "...")
        val rules = rulesDir.resolve("dependency-groups-alias-rules.txt")

        val removalsRevisionsHistoryFile = mainResources.resolve("removals-revisions-history.md")

        val validatedMappingFile = testResources.resolve("dependencies-mapping-validated.txt")
        val validateMappingDescription = """
            ## Generated by BundledDependenciesTest.`Removed dependency notations should be tracked`()
            ## This test makes sure we don't remove a dependency notation by accident
            ## If it's on purpose we should track them in the file "removals-revisions-history.md"
        """.trimIndent() + "\n"

        val existingKeys = testResources.resolve("dependencies-versions-key-validated.txt")
        val receivedKeys = testResources.resolve("dependencies-versions-key-received.txt")

        val versionKeysDescription = """
            ## Generated by BundledDependenciesTest.`Version keys should be up to date`()
            ## Make sure that the version keys you see there are the ones you want to see in versions.properties
        """.trimIndent() + "\n"

        val removedKeys = mainResources.resolve("removed-dependencies-versions-keys.txt")
        val removeKeysDescription = """
            ## Generated by BundledDependenciesTest.`Version keys should be up to date`()
            ## This file keeps track of version keys that have been removed
        """.trimIndent() + "\n"

        val validatedDependencyMappingFile = testResources.resolve("bundled-dependencies-validated.txt")
        val dependencyMappingDescription = """
            ## Generated by BundledDependenciesTest.`Version keys should be up to date`()
            ## This test makes sure that all bundled modules exist on mavenCentral() google() or gradlePluginPortal()
        """.trimIndent()  + "\n"
    }
    companion object {
        private fun Sequence<String>.withoutComments(): Sequence<String> =
            filterNot { it.startsWith("##") || it.isBlank() }

        @JvmStatic // Required for @BeforeAll
        @BeforeAll
        fun `Generate rule files for dependency groups with a rawRule`() {
            ALL_DEPENDENCIES_NOTATIONS // Ensure all objects are initialized.
            val file = Files.rules
            val content = AbstractDependencyGroup.ALL_RULES
                .sorted()
                .distinct()
                .joinToString(separator = "\n\n", postfix = "\n") { it.text() }
            if (file.readText() != content) file.writeText(content)
        }

        private var dependenciesExistInStandardMavenReposPassed = false

        @JvmStatic // Required for @BeforeAll
        @BeforeAll
        fun reset() {
            dependenciesExistInStandardMavenReposPassed = false
        }
    }

    @Test
    fun `The artifactVersionKeyRules property should contain all rules`() {
        val dirFileNames = Files.rulesDir
            .listFiles { file -> file.extension == "txt" }!!
            .map { it.name }
            .toSet()
        RefreshVersionsPlugin.artifactVersionKeyRulesFileNames shouldContainAll dirFileNames
    }

    @Test
    @Order(2)
    fun `Removed dependency notations should be tracked`() {
        checkDependenciesExistInStandardRepos()

        val existingMapping = Files.validatedMappingFile.useLines { lines ->
            lines.withoutComments().mapNotNull { DependencyMapping.fromLine(it) }.toSet()
        }
        val receivedMapping = getArtifactNameToConstantMapping().toSet()

        if (receivedMapping == existingMapping) return
        if (isInCi()) withClue("Run the tests locally and commit the changes to fix this") {
            fail("There are dependency mapping updates that haven't been committed!")
        }

        val removals = existingMapping - receivedMapping
        if (removals.isNotEmpty()) updateRemovalsRevisionsHistory(
            currentMapping = receivedMapping,
            removals = removals
        )

        Files.validatedMappingFile.writeText(
            receivedMapping.joinToString(separator = "\n", postfix = "\n", prefix = validateMappingDescription)
        )
    }

    private fun updateRemovalsRevisionsHistory(
        currentMapping: Set<DependencyMapping>,
        removals: Set<DependencyMapping>
    ) {
        val removalsRevisionsHistory = removalsRevisionsHistoryFile.readText()
        val hasWipHeading = removalsRevisionsHistory.lineSequence().any { it.startsWith("## [WIP]") }
        val extraText = buildString {
            run {
                val lineBreaks = when {
                    removalsRevisionsHistory.endsWith("\n\n") -> ""
                    removalsRevisionsHistory.endsWith('\n') -> "\n"
                    else -> "\n\n"
                }
                append(lineBreaks)
            }
            if (hasWipHeading.not()) {
                val lastRevision = removalsRevisionsHistory.lineSequence().last {
                    it.startsWith("## Revision ")
                }.substringAfter(
                    delimiter = "## Revision "
                ).substringBefore(
                    delimiter = ' ' // For cases like revision 11 where we have a comment in parentheses.
                ).toInt()
                appendLine("## [WIP] Revision ${lastRevision + 1}")
                appendLine()
            }
            val removedEntriesText = removals.joinToString(
                separator = "\n\n",
                postfix = "\n"
            ) { removedMapping ->
                val group = removedMapping.moduleId.group
                val name = removedMapping.moduleId.name
                val stillExistsWithAnotherName = currentMapping.any { it.moduleId == removedMapping.moduleId }
                if (stillExistsWithAnotherName) """
                    ~~${removedMapping.constantName}~~
                    id:[$group:$name]
                """.trimIndent() else """
                    ~~${removedMapping.constantName}~~
                    **Remove this line when comments are complete.**
                    // TODO: Put guidance comment lines here.
                    // We recommend prefixing them with "FIXME:" if the user should take further action,
                    // such as using new maven coordinates, or stop depending on the deprecated library.
                    moved:[<insert replacement group:name here, or remove this line>]
                    id:[$group:$name]
                    """.trimIndent()
            }
            append(removedEntriesText)
        }
        removalsRevisionsHistoryFile.appendText(extraText)
    }

    @Test
    fun `removals-revisions-history should parse correctly`() {
        parseRemovedDependencyNotations(mainResources.resolve("removals-revisions-history.md"))
    }

    @Test
    @Order(2)
    fun `Version keys should be up to date`() {
        checkDependenciesExistInStandardRepos()
        val versionKeyReader = ArtifactVersionKeyReader.fromRules(rulesDir.listFiles()!!.map { it.readText() })

        val existingMapping = existingKeys.useLines { lines ->
            lines.withoutComments().mapNotNull { DependencyMapping.fromLine(it) }.toSet()
        }

        val receivedMapping = getArtifactNameToConstantMapping().map {
            val key = versionKeyReader.readVersionKey(it.group, it.artifact) ?: "NO-RULE"
            it.copy(constantName = "version.$key")
        }.toSet()
        receivedKeys.writeText(
            receivedMapping.joinToString(separator = "\n", postfix = "\n", prefix = versionKeysDescription)
        )

        val breakingChanges = existingMapping - receivedMapping
        if (isInCi()) {
            withClue("diff -u ${existingKeys.absolutePath}  ${receivedKeys.absolutePath}") {
                breakingChanges should haveSize(0)
            }
            withClue("Changes to $existingKeys must be committed, but I got new entries") {
                (receivedMapping - existingMapping) should haveSize(0)
            }
        } else if (breakingChanges.isNotEmpty()) {
            val changesToWrite = breakingChanges.filter { it.constantName != "version.NO-RULE" }
            if (changesToWrite.isNotEmpty()) {
                val text = Files.removedKeys.useLines { lines ->
                    (lines.withoutComments() + breakingChanges).joinToString(
                        separator = "\n",
                        postfix = "\n",
                        prefix = removeKeysDescription
                    )
                }
                Files.removedKeys.writeText(text)
            }
        }
        receivedKeys.copyTo(existingKeys, overwrite = true)
        receivedKeys.deleteOnExit()
    }

    @Test
    @Order(2)
    fun `Dependencies should not be in the 'dependencies' package`() {
        checkDependenciesExistInStandardRepos()
        getArtifactNameToConstantMapping().forEach {
            if (it.constantName.startsWith("dependencies.")) {
                fail("This dependency should not be in the dependencies package: ${it.constantName}")
            }
            it.constantName.startsWith("dependencies.").shouldBeFalse()
        }
    }

    private fun checkDependenciesExistInStandardRepos() {
        if (dependenciesExistInStandardMavenReposPassed.not()) {
            throw TestAbortedException("Some dependencies don't exist in standard maven repos")
        }
    }

    @Test
    @Order(1)
    fun `test bundled dependencies exist in standard repositories`() {
        val validatedDependencyMapping = validatedDependencyMappingFile.useLines { lines ->
            lines.withoutComments().toSet()
        }

        // "standard repositories" are mavenCentral and google
        val reposUrls = listOf(
            "https://repo.maven.apache.org/maven2/",
            "https://dl.google.com/dl/android/maven2/",
            "https://plugins.gradle.org/m2/"
        )
        val mappingWithLines = getArtifactNameToConstantMapping().associateWith {
            "${it.group}:${it.artifact}"
        }

        val newValidatedMappings: List<Maven> = runBlocking {
            mappingWithLines.filter {
                it.value !in validatedDependencyMapping
            }.keys.map { dependencyMapping ->
                Maven(dependencyMapping.group, dependencyMapping.artifact)
            }.distinct().onEach { mavenModuleId ->
                launch {
                    val foundVersions: List<Version> = getVersionCandidates(
                        httpClient = defaultHttpClient,
                        mavenModuleId = mavenModuleId,
                        repoUrls = reposUrls,
                        currentVersion = Version("")
                    )
                    withClue("No versions found for $mavenModuleId! Is there a typo?") {
                        foundVersions shouldHaveAtLeastSize 1
                    }
                }
            }
        }

        when {
            newValidatedMappings.isEmpty() && validatedDependencyMapping.size == mappingWithLines.size -> return
            isInCi() -> withClue(
                "Unit tests must be run and changes to bundled-dependencies-validated.txt must be committed, " +
                    "but that wasn't the case for those dependency notations."
            ) {
                newValidatedMappings shouldBe emptyList()
            }
            else -> {
                val mappings = mappingWithLines.values.distinct().sorted()
                    .joinToString(separator = "\n", prefix = dependencyMappingDescription)
                validatedDependencyMappingFile.writeText(mappings)
            }
        }
        dependenciesExistInStandardMavenReposPassed = true
    }

    private val defaultHttpClient by lazy { createTestHttpClient() }

    private fun createTestHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor(logger = object : HttpLoggingInterceptor.Logger {
                override fun log(message: String) {
                    println(message)
                }
            }).setLevel(HttpLoggingInterceptor.Level.BASIC))
            .build()
    }

    private val rulesDir = mainResources.resolve("refreshVersions-rules")
}
