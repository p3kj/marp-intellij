---
marp: true
theme: showcase
paginate: true
footer: Marp Preview for JetBrains IDEs
math: mathjax
---

<!-- _class: lead -->
<!-- _paginate: false -->
<!-- _footer: '' -->

# Marp Preview

Write **Markdown**, get **slides**, right inside your JetBrains IDE.

`marp: true` is all it takes.

<!-- This deck is about the plugin, and it is written with the plugin. -->

---

## Slides are just Markdown

```markdown
---
marp: true
theme: showcase
---

# Hello, Marp

Slides in your IDE, live.

---

## Second slide
```

Separate slides with `---`, style them with CSS.

---

## Live preview

- Renders with **marp-core**, the engine of Marp CLI
- Refreshes **while you type**, no save needed
- **Scroll sync** in both directions
- The slide under the caret is **highlighted**
- Double-click a slide to jump to its source
- Follows the IDE **light or dark** theme

---

![bg right:38%](images/slides.svg)

## Your themes

- Theme **CSS files, folders and URLs**
- `themeSet` from **.marprc.yml**, picked up automatically
- Edits to a theme show up **before you save**
- Ctrl+click a theme name to open its CSS

<!-- The image on the right is ![bg right:38%]. -->

---

<!-- _class: two -->

## The editor knows Marp

- **Completion** for directives and their values
- **Hover docs** for every directive
- **Inspections** for typos, `_theme` and bad values
- **Quick fixes** for unknown themes
- **Image keywords**: `bg`, `left:40%`, `w:400`, `sepia`
- **Live templates**: `slide`, `lead`, `bg`, `notes`

---

## Find your way around

- **Slide 3 / 12** in the status bar, click to jump
- Next / Previous Slide and Go to Slide
- Slides in the **Structure** view with their headings
- **Fold** a slide down to its title
- **Slide overview**: all slides as thumbnails
- **Drag a thumbnail** to reorder the slides

---

## Math, emoji and code

$$
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
$$

MathJax works offline, KaTeX is one setting away :sparkles:

```kotlin
fun main() = println("Hello from a slide")
```

---

## Export and present

| Format                | How                                  |
|-----------------------|--------------------------------------|
| HTML, PDF             | Built in, no Node.js needed          |
| PowerPoint, PNG, JPEG | Through Marp CLI                     |
| Presentation          | Present Deck opens it in the browser |

With Marp CLI you get Marp's presenter view, notes and timer.

---

## Every JetBrains IDE

- IntelliJ IDEA, PhpStorm, WebStorm, PyCharm
- GoLand, CLion, Rider, RubyMine and others
- 2026.2 and newer
- Locked-down preview, safe in untrusted projects

---

<!-- _class: lead -->
<!-- _paginate: false -->
<!-- _footer: '' -->

## Get it

Settings | Plugins | Marketplace | **Marp Preview**

Source and issues: **github.com/p3kj/marp-intellij**
