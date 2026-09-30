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
        myFixture.checkHighlighting(true, false, true)
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
            "$deck<!-- <weak_warning descr=\"'_clas' is not a Marp directive, did you mean '_class'? This comment is shown as a presenter note.\">_clas</weak_warning>: lead -->\n",
        )
    }

    fun testUnknownDirectiveNextToAKnownOne() {
        check(
            "$deck<!--\n_class: lead\n<warning descr=\"Unknown Marp directive 'sparkle'\">sparkle</warning>: yes\n-->\n",
        )
    }

    fun testNearMissInANoteIsReportedAsANote() {
        check(
            "$deck<!-- <weak_warning descr=\"'Class' is not a Marp directive, did you mean 'class'? This comment is shown as a presenter note.\">Class</weak_warning>: lead -->\n",
        )
    }

    fun testInvalidPaginate() {
        check("$deck<!-- paginate: <warning descr=\"Invalid value 'yes' for 'paginate', expected one of: true, false, hold, skip\">yes</warning> -->\n")
    }

    fun testTrailingCommentBecomesPartOfAMarpitValue() {
        // Marpit's loose YAML quotes the value: `true # c` is not `true`, so paginate is off.
        check("$deck<!-- paginate: <warning descr=\"Invalid value 'true # c' for 'paginate', expected one of: true, false, hold, skip\">true # c</warning> -->\n")
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

<!-- header: **Bold** -->

<!--
_class: lead
_class: invert
-->

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

    // Front matter ----------------------------------------------------------------------------------------------------

    fun testMisspelledDirectiveInTheFrontMatterIsAWeakWarning() {
        check(
            "---\nmarp: true\n<weak_warning descr=\"'pagiante' is not a Marp directive, did you mean 'paginate'?\">pagiante</weak_warning>: true\n---\n",
        )
    }

    fun testGlobalDirectiveWithUnderscoreInTheFrontMatter() {
        check(
            "---\nmarp: true\n<warning descr=\"'_theme' is a global directive: it applies to the whole deck and has no '_' form, use 'theme'\">_theme</warning>: gaia\n---\n",
        )
    }

    fun testInvalidValuesInTheFrontMatter() {
        check(
            "---\nmarp: true\n" +
                "paginate: <warning descr=\"Invalid value 'yes' for 'paginate', expected one of: true, false, hold, skip\">yes</warning>\n" +
                "math: <warning descr=\"Invalid value 'foo' for 'math', expected one of: mathjax, katex\">foo</warning>\n" +
                "headingDivider: <warning descr=\"Invalid value '9' for 'headingDivider', expected a level from 1 to 6, a list of levels or false\">9</warning>\n" +
                "---\n",
        )
    }

    fun testTrailingCommentBecomesPartOfAMarpitValueInTheFrontMatter() {
        check(
            "---\nmarp: true\n" +
                "paginate: <warning descr=\"Invalid value 'true # c' for 'paginate', expected one of: true, false, hold, skip\">true # c</warning>\n" +
                "---\n",
        )
    }

    fun testMathAndSizeWithATrailingCommentInTheFrontMatterAreQuiet() {
        // Plain YAML: the comment is not part of the value, so this is KaTeX and 4:3.
        check("---\nmarp: true\nmath: katex # c\nsize: 4:3 # c\n---\n")
    }

    fun testNearMissOfALongDirectiveNameOrACaseMismatchInTheFrontMatter() {
        check(
            "---\nmarp: true\n" +
                "<weak_warning descr=\"'themes' is not a Marp directive, did you mean 'theme'?\">themes</weak_warning>: gaia\n" +
                "<weak_warning descr=\"'Size' is not a Marp directive, did you mean 'size'?\">Size</weak_warning>: 4:3\n" +
                "<weak_warning descr=\"'backgroundColour' is not a Marp directive, did you mean 'backgroundColor'?\">backgroundColour</weak_warning>: red\n" +
                "---\n",
        )
    }

    fun testShortNearMissesInTheFrontMatterAreMetadata() {
        // `path` is one edit from `math`, `site` from `size`, `match` from `math`: ordinary metadata, not typos.
        check("---\nmarp: true\npath: /docs\nsite: example.org\nmatch: all\n---\n")
    }

    fun testInvalidValueOfASpotDirectiveInTheFrontMatterNamesTheKeyAsWritten() {
        check(
            "---\nmarp: true\n_paginate: <warning descr=\"Invalid value 'nope' for '_paginate', expected one of: true, false, hold, skip\">nope</warning>\n---\n",
        )
    }

    fun testFrontMatterAndCommentsAreBothChecked() {
        check(
            "---\nmarp: true\npaginate: <warning descr=\"Invalid value 'yes' for 'paginate', expected one of: true, false, hold, skip\">yes</warning>\n---\n\n" +
                "<!-- math: <warning descr=\"Invalid value 'foo' for 'math', expected one of: mathjax, katex\">foo</warning> -->\n",
        )
    }

    fun testFrontMatterOfOtherToolsIsQuiet() {
        check(
            """---
marp: true
title: My deck
author: Me
description: A talk
date: 2026-09-30
tags:
  - slides
  - talk
theme: gaia
size: 4:3
math: katex
paginate: true
class: lead
headingDivider: 2
header: '**Hi**'
_class: invert
---

# Title
""",
        )
    }

    fun testFrontMatterThatIsNotAMappingIsQuiet() {
        // A repeated key makes YAML reject the whole front matter, the YAML plugin reports that.
        check("---\nmarp: true\nmarp: true\npaginate: yes\npagiante: 1\n---\n")
    }

    fun testNothingAfterTheFrontMatter() {
        check("---\nmarp: true\n---\n\npaginate: yes\npagiante: true\n")
    }

    fun testNothingInTheFrontMatterOfPlainMarkdown() {
        check("---\ntitle: Post\npaginate: yes\npagiante: true\n_theme: gaia\n---\n\n# Post\n")
        check("---\nmarp: false\npaginate: yes\n---\n")
    }

    fun testNothingInPlainMarkdown() {
        check("# Not a deck\n\n<!-- _theme: gaia -->\n\n<!-- paginate: yes -->\n")
    }

    fun testNothingInACodeBlock() {
        check("$deck```html\n<!-- _theme: gaia -->\n```\n")
    }
}
