// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.keyrook.core.security.BreachCheck
import app.keyrook.core.security.BreachCheckException
import app.keyrook.core.security.BreachPlan
import app.keyrook.core.security.BreachReport
import app.keyrook.core.security.RangeSource
import app.keyrook.core.update.ReleaseVersion
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

internal val BREACH_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
private const val BREACH_REQUEST_TOTAL_SECONDS = 30L
/** Requests of one run in flight at the same time. */
internal const val BREACH_PARALLELISM = 2

/**
 * One range query: an HTTPS GET for the five-character hash prefix with response padding requested. No cookies,
 * credentials, identifiers or query parameters are sent.
 */
internal fun rangeRequest(prefix: String, version: String?): HttpRequest =
    HttpRequest.newBuilder(URI(BreachCheck.rangeAddress(prefix)))
        .GET()
        .timeout(BREACH_REQUEST_TIMEOUT)
        .header("Add-Padding", "true")
        .header("User-Agent", "Keyrook/${ReleaseVersion.parseRunning(version) ?: "dev"}")
        .build()

/** Like the update check: system proxy settings, no cookie handler or authenticator, no redirects. */
internal fun breachHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(BREACH_REQUEST_TIMEOUT)
    .followRedirects(HttpClient.Redirect.NEVER)
    .proxy(ProxySelector.getDefault() ?: HttpClient.Builder.NO_PROXY)
    .build()

/**
 * Fetches range responses from api.pwnedpasswords.com for one run. Only status 200 from the exact request address is
 * accepted, bodies are bounded while they arrive, and an interrupted request is canceled. [close] ends the client.
 */
internal class PwnedRangeSource(
    private val version: String?,
    private val client: HttpClient = breachHttpClient(),
) : RangeSource, AutoCloseable {
    override fun range(prefix: String): ByteArray {
        val request = rangeRequest(prefix, version)
        val future = client.sendAsync(request,
            HttpResponse.BodyHandler<ByteArray> { BoundedBodySubscriber(BreachCheck.MAX_RANGE_BYTES) })
        val response = try { future.get(BREACH_REQUEST_TOTAL_SECONDS, TimeUnit.SECONDS) } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw IOException("Range request timed out", timeout)
        } catch (interrupted: InterruptedException) {
            future.cancel(true)
            throw interrupted
        } catch (failure: ExecutionException) {
            throw IOException("Range request failed", failure.cause)
        }
        if (response.statusCode() != 200 || response.uri() != request.uri()) throw IOException("Unexpected range response")
        return response.body()
    }

    override fun close() { client.shutdownNow() }
}

internal sealed interface BreachStatus {
    data object Idle : BreachStatus
    /** Hashing locally before the question; nothing is sent. */
    data object Preparing : BreachStatus
    /** Waiting for the explicit consent to send [plan]'s prefixes; the plan is closed on any answer. */
    data class Consent(val plan: BreachPlan) : BreachStatus
    data class Running(val done: Int, val total: Int) : BreachStatus
    data object NothingToCheck : BreachStatus
    data object Cancelled : BreachStatus
    data class Finished(val report: BreachReport) : BreachStatus
    /** [reason] is null when the vault could not be read locally. */
    data class Failed(val reason: BreachCheckException.Reason?) : BreachStatus
}

/**
 * The optional breach check of the open vault. It never starts by itself: [prepare] only hashes locally and asks for
 * consent, and only [confirm] contacts the service, once per question. Runs use their own daemon thread, never the
 * vault worker; state changes happen on the UI thread. [report] lives in memory only and [clear], called on lock,
 * discards it together with a pending question or a running request.
 */
