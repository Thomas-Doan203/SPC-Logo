package vn.chieusang.logo

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Lỗi do LOGO! trả về (địa chỉ sai, hàm không hỗ trợ...). Kết nối vẫn còn dùng được. */
class ModbusException(message: String) : IOException(message)

/**
 * Modbus TCP client tối giản, đủ cho LOGO! 8:
 * đọc coil (FC1), đọc discrete input (FC2), đọc holding register (FC3),
 * ghi một coil (FC5), ghi một register (FC6).
 * Không an toàn đa luồng: chỉ gọi từ một luồng nền duy nhất.
 */
class ModbusClient(
    private val host: String,
    private val port: Int,
    private val unitId: Int,
    private val socketFactory: SocketFactory,
    private val timeoutMs: Int = 2500
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var tid = 0

    val isConnected: Boolean
        get() {
            val s = socket ?: return false
            return s.isConnected && !s.isClosed
        }

    fun connect() {
        close()
        val s = socketFactory.createSocket()
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = DataInputStream(s.getInputStream())
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
    }

    private fun request(pdu: ByteArray): ByteArray {
        val s = socket ?: throw IOException("Chưa kết nối")
        val inp = input ?: throw IOException("Chưa kết nối")
        tid = (tid + 1) and 0xFFFF
        val len = pdu.size + 1
        val frame = ByteArray(7 + pdu.size)
        frame[0] = (tid shr 8).toByte()
        frame[1] = tid.toByte()
        frame[2] = 0
        frame[3] = 0
        frame[4] = (len shr 8).toByte()
        frame[5] = len.toByte()
        frame[6] = unitId.toByte()
        System.arraycopy(pdu, 0, frame, 7, pdu.size)
        val fcSent = pdu[0].toInt() and 0xFF

        return try {
            val out = s.getOutputStream()
            out.write(frame)
            out.flush()
            var result: ByteArray? = null
            var guard = 0
            while (result == null) {
                guard++
                if (guard > 5) throw IOException("Phản hồi không khớp")
                val header = ByteArray(7)
                inp.readFully(header)
                val rTid = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                val rLen = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
                if (rLen < 2 || rLen > 260) throw IOException("Phản hồi không hợp lệ")
                val body = ByteArray(rLen - 1)
                inp.readFully(body)
                if (rTid != tid) continue
                val fc = body[0].toInt() and 0xFF
                if (fc == (fcSent or 0x80)) {
                    val code = if (body.size > 1) body[1].toInt() and 0xFF else 0
                    throw ModbusException(exceptionText(code))
                }
                if (fc != fcSent) throw IOException("Sai mã hàm phản hồi")
                result = body
            }
            result!!
        } catch (e: IOException) {
            if (e !is ModbusException) close()
            throw e
        }
    }

    private fun exceptionText(code: Int): String = when (code) {
        1 -> "LOGO! không hỗ trợ lệnh này"
        2 -> "Địa chỉ không hợp lệ, kiểm tra bảng địa chỉ trong Cài đặt"
        3 -> "Giá trị không hợp lệ"
        4 -> "LOGO! báo lỗi thiết bị"
        else -> "LOGO! trả mã lỗi $code"
    }

    private fun readBits(function: Int, address: Int, count: Int): BooleanArray {
        val pdu = byteArrayOf(
            function.toByte(),
            (address shr 8).toByte(), address.toByte(),
            (count shr 8).toByte(), count.toByte()
        )
        val body = request(pdu)
        val n = body[1].toInt() and 0xFF
        if (body.size < 2 + n || n * 8 < count) throw IOException("Thiếu dữ liệu")
        return BooleanArray(count) { i -> ((body[2 + i / 8].toInt() shr (i % 8)) and 1) == 1 }
    }

    fun readCoils(address: Int, count: Int): BooleanArray = readBits(1, address, count)

    fun readDiscreteInputs(address: Int, count: Int): BooleanArray = readBits(2, address, count)

    fun readHoldingRegisters(address: Int, count: Int): IntArray {
        val pdu = byteArrayOf(
            3,
            (address shr 8).toByte(), address.toByte(),
            (count shr 8).toByte(), count.toByte()
        )
        val body = request(pdu)
        val n = body[1].toInt() and 0xFF
        if (body.size < 2 + n || n < count * 2) throw IOException("Thiếu dữ liệu")
        return IntArray(count) { i ->
            val v = ((body[2 + 2 * i].toInt() and 0xFF) shl 8) or (body[3 + 2 * i].toInt() and 0xFF)
            if (v >= 0x8000) v - 0x10000 else v
        }
    }

    fun writeCoil(address: Int, value: Boolean) {
        val pdu = byteArrayOf(
            5,
            (address shr 8).toByte(), address.toByte(),
            (if (value) 0xFF else 0).toByte(), 0
        )
        request(pdu)
    }

    fun writeRegister(address: Int, value: Int) {
        val v = value and 0xFFFF
        val pdu = byteArrayOf(
            6,
            (address shr 8).toByte(), address.toByte(),
            (v shr 8).toByte(), v.toByte()
        )
        request(pdu)
    }
}
