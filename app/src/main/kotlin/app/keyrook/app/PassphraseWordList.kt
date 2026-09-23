// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import java.nio.ByteBuffer
import java.nio.CharBuffer
import kotlin.math.log2

internal const val MAX_WORD_LIST_BYTES = 6_500_000

internal class InvalidWordListException : IllegalArgumentException(
    "Wortliste ungültig: UTF-8, 1024–65536 verschiedene Wörter, je 2–32 Buchstaben pro Zeile, höchstens 6,5 MB.")

/** Vocabulary is public; the owned input and decoder buffers are erased on every exit path. */
internal fun parsePassphraseWordList(bytes: ByteArray): List<String> {
    var chars: CharArray? = null
    try {
        require(bytes.size <= MAX_WORD_LIST_BYTES)
        val decoded = CharArray(bytes.size).also { chars = it }
        val output = CharBuffer.wrap(decoded)
        val decoder = Charsets.UTF_8.newDecoder()
        decoder.decode(ByteBuffer.wrap(bytes), output, true).throwExceptionIfInvalid()
        decoder.flush(output).throwExceptionIfInvalid()
        val text = String(decoded, 0, output.position()).removePrefix("\uFEFF")
        val words = ArrayList<String>()
        val distinct = HashSet<String>()
        for (line in text.lineSequence()) {
            val word = line.trim()
            if (word.isEmpty()) continue
            require(words.size < 65536 && word.length in 2..32 && word.all { it.isLetter() })
            require(distinct.add(word))
            words.add(word)
        }
        require(words.size >= 1024)
        return words
    } catch (_: Exception) {
        throw InvalidWordListException()
    } finally { chars?.fill('\u0000'); bytes.fill(0) }
}

private fun java.nio.charset.CoderResult.throwExceptionIfInvalid() {
    if (isError) throwException()
    require(!isOverflow)
}

internal fun minimumPassphraseWords(vocabularySize: Int): Int {
    require(vocabularySize in 1024..65536)
    return (5..20).first { it * log2(vocabularySize.toDouble()) >= 60.0 }
}

/** Takes ownership even when the editor was disposed or the insertion callback fails. */
internal fun deliverGeneratedSecret(secret: Secret, active: () -> Boolean, insert: (String) -> Unit) {
    secret.use { if (active()) insert(it.useChars { chars -> String(chars) }) }
}
