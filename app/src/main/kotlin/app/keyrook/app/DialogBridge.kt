// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.transfer.CsvMapping
import java.util.concurrent.CompletableFuture

/**
 * One question for the user, answered at most once. A null answer means canceled: the cancel or no button, closing
 * the dialog, or locking. [discard] erases an answer that is never delivered, such as a password confirmed after
 * the session changed.
 */
internal sealed class DialogRequest<T : Any> {
    private val answer = CompletableFuture<T?>()

    open fun discard(value: T) {}

    internal val done: Boolean get() = answer.isDone

    /** Returns false when the request was already answered or canceled; the caller then discards [value]. */
    internal fun complete(value: T?): Boolean = answer.complete(value)

    /** Cancels an unanswered request; an answer that won the race is erased because nobody will receive it. */
    internal fun withdraw() {
        if (!answer.complete(null)) answer.getNow(null)?.let(::discard)
    }

    internal fun await(): T? = answer.get()
}

/** Information with a single OK button. */
internal class MessageRequest(val title: String, val text: String) : DialogRequest<Unit>()

/** Yes/No question; no and cancel are the same answer. */
internal class ConfirmRequest(val title: String, val text: String) : DialogRequest<Boolean>()

/** Plain text in a scrollable read-only area; it is never interpreted as markup. */
internal class ReportRequest(val title: String, val text: String) : DialogRequest<Unit>()

/** Answers the index of the chosen label, so translated labels never act as keys. */
internal class ChoiceRequest(val title: String, val labels: List<String>) : DialogRequest<Int>() {
    init { require(labels.isNotEmpty()) }
}

internal class InputField(val label: String, val initial: String)

/** Non-secret text inputs, answered in the order of [fields]; [header] and [footer] are explanatory lines. */
internal class FieldsRequest(val title: String, val header: List<String>, val fields: List<InputField>,
                             val footer: List<String>) : DialogRequest<List<String>>() {
    init { require(fields.isNotEmpty()) }
}

/** Password, optional repetition and optional key-file path; an undelivered answer is erased. */
internal class CredentialRequest(val title: String, val confirm: Boolean, val replacing: Boolean) :
    DialogRequest<CredentialInput>() {
    override fun discard(value: CredentialInput) = value.close()
}

/** Column names only; no data row is shown. The answer uses exact header names. */
internal class CsvMappingRequest(val columns: List<String>, val suggested: CsvMapping) : DialogRequest<CsvMapping>() {
    init { require(columns.isNotEmpty()) }
}

/** Owned copies of the entered passwords; the receiver erases them with [close] on every path. */
internal class CredentialInput(val password: CharArray, val repeated: CharArray, val keyPath: String) : AutoCloseable {
    override fun close() {
        password.fill('\u0000')
        repeated.fill('\u0000')
    }
}

/** How vault operations ask the user; tests substitute answers. */
internal interface Dialogs {
    /** Blocks the calling worker until the user answers; null means canceled, including by locking. */
    fun <T : Any> ask(request: DialogRequest<T>): T?
}

internal fun Dialogs.inform(text: String, title: String = "Keyrook") { ask(MessageRequest(title, text)) }

internal fun Dialogs.confirm(text: String, title: String = "Keyrook"): Boolean = ask(ConfirmRequest(title, text)) == true

internal fun Dialogs.report(title: String, text: String) { ask(ReportRequest(title, text)) }

/** The dialog shows catalog labels; the selection is mapped back by position, so labels never act as keys. */
internal fun <T : Any> Dialogs.choose(title: String, values: List<T>, label: (T) -> String): T? =
    ask(ChoiceRequest(title, values.map(label)))?.let(values::getOrNull)

/**
 * Hands requests from worker threads to the UI thread, one dialog at a time and in order.
 *
 * [ask] checks the worker's operation guard before a request is queued and again after it is answered, so a dialog
 * never opens for an expired session and an answer never reaches a worker whose session changed meanwhile. The
 * guard check and queueing share a lock with [cancelAll]: a lock that invalidates the session before canceling
 * either cancels the request or makes its guard fail. [answer] delivers only to the request currently shown; an
 * answer for a canceled or replaced request is discarded. [changed] runs after every change of [current].
 */
internal class DialogBridge(private val changed: () -> Unit = {}, private val uiThread: () -> Boolean = { false }) {
    private val lock = Any()
    private val pending = ArrayDeque<DialogRequest<*>>()
    private var closed = false

    val current: DialogRequest<*>? get() = synchronized(lock) { pending.firstOrNull() }

    fun <T : Any> ask(request: DialogRequest<T>, guard: () -> Unit): T? {
        check(!uiThread()) { "Dialogs are answered on the UI thread and must be requested by a worker" }
        synchronized(lock) {
            guard()
            check(!closed) { "Dialog host closed" }
            check(!request.done && pending.none { it === request }) { "Dialog request reused" }
            pending.addLast(request)
        }
        changed()
        val value = try { request.await() } catch (interrupted: InterruptedException) {
            synchronized(lock) { pending.removeAll { it === request } }
            request.withdraw()
            changed()
            throw interrupted
        }
        try { guard() } catch (failure: Throwable) {
            value?.let(request::discard)
            throw failure
        }
        return value
    }

    /** Returns whether [value] reached the waiting worker; otherwise it has been discarded. */
    fun <T : Any> answer(request: DialogRequest<T>, value: T?): Boolean {
        val delivered = synchronized(lock) {
            if (pending.firstOrNull() !== request) false
            else {
                pending.removeFirst()
                request.complete(value)
            }
        }
        if (!delivered) value?.let(request::discard)
        changed()
        return delivered
    }

    /** Dismisses the shown and all queued dialogs as canceled. */
    fun cancelAll() {
        val canceled = synchronized(lock) { pending.toList().also { pending.clear() } }
        canceled.forEach { it.withdraw() }
        changed()
    }

    /** Cancels everything and refuses later requests. */
    fun close() {
        synchronized(lock) { closed = true }
        cancelAll()
    }
}
