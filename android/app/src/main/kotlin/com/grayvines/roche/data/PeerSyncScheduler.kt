package com.grayvines.roche.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import com.grayvines.roche.RocheApp
import java.net.URI
import java.util.Locale
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

    override fun isVpnTransportActive(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeHasVpn =
            cm.activeNetwork?.let { active ->
                cm.getNetworkCapabilities(active)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } == true
        if (activeHasVpn) return true
        @Suppress("DEPRECATION")
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ==
                true
        }
    }
        .getOrDefault(false)
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
    private const val EXTRA_REQUIRES_VPN = "requires_vpn"

    internal fun requiresVpnForServerUrl(serverUrl: String): Boolean {
        val trimmed = serverUrl.trim()
        if (trimmed.isEmpty()) return false
        val host = runCatching {
            URI(trimmed.replace(" ", "%20")).host?.trim()?.lowercase(Locale.ROOT)
        }
            .getOrNull()
            .orEmpty()
        return host.isNotEmpty() && !isLoopbackHost(host) && isTailnetOnlyHost(host)
    }

    fun schedulePeriodicSync(context: Context, serverUrl: String = "") {
        runCatching {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
            val requiresVpn = requiresVpnForServerUrl(serverUrl)
            val pending = scheduler.getPendingJob(PEER_SYNC_JOB_ID)
            if (
                pending != null &&
                    pending.extras.getBoolean(EXTRA_REQUIRES_VPN, false) == requiresVpn
            ) {
                return
            }
            val serviceComponent = ComponentName(appContext, PeerSyncJobService::class.java)
            val extras = PersistableBundle().apply { putBoolean(EXTRA_REQUIRES_VPN, requiresVpn) }
            val builder =
                JobInfo.Builder(PEER_SYNC_JOB_ID, serviceComponent)
                    .setPeriodic(PERIODIC_SYNC_INTERVAL_MS)
                    .setRequiresBatteryNotLow(true)
                    .setExtras(extras)
            if (requiresVpn) {
                val vpnRequest =
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build()
                builder.setRequiredNetwork(vpnRequest)
            } else {
                builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            }
            scheduler.schedule(builder.build())
        }
    }
}
