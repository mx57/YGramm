// File: app/src/main/java/com/example/service/YggNodeForegroundService.kt
package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class YggForegroundServiceState(
    val isServiceRunning: Boolean = false,
    val activeProfilesCount: Int = 1,
    val connectedPeersCount: Int = 0,
    val primaryIpv6: String = "0201::",
    val activePortsSummary: String = ":51921",
    val txPackets: Int = 0,
    val rxPackets: Int = 0,
    val uptimeSeconds: Long = 0L
)

/**
 * Persistent Android Foreground Service that keeps the Yggdrasil overlay node and
 * multi-profile P2P listeners active in the background.
 * Displays a persistent status notification with live node telemetry and a "Stop Node" action button.
 */
class YggNodeForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var startedTimestampMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        startedTimestampMs = System.currentTimeMillis()
        _serviceState.update { it.copy(isServiceRunning = true, uptimeSeconds = 0L) }
        startHeartbeatLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_NODE -> {
                _serviceState.update { it.copy(isServiceRunning = false) }
                onUserRequestedStop?.invoke()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PING_NOW -> {
                onUserRequestedPing?.invoke()
            }
            else -> {
                val profilesCount = intent?.getIntExtra(EXTRA_PROFILES_COUNT, _serviceState.value.activeProfilesCount)
                    ?: _serviceState.value.activeProfilesCount
                val peersCount = intent?.getIntExtra(EXTRA_PEERS_COUNT, _serviceState.value.connectedPeersCount)
                    ?: _serviceState.value.connectedPeersCount
                val ipv6 = intent?.getStringExtra(EXTRA_PRIMARY_IPV6) ?: _serviceState.value.primaryIpv6
                val ports = intent?.getStringExtra(EXTRA_ACTIVE_PORTS) ?: _serviceState.value.activePortsSummary
                val tx = intent?.getIntExtra(EXTRA_TX_PACKETS, _serviceState.value.txPackets)
                    ?: _serviceState.value.txPackets
                val rx = intent?.getIntExtra(EXTRA_RX_PACKETS, _serviceState.value.rxPackets)
                    ?: _serviceState.value.rxPackets

                _serviceState.update {
                    it.copy(
                        isServiceRunning = true,
                        activeProfilesCount = profilesCount.coerceAtLeast(1),
                        connectedPeersCount = peersCount.coerceAtLeast(0),
                        primaryIpv6 = ipv6,
                        activePortsSummary = ports,
                        txPackets = tx,
                        rxPackets = rx
                    )
                }
            }
        }

        val notification = buildStatusNotification(_serviceState.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
            }
        }

        return START_STICKY
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(5000L)
                val upSec = ((System.currentTimeMillis() - startedTimestampMs) / 1000L).coerceAtLeast(0L)
                _serviceState.update { it.copy(uptimeSeconds = upSec) }
                if (upSec > 0 && upSec % 25L == 0L) {
                    onPeriodicBackgroundHeartbeat?.invoke()
                }
                updateNotificationNow(_serviceState.value)
            }
        }
    }

    private fun updateNotificationNow(state: YggForegroundServiceState) {
        if (!state.isServiceRunning) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID, buildStatusNotification(state))
        } catch (_: Throwable) {
        }
    }

    private fun buildStatusNotification(state: YggForegroundServiceState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, YggNodeForegroundService::class.java).apply {
            action = ACTION_STOP_NODE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pingIntent = Intent(this, YggNodeForegroundService::class.java).apply {
            action = ACTION_PING_NOW
        }
        val pingPendingIntent = PendingIntent.getService(
            this,
            102,
            pingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Узел Yggdrasil активен • Профилей: ${state.activeProfilesCount} • Пиров: ${state.connectedPeersCount}"
        val body = "IPv6: ${state.primaryIpv6.take(20)} • Порты ${state.activePortsSummary} • TX:${state.txPackets} RX:${state.rxPackets}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_popup_sync,
                "Пинг пиров",
                pingPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить узел",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновый узел Yggdrasil P2P",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Обеспечивает фоновую работу оверлейной сети Yggdrasil и одновременную работу профилей P2P"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YggGram::MeshNodeWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(lockMode, "YggGram::MeshWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        _serviceState.update { it.copy(isServiceRunning = false) }
        heartbeatJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "ygggram_foreground_node_channel"
        const val NOTIFICATION_ID = 7743
        const val ACTION_START_OR_UPDATE = "com.example.action.START_OR_UPDATE_NODE"
        const val ACTION_STOP_NODE = "com.example.action.STOP_YGG_NODE"
        const val ACTION_PING_NOW = "com.example.action.PING_YGG_PEERS"

        const val EXTRA_PROFILES_COUNT = "extra_profiles_count"
        const val EXTRA_PEERS_COUNT = "extra_peers_count"
        const val EXTRA_PRIMARY_IPV6 = "extra_primary_ipv6"
        const val EXTRA_ACTIVE_PORTS = "extra_active_ports"
        const val EXTRA_TX_PACKETS = "extra_tx_packets"
        const val EXTRA_RX_PACKETS = "extra_rx_packets"

        private val _serviceState = MutableStateFlow(YggForegroundServiceState())
        val serviceState: StateFlow<YggForegroundServiceState> = _serviceState.asStateFlow()

        var onUserRequestedStop: (() -> Unit)? = null
        var onUserRequestedPing: (() -> Unit)? = null
        var onPeriodicBackgroundHeartbeat: (() -> Unit)? = null

        fun startOrUpdateNodeService(
            context: Context,
            activeProfilesCount: Int,
            connectedPeersCount: Int,
            primaryIpv6: String,
            activePortsSummary: String,
            txPackets: Int,
            rxPackets: Int
        ) {
            _serviceState.update {
                it.copy(
                    isServiceRunning = true,
                    activeProfilesCount = activeProfilesCount.coerceAtLeast(1),
                    connectedPeersCount = connectedPeersCount.coerceAtLeast(0),
                    primaryIpv6 = primaryIpv6,
                    activePortsSummary = activePortsSummary,
                    txPackets = txPackets,
                    rxPackets = rxPackets
                )
            }
            try {
                val intent = Intent(context, YggNodeForegroundService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_PROFILES_COUNT, activeProfilesCount)
                    putExtra(EXTRA_PEERS_COUNT, connectedPeersCount)
                    putExtra(EXTRA_PRIMARY_IPV6, primaryIpv6)
                    putExtra(EXTRA_ACTIVE_PORTS, activePortsSummary)
                    putExtra(EXTRA_TX_PACKETS, txPackets)
                    putExtra(EXTRA_RX_PACKETS, rxPackets)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }

        fun stopNodeService(context: Context) {
            _serviceState.update { it.copy(isServiceRunning = false) }
            try {
                val intent = Intent(context, YggNodeForegroundService::class.java)
                context.stopService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
