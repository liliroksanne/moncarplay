package com.lilyroksanne.mon

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.lilyroksanne.mon.util.MonLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DriveSyncJob : JobService() {
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val assigned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.network else null
        val network = availableNetwork(applicationContext, assigned)
        if (network == null) {
            DriveSyncStatus.waiting()
            return false
        }
        running[params.jobId] = CoroutineScope(Dispatchers.Main).launch {
            val complete = withContext(Dispatchers.IO) { MusicDownloads(applicationContext, network).sync() }
            running.remove(params.jobId)
            jobFinished(params, !complete)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        running.values.forEach { it.cancel() }
        running.clear()
        super.onDestroy()
    }

    companion object {
        private const val PERIODIC_JOB = 17301
        private const val DEFERRED_JOB = 17302
        private const val INTERVAL = 15L * 60 * 1000
        private const val AUTO_RECONNECT_COOLDOWN_MS = 60_000L

        private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var activeSyncJob: Job? = null
        private var networkCallback: ConnectivityManager.NetworkCallback? = null
        private var lastAutoSyncAttemptMs = 0L

        fun schedule(context: Context) = start(context)

        fun start(context: Context, force: Boolean = false) {
            val app = context.applicationContext
            val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(app, DriveSyncJob::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
                runCatching {
                    scheduler.schedule(
                        JobInfo.Builder(PERIODIC_JOB, component)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true)
                            .setPeriodic(INTERVAL)
                            .build()
                    )
                }
            }

            val network = availableNetwork(app)
            if (network == null) {
                DriveSyncStatus.waiting("Sin red disponible todavía. Se descargará al conectar Wi-Fi/datos.")
                if (scheduler.getPendingJob(DEFERRED_JOB) == null) {
                    runCatching {
                        scheduler.schedule(
                            JobInfo.Builder(DEFERRED_JOB, component)
                                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                                .setPersisted(true)
                                .build()
                        )
                    }
                }
                return
            }

            if (activeSyncJob?.isActive == true) return
            if (!force && System.currentTimeMillis() - lastAutoSyncAttemptMs < 10_000L) return

            lastAutoSyncAttemptMs = System.currentTimeMillis()
            activeSyncJob = startupScope.launch {
                MusicDownloads(app, network).sync()
            }
        }

        fun registerNetworkObserver(context: Context) {
            if (networkCallback != null) return
            val app = context.applicationContext
            val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val now = System.currentTimeMillis()
                    val currentPhase = DriveSyncStatus.state.value.phase
                    val shouldSync = currentPhase == DriveSyncStatus.Phase.WAITING ||
                        currentPhase == DriveSyncStatus.Phase.ERROR ||
                        currentPhase == DriveSyncStatus.Phase.IDLE ||
                        (now - lastAutoSyncAttemptMs) >= AUTO_RECONNECT_COOLDOWN_MS
                    if (shouldSync && activeSyncJob?.isActive != true) {
                        MonLogger.i("DriveSyncJob", "Red detectada; iniciando búsqueda en Drive.")
                        lastAutoSyncAttemptMs = now
                        activeSyncJob = startupScope.launch {
                            MusicDownloads(app, network).sync()
                        }
                    }
                }
            }
            runCatching {
                manager.registerNetworkCallback(request, callback)
                networkCallback = callback
            }.onFailure {
                MonLogger.w("DriveSyncJob", "No se pudo registrar NetworkCallback: ${it.message}")
            }
        }

        fun unregisterNetworkObserver(context: Context) {
            val cb = networkCallback ?: return
            networkCallback = null
            val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { manager.unregisterNetworkCallback(cb) }
        }

        fun hasInternet(context: Context): Boolean = availableNetwork(context.applicationContext) != null

        private fun availableNetwork(context: Context, assigned: Network? = null): Network? {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val candidate = assigned ?: manager.activeNetwork ?: return null
            return candidate.takeIf {
                manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }
        }
    }
}
