package io.github.treesitter.ktreesitter

import io.github.treesitter.ktreesitter.java.TreeSitterJava
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class InputEncodingTest : FunSpec({
    val parser = Parser(Language(TreeSitterJava.language()))

    test("parse(source, encoding) uses byte offsets in the input encoding") {
        for (source in SOURCES) {
            for (encoding in ENCODINGS) {
                withClue("$encoding: $source") {
                    assertEncoding(parser, source, encoding)
                }
            }
        }
    }

    test("parse(source, encoding) keeps the UTF-8 syntax tree") {
        for (source in SOURCES) {
            val baseline = parser.parse(source)
            for (encoding in ENCODINGS) {
                withClue("$encoding: $source") {
                    val tree = parser.parse(source, encoding)
                    tree.rootNode.sexp() shouldBe baseline.rootNode.sexp()
                    assertSameStructure(baseline.rootNode, tree.rootNode)
                }
            }
        }
    }

    test("parse(source with BOM, encoding)") {
        val source = "﻿class Foo { int a = 12; }"
        val baseline = parser.parse(source.removePrefix("﻿"))
        for (encoding in ENCODINGS) {
            withClue(encoding.toString()) {
                val tree = parser.parse(source, encoding)
                tree.rootNode.hasError shouldBe false
                tree.rootNode.sexp() shouldBe baseline.rootNode.sexp()
                if (encoding != InputEncoding.UTF_8) {
                    tree.rootNode.startByte shouldBe 2U
                }
            }
        }
    }

    test("parse(source with a lone surrogate, encoding) does not crash") {
        val source = "class Foo { String s = \"\uD83D\"; }"
        for (encoding in ENCODINGS) {
            withClue(encoding.toString()) {
                val tree = parser.parse(source, encoding)
                tree.rootNode.endByte shouldBe encodedLength(source, encoding).toUInt()
            }
        }
    }

    test("parse(encoding, readCallback)") {
        val source = "class Foo { String s = \"é中😀\"; }"
        val baseline = parser.parse(source)
        for (encoding in ENCODINGS) {
            withClue(encoding.toString()) {
                val tree = parser.parse(encoding) { byte, _ ->
                    if (byte == 0U) source else null
                }
                tree.text().shouldBeNull()
                tree.rootNode.hasError shouldBe false
                tree.rootNode.endByte shouldBe encodedLength(source, encoding).toUInt()
                tree.rootNode.sexp() shouldBe baseline.rootNode.sexp()
            }
        }
    }
})

private val ENCODINGS = listOf(
    InputEncoding.UTF_8,
    InputEncoding.UTF_16LE,
    InputEncoding.UTF_16BE
)

private val SOURCES = listOf(
    "class Foo { int a = 12; }",
    "class Foo { String s = \"é中\"; }",
    "class Foo { String s = \"😀\"; }",
    "class Foo {\n    String s = \"a中😀b\";\n    int[] n = {1, 2, 3};\n}",
    buildString {
        append("class Foo {")
        for (i in 0 until 20) append(" int f").append(i).append(" = ").append(i).append(';')
        append(" String s = \"项😀\"; }")
    }
)

private const val UTF16_CODE_UNIT_BYTES = 2
private const val UTF8_SURROGATE_PAIR_BYTES = 4
private const val UTF8_ONE_BYTE_LIMIT = 0x80
private const val UTF8_TWO_BYTE_LIMIT = 0x800

/** Assert that every node maps to the text at its byte offset in the given encoding. */
private fun assertEncoding(parser: Parser, source: String, encoding: InputEncoding) {
    val tree = parser.parse(source, encoding)
    val root = tree.rootNode
    root.hasError shouldBe false
    root.startByte shouldBe 0U
    root.endByte shouldBe encodedLength(source, encoding).toUInt()

    val charToByte = charToByteOffsets(source, encoding)
    val byteToChar = byteToCharOffsets(charToByte)
    val lineMap = LineMap(source)
    for ((index, node) in flatten(root).withIndex()) {
        val start = byteToChar[node.startByte.toInt()]
        val end = byteToChar[node.endByte.toInt()]
        withClue("node #$index ${node.type} ${node.startByte}..${node.endByte}") {
            (start >= 0 && end >= 0) shouldBe true
            node.startByte shouldBe charToByte[start].toUInt()
            node.endByte shouldBe charToByte[end].toUInt()
            node.startPoint shouldBe expectedPoint(lineMap, charToByte, start)
            node.endPoint shouldBe expectedPoint(lineMap, charToByte, end)
            if (node.childCount == 0u) {
                node.text() shouldBe source.substring(start, end)
            }
        }
    }
}

