package cz.p3kj.marp.directives

import com.intellij.codeInspection.LocalInspectionEP
import cz.p3kj.marp.MarpLightTestCase

class MarpDirectiveInspectionTest : MarpLightTestCase() {

    private val deck = "---\nmarp: true\n---\n\n"

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(MarpDirectiveInspection::class.java)
    }

    private fun check(text: String) {
        myFixture.configureByText("deck.md", text)
        myFixture.checkHighlighting(true, false, false)
    }

    fun testIsRegisteredWithADescription() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.firstOrNull { it.shortName == "MarpDirective" }
        assertNotNull(ep)
        assertEquals(MarpDirectiveInspection::class.java.name, ep!!.implementationClass)
        assertEquals("Marp directive problems", ep.getDisplayName())
        assertEquals("Marp", ep.getGroupDisplayName())
        assertNotNull(MarpDirectiveInspection::class.java.getResource("/inspectionDescriptions/MarpDirective.html"))
    }

    fun testGlobalDirectiveWithUnderscore() {
        check(
            "$deck<!-- <warning descr=\"'_theme' is a global directive: it applies to the whole deck and has no '_' form, use 'theme'\">_theme</warning>: gaia -->\n",
        )
    }

    fun testUnknownDirectiveWithASuggestion() {
        check(
            "$deck<!--\n<warning descr=\"Unknown Marp directive '_clas', did you mean '_class'?\">_clas</warning>: lead\npaginate: true\n-->\n",
        )
    }

    fun testMisspelledDirectiveAloneIsAPresenterNote() {
        check(
            "$deck<!-- <warning descr=\"'_clas' is not a Marp directive, did you mean '_class'? This comment is shown as a presenter note.\">_clas</warning>: lead -->\n",
        )
    }

    fun testUnknownDirectiveNextToAKnownOne() {
        check(
            "$deck<!--\n_class: lead\n<warning descr=\"Unknown Marp directive 'sparkle'\">sparkle</warning>: yes\n-->\n",
        )
    }

    fun testNearMissInANoteIsReportedAsANote() {
        check(
            "$deck<!-- <warning descr=\"'Class' is not a Marp directive, did you mean 'class'? This comment is shown as a presenter note.\">Class</warning>: lead -->\n",
        )
    }

    fun testInvalidPaginate() {
        check("$deck<!-- paginate: <warning descr=\"Invalid value 'yes' for 'paginate', expected one of: true, false, hold, skip\">yes</warning> -->\n")
    }

    fun testInvalidMath() {
        check("$deck<!-- math: <warning descr=\"Invalid value 'foo' for 'math', expected one of: mathjax, katex\">foo</warning> -->\n")
    }

    fun testInvalidHeadingDivider() {
        check(
            "$deck<!-- headingDivider: <warning descr=\"Invalid value '9' for 'headingDivider', expected a level from 1 to 6, a list of levels or false\">9</warning> -->\n",
        )
    }

    fun testInvalidValueOfASpotDirectiveNamesTheKeyAsWritten() {
        check("$deck<!-- _paginate: <warning descr=\"Invalid value 'nope' for '_paginate', expected one of: true, false, hold, skip\">nope</warning> -->\n")
    }

    fun testInlineCommentInAParagraph() {
        check("${deck}Text <!-- paginate: <warning descr=\"Invalid value 'yes' for 'paginate', expected one of: true, false, hold, skip\">yes</warning> --> more\n")
    }

    fun testSeveralProblemsInOneComment() {
        check(
            "$deck<!--\n<warning descr=\"'_theme' is a global directive: it applies to the whole deck and has no '_' form, use 'theme'\">_theme</warning>: gaia\n" +
                "paginate: <warning descr=\"Invalid value 'maybe' for 'paginate', expected one of: true, false, hold, skip\">maybe</warning>\n-->\n",
        )
    }

    fun testValidCommentsAreQuiet() {
        check(
            """$deck<!-- Note: hello -->

<!-- _class: lead -->

<!-- paginate: TRUE -->

<!-- math: katex -->

<!-- headingDivider: [1, 2] -->

<!-- headingDivider: false -->

<!-- theme: gaia -->

<!-- header: '' -->

<!-- prettier-ignore -->

<!-- markdownlint-disable MD033 -->

<!-- Say hello, then show the demo. -->

<!-- Todo: buy milk -->

# Title <!-- fit -->
""",
        )
    }

    fun testBlockSequenceIsNotChecked() {
        check("$deck<!--\nheadingDivider:\n  - 1\n  - 3\n-->\n")
    }

    fun testTheNoteThatOnlyContainsAnUnderscoredGlobalIsStillReported() {
        check(
            "$deck<!-- <warning descr=\"'_theme' is a global directive: it applies to the whole deck and has no '_' form, use 'theme'\">_theme</warning>: gaia -->\n",
        )
    }

    fun testNothingInPlainMarkdown() {
        check("# Not a deck\n\n<!-- _theme: gaia -->\n\n<!-- paginate: yes -->\n")
    }

    fun testNothingInACodeBlock() {
        check("$deck```html\n<!-- _theme: gaia -->\n```\n")
    }
}
