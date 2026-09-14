package dev.patrickgold.florisboard.education

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SuggestionDiffTest : FunSpec({
    fun changed(segments: List<DiffSegment>) = segments.filter { it.changed }.map { it.text }

    test("segments always rebuild the suggestion verbatim") {
        val s = SuggestionDiff.compute("parque i jugé con mis amijos", "parque y jugué con mis amigos")
        s.joinToString("") { it.text } shouldBe "parque y jugué con mis amigos"
    }

    test("substituted words are marked, unchanged words are not") {
        val s = SuggestionDiff.compute("parque i jugé con mis amijos", "parque y jugué con mis amigos")
        changed(s) shouldBe listOf("y", "jugué", "amigos")
        SuggestionDiff.changedCount(s) shouldBe 3
    }

    test("inserted punctuation attached to a word marks that token") {
        val s = SuggestionDiff.compute("hola como estas", "hola, ¿cómo estás?")
        changed(s) shouldBe listOf("hola,", "¿cómo", "estás?")
    }

    test("an inserted word is marked") {
        val s = SuggestionDiff.compute("fui parque", "fui al parque")
        changed(s) shouldBe listOf("al")
    }

    test("a deleted word does not produce a changed segment but keeps the rest intact") {
        val s = SuggestionDiff.compute("fui al al parque", "fui al parque")
        s.joinToString("") { it.text } shouldBe "fui al parque"
        changed(s) shouldBe emptyList()
    }

    test("identical texts have zero changes") {
        val s = SuggestionDiff.compute("todo bien", "todo bien")
        SuggestionDiff.changedCount(s) shouldBe 0
    }

    test("accent-only differences count as changes") {
        changed(SuggestionDiff.compute("el nino canto", "el niño cantó")) shouldBe listOf("niño", "cantó")
    }

    test("empty suggestion yields no segments") {
        SuggestionDiff.compute("algo", "") shouldBe emptyList()
    }
})
