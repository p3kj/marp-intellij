package cz.p3kj.marp.preview

import com.intellij.openapi.application.ApplicationManager
import cz.p3kj.marp.MarpLightTestCase
import kotlinx.coroutines.runBlocking

/**
 * Guards the platform behaviour [MarpJcefStartup] relies on: reading the proxy settings creates the services that
 * `JBCefApp`'s class initializer would otherwise create on the first browser (`HttpConfigurable`, whose initialization
 * requests `ProxyMigrationService`). If the platform stops doing that, the startup IDE error may come back.
 */
class MarpJcefStartupTest : MarpLightTestCase() {

    fun testPrepareCreatesTheProxyServicesThatJcefStartupReads() {
        runBlocking { MarpJcefStartup.prepare() }

        val app = ApplicationManager.getApplication()
        for (name in listOf("com.intellij.util.net.HttpConfigurable", "com.intellij.util.net.internal.ProxyMigrationService")) {
            val serviceClass = Class.forName(name, false, app.javaClass.classLoader)
            assertNotNull("$name should exist after prepare()", app.getServiceIfCreated(serviceClass))
        }
    }
}
