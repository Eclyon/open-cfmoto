// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.Network
import java.net.Inet4Address

/**
 * Process-global handle to the bike PXC client ([EasyConnProber]).
 *
 * Held here — not only as a [MainActivity] field — so the Android Auto → bike hand-off and the
 * Stop control survive a [MainActivity] recreation. Triggering Google Android Auto (self-mode)
 * brings Gearhead to the foreground, which can destroy and recreate [MainActivity] while the AA
 * receiver keeps running in [AndroidAutoService]. The bike connection must not be torn down or
 * orphaned when that happens: a fresh activity re-reads the SAME prober instance from here instead
 * of constructing a new one that would leave the running one leaked and unstoppable.
 *
 * Matches the existing process-global style ([AaVideoBridge], [ProjectionHolder], [BikeProfileHolder]).
 */
object BikeLink {
    enum class Backend {
        EASYCONN,
        EYLINK,
    }

    @Volatile var eylink: EylinkLink? = null
    @Volatile private var backend: Backend = Backend.EASYCONN

    @Synchronized
    fun selectBackend(context: Context, useEylink: Boolean) {
        appContext = context.applicationContext
        backend = if (useEylink) Backend.EYLINK else Backend.EASYCONN

        if (useEylink && eylink == null) {
            eylink = EylinkLink(appContext!!, LogBus::log)
        }

        LogBus.log("bike backend = $backend")
    }

    @Volatile var prober: EasyConnProber? = null
    @Volatile private var appContext: Context? = null

    // ---- Android Auto → bike start coordination (parallel-startup gate) ----
    // The two slow steps used to be serial: wait for AA "steady video", THEN pop the Wi-Fi join
    // dialog, THEN probe the bike. We now kick off the Wi-Fi join IN PARALLEL with AA boot and only
    // start the probe once BOTH are ready — the user accepts the Wi-Fi dialog while AA is still
    // spinning up, shaving several seconds. The bike still never gets probed before AA has frames to
    // serve, so streaming correctness is unchanged. Lives here so a MainActivity recreation
    // mid-startup doesn't lose the pending gate.
    @Volatile private var aaVideoSteady = false
    @Volatile private var bikeNetwork: Network? = null
    @Volatile private var networkReady = false
    @Volatile private var proberStarted = false
    /** Wi‑Fi Direct path: no [Network], so the prober binds/probes with these overrides. */
    @Volatile private var p2pBindIp: Inet4Address? = null
    @Volatile private var p2pGatewayIp: Inet4Address? = null
    @Volatile private var aaDropRetried = false

    /** Reset the gate at the start of a fresh Android Auto connection attempt. */
    @Synchronized
    fun beginHandoff(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        aaVideoSteady = false
        bikeNetwork = null
        networkReady = false
        proberStarted = false
        p2pBindIp = null
        p2pGatewayIp = null
        aaDropRetried = false
        AaVideoBridge.aaSessionSeen = false
        // Leave the bike Network held, but unpin the process so AA can use 127.0.0.1.
        appContext?.let { BikeWifi.unbindProcess(context = it) }
    }

    /** One extra self-mode trigger after AA attaches then dies before video is steady. */
    @Synchronized
    fun takeAaDropRetry(): Boolean {
        if (aaDropRetried || aaVideoSteady) return false
        aaDropRetried = true
        return true
    }

    @Synchronized
    fun markAaVideoSteady() {
        aaVideoSteady = true
        maybeStartProbe()
    }

    /** [network] may be null on some devices (process already bound); readiness is the real signal. */
    @Synchronized
    fun markWifiReady(network: Network?) {
        bikeNetwork = network
        networkReady = true
        maybeStartProbe()
    }

    /** Wi‑Fi Direct: no [Network] — store bind/gateway IPs and mark ready. */
    @Synchronized
    fun markP2pReady(bindIp: Inet4Address, gatewayIp: Inet4Address) {
        if (backend == Backend.EYLINK && AndroidAutoService.isParked) {
            AndroidAutoService.requestResume(P2pEndpoint(bindIp, gatewayIp))
            return
        }
        p2pBindIp = bindIp
        p2pGatewayIp = gatewayIp
        bikeNetwork = null
        networkReady = true
        maybeStartProbe()
    }

