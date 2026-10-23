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
 * Lịch bật/tắt của một khối Weekly Timer, đã gắn vào VM bằng "Parameter VM Mapping" trong Soft Comfort.
 * [vw] = địa chỉ VW đầu tiên. Kiểu lưu [fmt]:
 *  - "bcd":  2 word: VW = giờ:phút bật dạng BCD (18:25 → 0x1825), VW+2 = giờ:phút tắt
 *  - "goi":  2 word: VW = giờ bật × 256 + phút bật, VW+2 = giờ tắt × 256 + phút tắt
 *  - "hhmm": 2 word: VW = giờ bật × 100 + phút bật (vd 1825), VW+2 = giờ tắt × 100 + phút tắt
 *  - "4w":   4 word: VW giờ bật, VW+2 phút bật, VW+4 giờ tắt, VW+6 phút tắt
 */
data class Sched(val name: String, val vw: Int, val fmt: String) {
    val words: Int get() = if (fmt == "4w") 4 else 2
    fun addr(i: Int): Int = vw + 2 * i
}

/** Công tắc phụ: một bit lệnh bật/tắt từ app (vd V0.0 = bật cưỡng bức). [bit] đánh số như các bit lệnh khác. */
data class Extra(val name: String, val bit: Int)

/**
 * Cấu hình app. Mặc định khớp chương trình LOGO-SLIGHT (ban hành):
 *   Q1 = đèn PARK (M7), Q2 = nhánh B1 (M2), Q3 = nhánh B2 (M3), Q4 = HPS (T006)
 *   I2 = nút bật tay 15 phút, I5/I6 = phản hồi nhánh B1/B2
 *   NI1 = V0.0 bật cưỡng bức 15 phút, NI2 = V0.1 cấm B1, NI3 = V0.2 cấm B2
 *   Bổ sung cho app: NI4 = V1.0 chế độ TAY, NI5..NI8 = V1.1..V1.4 lệnh tay Q1..Q4
 *   Giờ hẹn: T001 B1 = VW2/VW4, T002 B2 = VW6/VW8, T006 HPS = VW22/VW24, T007 PARK (bổ sung) = VW26..VW32
 *
 * Bit lệnh đánh số từ 1:
 *   cmdArea "V": số n ↔ V((n-1)/8).((n-1)%8). V0.0 = 1, V0.1 = 2, V1.0 = 9 ... Modbus coil = n-1.
 *   cmdArea "M": số n ↔ cờ Mn. Modbus coil = mBase + n - 1.
 *   Tên biến MQTT: V1_0 hoặc M8.
 */
