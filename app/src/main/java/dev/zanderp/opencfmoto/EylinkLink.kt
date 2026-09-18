// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Context
import android.net.Network
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Phone -> Kove Eylink bridge.
 *
 * Wi-Fi/P2P is established elsewhere. This class owns only the Eylink TCP layer:
 *
 *   :11113 control / Mapheart
 *   :11111 mirror negotiation + H.264 video
 *
 * Android Auto video comes from the shared [AaVideoBridge.pipeline]. The pipeline already returns
 * complete Annex-B access units and guarantees SPS+PPS+IDR on [VideoPipeline.onBikeDataStart], so
 * Eylink must not split or rewrite the H.264 stream.
 */
class EylinkLink(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    @Volatile private var running = false

    @Volatile private var controlSocket: Socket? = null
    @Volatile private var videoSocket: Socket? = null

    @Volatile private var video: VideoPipeline? = null

    private var heartbeatThread: Thread? = null
    private var sendThread: Thread? = null

    @Volatile var framesSent: Int = 0
        private set

    @Volatile var streamWidth: Int = 0
        private set

    @Volatile var streamHeight: Int = 0
        private set

    val isConnected: Boolean
        get() =
            running &&
                controlSocket?.isConnected == true &&
                controlSocket?.isClosed == false &&
                videoSocket?.isConnected == true &&
                videoSocket?.isClosed == false

    /**
     * Open the Eylink control/video sockets, negotiate the TFT mirror canvas and begin forwarding
     * the shared Android Auto H.264 pipeline.
     */
    fun connectAndStream(
        host: Inet4Address,
        network: Network? = null,
        bindIp: Inet4Address? = null,
    ): Boolean {
        stop()

        running = true
        framesSent = 0
        streamWidth = 0
        streamHeight = 0

        BikeWifi.rebindProcessToBike(context)

        val ctrl = openSocket(network, bindIp) ?: run {
            log("[EYLINK] could not create control socket")
            running = false
            return false
        }

        try {
            log("[EYLINK] control connect -> ${host.hostAddress}:${EylinkProtocol.CONTROL_PORT}")
            ctrl.connect(
                InetSocketAddress(host, EylinkProtocol.CONTROL_PORT),
                5_000,
            )
            ctrl.tcpNoDelay = true
            controlSocket = ctrl
        } catch (e: Exception) {
            log("[EYLINK] control connect failed: ${e.javaClass.simpleName}: ${e.message}")
            try { ctrl.close() } catch (_: Exception) {}
            running = false
            return false
        }

        val controlOut = BufferedOutputStream(ctrl.getOutputStream())
        startHeartbeat(controlOut)

        val vid = openSocket(network, bindIp) ?: run {
            log("[EYLINK] could not create video socket")
            stop()
            return false
        }

        try {
            log("[EYLINK] video connect -> ${host.hostAddress}:${EylinkProtocol.VIDEO_PORT}")
            vid.connect(
                InetSocketAddress(host, EylinkProtocol.VIDEO_PORT),
                5_000,
            )
            vid.tcpNoDelay = true
            vid.soTimeout = 5_000
            videoSocket = vid
        } catch (e: Exception) {
            log("[EYLINK] video connect failed: ${e.javaClass.simpleName}: ${e.message}")
            try { vid.close() } catch (_: Exception) {}
            stop()
            return false
        }

        val videoOut = BufferedOutputStream(vid.getOutputStream())
        val videoIn = vid.getInputStream()

        val mirrorStartedAt = SystemClock.elapsedRealtime()

        try {
            videoOut.write(EylinkProtocol.mirrorStart())
            videoOut.flush()
            log("[EYLINK] MIRROR_START sent")
        } catch (e: Exception) {
            log("[EYLINK] MIRROR_START failed: ${e.message}")
            stop()
            return false
        }

        val okBytes = ByteArray(13)
        if (!readFully(videoIn, okBytes)) {
            log("[EYLINK] no complete MIRROR_START OK response")
            stop()
            return false
        }

        val info = EylinkProtocol.parseMirrorOk(okBytes)
        if (info == null) {
            log("[EYLINK] invalid MIRROR_START response")
            stop()
            return false
        }

        vid.soTimeout = 0

        val width = info.landscapeWidth.coerceAtLeast(16)
        val height = info.landscapeHeight.coerceAtLeast(16)
        val fps = info.fps.coerceIn(5, 30)

        streamWidth = width
        streamHeight = height

        log(
            "[EYLINK] mirror OK: " +
                "portrait=${info.portraitWidth}x${info.portraitHeight} " +
                "landscape=${info.landscapeWidth}x${info.landscapeHeight} " +
                "${info.fps}fps ${info.bitrateKbps}kbps",
        )

        val shared = AaVideoBridge.pipeline
        if (shared == null) {
            log("[EYLINK] AA video pipeline is not available")
            stop()
            return false
        }

        video = shared

        try {
            shared.configureBikeCanvas(width, height, force = true)
            shared.setFrameCap(fps)
            shared.onBikeDataStart()
            log("[EYLINK] configured shared AA pipeline ${width}x${height} @${fps}fps")
        } catch (e: Exception) {
            log("[EYLINK] configure AA pipeline failed: ${e.message}")
            stop()
            return false
        }

        try {
            videoOut.write(EylinkProtocol.widthHeight(width, height))
            videoOut.flush()
            log("[EYLINK] WIDTH_HEIGHT ${width}x${height}")
        } catch (e: Exception) {
            log("[EYLINK] initial WIDTH_HEIGHT failed: ${e.message}")
            stop()
            return false
        }

        sendThread = thread(name = "eylink-video-send", isDaemon = true) {
            sendLoop(
                out = videoOut,
                width = width,
                height = height,
                mirrorStartedAt = mirrorStartedAt,
            )
        }

        return true
    }

    private fun startHeartbeat(out: OutputStream) {
        heartbeatThread = thread(name = "eylink-heartbeat", isDaemon = true) {
            val packet = controlJson("""{"EYLINKheart":"Mapheart"}""")

            while (running) {
                try {
                    synchronized(out) {
                        out.write(packet)
                        out.flush()
                    }
                } catch (e: Exception) {
                    if (running) {
                        log("[EYLINK] Mapheart failed: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    break
                }

                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendLoop(
        out: OutputStream,
        width: Int,
        height: Int,
        mirrorStartedAt: Long,
    ) {
        var index = 0
        var nextDimensionsAt = SystemClock.elapsedRealtime() + 2_000L
        var idlePolls = 0

        while (running) {
            val au = video?.pollFrame(1_500L)

            if (au == null) {
                idlePolls++
                if (idlePolls == 1 || idlePolls % 4 == 0) {
                    log("[EYLINK] waiting for encoder frames (poll#$idlePolls)")
                }
                continue
            }

            idlePolls = 0

            try {
                val now = SystemClock.elapsedRealtime()

                if (now >= nextDimensionsAt) {
                    out.write(EylinkProtocol.widthHeight(width, height))
                    nextDimensionsAt = now + 2_000L
                }

                val timestampMs = now - mirrorStartedAt

                out.write(
                    EylinkProtocol.videoData(
                        index = index,
                        timestampMs = timestampMs,
                        h264 = au,
                    ),
                )
                out.flush()

                framesSent++
                if (framesSent == 1) ConnectionState.set(Phase.STREAMING, "Eylink ${width}x${height}")
                index = (index + 1) and 0xFFFF

                if (framesSent == 1 || framesSent % 120 == 0) {
                    log(
                        "[EYLINK] video frames=$framesSent " +
                            "last=${au.size}b index=$index ts=${timestampMs}ms",
                    )
                }
            } catch (e: Exception) {
                if (running) {
                    log("[EYLINK] video send failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                break
            }
        }
    }

    /**
     * Clean Eylink shutdown: explicit MIRROR_STOP before closing :11111, then stop heartbeat and
     * close :11113. The shared AA VideoPipeline is owned by AndroidAutoService and is not stopped.
     */
    fun stop() {
        val wasRunning = running
        running = false

        if (wasRunning) {
            try {
                val out = videoSocket?.getOutputStream()
                if (out != null) {
                    out.write(EylinkProtocol.mirrorStop())
                    out.flush()
                    log("[EYLINK] MIRROR_STOP sent")
                }
            } catch (_: Exception) {}
        }

        try { videoSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}

        videoSocket = null
        controlSocket = null

        heartbeatThread = null
        sendThread = null

        video = null
        streamWidth = 0
        streamHeight = 0
    }

    private fun openSocket(
        network: Network?,
        bindIp: Inet4Address?,
    ): Socket? {
        if (network != null) {
            try {
                return network.socketFactory.createSocket()
            } catch (e: Exception) {
                log("[EYLINK] socketFactory: ${e.message}")
            }

            try {
                val s = Socket()
                network.bindSocket(s)
                return s
            } catch (e: Exception) {
                log("[EYLINK] bindSocket: ${e.message}")
            }
        }

        return try {
            Socket().apply {
                if (bindIp != null) {
                    bind(InetSocketAddress(bindIp, 0))
                }
            }
        } catch (e: Exception) {
            log("[EYLINK] Socket/bind ${bindIp?.hostAddress}: ${e.message}")
            null
        }
    }

    /**
     * Phone -> TFT control framing:
     *
     * AF BB CC 0F 00 00 00 00 <UTF-8 length LE32> <JSON bytes>
     */
    private fun controlJson(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        val out = ByteArray(12 + payload.size)

        out[0] = 0xAF.toByte()
        out[1] = 0xBB.toByte()
        out[2] = 0xCC.toByte()
        out[3] = 0x0F

        putLe32(out, 8, payload.size)

        System.arraycopy(payload, 0, out, 12, payload.size)
        return out
    }

    private fun putLe32(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readFully(input: InputStream, dst: ByteArray): Boolean {
        var offset = 0

        while (offset < dst.size) {
            val n = try {
                input.read(dst, offset, dst.size - offset)
            } catch (_: Exception) {
                return false
            }

            if (n <= 0) return false
            offset += n
        }

        return true
    }
}
