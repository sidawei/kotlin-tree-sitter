package io.github.treesitter.ktreesitter

import io.github.treesitter.ktreesitter.internal.TSInputEncoding
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * The encoding of the input text.
 *
 * @since 0.25.0
 */
@OptIn(ExperimentalForeignApi::class)
actual enum class InputEncoding(internal val value: TSInputEncoding) {
    UTF_8(TSInputEncoding.TSInputEncodingUTF8),
    UTF_16LE(TSInputEncoding.TSInputEncodingUTF16LE),
    UTF_16BE(TSInputEncoding.TSInputEncodingUTF16BE)
}

/** Encode this text into bytes in the given input encoding. */
internal fun CharSequence.encode(encoding: InputEncoding): ByteArray {
    val source = toString()
    return when (encoding) {
        InputEncoding.UTF_8 -> source.encodeToByteArray()
        InputEncoding.UTF_16LE -> source.encodeUtf16(littleEndian = true)
        InputEncoding.UTF_16BE -> source.encodeUtf16(littleEndian = false)
    }
}

private fun String.encodeUtf16(littleEndian: Boolean): ByteArray {
    val result = ByteArray(length * 2)
    for (i in indices) {
        val code = this[i].code
        if (littleEndian) {
            result[i * 2] = code.toByte()
            result[i * 2 + 1] = (code ushr Byte.SIZE_BITS).toByte()
        } else {
            result[i * 2] = (code ushr Byte.SIZE_BITS).toByte()
            result[i * 2 + 1] = code.toByte()
        }
    }
    return result
}
