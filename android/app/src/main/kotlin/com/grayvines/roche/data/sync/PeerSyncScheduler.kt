package com.grayvines.roche.data.sync

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
import android.os.PersistableBundle
import com.grayvines.roche.core.isLoopbackHost
import com.grayvines.roche.core.isTailnetOnlyHost
import com.grayvines.roche.core.isTailscaleUlaAddress
import com.grayvines.roche.data.remote.NetworkTier
import com.grayvines.roche.data.settings.extractTailnetDomains
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidNetworkTierProvider(context: Context) : NetworkTierProvider, AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? = runCatching {
        appContext.getSystemService(ConnectivityManager::class.java)
    }
        .getOrNull()
    private val activeVpnNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()
    private val vpnCallback = VpnTransportNetworkCallback(activeVpnNetworks, _vpnActive)
    private val callbackRegistered = AtomicBoolean(false)

    init {
        registerVpnCallback()
    }

    override fun currentNetworkTier(): NetworkTier = runCatching {
        val cm = connectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let(cm::getNetworkCapabilities)
        if (caps == null) {
            NetworkTier.OFFLINE
        } else {
            resolveActiveNetworkTier(
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                isNotMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
    }
        .getOrDefault(NetworkTier.WIFI)

    override fun isVpnTransportActive(): Boolean = _vpnActive.value || hasActiveVpnTunnelInterface()

    override fun activeTailnetDomains(): List<String> = runCatching {
        val cm = connectivityManager ?: return emptyList()
        val networks = buildSet {
            addAll(activeVpnNetworks)
            cm.activeNetwork?.let(::add)
        }
        extractTailnetDomains(networks.mapNotNull { net -> cm.getLinkProperties(net)?.domains })
    }
        .getOrDefault(emptyList())

    override fun close() {
        if (callbackRegistered.compareAndSet(true, false)) {
            runCatching { connectivityManager?.unregisterNetworkCallback(vpnCallback) }
        }
    }

    private fun registerVpnCallback() {
        val cm = connectivityManager ?: return
        runCatching {
            val request =
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            cm.registerNetworkCallback(request, vpnCallback)
            callbackRegistered.set(true)
        }
    }
}

private val VPN_TUNNEL_NAME_PREFIXES = listOf("tun", "wg", "tailscale", "utun", "ppp", "ipsec")

internal fun hasActiveVpnTunnelInterface(): Boolean = runCatching {
    val interfaces = NetworkInterface.getNetworkInterfaces()
    interfaces?.asSequence()?.any { iface ->
        runCatching {
                isActiveVpnTunnelInterface(
                    name = iface.name.orEmpty(),
                    isUp = iface.isUp,
                    isLoopback = iface.isLoopback,
                    addresses = Collections.list(iface.inetAddresses),
                )
            }
            .getOrDefault(false)
    } ?: false
}
    .getOrDefault(false)

internal fun isActiveVpnTunnelInterface(
    name: String,
    isUp: Boolean,
    isLoopback: Boolean,
    addresses: List<InetAddress>,
): Boolean {
    if (!isUp || isLoopback) return false
    val lowerName = name.trim().lowercase(Locale.ROOT)
    val hasTunnelPrefix = VPN_TUNNEL_NAME_PREFIXES.any { lowerName.startsWith(it) }
    val hasNonLoopbackAddress = addresses.any { !it.isLoopbackAddress }
    val hasTailscaleUla = addresses.any(::isTailscaleUlaAddress)
    return hasTunnelPrefix && hasNonLoopbackAddress || hasTailscaleUla
}

@Suppress("LongParameterList")
internal fun resolveActiveNetworkTier(
    hasInternet: Boolean,
    hasVpn: Boolean,
    hasEthernet: Boolean,
    hasCellular: Boolean,
    hasWifi: Boolean,
    isNotMetered: Boolean,
): NetworkTier =
    when {
        !hasVpn && !hasInternet -> NetworkTier.OFFLINE
        hasEthernet -> NetworkTier.WIRED
        hasCellular -> NetworkTier.CELLULAR
        hasWifi -> NetworkTier.WIFI
        !isNotMetered -> NetworkTier.CELLULAR
        else -> NetworkTier.WIFI
    }

internal class VpnTransportNetworkCallback(
    private val activeVpnNetworks: MutableSet<Network>,
    private val vpnActiveState: MutableStateFlow<Boolean>,
) : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        activeVpnNetworks.add(network)
        vpnActiveState.value = true
    }

    override fun onCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
    ) {
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            activeVpnNetworks.add(network)
        } else {
            activeVpnNetworks.remove(network)
        }
        vpnActiveState.value = activeVpnNetworks.isNotEmpty()
    }

    override fun onLost(network: Network) {
        activeVpnNetworks.remove(network)
        vpnActiveState.value = activeVpnNetworks.isNotEmpty()
    }
}

