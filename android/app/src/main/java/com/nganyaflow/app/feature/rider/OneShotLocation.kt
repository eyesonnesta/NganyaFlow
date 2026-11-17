package com.nganyaflow.app.feature.rider

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** One coarse fix with a timeout; cancellation removes every callback. No location is stored. */
class OneShotLocation(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var signal: CancellationSignal? = null
    private var timeout: Runnable? = null
    fun cancel() {
        listener?.let(manager::removeUpdates)
        listener = null
        signal?.cancel()
        signal = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    @SuppressLint("MissingPermission")
    fun request(callback: (Location?) -> Unit) {
        cancel()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            callback(null); return
        }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) { callback(null); return }
        var completed = false
        fun finish(location: Location?) {
            if (!completed) { completed = true; cancel(); callback(location) }
        }
        timeout = Runnable { finish(null) }.also { handler.postDelayed(it, 15_000) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                signal = CancellationSignal()
                manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { finish(it) }
            } else {
                @Suppress("DEPRECATION")
                listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = finish(location)
                    @Deprecated("Legacy platform callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = finish(null)
                }.also { manager.requestSingleUpdate(provider, it, Looper.getMainLooper()) }
            }
        } catch (_: SecurityException) { finish(null) }
        catch (_: IllegalArgumentException) { finish(null) }
    }
}
