package io.github.treesitter.ktreesitter

import cnames.structs.TSTree
import io.github.treesitter.ktreesitter.internal.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.*

/** A class that represents a syntax tree. */
@OptIn(ExperimentalForeignApi::class)
actual class Tree internal constructor(
    internal val self: CPointer<TSTree>,
    private var source: String?,
    internal val encoding: InputEncoding,
    /** The language that was used to parse the syntax tree. */
    actual val language: Language
) {
    @Suppress("unused")
    @OptIn(ExperimentalNativeApi::class)
    private val cleaner = createCleaner(self, ::ts_tree_delete)

    /** The source code encoded as bytes in [encoding]. */
    private val sourceBytes: ByteArray? by lazy { source?.encode(encoding) }

    /** The root node of the syntax tree. */
    actual val rootNode = Node(ts_tree_root_node(self), this)

    /** The included ranges of the syntax tree. */
    actual val includedRanges by lazy {
        memScoped {
            val length = alloc<UIntVar>()
            val ranges = ts_tree_included_ranges(self, length.ptr) ?: return@lazy emptyList()
            val result = List(length.value.convert()) { ranges[it].convert() }
            kts_free(ranges)
            result
        }
    }

    /**
     * Get the root node of the syntax tree, but with
     * its position shifted forward by the given offset.
     */
    actual fun rootNodeWithOffset(bytes: UInt, extent: Point): Node? {
        val offsetExtent = cValue<TSPoint> { from(extent) }
        return ts_tree_root_node_with_offset(self, bytes, offsetExtent).convert(this)
    }

    /**
     * Edit the syntax tree to keep it in sync
     * with source code that has been modified.
     */
    actual fun edit(edit: InputEdit) {
        val inputEdit = cValue<TSInputEdit> { from(edit) }
        ts_tree_edit(self, inputEdit)
        source = null
    }

    /**
     * Create a shallow copy of the syntax tree.
     *
     * You need to copy a syntax tree in order to use it on multiple
     * threads or coroutines, as syntax trees are not thread safe.
     */
    actual fun copy() = Tree(ts_tree_copy(self)!!, source, encoding, language)

    /** Create a new tree cursor starting from the node of the tree. */
    actual fun walk() = TreeCursor(rootNode)

    /** Get the source code of the syntax tree, if available. */
    actual fun text(): CharSequence? = source

    /** Get the source text spanning the given byte range, if available. */
    internal fun sourceText(startByte: UInt, endByte: UInt): String? {
        if (source == null) return null
        val bytes = sourceBytes ?: return null
        val start = minOf(startByte.toInt(), bytes.size)
        val end = minOf(endByte.toInt(), bytes.size)
        return if (start >= end) "" else when (encoding) {
            InputEncoding.UTF_8 -> bytes.decodeToString(start, end)
            InputEncoding.UTF_16LE -> decodeUtf16(bytes, start, end, littleEndian = true)
            InputEncoding.UTF_16BE -> decodeUtf16(bytes, start, end, littleEndian = false)
        }
    }

    /**
     * Compare an old edited syntax tree to a new
     * syntax tree representing the same document.
     *
     * For this to work correctly, this tree must have been
     * edited such that its ranges match up to the new tree.
     *
     * @return A list of ranges whose syntactic structure has changed.
     */
    actual fun changedRanges(newTree: Tree): List<Range> = memScoped {
        val length = alloc<UIntVar>()
        val ranges = ts_tree_get_changed_ranges(self, newTree.self, length.ptr)
        if (length.value == 0U || ranges == null) return emptyList()
        val result = List(length.value.convert()) { ranges[it].convert() }
        kts_free(ranges)
        return result
    }

    override fun toString() = "Tree(language=$language, source=$source)"
}

/** Decode a UTF-16 byte range into a string, preserving surrogate pairs. */
private fun decodeUtf16(bytes: ByteArray, start: Int, end: Int, littleEndian: Boolean): String {
    val length = (end - start) / 2
    val chars = CharArray(length) { i ->
        val offset = start + i * 2
        val first = bytes[offset].toUByte().toInt()
        val second = bytes[offset + 1].toUByte().toInt()
        val code = if (littleEndian) {
            first or (second shl Byte.SIZE_BITS)
        } else {
            (first shl Byte.SIZE_BITS) or second
        }
        code.toChar()
    }
    return chars.concatToString()
}
