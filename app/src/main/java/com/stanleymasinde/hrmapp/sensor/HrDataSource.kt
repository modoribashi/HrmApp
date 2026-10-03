package com.stanleymasinde.hrmapp.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*

/** Complete unregister before registering again after a rapid stop/start. */
class HrDataSource(
    context: Context,
    private val onHeartRateChanged: (Int) -> Unit,
    private val onRegistrationChanged: (Boolean) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onAvailabilityChanged: (Boolean) -> Unit
) {
    private val measureClient = HealthServices.getClient(context).measureClient
    private val executor = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private var wanted = false
    private var current: Callback? = null
    private val retry = Runnable { if (wanted && current == null) register() }

    private inner class Callback : MeasureCallback {
        var registered = false
        var stopping = false

        override fun onRegistered() {
            if (current !== this) return
            registered = true
            Log.d(TAG, "Heart rate callback registered")
            if (wanted) onRegistrationChanged(true) else unregister(this)
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            if (current !== this) return
            current = null
            Log.e(TAG, "Heart rate registration failed", throwable)
            onRegistrationChanged(false)
            onAvailabilityChanged(false)
            if (wanted) {
                onStatusChanged("Sensor registration failed; check permissions")
                handler.postDelayed(retry, 10_000L)
            }
        }

        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            if (current !== this || !wanted || stopping || dataType != DataType.HEART_RATE_BPM) return
            Log.d(TAG, "Availability: $availability")
            onAvailabilityChanged(availability == DataTypeAvailability.AVAILABLE)
            onStatusChanged(when (availability) {
                DataTypeAvailability.AVAILABLE -> "Waiting for heart rate..."
                DataTypeAvailability.ACQUIRING -> "Acquiring heart rate..."
                DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> "Wear the watch snugly"
                else -> "Heart rate sensor unavailable"
            })
        }

        override fun onDataReceived(data: DataPointContainer) {
            if (current !== this || !wanted || stopping) return
            data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let { value ->
                if (value.isFinite() && value > 0 && value <= 255) {
                    onAvailabilityChanged(true)
                    onStatusChanged("Sensor active")
                    onHeartRateChanged(value.toInt())
                }
            }
        }
    }

    fun start() {
        wanted = true
        handler.removeCallbacks(retry)
        if (current == null) register()
    }

    private fun register() {
        if (!wanted || current != null) return
        onAvailabilityChanged(false)
        onStatusChanged("Starting heart rate sensor...")
        val callback = Callback()
        current = callback
        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, executor, callback)
        } catch (e: Exception) {
            callback.onRegistrationFailed(e)
        }
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(retry)
        onRegistrationChanged(false)
        onAvailabilityChanged(false)
        // If registration is pending, onRegistered will finish this stop.
        current?.takeIf { it.registered }?.let { unregister(it) }
    }

    private fun unregister(callback: Callback) {
        if (callback.stopping) return
        callback.stopping = true
        try {
            val future = measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
            future.addListener({
                try {
                    future.get()
                    if (current === callback) {
                        current = null
                        Log.d(TAG, "Heart rate callback unregistered")
                        if (wanted) register()
                    }
                } catch (e: Exception) {
                    unregisterFailed(callback, e)
                }
            }, executor)
        } catch (e: Exception) {
            unregisterFailed(callback, e)
        }
    }

    private fun unregisterFailed(callback: Callback, error: Exception) {
        Log.w(TAG, "Unable to unregister sensor", error)
        if (current !== callback) return
        callback.stopping = false
        onStatusChanged("Retrying sensor cleanup...")
        // Never overlap a new registration with an unresolved old one.
        handler.postDelayed({ if (current === callback) unregister(callback) }, 5_000L)
    }

    companion object { private const val TAG = "HrDataSource" }
}

