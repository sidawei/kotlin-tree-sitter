package io.github.treesitter.ktreesitter

import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative

/**
 * A class that represents a syntax tree.
 *
 * __NOTE:__ If you're targeting Android SDK level < 33,
 * you must `use` or [close] the instance to free up resources.
 */
actual class Tree internal constructor(
    private val self: Long,
    private var source: String?,
    internal val encoding: InputEncoding,
    /** The language that was used to parse the syntax tree. */
    actual val language: Language
) : AutoCloseable {
    init {
        RefCleaner(this, CleanAction(self))
    }

    /** The root node of the syntax tree. */
    actual val rootNode: Node
        @FastNative external get

    /** The included ranges that were used to parse the syntax tree. */
    actual val includedRanges by lazy { nativeIncludedRanges() }

    /** The source code encoded as bytes in [encoding]. */
    private val sourceBytes: ByteArray? by lazy { source?.toByteArray(encoding.charset) }

    /**
     * Get the root node of the syntax tree, but with
     * its position shifted forward by the given offset.
     */
    @FastNative
    @JvmName("rootNodeWithOffset")
    actual external fun rootNodeWithOffset(bytes: UInt, extent: Point): Node?

    /**
     * Edit the syntax tree to keep it in sync
     * with source code that has been modified.
     */
    @FastNative
    actual external fun edit(edit: InputEdit)

    /**
     * Create a shallow copy of the syntax tree.
     *
     * You need to copy a syntax tree in order to use it on multiple
     * threads or coroutines, as syntax trees are not thread safe.
     */
    actual fun copy() = Tree(copy(self), source, encoding, language)

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
        return if (start >= end) "" else String(bytes, start, end - start, encoding.charset)
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
    actual external fun changedRanges(newTree: Tree): List<Range>

    override fun toString() = "Tree(language=$language, source=$source)"

    override fun close() = delete(self)

    private external fun nativeIncludedRanges(): List<Range>

    private class CleanAction(private val ptr: Long) : Runnable {
        override fun run() = delete(ptr)
    }

    private companion object {
        @JvmStatic
        @CriticalNative
        private external fun copy(self: Long): Long

        @JvmStatic
        @CriticalNative
        private external fun delete(self: Long)
    }
}
