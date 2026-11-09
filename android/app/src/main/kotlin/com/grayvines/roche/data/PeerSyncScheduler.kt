package com.grayvines.roche.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.grayvines.roche.RocheApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AndroidNetworkTierProvider(context: Context) : NetworkTierProvider {
    private val appContext = context.applicationContext

    override fun currentNetworkTier(): NetworkTier = runCatching {
        val cm =
            appContext.getSystemService(ConnectivityManager::class.java)
                ?: return NetworkTier.OFFLINE
        val activeNetwork = cm.activeNetwork ?: return NetworkTier.OFFLINE
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkTier.OFFLINE
        classifyNetworkCapabilities(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
        .getOrDefault(NetworkTier.WIFI)
}

class PeerSyncJobService : JobService() {
    private var runningJob: Job? = null
    @Volatile private var stoppedBySystem: Boolean = false

    override fun onStartJob(params: JobParameters?): Boolean {
        val graph = (application as? RocheApp)?.graph ?: return false
        stoppedBySystem = false
        runningJob?.cancel()
        runningJob =
            graph.appScope.launch {
                try {
                    graph.repository.runBackgroundPeerSyncAndPoll()
                } finally {
                    if (!stoppedBySystem && params != null) {
                        jobFinished(params, false)
                    }
                }
            }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        stoppedBySystem = true
        runningJob?.cancel()
        runningJob = null
        return true
    }
}

object PeerSyncScheduler {
    internal const val PEER_SYNC_JOB_ID = 4107
    internal const val PERIODIC_SYNC_INTERVAL_MS = 30L * 60L * 1000L

    fun schedulePeriodicSync(context: Context) {
        runCatching {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(PEER_SYNC_JOB_ID) != null) return
            val serviceComponent = ComponentName(appContext, PeerSyncJobService::class.java)
            val jobInfo =
                JobInfo.Builder(PEER_SYNC_JOB_ID, serviceComponent)
                    .setPeriodic(PERIODIC_SYNC_INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .build()
            scheduler.schedule(jobInfo)
        }
    }
}