internal class PeerSyncJobTracker<P>(
    private val jobIdOf: (P) -> Int,
    private val onJobFinished: (P, Boolean) -> Unit,
) {
    private class RunningJobEntry(
        val job: Job,
        val stoppedBySystem: AtomicBoolean = AtomicBoolean(false),
    )

    private val activeJobs = ConcurrentHashMap<Int, RunningJobEntry>()

    fun start(scope: CoroutineScope, params: P, work: suspend () -> Unit): Boolean {
        val jobId = jobIdOf(params)
        val stoppedBySystem = AtomicBoolean(false)
        var entryRef: RunningJobEntry? = null
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    work()
                } finally {
                    entryRef?.let { activeJobs.remove(jobId, it) }
                    if (!stoppedBySystem.get()) {
                        onJobFinished(params, false)
                    }
                }
            }
        val entry = RunningJobEntry(job = job, stoppedBySystem = stoppedBySystem)
        entryRef = entry
        activeJobs.put(jobId, entry)?.let { previous ->
            previous.stoppedBySystem.set(true)
            previous.job.cancel()
        }
        job.start()
        return true
    }

    fun stop(params: P): Boolean {
        val jobId = jobIdOf(params)
        activeJobs.remove(jobId)?.let { entry ->
            entry.stoppedBySystem.set(true)
            entry.job.cancel()
        }
        return true
    }
}

interface PeerSyncHost {
    val peerSyncScope: CoroutineScope

    suspend fun runBackgroundPeerSyncAndPoll()
}

class PeerSyncJobService : JobService() {
    private val tracker =
        PeerSyncJobTracker<JobParameters>(
            jobIdOf = { it.jobId },
            onJobFinished = { params, needsReschedule -> jobFinished(params, needsReschedule) },
        )

    override fun onStartJob(params: JobParameters?): Boolean {
        val jobParams = params ?: return false
        val host = application as? PeerSyncHost ?: return false
        return tracker.start(host.peerSyncScope, jobParams) { host.runBackgroundPeerSyncAndPoll() }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val jobParams = params ?: return false
        return tracker.stop(jobParams)
    }
}

object PeerSyncScheduler {
    internal const val PEER_SYNC_JOB_ID = 4107
    internal const val PEER_SYNC_IMMEDIATE_JOB_ID = 4108
    internal const val PERIODIC_SYNC_INTERVAL_MS = 30L * 60L * 1000L
    private const val IMMEDIATE_SYNC_DEADLINE_MS = 30_000L
    private const val EXTRA_LEGACY_REQUIRES_VPN = "requires_vpn"
    private const val EXTRA_NETWORK_ANY_CONFIGURED = "network_any_configured"
    private const val EXTRA_TAILNET_SERVER_HINT = "tailnet_server_hint"

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

    internal fun shouldKeepExistingPeriodicJob(
        isPersisted: Boolean,
        intervalMillis: Long,
        legacyRequiresVpnExtra: Boolean,
        networkAnyConfiguredExtra: Boolean,
    ): Boolean =
        isPersisted &&
            intervalMillis == PERIODIC_SYNC_INTERVAL_MS &&
            !legacyRequiresVpnExtra &&
            networkAnyConfiguredExtra

    fun schedulePeriodicSync(context: Context, serverUrl: String = "") {
        runCatching {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
            val tailnetServerHint = requiresVpnForServerUrl(serverUrl)
            val pending = scheduler.getPendingJob(PEER_SYNC_JOB_ID)
            if (
                pending != null &&
                    shouldKeepExistingPeriodicJob(
                        isPersisted = pending.isPersisted,
                        intervalMillis = pending.intervalMillis,
                        legacyRequiresVpnExtra =
                            pending.extras.getBoolean(EXTRA_LEGACY_REQUIRES_VPN, false),
                        networkAnyConfiguredExtra =
                            pending.extras.getBoolean(EXTRA_NETWORK_ANY_CONFIGURED, false),
                    )
            ) {
                return
            }
            val serviceComponent = ComponentName(appContext, PeerSyncJobService::class.java)
            val extras =
                PersistableBundle().apply {
                    putBoolean(EXTRA_NETWORK_ANY_CONFIGURED, true)
                    putBoolean(EXTRA_TAILNET_SERVER_HINT, tailnetServerHint)
                }
            val jobInfo =
                JobInfo.Builder(PEER_SYNC_JOB_ID, serviceComponent)
                    .setPeriodic(PERIODIC_SYNC_INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setRequiresBatteryNotLow(true)
                    .setExtras(extras)
                    .build()
            scheduler.schedule(jobInfo)
        }
    }

    fun scheduleImmediateSync(context: Context, serverUrl: String = "") {
        runCatching {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
            val tailnetServerHint = requiresVpnForServerUrl(serverUrl)
            val serviceComponent = ComponentName(appContext, PeerSyncJobService::class.java)
            val extras =
                PersistableBundle().apply {
                    putBoolean(EXTRA_NETWORK_ANY_CONFIGURED, true)
                    putBoolean(EXTRA_TAILNET_SERVER_HINT, tailnetServerHint)
                }
            val jobInfo =
                JobInfo.Builder(PEER_SYNC_IMMEDIATE_JOB_ID, serviceComponent)
                    .setMinimumLatency(0L)
                    .setOverrideDeadline(IMMEDIATE_SYNC_DEADLINE_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(extras)
                    .build()
            scheduler.schedule(jobInfo)
        }
    }
}
