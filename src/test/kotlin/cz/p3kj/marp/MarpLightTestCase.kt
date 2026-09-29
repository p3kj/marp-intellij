package cz.p3kj.marp

import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base for light platform tests.
 *
 * The unified IntelliJ IDEA 2026.2 distribution has two obfuscated classes named `Z.Z.Z.Z.Z` on the flattened test
 * classpath, so an Ultimate-only startup activity fails to instantiate when the test project opens and the test logger
 * turns that into a failure. Errors from `com.intellij.modules.ultimate` are unrelated to this plugin and are dropped.
 */
abstract class MarpLightTestCase : BasePlatformTestCase() {

    private var ignoreUltimateErrors: AccessToken? = null

    override fun setUp() {
        ignoreUltimateErrors = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                val text = message + " " + t?.message.orEmpty()
                return if (text.contains("com.intellij.modules.ultimate")) Action.NONE else super.processError(category, message, details, t)
            }
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
}
