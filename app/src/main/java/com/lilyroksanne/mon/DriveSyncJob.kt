package com.lilyroksanne.mon

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.lilyroksanne.mon.util.MonLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DriveSyncJob : JobService() {
    private val running = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val connection = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val assigned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.network else null
        val network = assigned ?: connection.activeNetwork ?: return false
        if (connection.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true
        ) return false

        running[params.jobId] = CoroutineScope(Dispatchers.Main).launch {
            val complete = try {
                withContext(Dispatchers.IO) { MusicDownloads(applicationContext, network).sync() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MonLogger.e("DriveMusic", "No se pudo revisar la carpeta pública", error)
                false
            }
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
        private const val INITIAL_JOB = 17302
        private const val INTERVAL = 15L * 60 * 1000

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, DriveSyncJob::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
                scheduler.schedule(JobInfo.Builder(PERIODIC_JOB, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(INTERVAL)
                    .build())
            }
            val last = MusicDownloads(context).index.getLong(MusicDownloads.LAST_SYNC, 0)
            if (System.currentTimeMillis() - last >= INTERVAL && scheduler.getPendingJob(INITIAL_JOB) == null) {
                scheduler.schedule(JobInfo.Builder(INITIAL_JOB, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build())
            }
        }
    }
}
