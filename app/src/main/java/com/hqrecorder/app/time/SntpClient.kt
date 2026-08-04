package com.hqrecorder.app.time

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * RFC 4330準拠の最小限のSNTPクライアント。既存依存(OkHttp等)を使わず
 * DatagramSocketで直接UDPパケットをやり取りする。時刻源の信頼性表示(9.7)用。
 */
class SntpClient {

    /** サーバとの時刻差分(server - local, ミリ秒)を返す。通信失敗時はnull。 */
    fun query(host: String, timeoutMs: Int = 5000, port: Int = 123): Long? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(host)
                val buffer = ByteArray(NTP_PACKET_SIZE)
                // LI=0(no warning), VN=3, Mode=3(client)
                buffer[0] = 0x1B

                val requestTime = System.currentTimeMillis()
                socket.send(DatagramPacket(buffer, buffer.size, address, port))

                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)
                val responseTime = System.currentTimeMillis()

                val receiveTime = readTimestamp(buffer, RECEIVE_TIME_OFFSET)
                val transmitTime = readTimestamp(buffer, TRANSMIT_TIME_OFFSET)

                ((receiveTime - requestTime) + (transmitTime - responseTime)) / 2
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readTimestamp(buffer: ByteArray, offset: Int): Long {
        val seconds = readUnsignedInt(buffer, offset)
        val fraction = readUnsignedInt(buffer, offset + 4)
        val millisSinceNtpEpoch = seconds * 1000L + (fraction * 1000L) / 0x100000000L
        return millisSinceNtpEpoch - NTP_TO_UNIX_EPOCH_OFFSET_MS
    }

    private fun readUnsignedInt(buffer: ByteArray, offset: Int): Long =
        ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)

    companion object {
        private const val NTP_PACKET_SIZE = 48
        private const val RECEIVE_TIME_OFFSET = 32
        private const val TRANSMIT_TIME_OFFSET = 40

        /** 1900-01-01(NTPエポック)から1970-01-01(Unixエポック)までのミリ秒数 */
        private const val NTP_TO_UNIX_EPOCH_OFFSET_MS = 2208988800000L
    }
}