/** Assert that both syntax trees contain the same nodes in the same order. */
private fun assertSameStructure(baseline: Node, candidate: Node) {
    val baselineNodes = flatten(baseline)
    val candidateNodes = flatten(candidate)
    candidateNodes.size shouldBe baselineNodes.size
    for (index in baselineNodes.indices) {
        withClue("node #$index") {
            val base = baselineNodes[index]
            val node = candidateNodes[index]
            node.type shouldBe base.type
            node.childCount shouldBe base.childCount
            node.isNamed shouldBe base.isNamed
            node.isMissing shouldBe base.isMissing
        }
    }
}

/** The number of bytes the source occupies in the given encoding. */
private fun encodedLength(source: String, encoding: InputEncoding): Int =
    if (encoding == InputEncoding.UTF_8) {
        source.encodeToByteArray().size
    } else {
        source.length * UTF16_CODE_UNIT_BYTES
    }

/** Map char indexes to byte offsets in the given encoding. */
private fun charToByteOffsets(source: String, encoding: InputEncoding): IntArray {
    if (encoding != InputEncoding.UTF_8) {
        return IntArray(source.length + 1) { it * UTF16_CODE_UNIT_BYTES }
    }

    val offsets = IntArray(source.length + 1) { -1 }
    var charIndex = 0
    var byteOffset = 0
    while (charIndex < source.length) {
        offsets[charIndex] = byteOffset
        byteOffset += utf8Size(source, charIndex)
        charIndex += if (isSurrogatePair(source, charIndex)) 2 else 1
    }
    offsets[source.length] = byteOffset
    return offsets
}

/** Map byte offsets back to char indexes, leaving non-boundary offsets at -1. */
private fun byteToCharOffsets(charToByte: IntArray): IntArray {
    val inverse = IntArray(charToByte.last() + 1) { -1 }
    for (charIndex in charToByte.indices) {
        val byteOffset = charToByte[charIndex]
        if (byteOffset >= 0) inverse[byteOffset] = charIndex
    }
    return inverse
}

/** Whether the char at the given index starts a valid surrogate pair. */
private fun isSurrogatePair(source: String, index: Int): Boolean =
    source[index].isHighSurrogate() && index + 1 < source.length &&
        source[index + 1].isLowSurrogate()

/** The number of bytes the char at the given index occupies in UTF-8. */
private fun utf8Size(source: String, index: Int): Int = when {
    isSurrogatePair(source, index) -> UTF8_SURROGATE_PAIR_BYTES
    source[index].isSurrogate() -> 1
    source[index].code < UTF8_ONE_BYTE_LIMIT -> 1
    source[index].code < UTF8_TWO_BYTE_LIMIT -> 2
    else -> 3
}

/** The row and line start char index for every char index of a source string. */
private class LineMap(source: String) {
    val rowOf = IntArray(source.length + 1)
    val lineStartOf = IntArray(source.length + 1)

    init {
        var row = 0
        var lineStart = 0
        for (index in 0..source.length) {
            rowOf[index] = row
            lineStartOf[index] = lineStart
            if (index < source.length && source[index] == '\n') {
                row++
                lineStart = index + 1
            }
        }
    }
}

/** The expected start or end point of a char index, with byte columns. */
private fun expectedPoint(lineMap: LineMap, charToByte: IntArray, index: Int): Point {
    val lineStart = lineMap.lineStartOf[index]
    return Point(
        lineMap.rowOf[index].toUInt(),
        (charToByte[index] - charToByte[lineStart]).toUInt()
    )
}

/** Collect all nodes of the given subtree in depth-first order. */
private fun flatten(root: Node): List<Node> {
    val nodes = ArrayList<Node>(root.descendantCount.toInt())
    fun visit(node: Node) {
        nodes += node
        for (child in node.children) visit(child)
    }
    visit(root)
    return nodes
}
