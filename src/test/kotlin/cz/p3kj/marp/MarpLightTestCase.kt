package cz.p3kj.marp

import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base for light platform tests.
 *
 * The unified IntelliJ IDEA 2026.2 distribution has two obfuscated classes named `Z.Z.Z.Z.Z` on the flattened test
 * classpath, so an Ultimate-only startup activity fails to instantiate when the test project opens and the test logger
 * turns that into a failure. Only that exact error (`Cannot create extension (class=Z.Z.Z.Z.Z) [Plugin:
 * com.intellij.modules.ultimate]`, logged by `ExtensionPointName`) is dropped, so a real error that merely mentions the
 * Ultimate module still fails the test.
 */
abstract class MarpLightTestCase : BasePlatformTestCase() {

    private var ignoreUltimateErrors: AccessToken? = null

    override fun setUp() {
        ignoreUltimateErrors = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (isUltimateStartupError(category, message)) Action.NONE else super.processError(category, message, details, t)
        })
        super.setUp()
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            ignoreUltimateErrors?.close()
        }
    }

    private companion object {
        fun isUltimateStartupError(category: String, message: String): Boolean =
            category.endsWith("com.intellij.openapi.extensions.ExtensionPointName") &&
                message.startsWith("Cannot create extension (class=Z.Z.Z.Z.Z)") &&
                message.contains("[Plugin: com.intellij.modules.ultimate]")
    }
}
