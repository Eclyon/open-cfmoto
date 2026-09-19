// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/**
 * Eylink wire protocol used by Kove dashboards.
 *
 * Reverse-engineered from the official com.eryanet.eylink Android client and verified against a
 * Kove 350 RR TFT.
 *
 * Mirror/video TCP:
 *   phone -> bike 192.168.13.1:11111
 *
 * Common packet header:
 *   AA BB CC <func> <4 bytes command-specific> <payloadLen LE32>
 *
 * VIDEO_DATA (func 0):
 *   AA BB CC 00
 *   index LE16
 *   checksum16 LE16
 *   payloadLen LE32
 *   timestampMs LE32
 *   H.264 Annex-B access unit
 *
 * The VIDEO checksum covers ONLY the H.264 Annex-B bytes, not the timestamp.
 */
object EylinkProtocol {
    const val VIDEO_PORT = 11111
    const val CONTROL_PORT = 11113

    const val FUNC_VIDEO = 0
    const val FUNC_MIRROR_START = 4
    const val FUNC_MIRROR_STOP = 5
    const val FUNC_WIDTH_HEIGHT = 6

    /**
     * Parameters returned by the TFT after MIRROR_START.
     *
     * Proven Kove response:
     *   4F 4B 10 03 60 01 10 03 60 01 1E D0 07
     *
     * = OK, 784x352 portrait, 784x352 landscape, 30 fps, 2000 kbps.
     */
    data class MirrorInfo(
        val portraitWidth: Int,
        val portraitHeight: Int,
        val landscapeWidth: Int,
        val landscapeHeight: Int,
        val fps: Int,
        val bitrateKbps: Int,
    )

    /**
     * Exact MIRROR_START emitted by the official Eylink client for the tested phone.
     *
     * Payload = 1152 x 2560 as LE16 values.
     */
    fun mirrorStart(): ByteArray = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), FUNC_MIRROR_START.toByte(),
        0x00, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00,
        0x80.toByte(), 0x04,
        0x00, 0x0A,
    )

    /** Exact zero-payload MIRROR_STOP packet. */
    fun mirrorStop(): ByteArray = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), FUNC_MIRROR_STOP.toByte(),
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
    )

    /**
     * WIDTH_HEIGHT packet sent immediately before the first accepted H.264 AU and periodically
     * while streaming.
     */
    fun widthHeight(width: Int, height: Int): ByteArray {
        val out = ByteArray(16)
        out[0] = 0xAA.toByte()
        out[1] = 0xBB.toByte()
        out[2] = 0xCC.toByte()
        out[3] = FUNC_WIDTH_HEIGHT.toByte()

        putLe32(out, 8, 4)
        putLe16(out, 12, width)
        putLe16(out, 14, height)

        return out
    }

    /**
     * Wrap one complete Annex-B H.264 access unit for Eylink.
     *
     * [timestampMs] is elapsed time since MIRROR_START.
     * [index] is transmitted modulo 65536.
     */
    fun videoData(index: Int, timestampMs: Long, h264: ByteArray): ByteArray {
        val payloadLen = 4 + h264.size
        val out = ByteArray(12 + payloadLen)

        out[0] = 0xAA.toByte()
        out[1] = 0xBB.toByte()
        out[2] = 0xCC.toByte()
        out[3] = FUNC_VIDEO.toByte()

        putLe16(out, 4, index)
        putLe16(out, 6, checksum16(h264))
        putLe32(out, 8, payloadLen)

        putLe32(out, 12, timestampMs)
        System.arraycopy(h264, 0, out, 16, h264.size)

        return out
    }

    /**
     * Eylink VIDEO_DATA checksum.
     *
     * Sum little-endian 32-bit words, include a final partial LE word when present, then fold
     * carries down to 16 bits. Verified against captured official Eylink VIDEO_DATA packets.
     */
    fun checksum16(data: ByteArray): Int {
        var sum = 0L
        var i = 0

        while (i + 3 < data.size) {
            val word =
                (data[i].toLong() and 0xFFL) or
                    ((data[i + 1].toLong() and 0xFFL) shl 8) or
                    ((data[i + 2].toLong() and 0xFFL) shl 16) or
                    ((data[i + 3].toLong() and 0xFFL) shl 24)

            sum += word
            i += 4
        }

        var shift = 0
        var tail = 0L
        while (i < data.size) {
            tail = tail or ((data[i].toLong() and 0xFFL) shl shift)
            shift += 8
            i++
        }
        sum += tail

        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }

        return (sum and 0xFFFFL).toInt()
    }

    /**
     * Parse the 13-byte "OK" response returned by the TFT after MIRROR_START.
     */
    fun parseMirrorOk(data: ByteArray): MirrorInfo? {
        if (data.size < 13) return null
        if (data[0] != 'O'.code.toByte() || data[1] != 'K'.code.toByte()) return null

        return MirrorInfo(
            portraitWidth = getLe16(data, 2),
            portraitHeight = getLe16(data, 4),
            landscapeWidth = getLe16(data, 6),
            landscapeHeight = getLe16(data, 8),
            fps = data[10].toInt() and 0xFF,
            bitrateKbps = getLe16(data, 11),
        )
    }

    private fun putLe16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(dst: ByteArray, offset: Int, value: Int) {
        putLe32(dst, offset, value.toLong())
    }

    private fun putLe32(dst: ByteArray, offset: Int, value: Long) {
        dst[offset] = (value and 0xFFL).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
    }

    private fun getLe16(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or
            ((src[offset + 1].toInt() and 0xFF) shl 8)
}
