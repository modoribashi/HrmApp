package com.stanleymasinde.hrmapp.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

/** Serialize lifecycle and GATT callbacks on the main looper. */
@SuppressLint("MissingPermission")
class BleHrmServer(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("bonded_hr_cccd", Context.MODE_PRIVATE)
    private val subscriptions = ReceiverSubscriptions()
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisement: AdvertiseCallback? = null
    private var generation = 0
    private var ready = false
    private var wanted = false
    private var receiverRegistered = false
    private var retryCount = 0
    private var lastDemand = false
    private var pairingAddress: String? = null
    private var pairingMessage: String? = null
    private var pairingTimedOut = false
    private val pairingTimeout = Runnable {
        pairingTimedOut = true
        val state = try { pairingAddress?.let { adapter?.getRemoteDevice(it)?.bondState } }
            catch (_: RuntimeException) { null }
        if (state == BluetoothDevice.BOND_BONDED) {
            pairingAddress?.let { address ->
                if (address in devices) preferences.edit()
                    .putBoolean(address, subscriptions.subscribed(address)).apply()
            }
            pairingAddress = null
            pairingMessage = "Paired; test reconnect after address changes"
        } else {
            if (state != BluetoothDevice.BOND_BONDING) pairingAddress = null
            pairingMessage = if (state == BluetoothDevice.BOND_BONDING) {
                "Pairing still pending; check both devices"
            } else "Pairing timed out; check receiver support"
            Log.w(TAG, "Receiver bonding timeout; state=$state")
        }
        publishPairingState()
    }
    private val retry = Runnable { ensureServerAndAdvertising() }
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onAdvertisingStateChanged: ((Boolean) -> Unit)? = null
    var onMeasurementDemandChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPairingStateChanged: ((String, Boolean) -> Unit)? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val address = device.address
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_NONE) preferences.edit().remove(address).apply()
                if (address !in devices && address != pairingAddress) return
                Log.d(TAG, "Receiver bond state=$state")
                if (state == BluetoothDevice.BOND_BONDED && address in devices) {
                    // A receiver may subscribe before pairing completes.
                    preferences.edit().putBoolean(address, subscriptions.subscribed(address)).apply()
                }
                if (address == pairingAddress && state != BluetoothDevice.BOND_BONDING) {
                    handler.removeCallbacks(pairingTimeout)
                    pairingAddress = null
                    pairingMessage = if (state == BluetoothDevice.BOND_BONDED) {
                        "Paired; test reconnect after address changes"
                    } else "Pairing failed or cancelled; receiver may not support it"
                }
                publishPairingState()
                return
            }
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    releaseServer()
                    if (wanted) onError?.invoke("Waiting for Bluetooth")
                }
                BluetoothAdapter.STATE_ON -> if (wanted) ensureServerAndAdvertising()
            }
        }
    }

    fun start(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        wanted = true
        subscriptions.running = true
        try {
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(context, bluetoothStateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    }, ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
            }
            publishConnectionAndDemand()
            ensureServerAndAdvertising()
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission missing", e)
            onError?.invoke("Grant Nearby devices permission")
            wanted = false
            subscriptions.running = false
            publishConnectionAndDemand()
            return false
        }
    }

    /** Pause without deleting the service/CCCD that a connected receiver is still using. */
    fun stop() {
        wanted = false
        subscriptions.running = false
        handler.removeCallbacks(retry)
        stopAdvertising()
        publishConnectionAndDemand()
    }

    fun close() {
        stop()
        releaseServer()
        if (receiverRegistered) {
            context.unregisterReceiver(bluetoothStateReceiver)
            receiverRegistered = false
        }
    }

    private fun ensureServerAndAdvertising() {
        try { ensureServerAndAdvertisingInternal() }
        catch (e: RuntimeException) {
            Log.w(TAG, "Bluetooth stack not ready", e)
            releaseServer()
            scheduleRetry("Waiting for Bluetooth access")
        }
    }

    private fun ensureServerAndAdvertisingInternal() {
        if (!wanted) return
        if (adapter?.isEnabled != true) {
            onError?.invoke("Waiting for Bluetooth")
            return
        }
        if (server == null) {
            val session = ++generation
            ready = false
            server = manager.openGattServer(context, callback(session))
            val service = BluetoothGattService(HrmUuids.HEART_RATE_SERVICE,
                BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val measurement = BluetoothGattCharacteristic(HrmUuids.HR_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            measurement.addDescriptor(BluetoothGattDescriptor(HrmUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            service.addCharacteristic(measurement)
            if (server?.addService(service) != true) {
                releaseServer()
                scheduleRetry("Unable to publish heart rate service")
            }
            // addService is asynchronous: advertise only after onServiceAdded.
            return
        }
        if (ready) startAdvertising()
    }

    private fun callback(session: Int) = object : BluetoothGattServerCallback() {
        private fun dispatch(action: () -> Unit) {
            handler.post { if (session == generation && server != null) action() }
        }
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = dispatch {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                releaseServer()
                scheduleRetry("Heart rate service setup failed ($status)")
            } else {
                ready = true
                ensureServerAndAdvertising()
            }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = dispatch {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                devices[address] = device
                val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                if (!bonded) preferences.edit().remove(address).apply()
                subscriptions.connect(address, bonded && preferences.getBoolean(address, false))
                Log.d(TAG, "Receiver connected; restored subscription=${subscriptions.subscribed(address)}")
                // Keep one advertising instance alive, including while connected.
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                devices.remove(address)
                subscriptions.disconnect(address)
                Log.d(TAG, "Receiver disconnected; status=$status")
                ensureServerAndAdvertising()
            }
            publishConnectionAndDemand()
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int,
            offset: Int, descriptor: BluetoothGattDescriptor) = dispatch {
            if (!isHeartRateCccd(descriptor)) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            } else {
                val value = if (subscriptions.subscribed(device.address)) byteArrayOf(1, 0) else byteArrayOf(0, 0)
                if (offset !in 0..value.size) {
                    respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                } else {
                    respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
                }
            }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) = dispatch {
            val status = when {
                !isHeartRateCccd(descriptor) || preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                value.size != 2 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                !value.contentEquals(byteArrayOf(0, 0)) && !value.contentEquals(byteArrayOf(1, 0)) -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else -> BluetoothGatt.GATT_SUCCESS
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                devices[device.address] = device
                subscriptions.subscribe(device.address, value[0].toInt() == 1)
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    preferences.edit().putBoolean(device.address, subscriptions.subscribed(device.address)).apply()
                }
                Log.d(TAG, "Heart rate subscription=${subscriptions.subscribed(device.address)}")
            }
            // Acknowledge ATT before starting any sensor work.
            if (responseNeeded) respond(device, requestId, status, offset, null)
            if (status == BluetoothGatt.GATT_SUCCESS) publishConnectionAndDemand()
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int,
            offset: Int, characteristic: BluetoothGattCharacteristic) = dispatch {
            respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
        }
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = dispatch {
            respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) = dispatch {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Notification delivery failed: $status")
        }
    }

    private fun isHeartRateCccd(descriptor: BluetoothGattDescriptor) =
        descriptor.uuid == HrmUuids.CCCD && descriptor.characteristic.uuid == HrmUuids.HR_MEASUREMENT &&
            descriptor.characteristic.service.uuid == HrmUuids.HEART_RATE_SERVICE

    private fun respond(device: BluetoothDevice, id: Int, status: Int, offset: Int, value: ByteArray?) {
        try { server?.sendResponse(device, id, status, offset, value) }
        catch (e: SecurityException) { Log.w(TAG, "ATT response permission lost", e) }
    }

    /** Explicit user action only; never guess which of several receivers to pair. */
    fun pairReceiver() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val device = subscriptions.subscribers.mapNotNull { devices[it] }.singleOrNull()
        if (!wanted || device == null || pairingAddress != null) return
        try {
            if (device.bondState != BluetoothDevice.BOND_NONE) {
                publishPairingState()
                return
            }
            pairingAddress = device.address
            pairingMessage = null
            pairingTimedOut = false
            handler.removeCallbacks(pairingTimeout)
            handler.postDelayed(pairingTimeout, 60_000L)
            // Public API: Android displays any required pairing confirmation.
            // A true return only means the request started, not that it succeeded.
            val accepted = device.createBond()
            Log.d(TAG, "Receiver bonding requested; accepted=$accepted")
            if (!accepted) {
                handler.removeCallbacks(pairingTimeout)
                pairingAddress = null
                pairingMessage = "Could not start pairing; try while connected"
            }
        } catch (e: RuntimeException) {
            handler.removeCallbacks(pairingTimeout)
            pairingAddress = null
            pairingMessage = "Pairing unavailable; check Nearby devices permission"
            Log.w(TAG, "Receiver bonding unavailable", e)
        }
        publishPairingState()
    }

    private fun publishPairingState() {
        val peers = subscriptions.subscribers.mapNotNull { devices[it] }
        val device = peers.singleOrNull()
        val bondState = try { device?.bondState } catch (_: SecurityException) { null }
        val message = when {
            pairingAddress != null && !pairingTimedOut -> "Pairing... confirm on both devices if asked"
            pairingAddress != null -> pairingMessage ?: "Pairing still pending"
            pairingMessage != null -> pairingMessage!!
            !wanted -> "Pairing test: start broadcasting first"
            peers.isEmpty() -> "Pairing test: connect a heart rate receiver first"
            peers.size > 1 -> "Pairing test: keep only one receiver connected"
            bondState == BluetoothDevice.BOND_BONDED -> "Receiver paired; test automatic reconnect"
            bondState == BluetoothDevice.BOND_BONDING -> "System pairing in progress"
            else -> "Pairing may let your receiver recognize changing addresses"
        }
        val canPair = wanted && peers.size == 1 && pairingAddress == null &&
            bondState == BluetoothDevice.BOND_NONE
        onPairingStateChanged?.invoke(message, canPair)
    }

    private fun publishConnectionAndDemand() {
        publishPairingState()
        onConnectionStateChanged?.invoke(subscriptions.connected)
        val demand = subscriptions.measurementNeeded
        if (demand != lastDemand) {
            lastDemand = demand
            onMeasurementDemandChanged?.invoke(demand)
        }
    }

    private fun startAdvertising() {
        if (!wanted || !ready || advertisement != null) return
        advertiser = adapter?.bluetoothLeAdvertiser
        val currentAdvertiser = advertiser ?: run { scheduleRetry("BLE advertiser unavailable"); return }
        val session = generation
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                handler.post {
                    if (session != generation || advertisement !== this || !wanted) return@post
                    retryCount = 0
                    Log.d(TAG, "BLE advertising ready")
                    onAdvertisingStateChanged?.invoke(true)
                }
            }
            override fun onStartFailure(errorCode: Int) {
                handler.post {
                    if (session != generation || advertisement !== this) return@post
                    advertisement = null
                    onAdvertisingStateChanged?.invoke(false)
                    scheduleRetry("Retrying BLE advertising ($errorCode)")
                }
            }
        }
        advertisement = callback
        val settings = AdvertiseSettings.Builder().setConnectable(true).setTimeout(0)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(HrmUuids.HEART_RATE_SERVICE)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        try { currentAdvertiser.startAdvertising(settings, data, scanResponse, callback) }
        catch (e: RuntimeException) {
            advertisement = null
            Log.w(TAG, "Cannot advertise yet", e)
            scheduleRetry("Waiting to resume BLE advertising")
        }
    }

    private fun scheduleRetry(message: String) {
        if (!wanted) return
        Log.w(TAG, message)
        onError?.invoke(message)
        handler.removeCallbacks(retry)
        val delay = (1000L shl retryCount.coerceAtMost(5)).coerceAtMost(30_000L)
        retryCount = (retryCount + 1).coerceAtMost(5)
        handler.postDelayed(retry, delay)
    }

    private fun stopAdvertising() {
        val callback = advertisement
        advertisement = null
        try { if (callback != null) advertiser?.stopAdvertising(callback) }
        catch (e: RuntimeException) { Log.w(TAG, "Advertiser already unavailable", e) }
        onAdvertisingStateChanged?.invoke(false)
    }

    private fun releaseServer() {
        handler.removeCallbacks(pairingTimeout)
        pairingAddress = null
        pairingMessage = null
        generation++
        ready = false
        stopAdvertising()
        val old = server
        server = null
        try { devices.values.forEach { old?.cancelConnection(it) } }
        catch (e: RuntimeException) { Log.w(TAG, "Connections already unavailable", e) }
        try { old?.close() }
        catch (e: RuntimeException) { Log.w(TAG, "GATT server already unavailable", e) }
        devices.clear()
        subscriptions.clear()
        publishConnectionAndDemand()
    }

    fun updateHeartRate(bpm: Int) {
        val current = server ?: return
        val characteristic = current.getService(HrmUuids.HEART_RATE_SERVICE)
            ?.getCharacteristic(HrmUuids.HR_MEASUREMENT) ?: return
        val payload = byteArrayOf(0, bpm.coerceIn(0, 255).toByte())
        for (address in subscriptions.subscribers) {
            val device = devices[address] ?: continue
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    val result = current.notifyCharacteristicChanged(device, characteristic, false, payload)
                    if (result != BluetoothStatusCodes.SUCCESS) Log.w(TAG, "Notification rejected: $result")
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    @Suppress("DEPRECATION")
                    current.notifyCharacteristicChanged(device, characteristic, false)
                }
            } catch (e: RuntimeException) { Log.w(TAG, "Notification unavailable", e) }
        }
    }
    companion object { private const val TAG = "BleHrmServer" }
}

