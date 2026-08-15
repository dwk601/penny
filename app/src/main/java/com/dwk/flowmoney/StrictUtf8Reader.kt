package com.dwk.flowmoney

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal class InputTooLargeException : IOException("Input exceeds size limit")

internal class MalformedUtf8Exception : IOException("Input is not valid UTF-8")

internal class InputReadException(
    cause: IOException? = null,
) : IOException("Input could not be read", cause)

internal object StrictUtf8Reader {
    fun read(
        input: InputStream,
        maxBytes: Int,
        advertisedLength: Long = -1,
    ): String {
        require(maxBytes >= 0)
        return try {
            input.use { stream ->
                if (advertisedLength > maxBytes) throw InputTooLargeException()
                val out = ByteArrayOutputStream(minOf(maxBytes, BUFFER_SIZE))
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
                    if (count < 0) break
                    if (count == 0) {
                        val byte = stream.read()
                        if (byte < 0) break
                        if (total == maxBytes) throw InputTooLargeException()
                        out.write(byte)
                        total++
                    } else {
                        if (total + count > maxBytes) throw InputTooLargeException()
                        out.write(buffer, 0, count)
                        total += count
                    }
                }
                try {
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(out.toByteArray()))
                        .toString()
                } catch (_: CharacterCodingException) {
                    throw MalformedUtf8Exception()
                }
            }
        } catch (error: IOException) {
            when (error) {
                is InputTooLargeException, is MalformedUtf8Exception, is InputReadException -> throw error
                else -> throw InputReadException(error)
            }
        }
    }

    private const val BUFFER_SIZE = 8 * 1024
}