data class AppConfig(
    val title: String = "Tủ LOGO-SLIGHT",
    val mode: String = MODE_MODBUS,
    // Modbus
    val ip: String = "192.168.0.3",
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
    val outNames: List<String> = listOf("Đèn PARK", "Nhánh B1", "Nhánh B2", "HPS (22h–4h)"),
    val inNames: List<String> = listOf("I1", "Nút tay 15'", "I3", "I4", "Phản hồi B1", "Phản hồi B2", "I7", "I8"),
    val params: List<Param> = emptyList(),
    /** Vùng bit lệnh: "V" (qua khối Network Input – khuyến nghị) hoặc "M". */
    val cmdArea: String = "V",
    /** Bit lệnh tay của Q1 (các ngõ sau nối tiếp). V1.1 = 10. */
    val outCmdM: Int = 10,
    /** Bit kích của I1 (các ngõ sau nối tiếp). 0 = không dùng nút Kích. */
    val inCmdM: Int = 0,
    val pulseMs: Int = 1000,
    /** Bit chọn chế độ: 1 = Tay, 0 = Tự động. V1.0 = 9. 0 = không dùng chế độ. */
    val modeM: Int = 9,
    /** Ngõ vào phản hồi điện nhánh cho từng ngõ ra. 0 = không có. */
    val fbInputs: List<Int> = listOf(0, 5, 6, 0),
    val scheds: List<Sched> = listOf(
        Sched("Lịch nhánh B1", 2, "bcd"),
        Sched("Lịch nhánh B2", 6, "bcd"),
        Sched("Lịch HPS", 22, "bcd"),
        Sched("Lịch PARK tối", 26, "bcd"),
        Sched("Lịch PARK sáng", 30, "bcd")
    ),
    val extras: List<Extra> = listOf(
        Extra("Bật cưỡng bức tất cả (tự tắt sau 15 phút)", 1),
        Extra("Cấm nhánh B1", 2),
        Extra("Cấm nhánh B2", 3)
    )
) {
    fun fbFor(outIdx: Int): Int = fbInputs.getOrNull(outIdx) ?: 0

    val isMqtt: Boolean get() = mode == MODE_MQTT
    val isV: Boolean get() = cmdArea.equals("V", ignoreCase = true)

    /** Địa chỉ coil Modbus của bit lệnh số [n]. */
    fun cmdCoil(n: Int): Int = if (isV) n - 1 else mBase + n - 1

    /** Tên khóa / tên biến MQTT của bit lệnh số [n]. */
    fun cmdKey(n: Int): String = if (isV) "V${(n - 1) / 8}_${(n - 1) % 8}" else "M$n"

    /** Tên hiển thị: V1.0 hoặc M8. */
    fun bitName(n: Int): String = if (n <= 0) "0" else if (isV) "V${(n - 1) / 8}.${(n - 1) % 8}" else "M$n"

    /** Đọc "V1.0", "M8" hoặc số. Trả về số bit (≥ 0), null nếu sai dạng. */
    fun parseBit(s: String): Int? {
        val t = s.trim().uppercase()
        if (t.isEmpty() || t == "0") return 0
        if (t.startsWith("V")) {
            val p = t.substring(1).split(".")
            val by = p.getOrNull(0)?.toIntOrNull() ?: return null
            val bi = p.getOrNull(1)?.toIntOrNull() ?: 0
            if (by < 0 || by > 850 || bi !in 0..7) return null
            return by * 8 + bi + 1
        }
        if (t.startsWith("M")) return t.substring(1).toIntOrNull()?.takeIf { it in 1..64 }
        return t.toIntOrNull()?.takeIf { it >= 0 }
    }

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
        o.put("cmdArea", cmdArea)
        o.put("outCmdM", outCmdM)
        o.put("inCmdM", inCmdM)
        o.put("pulseMs", pulseMs)
        o.put("modeM", modeM)
        o.put("fbInputs", JSONArray(fbInputs))
        val sa = JSONArray()
        for (s in scheds) sa.put(JSONObject().put("name", s.name).put("vw", s.vw).put("fmt", s.fmt))
        o.put("scheds", sa)
        val ea = JSONArray()
        for (e in extras) ea.put(JSONObject().put("name", e.name).put("bit", e.bit))
        o.put("extras", ea)
        return o.toString()
    }

    companion object {
        const val MODE_MODBUS = "modbus"
        const val MODE_MQTT = "mqtt"
        val FORMATS = listOf("bcd", "goi", "hhmm", "4w")

        private const val PREFS = "logo_app"
        // Đổi khóa lưu để bỏ cấu hình cũ (địa chỉ cũ trùng vùng nhớ của chương trình LOGO-SLIGHT)
        private const val KEY = "config_v2"

        private fun strings(a: JSONArray?, fallback: List<String>): List<String> {
            if (a == null) return fallback
            val r = ArrayList<String>()
            for (i in 0 until a.length()) r.add(a.optString(i))
            return r
        }

        private fun scheds(a: JSONArray?): List<Sched>? {
            if (a == null) return null
            val r = ArrayList<Sched>()
            for (i in 0 until a.length()) {
                val so = a.getJSONObject(i)
                r.add(Sched(so.optString("name", "Lịch"), so.optInt("vw", 2), so.optString("fmt", "bcd")))
            }
            return r
        }

        private fun extras(a: JSONArray?): List<Extra>? {
            if (a == null) return null
            val r = ArrayList<Extra>()
            for (i in 0 until a.length()) {
                val eo = a.getJSONObject(i)
                r.add(Extra(eo.optString("name", "Lệnh"), eo.optInt("bit", 0)))
            }
            return r
        }

        private fun ints(a: JSONArray?, fallback: List<Int>): List<Int> {
            if (a == null) return fallback
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
                cmdArea = o.optString("cmdArea", d.cmdArea),
                outCmdM = o.optInt("outCmdM", d.outCmdM),
                inCmdM = o.optInt("inCmdM", d.inCmdM),
                pulseMs = o.optInt("pulseMs", d.pulseMs),
                modeM = o.optInt("modeM", d.modeM),
                fbInputs = ints(o.optJSONArray("fbInputs"), d.fbInputs),
                scheds = scheds(o.optJSONArray("scheds")) ?: d.scheds,
                extras = extras(o.optJSONArray("extras")) ?: d.extras
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
