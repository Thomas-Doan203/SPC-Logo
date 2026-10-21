package vn.chieusang.logo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Một thông số số nguyên lưu ở vùng VM của LOGO! (VW0, VW2...). */
data class Param(
    val name: String,
    val vw: Int,
    val min: Int,
    val max: Int,
    val unit: String,
    /** Tên biến trong danh sách "Cloud transfer" của LOGO! (chế độ MQTT). */
    val tag: String
)

/**
 * Cấu hình app.
 *
 * Chế độ "modbus" (tại chỗ): nối thẳng LOGO! qua WiFi/LAN, cổng 502.
 *   Địa chỉ Modbus tính từ 0: I1 = discrete input 0, Q1 = coil 8192, M1 = coil 8256, VW0 = register 0.
 *
 * Chế độ "mqtt" (từ xa): LOGO! 8.4 tự gửi dữ liệu lên máy chủ MQTT.
 *   LOGO! gửi lên topic pubTopic:  {"state":{"reported":{"Q1":{"value":[1]}, "$logotime":...}}}
 *   App gửi lệnh vào subTopic:     {"state":{"M1":{"value":[1]}}}
 *   Tên biến (tag) mặc định: Q1.., I1.., M1.., VW0.. – đặt đúng các tên này trong Soft Comfort.
 */
data class AppConfig(
    val title: String = "Tủ điều khiển",
    val mode: String = MODE_MODBUS,
    // Modbus
    val ip: String = "192.168.0.10",
    val port: Int = 502,
    val unitId: Int = 1,
    val pollMs: Int = 1000,
    val iBase: Int = 0,
    val qBase: Int = 8192,
    val mBase: Int = 8256,
    // MQTT
    val broker: String = "ssl://xxxx.s1.eu.hivemq.cloud:8883",
    val mqttUser: String = "",
    val mqttPass: String = "",
    val pubTopic: String = "LOGO_Pub",
    val subTopic: String = "LOGO_Sub",
    val staleSec: Int = 40,
    // Chung
    val confirm: Boolean = true,
    val outNames: List<String> = (1..4).map { "Ngõ ra Q$it" },
    val inNames: List<String> = (1..8).map { "Ngõ vào I$it" },
    val params: List<Param> = listOf(
        Param("Thời gian trễ", 0, 0, 32767, "giây", "VW0"),
        Param("Giờ bật đèn", 2, 0, 23, "giờ", "VW2")
    ),
    val outCmdM: Int = 1,
    val inCmdM: Int = 11,
    val pulseMs: Int = 1000,
    /** Cờ M chọn chế độ: 1 = Tay (app bật/tắt), 0 = Tự động (theo hẹn giờ). 0 ở đây = không dùng chế độ. */
    val modeM: Int = 8,
    /** Ngõ vào phản hồi điện nhánh cho từng ngõ ra (vd [5,6,7,8] = Q1↔I5...). 0 = không có. */
    val fbInputs: List<Int> = emptyList()
) {
    fun fbFor(outIdx: Int): Int = fbInputs.getOrNull(outIdx) ?: 0

    val isMqtt: Boolean get() = mode == MODE_MQTT

    /** Địa chỉ coil Modbus của cờ M số [m] (M1 = 1). */
    fun mCoil(m: Int): Int = mBase + m - 1

    fun toJson(): String {
        val o = JSONObject()
        o.put("title", title)
        o.put("mode", mode)
        o.put("ip", ip)
        o.put("port", port)
        o.put("unitId", unitId)
        o.put("pollMs", pollMs)
        o.put("iBase", iBase)
        o.put("qBase", qBase)
        o.put("mBase", mBase)
        o.put("broker", broker)
        o.put("mqttUser", mqttUser)
        o.put("mqttPass", mqttPass)
        o.put("pubTopic", pubTopic)
        o.put("subTopic", subTopic)
        o.put("staleSec", staleSec)
        o.put("confirm", confirm)
        o.put("outNames", JSONArray(outNames))
        o.put("inNames", JSONArray(inNames))
        val pa = JSONArray()
        for (p in params) {
            val po = JSONObject()
            po.put("name", p.name)
            po.put("vw", p.vw)
            po.put("min", p.min)
            po.put("max", p.max)
            po.put("unit", p.unit)
            po.put("tag", p.tag)
            pa.put(po)
        }
        o.put("params", pa)
        o.put("outCmdM", outCmdM)
        o.put("inCmdM", inCmdM)
        o.put("pulseMs", pulseMs)
        o.put("modeM", modeM)
        o.put("fbInputs", JSONArray(fbInputs))
        return o.toString()
    }

    companion object {
        const val MODE_MODBUS = "modbus"
        const val MODE_MQTT = "mqtt"

        private const val PREFS = "logo_app"
        private const val KEY = "config_v1"

        private fun strings(a: JSONArray?, fallback: List<String>): List<String> {
            if (a == null) return fallback
            val r = ArrayList<String>()
            for (i in 0 until a.length()) r.add(a.optString(i))
            return r
        }

        private fun ints(a: JSONArray?): List<Int> {
            if (a == null) return emptyList()
            val r = ArrayList<Int>()
            for (i in 0 until a.length()) r.add(a.optInt(i, 0))
            return r
        }

        fun fromJson(s: String): AppConfig {
            val d = AppConfig()
            val o = JSONObject(s)
            val params = ArrayList<Param>()
            val pa = o.optJSONArray("params")
            if (pa != null) {
                for (i in 0 until pa.length()) {
                    val po = pa.getJSONObject(i)
                    val vw = po.optInt("vw", 0)
                    params.add(
                        Param(
                            po.optString("name", "Thông số"),
                            vw,
                            po.optInt("min", 0),
                            po.optInt("max", 32767),
                            po.optString("unit", ""),
                            po.optString("tag", "VW$vw")
                        )
                    )
                }
            }
            return AppConfig(
                title = o.optString("title", d.title),
                mode = o.optString("mode", d.mode),
                ip = o.optString("ip", d.ip),
                port = o.optInt("port", d.port),
                unitId = o.optInt("unitId", d.unitId),
                pollMs = o.optInt("pollMs", d.pollMs),
                iBase = o.optInt("iBase", d.iBase),
                qBase = o.optInt("qBase", d.qBase),
                mBase = o.optInt("mBase", d.mBase),
                broker = o.optString("broker", d.broker),
                mqttUser = o.optString("mqttUser", d.mqttUser),
                mqttPass = o.optString("mqttPass", d.mqttPass),
                pubTopic = o.optString("pubTopic", d.pubTopic),
                subTopic = o.optString("subTopic", d.subTopic),
                staleSec = o.optInt("staleSec", d.staleSec),
                confirm = o.optBoolean("confirm", d.confirm),
                outNames = strings(o.optJSONArray("outNames"), d.outNames),
                inNames = strings(o.optJSONArray("inNames"), d.inNames),
                params = if (pa == null) d.params else params,
                outCmdM = o.optInt("outCmdM", d.outCmdM),
                inCmdM = o.optInt("inCmdM", d.inCmdM),
                pulseMs = o.optInt("pulseMs", d.pulseMs),
                modeM = o.optInt("modeM", d.modeM),
                fbInputs = ints(o.optJSONArray("fbInputs"))
            )
        }

        fun load(ctx: Context): AppConfig {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return AppConfig()
            return try {
                fromJson(s)
            } catch (_: Exception) {
                AppConfig()
            }
        }

        fun save(ctx: Context, cfg: AppConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, cfg.toJson()).apply()
        }
    }
}
