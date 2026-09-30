package cz.p3kj.marp.preview

import org.cef.misc.CefPdfPrintSettings

/**
 * Settings for printing the preview page to PDF. Every field is set on purpose: the constructor of
 * [CefPdfPrintSettings] leaves `margin_type` and the strings `null`, and JCEF does not accept that.
 *
 * The page size and the margins come from the CSS: marp-core's `@page { size: <w>px <h>px; margin: 0 }` follows the
 * `size` directive of the deck, so `prefer_css_page_size` makes every slide one page of the deck's own size.
 */
internal object MarpPdfSettings {
    fun create(): CefPdfPrintSettings = CefPdfPrintSettings().apply {
        landscape = false
        print_background = true
        scale = 1.0
        paper_width = 0.0
        paper_height = 0.0
        prefer_css_page_size = true
        margin_type = CefPdfPrintSettings.MarginType.NONE
        margin_top = 0.0
        margin_right = 0.0
        margin_bottom = 0.0
        margin_left = 0.0
        page_ranges = ""
        display_header_footer = false
        header_template = ""
        footer_template = ""
        generate_tagged_pdf = false
        generate_document_outline = false
    }
}
