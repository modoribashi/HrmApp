package com.stanleymasinde.hrmapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.stanleymasinde.hrmapp.R
import com.stanleymasinde.hrmapp.ble.BleHrmServer
import com.stanleymasinde.hrmapp.sensor.HrDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HrmForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var bleIssue: String? = null
    private val staleSample = Runnable {
        _heartRate.value = 0
        _isSensorAvailable.value = false
        _sensorStatus.value = "No recent heart rate; check watch fit"
        refreshStatusMessage()
    }
    private val binder = LocalBinder()
    private lateinit var bleHrmServer: BleHrmServer
    private lateinit var hrDataSource: HrDataSource

    private val _heartRate = MutableStateFlow(0)
    val heartRate = _heartRate.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising = _isAdvertising.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed = _isSubscribed.asStateFlow()
    private val _sensorStatus = MutableStateFlow("Waiting for heart rate...")
    val sensorStatus = _sensorStatus.asStateFlow()

    private val _pairingStatus = MutableStateFlow("Connect a receiver before pairing")
    val pairingStatus = _pairingStatus.asStateFlow()
    private val _canPairReceiver = MutableStateFlow(false)
    val canPairReceiver = _canPairReceiver.asStateFlow()

    fun pairReceiver() = bleHrmServer.pairReceiver()

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring = _isMeasuring.asStateFlow()

    private val _isSensorAvailable = MutableStateFlow(false)
    val isSensorAvailable = _isSensorAvailable.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to broadcast")
    val statusMessage = _statusMessage.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): HrmForegroundService = this@HrmForegroundService
    }

    companion object {
        const val ACTION_START = "com.stanleymasinde.hrmapp.START"
        const val ACTION_STOP  = "com.stanleymasinde.hrmapp.STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "hrm_service_channel"
        private const val TAG = "HrmForegroundService"

        fun start(context: Context) {
            val intent = Intent(context, HrmForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HrmForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        bleHrmServer = BleHrmServer(this).apply {
            onConnectionStateChanged = { connected ->
                _isConnected.value = connected
                refreshStatusMessage()
            }
            onAdvertisingStateChanged = { advertising ->
                _isAdvertising.value = advertising
                if (advertising) bleIssue = null
                refreshStatusMessage()
            }
            onMeasurementDemandChanged = { demanded ->
                _isSubscribed.value = demanded
                if (demanded) hrDataSource.start() else {
                    hrDataSource.stop()
                    handler.removeCallbacks(staleSample)
                    _heartRate.value = 0
                }
                refreshStatusMessage()
            }
            onPairingStateChanged = { message, canPair ->
                _pairingStatus.value = message
                _canPairReceiver.value = canPair
            }
            onError = { message ->
                Log.w(TAG, message)
                bleIssue = message
                refreshStatusMessage()
            }
        }
        hrDataSource = HrDataSource(
            context = this,
            onHeartRateChanged = { bpm ->
                if (_isRunning.value && _isSubscribed.value) {
                    _heartRate.value = bpm
                    bleHrmServer.updateHeartRate(bpm)
                    handler.removeCallbacks(staleSample)
                    handler.postDelayed(staleSample, 10_000L)
                    refreshStatusMessage()
                }
            },
            onRegistrationChanged = { registered ->
                _isMeasuring.value = registered
                refreshStatusMessage()
            },
            onStatusChanged = { message ->
                _sensorStatus.value = message
                refreshStatusMessage()
            },
            onAvailabilityChanged = { available ->
                _isSensorAvailable.value = available
                if (!available) _heartRate.value = 0
                refreshStatusMessage()
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                // A system restart can resume an active session; explicit Stop cannot.
                try {
                    startForegroundServiceInternal()
                    if (!_isRunning.value) {
                        _isRunning.value = true
                        bleIssue = null
                        if (!bleHrmServer.start()) {
                            handleStartupFailure()
                            return START_NOT_STICKY
                        }
                    }
                    refreshStatusMessage()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Unable to start foreground session", e)
                    handleStartupFailure()
                    _statusMessage.value = "Open app and grant required permissions"
                }
            }
            ACTION_STOP -> {
                stopWork()
                stopSelf()
            }
        }
        return if (_isRunning.value) START_STICKY else START_NOT_STICKY
    }

    private fun startForegroundServiceInternal() {
        ensureChannel()
        val notification = buildNotification(0)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(bpm: Int): Notification {
        val stopIntent = Intent(this, HrmForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when {
            bpm > 0 -> "Current HR: $bpm BPM"
            !_isConnected.value -> "Waiting for receiver..."
            !_isSubscribed.value -> "Waiting for receiver subscription..."
            else -> "Waiting for heart rate..."
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HRM Broadcasting")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStopIntent)
            .build()
    }

    private fun updateNotification(bpm: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(bpm))
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HRM Background Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun stopWork() {
        _isRunning.value = false
        handler.removeCallbacks(staleSample)
        bleHrmServer.stop()
        hrDataSource.stop()
        _isSubscribed.value = false
        _heartRate.value = 0
        bleIssue = null
        refreshStatusMessage()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun handleStartupFailure() {
        stopWork()
        stopSelf()
    }

    private fun refreshStatusMessage() {
        _statusMessage.value = when {
            !_isRunning.value -> "Ready to broadcast"
            bleIssue != null -> bleIssue!!
            !_isConnected.value -> if (_isAdvertising.value) "Advertising BLE..." else "Preparing BLE..."
            !_isSubscribed.value -> "Connected, not subscribed"
            else -> "Receiver subscribed"
        }
        if (_isRunning.value) updateNotification(_heartRate.value)
    }

    override fun onDestroy() {
        stopWork()
        bleHrmServer.close()
        super.onDestroy()
    }
}
