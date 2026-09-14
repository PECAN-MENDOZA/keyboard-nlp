package dev.patrickgold.florisboard.education

import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.EditorRange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class InPlaceEditTrackerTest : FunSpec({
    fun content(text: String, cursor: Int, offset: Int = 0) = EditorContent(
        text = text,
        offset = offset,
        localSelection = EditorRange.cursor(cursor),
        localComposing = EditorRange.Unspecified,
        localCurrentWord = EditorRange.Unspecified,
    )

    // "Hoy fui al [parque i jugé] y volví."  → rango 11..24
    val original = "Hoy fui al parque i jugé y volví."
    val anchor = InPlaceEditTracker.anchor(content(original, cursor = 24), EditorRange(11, 24))!!

    test("anchor stores absolute start plus prefix and suffix windows") {
        anchor.start shouldBe 11
        anchor.prefix shouldBe "Hoy fui al "
        anchor.suffix shouldBe " y volví."
    }

    test("after replacing the range the tracked text is the replacement") {
        val now = "Hoy fui al parque y jugué y volví."
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 25)) shouldBe
            TrackedRange.Inside("parque y jugué", EditorRange(11, 25))
    }

    test("typing inside the range grows the tracked text") {
        val now = "Hoy fui al parque y jugué mucho y volví."
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 31)) shouldBe
            TrackedRange.Inside("parque y jugué mucho", EditorRange(11, 31))
    }

    test("deleting inside the range shrinks the tracked text") {
        val now = "Hoy fui al parque y volví."
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 17)) shouldBe
            TrackedRange.Inside("parque", EditorRange(11, 17))
    }

    test("typed text that repeats the suffix does not truncate the range") {
        // Sin punto final para que el sufijo (" y volví") pueda repetirse dentro de lo escrito.
        val src = "Hoy fui al parque i jugé y volví"
        val a = InPlaceEditTracker.anchor(content(src, cursor = 24), EditorRange(11, 24))!!
        a.suffix shouldBe " y volví"
        val now = "Hoy fui al parque y volví y jugué y volví"
        InPlaceEditTracker.resolve(a, content(now, cursor = 33)) shouldBe
            TrackedRange.Inside("parque y volví y jugué", EditorRange(11, 33))
    }

    test("cursor outside the range loses the edit") {
        val now = "Hoy fui al parque y jugué y volví."
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 3)) shouldBe TrackedRange.Lost
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 30)) shouldBe TrackedRange.Lost
    }

    test("a changed prefix loses the edit") {
        val now = "Ayer fui al parque y jugué y volví."
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 26)) shouldBe TrackedRange.Lost
    }

    test("a missing suffix loses the edit") {
        val now = "Hoy fui al parque y jugué"
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 25)) shouldBe TrackedRange.Lost
    }

    test("a range at the end of the text has an empty suffix and resolves to the end") {
        val a = InPlaceEditTracker.anchor(content("Hola mundo", cursor = 10), EditorRange(5, 10))!!
        a.suffix shouldBe ""
        InPlaceEditTracker.resolve(a, content("Hola mundos", cursor = 11)) shouldBe
            TrackedRange.Inside("mundos", EditorRange(5, 11))
    }

    test("content offset is honoured") {
        // El editor solo cachea desde el carácter 4 ("fui al ...").
        val now = "fui al parque y jugué y volví."
        InPlaceEditTracker.resolve(anchor, content(now, cursor = 21, offset = 4)) shouldBe
            TrackedRange.Inside("parque y jugué", EditorRange(11, 25))
    }

    test("invalid content yields no anchor") {
        InPlaceEditTracker.anchor(content(original, cursor = 0, offset = -1), EditorRange(11, 24)) shouldBe null
        InPlaceEditTracker.anchor(content(original, cursor = 0), EditorRange(11, 99)) shouldBe null
    }
})
