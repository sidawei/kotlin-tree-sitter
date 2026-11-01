package io.github.treesitter.ktreesitter

import io.github.treesitter.ktreesitter.java.TreeSitterJava
import io.kotest.assertions.throwables.shouldNotThrowAnyUnit
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forSome
import io.kotest.matchers.*
import io.kotest.matchers.collections.*
import io.kotest.matchers.nulls.*
import io.kotest.matchers.string.*
import io.kotest.matchers.types.*

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

        // UTF-8: supplementary characters must use standard 4-byte UTF-8
        source = "class Foo { String s = \"💩\"; }"
        tree = parser.parse(source)
        tree.rootNode.hasError shouldBe false
        tree.rootNode.startByte shouldBe 0U
        tree.rootNode.endByte shouldBe 32U

        // UTF-16LE / UTF-16BE: the source must be encoded as actual UTF-16 bytes
        for (encoding in listOf(InputEncoding.UTF_16LE, InputEncoding.UTF_16BE)) {
            tree = parser.parse(source, encoding)
            tree.rootNode.hasError shouldBe false
            tree.rootNode.startByte shouldBe 0U
            tree.rootNode.endByte shouldBe (source.length * 2).toUInt()
            tree.text().toString() shouldBe source
        }
    }

    test("parse(readCallback)") {
        val source = "class Foo {}"
        var tree = parser.parse { byte, _ ->
            val index = byte.toInt()
            if (index >= source.length) null
            else source.substring(index, minOf(index + 4, source.length))
        }
        tree.text().shouldBeNull()
        tree.rootNode.type shouldBe "program"
        tree.rootNode.hasError shouldBe false
        tree.rootNode.endByte shouldBe source.length.toUInt()

        // UTF-16 callback chunks are addressed by byte offset,
        // so the source index is half the byte offset.
        for (encoding in listOf(InputEncoding.UTF_16LE, InputEncoding.UTF_16BE)) {
            tree = parser.parse(encoding) { byte, _ ->
                val index = byte.toInt() / 2
                if (index >= source.length) null
                else source.substring(index, minOf(index + 4, source.length))
            }
            tree.text().shouldBeNull()
            tree.rootNode.type shouldBe "program"
            tree.rootNode.hasError shouldBe false
            tree.rootNode.endByte shouldBe (source.length * 2).toUInt()
        }
    }

    afterTest { (test, _) ->
        when (test.name.name) {
            "includedRanges" -> parser.includedRanges = emptyList()
            "logger" -> parser.logger = null
        }
    }
})
