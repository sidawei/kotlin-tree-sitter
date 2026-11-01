package io.github.treesitter.ktreesitter

import br.com.colman.kotest.KotestRunnerAndroid
import io.github.treesitter.ktreesitter.java.TreeSitterJava
import io.kotest.assertions.throwables.shouldNotThrowAnyUnit
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forSome
import io.kotest.matchers.*
import io.kotest.matchers.collections.*
import io.kotest.matchers.nulls.*
import io.kotest.matchers.string.*
import io.kotest.matchers.types.*
import org.junit.runner.RunWith

@RunWith(KotestRunnerAndroid::class)
class ParserTest : FunSpec({
    val parser = Parser()

    test("language") {
        val language = Language(TreeSitterJava.language())
        parser.language.shouldBeNull()
        parser.language = language
        parser.language shouldBeSameInstanceAs language
    }

    test("includedRanges") {
        val range = Range(Point(0U, 0U), Point(0U, 1U), 0U, 1U)
        parser.includedRanges.shouldBeEmpty()
        parser.includedRanges = listOf(range)
        parser.includedRanges shouldHaveSingleElement range
    }

    test("logger") {
        shouldNotThrowAnyUnit {
            parser.logger = { _, _ ->
                throw UnsupportedOperationException()
            }
        }
    }

    test("parse(source)") {
        // UTF-8
        var source = "class Foo {}"
        var tree = parser.parse(source)
        tree.text()?.get(5) shouldBe ' '

        // logging
        val logs = mutableListOf<String>()
        parser.logger = { type, msg -> logs += "$type - $msg" }
        parser.reset()
        parser.parse(source)
        parser.logger = null
        logs.shouldNotBeEmpty().forSome {
            it shouldStartWith "LEX"
        }

        // UTF-16: node byte offsets are twice the character offsets,
        // so text() must decode the bytes instead of using them as indices.
        source = "class Foo { String s = \"💩\"; }"
        tree = parser.parse(source, encoding = InputEncoding.UTF_16BE)
        tree.rootNode.hasError shouldBe false
        tree.rootNode.descendant(48U, 52U)?.text() shouldBe "💩"
    }

    test("parse(readCallback)") {
        val source = "class Foo {}"
        val tree = parser.parse { byte, _ ->
            val end = minOf(byte.toInt() * 2, source.length)
            source.subSequence(byte.toInt(), end).ifEmpty { null }
        }
        tree.text().shouldBeNull()
        tree.rootNode.type shouldBe "program"
    }

    afterTest { (test, _) ->
        when (test.name.name) {
            "includedRanges" -> parser.includedRanges = emptyList()
            "logger" -> parser.logger = null
        }
    }
})
