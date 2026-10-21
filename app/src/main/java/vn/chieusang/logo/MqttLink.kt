package vn.chieusang.logo

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Kết nối máy chủ MQTT để nói chuyện với LOGO! 8.4 qua cloud.
 * - Nhận: topic cfg.pubTopic, JSON {"state":{"reported":{"Q1":{"value":[1]},...}}}
 *   LOGO! chỉ gửi các biến vừa thay đổi, kèm "$logotime" khoảng 10 giây một lần.
 * - Gửi lệnh: topic cfg.subTopic, JSON {"state":{"M1":{"value":[1]}}}
 *
 * Các hàm callback được gọi trên luồng của thư viện MQTT; MainActivity tự chuyển về luồng giao diện.
 */
class MqttLink(
    private val cfg: AppConfig,
    private val onData: (Map<String, Double>) -> Unit,
    private val onStatus: (connected: Boolean, message: String) -> Unit
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var client: MqttClient? = null

    @Volatile
    private var stopped = false

    fun start() {
        stopped = false
        exec.execute { connectOnce() }
    }

    fun stop() {
        stopped = true
        exec.execute {
            val c = client
            client = null
            try {
                c?.disconnectForcibly(500, 500)
            } catch (_: Exception) {
            }
            try {
                c?.close()
            } catch (_: Exception) {
            }
        }
        exec.shutdown()
    }

    val isConnected: Boolean
        get() = client?.isConnected == true

    private fun connectOnce() {
        if (stopped) return
        var created: MqttClient? = null
        try {
            val c = MqttClient(
                cfg.broker.trim(),
                "logo-app-" + UUID.randomUUID().toString().substring(0, 8),
                MemoryPersistence()
            )
            created = c
            c.timeToWait = 8000
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try {
                        c.subscribe(cfg.pubTopic, 1)
                        onStatus(true, "Đã nối máy chủ, chờ dữ liệu LOGO!…")
                    } catch (e: Exception) {
                        onStatus(false, "Không đăng ký được topic: " + describe(e))
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    onStatus(false, "Mất kết nối máy chủ, đang nối lại…")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (message == null) return
                    val data = parse(String(message.payload, Charsets.UTF_8))
                    if (data != null) onData(data)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val o = MqttConnectOptions()
            o.mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            o.isCleanSession = true
            o.isAutomaticReconnect = true
            o.connectionTimeout = 10
            o.keepAliveInterval = 30
            if (cfg.mqttUser.isNotEmpty()) o.userName = cfg.mqttUser
            if (cfg.mqttPass.isNotEmpty()) o.password = cfg.mqttPass.toCharArray()
            onStatus(false, "Đang nối máy chủ MQTT…")
            c.connect(o)
            if (stopped) {
                try {
                    c.disconnectForcibly(500, 500)
                    c.close()
                } catch (_: Exception) {
                }
                return
            }
            client = c
        } catch (e: Exception) {
            try {
                created?.close()
            } catch (_: Exception) {
            }
            if (stopped) return
            onStatus(false, describe(e) + ". Thử lại sau 5 giây.")
            try {
                exec.schedule({ connectOnce() }, 5, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }

    /** Gửi lệnh {"state":{tag:{"value":[v]}}}. Chạy trên luồng nền. */
    fun write(tag: String, value: Int) {
        val c = client
        if (c == null || !c.isConnected) throw IOException("Chưa nối máy chủ MQTT")
        val payload = JSONObject().put(
            "state", JSONObject().put(tag, JSONObject().put("value", JSONArray().put(value)))
        ).toString()
        try {
            c.publish(cfg.subTopic, payload.toByteArray(Charsets.UTF_8), 1, false)
        } catch (e: MqttException) {
            throw IOException(describe(e))
        }
    }

    /** Gửi nhiều biến trong MỘT gói tin, để LOGO! nhận đủ giờ bật và giờ tắt cùng lúc. */
    fun writeMany(values: Map<String, Int>) {
        val c = client
        if (c == null || !c.isConnected) throw IOException("Chưa nối máy chủ MQTT")
        val st = JSONObject()
        for ((tag, v) in values) st.put(tag, JSONObject().put("value", JSONArray().put(v)))
        val payload = JSONObject().put("state", st).toString()
        try {
            c.publish(cfg.subTopic, payload.toByteArray(Charsets.UTF_8), 1, false)
        } catch (e: MqttException) {
            throw IOException(describe(e))
        }
    }

    companion object {
        fun describe(e: Throwable): String {
            if (e is MqttException) {
                return when (e.reasonCode.toInt()) {
                    MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
                    MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() -> "Sai tài khoản hoặc mật khẩu máy chủ"
                    MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() -> "Không kết nối được máy chủ, kiểm tra địa chỉ và Internet"
                    MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> "Máy chủ không phản hồi"
                    MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt() -> "Chưa nối máy chủ MQTT"
                    else -> e.message ?: "Lỗi MQTT ${e.reasonCode}"
                }
            }
            if (e is IllegalArgumentException) return "Địa chỉ máy chủ không hợp lệ (vd ssl://xxxx.hivemq.cloud:8883)"
            return e.message ?: e.javaClass.simpleName
        }

        private fun toNum(v: Any?): Double? = when (v) {
            null -> null
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is JSONArray -> if (v.length() > 0) toNum(v.opt(0)) else null
            is JSONObject -> if (v.has("value")) toNum(v.opt("value")) else null
            is String -> {
                val s = v.trim()
                s.toDoubleOrNull() ?: if (s.startsWith("0x", true)) s.substring(2).toLongOrNull(16)?.toDouble() else null
            }
            else -> null
        }

        /**
         * Đọc gói tin LOGO!. Trả về map tag → giá trị; map rỗng vẫn có nghĩa là LOGO! còn sống
         * (gói nhịp "$logotime"). Trả null nếu không phải JSON.
         */
        fun parse(s: String): Map<String, Double>? {
            val root = try {
                JSONObject(s)
            } catch (_: Exception) {
                return null
            }
            val state = root.optJSONObject("state")
            val rep = state?.optJSONObject("reported") ?: state ?: root
            val out = HashMap<String, Double>()
            val keys = rep.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("$")) continue
                val v = toNum(rep.opt(k))
                if (v != null) out[k] = v
            }
            return out
        }
    }
}