    data class P2pEndpoint(val bindIp: Inet4Address, val gatewayIp: Inet4Address)

    /** Invalidate only a lost Eylink P2P group; keep live AA ready for a short outage. */
    @Synchronized
    fun markP2pLost() {
        if (backend != Backend.EYLINK) return
        networkReady = false
        p2pBindIp = null
        p2pGatewayIp = null
    }

    private fun maybeStartProbe() {
        if (proberStarted || !aaVideoSteady || !networkReady) return

        proberStarted = true

        appContext?.let { ctx ->
            if (BikeWifi.rebindProcessToBike(ctx)) {
                LogBus.log("process bound to bike Wi-Fi (AA video is live)")
            }
        }

        ConnectionState.set(Phase.PXC_CONNECTING)

        when (backend) {
            Backend.EASYCONN -> {
                val p = prober
                if (p == null) {
                    proberStarted = false
                    LogBus.log("EasyConn backend selected but prober is null")
                    ConnectionState.set(Phase.ERROR, "EasyConn prober unavailable")
                    return
                }

                LogBus.log("AA video + bike Wi-Fi ready; starting EasyConn PXC flow")
                appContext?.let { DashClockBle.start(it) }

                try {
                    p.start(
                        bikeNetwork,
                        gatewayOverride = p2pGatewayIp,
                        bindIpOverride = p2pBindIp,
                    )
                } catch (e: Exception) {
                    LogBus.log("prober start failed: $e")
                    ConnectionState.set(Phase.ERROR, "prober start failed")
                }
            }

            Backend.EYLINK -> {
                val link = eylink
                val gateway = p2pGatewayIp

                if (link == null || gateway == null) {
                    proberStarted = false
                    LogBus.log("Eylink backend missing link or P2P gateway")
                    ConnectionState.set(Phase.ERROR, "Eylink P2P endpoint unavailable")
                    return
                }

                LogBus.log("AA video + EyLink P2P ready; starting EyLink flow")

                val ok = try {
                    link.connectAndStream(
                        host = gateway,
                        network = bikeNetwork,
                        bindIp = p2pBindIp,
                    )
                } catch (e: Exception) {
                    LogBus.log("Eylink start failed: $e")
                    false
                }

                if (!ok) {
                    proberStarted = false
                    ConnectionState.set(Phase.ERROR, "Eylink connection failed")
                }
            }
        }
    }

    /** Stop only the active bike transport. The shared Android Auto pipeline is owned elsewhere. */
    @Synchronized
    fun stopBackend() {
        proberStarted = false
        when (backend) {
            Backend.EASYCONN -> {
                try { prober?.stop() } catch (_: Exception) {}
            }
            Backend.EYLINK -> {
                try { eylink?.stop() } catch (_: Exception) {}
            }
        }
    }

    /**
     * The bike's Wi-Fi came back after a drop (e.g. the rider stopped and restarted the bike). The
     * old prober was probing a dead interface with stale IPs/server binds, so fully restart it on the
     * fresh [network]: stop it (this only closes sockets/servers — the shared Android Auto pipeline is
     * owned by [AndroidAutoService] and survives), then start again so it rebinds and re-probes. Called
     * by [BikeWifi] on every re-acquisition after the first.
     */
    @Synchronized
    fun onWifiReacquired(network: Network?) {
        // If the service parked Android Auto (long outage → torn down to save battery), it must rebuild
        // AA before the bike link is useful — hand off to the service instead of restarting the prober
        // against a dead (stopped) pipeline.
        if (AndroidAutoService.isParked) {
            LogBus.log("→ Wi-Fi back while AA parked — asking service to resume")
            AndroidAutoService.requestResume()
            return
        }
        val p = prober ?: return
        LogBus.log("→ restarting bike link on re-acquired Wi-Fi")
        ConnectionState.set(Phase.PXC_CONNECTING, "reconnecting")
        try { p.stop() } catch (_: Exception) {}
        bikeNetwork = network
        networkReady = true
        proberStarted = true
        appContext?.let { DashClockBle.start(it) }
        try {
            p.start(network)
        } catch (e: Exception) {
            LogBus.log("prober restart failed: $e")
            ConnectionState.set(Phase.ERROR, "prober restart failed")
        }
    }
}
