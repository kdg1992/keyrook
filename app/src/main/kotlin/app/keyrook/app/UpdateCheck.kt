// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyrook.core.update.ReleaseCheck
import app.keyrook.core.update.ReleaseSource
import app.keyrook.core.update.ReleaseVersion
import app.keyrook.core.update.UpdateCheckResult
import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.SwingUtilities

internal val UPDATE_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val UPDATE_TOTAL_TIMEOUT_SECONDS = 20L

/**
 * The only outbound request Keyrook makes itself: one HTTPS GET for the latest release, sent only after an explicit click or
 * when the user enabled the check on start. No cookies, credentials, identifiers or query parameters; redirects are refused.
 */
internal fun latestReleaseRequest(version: String?): HttpRequest =
    HttpRequest.newBuilder(URI(ReleaseCheck.LATEST_RELEASE_API))
        .GET()
        .timeout(UPDATE_TIMEOUT)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Keyrook/${ReleaseVersion.parseRunning(version) ?: "dev"}")
        .build()

/** System proxy settings apply; no cookie handler or authenticator is installed, and no redirect is followed. */
internal fun updateHttpClient(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(UPDATE_TIMEOUT)
    .followRedirects(HttpClient.Redirect.NEVER)
    .proxy(ProxySelector.getDefault() ?: HttpClient.Builder.NO_PROXY)
    .build()

/** Collects at most [limit] bytes and aborts the exchange as soon as the body grows beyond it. */
internal class BoundedBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val buffer = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (result.isDone) return
        for (chunk in item) {
            if (chunk.remaining() > limit - buffer.size()) {
                subscription?.cancel()
                result.completeExceptionally(IOException("Response body exceeds limit"))
                return
            }
            val bytes = ByteArray(chunk.remaining())
            chunk.get(bytes)
            buffer.write(bytes)
        }
    }

    override fun onError(throwable: Throwable) { result.completeExceptionally(throwable) }

    override fun onComplete() { result.complete(buffer.toByteArray()) }
}

/** Fetches the release document from GitHub. Only status 200 from the exact request address is accepted. */
internal class GitHubReleaseSource(
    private val version: String?,
    private val client: () -> HttpClient = ::updateHttpClient,
) : ReleaseSource {
    override fun latestRelease(): ByteArray {
        val http = client()
        try {
            val future = http.sendAsync(latestReleaseRequest(version),
                HttpResponse.BodyHandler<ByteArray> { BoundedBodySubscriber(ReleaseCheck.MAX_BODY_BYTES) })
            val response = try { future.get(UPDATE_TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS) } catch (timeout: TimeoutException) {
                future.cancel(true)
                throw IOException("Update check timed out", timeout)
            }
            if (response.statusCode() != 200 || response.uri() != URI(ReleaseCheck.LATEST_RELEASE_API)) {
                throw IOException("Unexpected update response")
            }
            return response.body()
        } finally {
            http.shutdownNow()
        }
    }
}

internal sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Finished(val result: UpdateCheckResult) : UpdateStatus
}

/**
 * Runs update checks on their own daemon thread, never on the vault worker, and independently of the vault state. Nothing
 * here reads vault data or credentials. Results are delivered on the UI thread.
 */
internal class UpdateChecks(
    private val version: String? = System.getProperty("keyrook.version", "dev"),
    private val source: ReleaseSource = GitHubReleaseSource(version),
    private val background: (Runnable) -> Unit = { Thread(it, "update-check").apply { isDaemon = true }.start() },
    private val deliver: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
) {
    var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set

    /** Set only by an automatic check that found a newer release; shown as a dismissible, non-modal notice. */
    var notice by mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null)
        private set

    fun checkNow() = start(automatic = false)

    /** Contacts the network only when the user enabled the check on start. */
    fun checkOnStart(settings: AppSettings) {
        if (settings.checkUpdatesOnStart) start(automatic = true)
    }

    fun dismissNotice() { notice = null }

    private fun start(automatic: Boolean) {
        if (status == UpdateStatus.Checking) return
        status = UpdateStatus.Checking
        background(Runnable {
            val result = runCatching { ReleaseCheck.check(version, source) }.getOrDefault(UpdateCheckResult.Failed)
            deliver(Runnable {
                status = UpdateStatus.Finished(result)
                if (automatic && result is UpdateCheckResult.UpdateAvailable) notice = result
            })
        })
    }
}

/** Opens only the release page built from the validated version, after the same checks as every other browser link. */
internal fun openReleasePage(result: UpdateCheckResult.UpdateAvailable,
                             browse: (URI) -> Unit = { Desktop.getDesktop().browse(it) }): Boolean =
    runCatching { browse(BrowserLinks.parse(result.releasePage)) }.isSuccess

/** Reads the setting once at start; enabling it later applies from the next start. */
@Composable
internal fun rememberUpdateChecks(settings: SettingsStore): UpdateChecks {
    val updates = remember { UpdateChecks() }
    LaunchedEffect(updates) { updates.checkOnStart(settings.current()) }
    return updates
}

@Composable
internal fun UpdateCheckSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(enabled, onCheckedChange = onChange)
        Text(UiText.text("update.onStart"))
    }
    Text(UiText.text("update.onStartHint"))
}

@Composable
internal fun UpdateCheckPanel(updates: UpdateChecks) {
    var openFailed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = updates.status != UpdateStatus.Checking, onClick = { openFailed = false; updates.checkNow() }) {
            Text(UiText.text("update.check"))
        }
        Text(UiText.text("update.manualHint"))
        when (val status = updates.status) {
            UpdateStatus.Idle -> Unit
            UpdateStatus.Checking -> Text(UiText.text("update.checking"))
            is UpdateStatus.Finished -> when (val result = status.result) {
                is UpdateCheckResult.UpToDate -> Text(UiText.text("update.upToDate", result.running.toString()))
                is UpdateCheckResult.UpdateAvailable -> {
                    Text(UiText.text("update.available", result.latest.toString(), result.running.toString()))
                    ReleaseNotes(result.notes)
                    Button(onClick = { openFailed = !openReleasePage(result) }) { Text(UiText.text("update.openRelease")) }
                }
                UpdateCheckResult.DevelopmentBuild -> Text(UiText.text("update.development"))
                UpdateCheckResult.Failed -> Text(UiText.text("update.failed"), color = MaterialTheme.colors.error)
            }
        }
        if (openFailed) Text(UiText.text("update.openFailed"), color = MaterialTheme.colors.error)
    }
}

/** Release notes are untrusted: shown as plain, already sanitized text in a bounded area, never as HTML or links. */
@Composable
private fun ReleaseNotes(notes: String) {
    if (notes.isEmpty()) return
    Text(UiText.text("update.notes"), style = MaterialTheme.typography.subtitle2)
    Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
        Text(notes, style = MaterialTheme.typography.body2)
    }
}

@Composable
internal fun UpdateNotice(updates: UpdateChecks) {
    val notice = updates.notice ?: return
    var openFailed by remember(notice) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(UiText.text("update.notice", notice.latest.toString()), modifier = Modifier.weight(1f))
        TextButton(onClick = { openFailed = !openReleasePage(notice) }) { Text(UiText.text("update.openRelease")) }
        TextButton(onClick = updates::dismissNotice) { Text(UiText.text("update.dismiss")) }
    }
    if (openFailed) Text(UiText.text("update.openFailed"), color = MaterialTheme.colors.error)
}
