package com.lilyroksanne.mon

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

internal object DriveSyncStatus {
    enum class Phase { IDLE, WAITING, SEARCHING, DOWNLOADING, COMPLETE, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val title: String = "",
        val position: Int = 0,
        val total: Int = 0,
        val totalFoundInDrive: Int = 0,
        val percent: Int? = null,
        val downloadedBytes: Long = 0L,
        val downloaded: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
        val logs: List<String> = emptyList(),
        val runId: Long = 0L
    ) {
        val busy: Boolean get() = phase == Phase.SEARCHING || phase == Phase.DOWNLOADING

        val summary: String get() = when (phase) {
            Phase.IDLE -> "DRIVE IDLE"
            Phase.WAITING -> "ESPERANDO RED"
            Phase.SEARCHING -> "ESCANEANDO DRIVE..."
            Phase.DOWNLOADING -> {
                val pct = percent?.let { " $it%" }
                    ?: if (downloadedBytes > 0L) " ${formatBytes(downloadedBytes)}" else ""
                "DL $position/$total$pct"
            }
            Phase.COMPLETE -> if (downloaded.isEmpty()) {
                "OK · AL DÍA ($totalFoundInDrive)"
            } else {
                "OK · +${downloaded.size} ${if (downloaded.size == 1) "ROLA" else "ROLAS"}"
            }
            Phase.ERROR -> if (errors.size == 1) "ERROR DRIVE" else "${errors.size} ERRORES"
        }

        private fun formatBytes(bytes: Long): String {
            val mb = bytes / (1024f * 1024f)
            return if (mb >= 1f) String.format(Locale.US, "%.1fMB", mb) else "${bytes / 1024L}KB"
        }
    }

    private const val MAX_LOGS = 18
    private val current = MutableStateFlow(State())
    val state = current.asStateFlow()

    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun appendLog(existing: List<String>, line: String): List<String> {
        val next = existing + "[${timestamp()}] $line"
        return if (next.size > MAX_LOGS) next.takeLast(MAX_LOGS) else next
    }

    fun restore(context: Context) {
        if (current.value.phase != Phase.IDLE) return
        val stored = context.getSharedPreferences("DriveSyncStatus", Context.MODE_PRIVATE)
        val phase = stored.getString("phase", null)?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: return
        current.value = State(
            phase = phase,
            totalFoundInDrive = stored.getInt("totalFound", 0),
            downloaded = readList(stored.getString("downloads", "[]")),
            errors = readList(stored.getString("errors", "[]")),
            logs = readList(stored.getString("logs", "[]"))
        )
    }

    fun waiting(reason: String = "Sin conexión a Internet. En espera de Wi-Fi/datos.") {
        current.update {
            if (it.busy) it else it.copy(
                phase = Phase.WAITING,
                logs = appendLog(it.logs, "WAIT: $reason")
            )
        }
    }

    fun waitingAfterInterruption() {
        current.update {
            it.copy(
                phase = Phase.WAITING,
                percent = null,
                downloadedBytes = 0L,
                title = "",
                logs = appendLog(it.logs, "WAIT: Sincronización interrumpida; se reintentará.")
            )
        }
    }

    fun searching() {
        current.update {
            val nextRun = it.runId + 1L
            State(
                phase = Phase.SEARCHING,
                logs = appendLog(it.logs, "SCAN: Conectando a carpeta pública de Drive..."),
                runId = nextRun
            )
        }
    }

    fun foundSummary(totalInDrive: Int, newCount: Int) {
        current.update {
            it.copy(
                totalFoundInDrive = totalInDrive,
                total = newCount,
                logs = appendLog(it.logs, "SCAN: $totalInDrive pistas en Drive, $newCount nuevas.")
            )
        }
    }

    fun downloading(title: String, position: Int, total: Int) {
        current.update {
            it.copy(
                phase = Phase.DOWNLOADING,
                title = title,
                position = position,
                total = total,
                percent = null,
                downloadedBytes = 0L,
                logs = appendLog(it.logs, "DL ($position/$total): $title")
            )
        }
    }

    fun progress(percent: Int?, bytes: Long = 0L) {
        current.update { it.copy(percent = percent, downloadedBytes = bytes) }
    }

    fun downloaded(title: String) {
        current.update {
            it.copy(
                downloaded = it.downloaded + title,
                logs = appendLog(it.logs, "OK: Guardada '$title'")
            )
        }
    }

    fun failure(title: String, error: Exception) {
        val reason = describe(error)
        current.update {
            it.copy(
                errors = it.errors + "$title · $reason",
                logs = appendLog(it.logs, "ERR [$title]: $reason")
            )
        }
    }

    fun finished(context: Context) {
        current.update {
            val nextPhase = if (it.errors.isEmpty()) Phase.COMPLETE else Phase.ERROR
            val doneMsg = if (nextPhase == Phase.COMPLETE) {
                "FIN: ${it.downloaded.size} descargadas, sin errores."
            } else {
                "FIN: ${it.downloaded.size} descargadas, ${it.errors.size} error(es)."
            }
            it.copy(
                phase = nextPhase,
                percent = if (nextPhase == Phase.COMPLETE) 100 else it.percent,
                title = "",
                logs = appendLog(it.logs, doneMsg)
            )
        }
        val result = current.value
        context.getSharedPreferences("DriveSyncStatus", Context.MODE_PRIVATE).edit()
            .putString("phase", result.phase.name)
            .putInt("totalFound", result.totalFoundInDrive)
            .putString("downloads", JSONArray(result.downloaded).toString())
            .putString("errors", JSONArray(result.errors).toString())
            .putString("logs", JSONArray(result.logs).toString())
            .apply()
    }

    private fun readList(value: String?): List<String> = runCatching {
        val json = JSONArray(value ?: "[]")
        (0 until json.length()).map { json.getString(it) }
    }.getOrDefault(emptyList())

    private fun describe(error: Exception): String = when (error) {
        is UnknownHostException -> "Sin DNS / no se alcanzó Drive (${error.message ?: "host"})."
        is SocketTimeoutException -> "Tiempo de espera agotado conectando a Drive."
        is SSLException -> "Error SSL/HTTPS (revisa fecha/hora del estéreo): ${error.message ?: "SSL"}"
        is IllegalArgumentException, is IllegalStateException ->
            error.message?.take(140) ?: error.javaClass.simpleName
        is IOException -> "Error de red/disco: ${error.message?.take(120) ?: error.javaClass.simpleName}"
        else -> "${error.javaClass.simpleName}: ${error.message?.take(100).orEmpty()}"
    }
}
