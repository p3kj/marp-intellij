package cz.p3kj.marp.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarpConfigurableUrlTest {

    @Test
    fun acceptsHttpAndHttpsUrlsWithAHost() {
        for (url in listOf("https://example.com/theme.css", "http://example.com", "HTTPS://cdn.example.com:8443/a/b.css?v=1", "https://localhost/x.css")) {
            assertTrue(url, MarpConfigurable.isValidHttpUrl(url))
        }
    }

    @Test
    fun rejectsEverythingElse() {
        for (url in listOf("", "https://", "https:///theme.css", "example.com/theme.css", "ftp://example.com/a.css", "file:///etc/passwd",
                           "javascript:alert(1)", "https://exa mple.com/a.css", "themes/a.css", "C:\\themes\\a.css")) {
            assertFalse(url, MarpConfigurable.isValidHttpUrl(url))
        }
    }
}