internal class BreachChecks(
    private val version: String? = System.getProperty("keyrook.version", "dev"),
    private val source: () -> RangeSource = { PwnedRangeSource(version) },
    private val background: (Runnable) -> Unit = { Thread(it, "breach-check-run").apply { isDaemon = true }.start() },
    private val deliver: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
) {
    var status by mutableStateOf<BreachStatus>(BreachStatus.Idle)
        private set
    var report by mutableStateOf<BreachReport?>(null)
        private set
    private var generation = 0L
    private var cancel: AtomicBoolean? = null

    val active: Boolean get() = status.let {
        it == BreachStatus.Preparing || it is BreachStatus.Consent || it is BreachStatus.Running
    }

    /** Hashes the passwords returned by [plan], typically `controller.read(BreachCheck::plan)`, then asks. */
    fun prepare(plan: () -> BreachPlan) {
        if (active) return
        val run = ++generation
        status = BreachStatus.Preparing
        background(Runnable {
            val prepared = runCatching(plan).getOrNull()
            deliver(Runnable {
                status = when {
                    run != generation -> { prepared?.close(); return@Runnable }
                    prepared == null -> BreachStatus.Failed(null)
                    prepared.requests == 0 -> { prepared.close(); BreachStatus.NothingToCheck }
                    else -> BreachStatus.Consent(prepared)
                }
            })
        })
    }

    /** The question was declined or dismissed: nothing is sent and the hashes are erased. */
    fun decline() {
        val consent = status as? BreachStatus.Consent ?: return
        consent.plan.close()
        status = BreachStatus.Idle
    }

    /** Sends the prefixes of the pending question. A previous report stays until this run has succeeded. */
    fun confirm() {
        val plan = (status as? BreachStatus.Consent)?.plan ?: return
        val run = generation
        val stop = AtomicBoolean(false)
        cancel = stop
        status = BreachStatus.Running(0, plan.requests)
        background(Runnable {
            val result = try {
                val range = source()
                try {
                    Result.success(BreachCheck.run(plan, range, stop::get, parallelism = BREACH_PARALLELISM,
                        progress = { done, total -> deliver(Runnable {
                            if (run == generation && status is BreachStatus.Running) status = BreachStatus.Running(done, total)
                        }) }))
                } finally { (range as? AutoCloseable)?.close() }
            } catch (failure: BreachCheckException) { Result.failure(failure) }
            catch (_: Exception) { Result.failure(BreachCheckException(BreachCheckException.Reason.NETWORK)) }
            finally { plan.close() }
            deliver(Runnable {
                if (run != generation) return@Runnable
                cancel = null
                val finished = result.getOrNull()
                if (finished != null) report = finished
                status = if (finished != null) BreachStatus.Finished(finished)
                else when (val reason = (result.exceptionOrNull() as? BreachCheckException)?.reason) {
                    BreachCheckException.Reason.CANCELLED -> BreachStatus.Cancelled
                    else -> BreachStatus.Failed(reason)
                }
            })
        })
    }

    /** Stops a running check; its partial results are discarded and the previous report stays. */
    fun cancelRun() {
        if (status !is BreachStatus.Running) return
        generation++
        cancel?.set(true)
        cancel = null
        status = BreachStatus.Cancelled
    }

    /** Forgets everything: pending question, running check and report. Called when the vault is locked. */
    fun clear() {
        generation++
        cancel?.set(true)
        cancel = null
        (status as? BreachStatus.Consent)?.plan?.close()
        status = BreachStatus.Idle
        report = null
    }
}

/** The start button, progress and outcome of the breach check inside the warning list. */
@Composable
internal fun BreachCheckPanel(breaches: BreachChecks, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(enabled = !breaches.active, onClick = onStart) { Text(UiText.text("breach.start")) }
        Text(UiText.text("breach.hint"), style = MaterialTheme.typography.caption)
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        when (val status = breaches.status) {
            BreachStatus.Idle, is BreachStatus.Consent -> Unit
            BreachStatus.Preparing -> Text(UiText.text("breach.preparing"), live)
            is BreachStatus.Running -> {
                LinearProgressIndicator(progress = if (status.total == 0) 0f else status.done.toFloat() / status.total,
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(UiText.text("breach.running", status.done, status.total), live.weight(1f))
                    TextButton(onClick = breaches::cancelRun) { Text(UiText.text("common.cancel")) }
                }
            }
            BreachStatus.NothingToCheck -> Text(UiText.text("breach.nothing"), live)
            BreachStatus.Cancelled -> Text(UiText.text("breach.cancelled"), live)
            is BreachStatus.Finished -> Text(UiText.text("breach.finished", status.report.passwords,
                status.report.requests, status.report.findings.size), live)
            is BreachStatus.Failed -> Text(UiText.text(when (status.reason) {
                BreachCheckException.Reason.INVALID_RESPONSE -> "breach.failedResponse"
                BreachCheckException.Reason.TIMEOUT -> "breach.failedTimeout"
                null -> "breach.failedLocal"
                else -> "breach.failedNetwork"
            }), live, color = MaterialTheme.colors.error)
        }
    }
}

/** Asked before every run; cancel has the focus and Enter alone does not confirm. */
@Composable
internal fun BreachConsentDialog(breaches: BreachChecks) {
    val consent = breaches.status as? BreachStatus.Consent ?: return
    ConfirmationDialog(UiText.text("breach.consentTitle"),
        UiText.text("breach.consentBody", consent.plan.passwords, consent.plan.requests),
        UiText.text("breach.consentConfirm", consent.plan.requests), busy = false, irreversible = true,
        onConfirm = breaches::confirm, onDismiss = breaches::decline)
}
