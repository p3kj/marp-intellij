package cz.p3kj.marp.preview

import org.cef.misc.CefPdfPrintSettings.MarginType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarpPdfSettingsTest {

    @Test
    fun theCssPageRuleDecidesSizeAndMargins() {
        val settings = MarpPdfSettings.create()
        assertTrue("marp-core's @page rule sets the page size of the deck", settings.prefer_css_page_size)
        assertEquals(MarginType.NONE, settings.margin_type)
        assertEquals(0.0, settings.margin_top, 0.0)
        assertEquals(0.0, settings.margin_right, 0.0)
        assertEquals(0.0, settings.margin_bottom, 0.0)
        assertEquals(0.0, settings.margin_left, 0.0)
        assertEquals(0.0, settings.paper_width, 0.0)
        assertEquals(0.0, settings.paper_height, 0.0)
        assertFalse(settings.landscape)
    }

    @Test
    fun slidesKeepTheirBackgroundsAtFullSize() {
        val settings = MarpPdfSettings.create()
        assertTrue(settings.print_background)
        assertEquals(1.0, settings.scale, 0.0)
    }

    @Test
    fun noHeaderFooterOutlineOrTags() {
        val settings = MarpPdfSettings.create()
        assertFalse(settings.display_header_footer)
        assertFalse(settings.generate_tagged_pdf)
        assertFalse(settings.generate_document_outline)
    }

    @Test
    fun noStringFieldIsNull() {
        val settings = MarpPdfSettings.create()
        assertEquals("", settings.page_ranges)
        assertEquals("", settings.header_template)
        assertEquals("", settings.footer_template)
    }

    @Test
    fun everyCallGivesAnObjectOfItsOwn() {
        assertNotSame(MarpPdfSettings.create(), MarpPdfSettings.create())
    }
}
