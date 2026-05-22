package com.vspace.privileged

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * mDNS discovery of Android's wireless-debugging ADB endpoints on the local network.
 *
 * Android advertises two ADB services over mDNS while wireless debugging is in use:
 *  - `_adb-tls-pairing._tcp` — *only* while the user has the "Pair device with a pairing
 *    code" dialog open; gone the moment the dialog closes. Holds the pairing port.
 *  - `_adb-tls-connect._tcp` — advertised while Wireless debugging is enabled; the port
 *    changes each time it is toggled, which is exactly why mDNS discovery exists.
 *
 * Both call back synchronously: `NsdManager` is asked to discover, the first resolved hit
 * is returned, then discovery is stopped.
 */
object AdbDiscovery {

    private const val TAG = "VSpace/Privileged"
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp."

    data class Endpoint(val host: String, val port: Int)

    /** Discover the pairing service; returns null if nothing showed up before [timeoutMs]. */
    fun discoverPairing(context: Context, timeoutMs: Long): Endpoint? =
        discover(context, PAIRING_SERVICE, timeoutMs)

    /** Discover the connect service; returns null if nothing showed up before [timeoutMs]. */
    fun discoverConnect(context: Context, timeoutMs: Long): Endpoint? =
        discover(context, CONNECT_SERVICE, timeoutMs)

    private fun discover(
        context: Context,
        serviceType: String,
        timeoutMs: Long,
    ): Endpoint? {
        val nsd = context.getSystemService(NsdManager::class.java)
        if (nsd == null) {
            Log.e(TAG, "no NsdManager")
            return null
        }
        val results = LinkedBlockingQueue<Endpoint>(1)

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.i(TAG, "mDNS discovery started: $type")
            }

            override fun onDiscoveryStopped(type: String) {}

            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery start failed ($errorCode): $type")
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}

            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                // resolveService is single-flight; if it's busy we ignore subsequent founds
                // — for our use, one resolved endpoint is enough.
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS resolve failed ($errorCode): ${info.serviceName}")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        Log.i(TAG, "mDNS resolved $serviceType -> $host:${info.port}")
                        results.offer(Endpoint(host, info.port))
                    }
                })
            }
        }

        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            results.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "mDNS discovery error: $serviceType", e)
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }
}
