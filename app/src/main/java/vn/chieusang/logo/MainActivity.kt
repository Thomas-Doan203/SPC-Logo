package vn.chieusang.logo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.SocketFactory

/**
 * Màn hình chính. Giao diện vẽ bằng assets/ui.html trong WebView;
 * phần kết nối LOGO! (Modbus TCP / MQTT) chạy bằng Kotlin.
 * Kotlin gửi trạng thái sang giao diện qua window.update(json),
 * giao diện gửi lệnh về qua đối tượng "Android" (lớp Bridge).
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var cfg = AppConfig()

    // Chế độ Modbus: chỉ truy cập từ luồng nền io
    private var client: ModbusClient? = null

    // Chế độ MQTT
    @Volatile
    private var mqtt: MqttLink? = null
    private var mqttConnected = false
    private var mqttMsg = ""
    private var lastMsg = 0L
    private var cacheTime = 0L

    // Trạng thái, chỉ dùng trên luồng giao diện. Khóa: "Q1", "I1", "M1", "VW0" hoặc tag MQTT.
    private val vals = HashMap<String, Double>()
    private var running = false
    private var gen = 0
    private var modbusOnline = false
    private var hasData = false
    private var lastOk = 0L
    private var lastError = ""
    private val busy = HashSet<String>()

    private lateinit var web: WebView
    private var pageReady = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            render()
            ui.postDelayed(this, 2000)
        }
    }

    // ---------------------------------------------------------------- vòng đời

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = AppConfig.load(this)
        window.statusBarColor = Color.parseColor("#132A45")
        window.navigationBarColor = Color.parseColor("#0B1320")
        setupWeb()
        if (cfg.isMqtt) loadCache()
    }

    override fun onResume() {
        super.onResume()
        running = true
        startLink()
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 2000)
    }

    override fun onPause() {
        super.onPause()
        running = false
        ui.removeCallbacks(tick)
        stopLink()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        web.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Nút Back đóng hộp thoại đang mở trên giao diện trước, rồi mới thoát app
        web.evaluateJavascript("(function(){var d=document.getElementById('dim');if(d&&d.classList.contains('show')){closeSheet();return 1}return 0})()") { r ->
            if (r != "1") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ---------------------------------------------------------------- giao diện web

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B1320"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                render()
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/ui.html")
    }

    /** Các hàm giao diện gọi được: Android.setMode(true)... Chạy trên luồng riêng nên chuyển về luồng chính. */
    inner class Bridge {
        @JavascriptInterface
        fun ready() = ui.post { render() }.let { }

        @JavascriptInterface
        fun haptic() = ui.post { web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }.let { }

        @JavascriptInterface
        fun settings() = ui.post { showSettings() }.let { }

        @JavascriptInterface
        fun setMode(manual: Boolean) = ui.post { cmdMode(manual) }.let { }

        @JavascriptInterface
        fun setOutput(idx: Int, on: Boolean) = ui.post { cmdOutput(idx, on) }.let { }

        @JavascriptInterface
        fun pulse(idx: Int) = ui.post { cmdPulse(idx) }.let { }

        @JavascriptInterface
        fun setSched(k: Int, onH: Int, onM: Int, offH: Int, offM: Int) =
            ui.post { cmdSched(k, intArrayOf(onH, onM, offH, offM)) }.let { }

        @JavascriptInterface
        fun setParam(k: Int, v: Int) = ui.post { cmdParam(k, v) }.let { }
    }

    private fun jsToast(s: String) {
        if (!pageReady) {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
            return
        }
        web.evaluateJavascript("toast(" + JSONObject.quote(s) + ")", null)
    }

    private fun startLink() {
        gen++
        val g = gen
        if (cfg.isMqtt) {
            mqttConnected = false
            mqttMsg = "Đang nối máy chủ MQTT…"
            val conf = cfg
            val link = MqttLink(
                conf,
                onData = { data -> ui.post { onMqttData(g, data) } },
                onStatus = { ok, msg ->
                    ui.post {
                        if (g != gen) return@post
                        mqttConnected = ok
                        mqttMsg = msg
                        render()
                    }
                }
            )
            mqtt = link
            link.start()
        } else {
            ui.post { pollLoop(g) }
        }
    }

    private fun stopLink() {
        gen++
        mqtt?.stop()
        mqtt = null
        mqttConnected = false
        io.execute {
            client?.close()
            client = null
        }
    }

    private fun isOnline(): Boolean {
        val conf = cfg
        if (!conf.isMqtt) return modbusOnline
        if (!mqttConnected || lastMsg == 0L) return false
        return System.currentTimeMillis() - lastMsg < conf.staleSec.coerceAtLeast(15) * 1000L
    }

    // ---------------------------------------------------------------- MQTT

    private fun onMqttData(g: Int, data: Map<String, Double>) {
        if (g != gen) return
        val now = System.currentTimeMillis()
        lastMsg = now
        if (data.isNotEmpty()) {
            vals.putAll(data)
            lastOk = now
            hasData = true
            cacheTime = 0L
            saveCache()
        }
        render()
    }

    /** LOGO! chỉ gửi biến khi thay đổi, nên lưu giá trị cuối để mở app là thấy ngay. */
    private fun saveCache() {
        try {
            val v = JSONObject()
            for ((k, x) in vals) v.put(k, x)
            getSharedPreferences("logo_app", Context.MODE_PRIVATE).edit()
                .putString("mqtt_cache", JSONObject().put("t", lastOk).put("v", v).toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadCache() {
        try {
            val s = getSharedPreferences("logo_app", Context.MODE_PRIVATE).getString("mqtt_cache", null) ?: return
            val o = JSONObject(s)
            val v = o.getJSONObject("v")
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                vals[k] = v.getDouble(k)
            }
            cacheTime = o.optLong("t", 0L)
            lastOk = cacheTime
            hasData = vals.isNotEmpty()
        } catch (_: Exception) {
        }
    }

    private fun clearCache() {
        getSharedPreferences("logo_app", Context.MODE_PRIVATE).edit().remove("mqtt_cache").apply()
    }

    // ---------------------------------------------------------------- Modbus

    private fun pollLoop(g: Int) {
        if (g != gen || !running || cfg.isMqtt) return
        io.execute {
            doPoll(g)
            ui.postDelayed({ pollLoop(g) }, cfg.pollMs.coerceAtLeast(300).toLong())
        }
    }

    /** Ưu tiên mạng có dây (Type-C LAN), rồi WiFi, để Android không đẩy kết nối sang 4G. */
    private fun localSocketFactory(): SocketFactory {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks
        for (transport in intArrayOf(NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_WIFI)) {
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(transport)) return n.socketFactory
            }
        }
        return SocketFactory.getDefault()
    }

    private fun ensureClient(): ModbusClient {
        val c = client
        if (c != null && c.isConnected) return c
        c?.close()
        val conf = cfg
        val n = ModbusClient(conf.ip, conf.port, conf.unitId, localSocketFactory())
        client = n
        n.connect()
        return n
    }

    private fun doPoll(g: Int) {
        val conf = cfg
        try {
            val c = ensureClient()
            val nQ = conf.outNames.size
            val nI = conf.inNames.size
            val data = HashMap<String, Double>()
            fun put(prefix: String, start: Int, bits: BooleanArray) {
                bits.forEachIndexed { k, b -> data["$prefix${start + k}"] = if (b) 1.0 else 0.0 }
            }
            if (nQ > 0) {
                put("Q", 1, c.readCoils(conf.qBase, nQ))
                put("M", conf.outCmdM, c.readCoils(conf.mCoil(conf.outCmdM), nQ))
            }
            if (nI > 0) {
                put("I", 1, c.readDiscreteInputs(conf.iBase, nI))
                put("M", conf.inCmdM, c.readCoils(conf.mCoil(conf.inCmdM), nI))
            }
            if (conf.modeM > 0) {
                put("M", conf.modeM, c.readCoils(conf.mCoil(conf.modeM), 1))
            }
            // Ngõ vào phản hồi nằm ngoài I1..In đang hiển thị thì đọc thêm
            val maxFb = conf.fbInputs.maxOrNull() ?: 0
            if (maxFb > nI) {
                put("I", nI + 1, c.readDiscreteInputs(conf.iBase + nI, maxFb - nI))
            }
            for (p in conf.params) {
                data["VW${p.vw}"] = c.readHoldingRegisters(p.vw / 2, 1)[0].toDouble()
            }
            for (sc in conf.scheds) {
                val regs = c.readHoldingRegisters(sc.vw / 2, sc.words)
                regs.forEachIndexed { i, v -> data["VW${sc.addr(i)}"] = v.toDouble() }
            }
            ui.post {
                if (g != gen) return@post
                vals.putAll(data)
                modbusOnline = true
                hasData = true
                lastOk = System.currentTimeMillis()
                lastError = ""
                render()
            }
        } catch (e: Exception) {
            client?.close()
            client = null
            val msg = describe(e)
            ui.post {
                if (g != gen) return@post
                modbusOnline = false
                lastError = msg
                render()
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "Hết thời gian chờ. Kiểm tra WiFi và IP của LOGO!"
        is ConnectException -> "Không kết nối được. Kiểm tra IP, cổng 502 và kết nối Modbus trên LOGO!"
        is NoRouteToHostException -> "Không tìm thấy LOGO!. Điện thoại có đang nối WiFi của tủ?"
        is UnknownHostException -> "Địa chỉ IP không hợp lệ"
        is ModbusException -> e.message ?: "LOGO! báo lỗi"
        else -> MqttLink.describe(e)
    }

    // ---------------------------------------------------------------- ghi xuống LOGO! (luồng nền)

    private fun writeBit(m: Int, v: Boolean) {
        val conf = cfg
        if (conf.isMqtt) {
            (mqtt ?: throw IOException("Chưa nối máy chủ MQTT")).write("M$m", if (v) 1 else 0)
        } else {
            ensureClient().writeCoil(conf.mCoil(m), v)
        }
    }

    private fun writeParam(p: Param, v: Int) {
        val conf = cfg
        if (conf.isMqtt) {
            (mqtt ?: throw IOException("Chưa nối máy chủ MQTT")).write(p.tag, v)
        } else {
            ensureClient().writeRegister(p.vw / 2, v)
        }
    }

    private fun paramKey(p: Param): String = if (cfg.isMqtt) p.tag else "VW${p.vw}"

    private fun bit(key: String): Boolean? = vals[key]?.let { it != 0.0 }

    // ---------------------------------------------------------------- gửi lệnh

    private fun sendCommand(key: String, question: String?, action: () -> Unit) {
        if (!isOnline()) {
            toast("LOGO! đang offline, không gửi lệnh")
            return
        }
        val go = {
            busy.add(key)
            render()
            io.execute {
                var err: String? = null
                try {
                    action()
                } catch (e: Exception) {
                    err = describe(e)
                    if (e !is ModbusException) {
                        client?.close()
                        client = null
                    }
                }
                val result = err
                ui.post {
                    busy.remove(key)
                    if (result != null) toast("Lỗi: $result") else toast("Đã gửi lệnh")
                    render()
                }
            }
        }
        if (question != null && cfg.confirm) {
            AlertDialog.Builder(this)
                .setMessage(question)
                .setPositiveButton("Xác nhận") { _, _ -> go() }
                .setNegativeButton("Hủy", null)
                .show()
        } else {
            go()
        }
    }

    /** null = không dùng chế độ, true = Tay, false = Tự động. */
    private fun manualMode(): Boolean? {
        val conf = cfg
        if (conf.modeM <= 0) return null
        return bit("M${conf.modeM}") ?: false
    }

    private fun cmdMode(manual: Boolean) {
        val conf = cfg
        if (conf.modeM <= 0) return
        sendCommand("mode", null) { writeBit(conf.modeM, manual) }
    }

    private fun cmdOutput(idx: Int, on: Boolean) {
        val conf = cfg
        if (idx !in conf.outNames.indices) return
        if (manualMode() == false) {
            toast("Chuyển sang chế độ Tay để bật/tắt bằng app")
            return
        }
        sendCommand("q$idx", null) { writeBit(conf.outCmdM + idx, on) }
    }

    private fun cmdPulse(idx: Int) {
        val conf = cfg
        if (idx !in conf.inNames.indices) return
        val m = conf.inCmdM + idx
        val ms = conf.pulseMs.coerceIn(100, 10000).toLong()
        sendCommand("i$idx", null) {
            writeBit(m, true)
            try {
                Thread.sleep(ms)
            } finally {
                writeBit(m, false)
            }
        }
    }

    private fun cmdSched(k: Int, t: IntArray) {
        val sc = cfg.scheds.getOrNull(k) ?: return
        if (t[0] !in 0..23 || t[1] !in 0..59 || t[2] !in 0..23 || t[3] !in 0..59) {
            toast("Giờ không hợp lệ")
            return
        }
        sendCommand("s$k", null) { writeSched(sc, t) }
    }

    private fun cmdParam(k: Int, v: Int) {
        val p = cfg.params.getOrNull(k) ?: return
        if (v < p.min || v > p.max) {
            toast("Giá trị phải từ ${p.min} đến ${p.max}")
            return
        }
        sendCommand("p$k", null) { writeParam(p, v) }
    }

    // ---------------------------------------------------------------- lịch bật/tắt

    private fun two(n: Int) = if (n < 10) "0$n" else "$n"

    /** Giờ bật và giờ tắt (h, m, h, m) đọc được từ LOGO!, null nếu chưa có hoặc sai dạng. */
    private fun schedTimes(sc: Sched): IntArray? {
        val w = IntArray(sc.words) { i -> vals["VW${sc.addr(i)}"]?.toInt() ?: return null }
        val t = when (sc.fmt) {
            "goi" -> intArrayOf(w[0] shr 8 and 0xFF, w[0] and 0xFF, w[1] shr 8 and 0xFF, w[1] and 0xFF)
            "hhmm" -> intArrayOf(w[0] / 100, w[0] % 100, w[1] / 100, w[1] % 100)
            else -> intArrayOf(w[0], w[1], w[2], w[3])
        }
        val ok = t[0] in 0..23 && t[1] in 0..59 && t[2] in 0..23 && t[3] in 0..59
        return if (ok) t else null
    }

    private fun schedWords(sc: Sched, t: IntArray): IntArray = when (sc.fmt) {
        "goi" -> intArrayOf(t[0] * 256 + t[1], t[2] * 256 + t[3])
        "hhmm" -> intArrayOf(t[0] * 100 + t[1], t[2] * 100 + t[3])
        else -> intArrayOf(t[0], t[1], t[2], t[3])
    }

    /** Ghi lịch xuống LOGO! (luồng nền). MQTT gửi tất cả trong một gói. */
    private fun writeSched(sc: Sched, t: IntArray) {
        val w = schedWords(sc, t)
        if (cfg.isMqtt) {
            val m = LinkedHashMap<String, Int>()
            w.forEachIndexed { i, v -> m["VW${sc.addr(i)}"] = v }
            (mqtt ?: throw IOException("Chưa nối máy chủ MQTT")).writeMany(m)
        } else {
            val c = ensureClient()
            w.forEachIndexed { i, v -> c.writeRegister(sc.addr(i) / 2, v) }
        }
    }

    // ---------------------------------------------------------------- gửi trạng thái sang giao diện

    private fun render() {
        if (!pageReady) return
        val conf = cfg
        val online = isOnline()
        val time = SimpleDateFormat("HH:mm:ss dd/MM", Locale("vi", "VN"))
        val st = JSONObject()
        st.put("title", conf.title)
        st.put("online", online)
        st.put("confirm", conf.confirm)
        st.put("pulseMs", conf.pulseMs)

        // Dòng trạng thái kết nối
        val conn: String
        val connText: String
        if (conf.isMqtt) {
            when {
                online -> { conn = "ok"; connText = "LOGO! online · qua cloud" }
                mqttConnected && lastMsg == 0L -> { conn = "wait"; connText = "Đã nối máy chủ, chờ LOGO!…" }
                mqttConnected -> { conn = "bad"; connText = "LOGO! mất tín hiệu" }
                else -> { conn = if (mqttMsg.startsWith("Đang")) "wait" else "bad"; connText = "Máy chủ: chưa kết nối" }
            }
        } else {
            when {
                online -> { conn = "ok"; connText = "LOGO! online · ${conf.ip}" }
                hasData -> { conn = "bad"; connText = "Mất kết nối · ${conf.ip}" }
                else -> { conn = "wait"; connText = "Đang kết nối ${conf.ip}…" }
            }
        }
        st.put("conn", conn)
        st.put("connText", connText)

        val manual = manualMode()
        st.put("modeAvail", conf.modeM > 0)
        st.put("manual", if (manual == null || bit("M${conf.modeM}") == null) JSONObject.NULL else manual)
        st.put("busyMode", busy.contains("mode"))

        val outs = JSONArray()
        conf.outNames.forEachIndexed { idx, name ->
            val q = bit("Q${idx + 1}")
            val mKnown = bit("M${conf.outCmdM + idx}")
            val fbNo = conf.fbFor(idx)
            val fb = if (fbNo > 0) bit("I$fbNo") else null
            var warn = false
            val state = when {
                q == null -> "Chưa có dữ liệu"
                q && fb == false -> { warn = true; "Có lệnh bật nhưng nhánh không có điện (I$fbNo)" }
                !q && fb == true -> { warn = true; "Đã tắt nhưng nhánh vẫn có điện (I$fbNo)" }
                q -> "Đang sáng"
                else -> "Đang tắt"
            }
            val extra = when {
                q == null || warn -> ""
                manual == false -> " · theo lịch"
                manual == true && mKnown != null && mKnown != q -> " · đã ra lệnh, chờ LOGO!"
                manual == true -> " · bằng tay"
                q && mKnown == false -> " · bật từ tủ"
                else -> ""
            }
            val o = JSONObject()
            o.put("name", name)
            o.put("q", q ?: JSONObject.NULL)
            o.put("cmd", if (manual == false) (q ?: false) else (mKnown ?: (q ?: false)))
            o.put("sub", state + extra)
            o.put("warn", warn)
            o.put("busy", busy.contains("q$idx"))
            outs.put(o)
        }
        st.put("outs", outs)

        val ins = JSONArray()
        conf.inNames.forEachIndexed { idx, name ->
            val o = JSONObject()
            o.put("name", name)
            o.put("on", bit("I${idx + 1}") == true)
            o.put("busy", busy.contains("i$idx") || bit("M${conf.inCmdM + idx}") == true)
            ins.put(o)
        }
        st.put("ins", ins)

        val scheds = JSONArray()
        conf.scheds.forEachIndexed { k, sc ->
            val o = JSONObject()
            o.put("name", sc.name)
            val t = schedTimes(sc)
            o.put("t", if (t == null) JSONObject.NULL else JSONArray().put(t[0]).put(t[1]).put(t[2]).put(t[3]))
            o.put("busy", busy.contains("s$k"))
            scheds.put(o)
        }
        st.put("scheds", scheds)

        val params = JSONArray()
        conf.params.forEachIndexed { k, p ->
            val o = JSONObject()
            o.put("name", p.name)
            o.put("unit", p.unit)
            o.put("min", p.min)
            o.put("max", p.max)
            val v = vals[paramKey(p)]?.toInt()
            o.put("v", v ?: JSONObject.NULL)
            o.put("busy", busy.contains("p$k"))
            params.put(o)
        }
        st.put("params", params)

        val cal = Calendar.getInstance()
        st.put("nowMin", cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))

        val err = if (conf.isMqtt) (if (!online && !mqttConnected) mqttMsg else "") else (if (!online) lastError else "")
        val footer = buildString {
            if (lastOk > 0) {
                append(if (cacheTime > 0 && cacheTime == lastOk) "Giá trị lưu lần trước, lúc " else "Cập nhật lúc ")
                append(time.format(Date(lastOk)))
            } else {
                append("Chưa nhận dữ liệu từ LOGO!")
            }
            if (conf.isMqtt && lastMsg > 0) append("\nTín hiệu LOGO! gần nhất: ").append(time.format(Date(lastMsg)))
            if (conf.isMqtt && online && lastOk == 0L) append("\nLOGO! chỉ gửi biến khi có thay đổi. Chờ tủ đổi trạng thái.")
        }
        st.put("footer", footer)
        st.put("err", err)
        web.evaluateJavascript("window.update&&window.update(" + st.toString() + ")", null)
    }

    // ---------------------------------------------------------------- tiện ích cho màn hình Cài đặt

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    // ---------------------------------------------------------------- cài đặt

    private fun label(parent: LinearLayout, s: String, topDp: Int = 10) {
        val l = text(s, 13f, Color.parseColor("#555A5C"))
        l.setPadding(0, dp(topDp), 0, dp(2))
        parent.addView(l)
    }

    private fun heading(parent: LinearLayout, s: String) {
        val h = text(s, 16f, Color.parseColor("#1F2326"), true)
        h.setPadding(0, dp(18), 0, 0)
        parent.addView(h)
    }

    private fun field(parent: LinearLayout, labelText: String, value: String, type: Int, lines: Int = 1): EditText {
        label(parent, labelText)
        val e = EditText(this)
        e.setText(value)
        e.inputType = if (lines > 1) type or InputType.TYPE_TEXT_FLAG_MULTI_LINE else type
        if (lines > 1) {
            e.minLines = lines
            e.gravity = Gravity.TOP or Gravity.START
        } else {
            e.setSingleLine(true)
        }
        parent.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun showSettings() {
        val c = cfg
        val txt = InputType.TYPE_CLASS_TEXT
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val num = InputType.TYPE_CLASS_NUMBER
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(4), dp(20), dp(8))

        val fTitle = field(box, "Tên tủ", c.title, txt)

        label(box, "Cách kết nối", 14)
        val group = RadioGroup(this)
        group.orientation = RadioGroup.VERTICAL
        val rModbus = RadioButton(this)
        rModbus.id = View.generateViewId()
        rModbus.text = "Tại chỗ: WiFi của tủ / cáp LAN / VPN (Modbus TCP)"
        val rMqtt = RadioButton(this)
        rMqtt.id = View.generateViewId()
        rMqtt.text = "Từ xa: qua máy chủ cloud (MQTT)"
        group.addView(rModbus)
        group.addView(rMqtt)
        group.check(if (c.isMqtt) rMqtt.id else rModbus.id)
        box.addView(group)

        // Khối Modbus
        val modbusBox = LinearLayout(this)
        modbusBox.orientation = LinearLayout.VERTICAL
        heading(modbusBox, "Tại chỗ (Modbus TCP)")
        val fIp = field(modbusBox, "Địa chỉ IP của LOGO!", c.ip, InputType.TYPE_CLASS_PHONE)
        val fPort = field(modbusBox, "Cổng Modbus TCP", c.port.toString(), num)
        val fUnit = field(modbusBox, "Unit ID", c.unitId.toString(), num)
        val fPoll = field(modbusBox, "Chu kỳ đọc (ms)", c.pollMs.toString(), num)
        val fIBase = field(modbusBox, "Địa chỉ ngõ vào I1 (discrete input, tính từ 0)", c.iBase.toString(), num)
        val fQBase = field(modbusBox, "Địa chỉ ngõ ra Q1 (coil)", c.qBase.toString(), num)
        val fMBase = field(modbusBox, "Địa chỉ cờ M1 (coil)", c.mBase.toString(), num)
        box.addView(modbusBox)

        // Khối MQTT
        val mqttBox = LinearLayout(this)
        mqttBox.orientation = LinearLayout.VERTICAL
        heading(mqttBox, "Từ xa (MQTT)")
        val fBroker = field(mqttBox, "Máy chủ MQTT (vd ssl://xxxx.s1.eu.hivemq.cloud:8883)", c.broker, uri)
        val fUser = field(mqttBox, "Tài khoản", c.mqttUser, uri)
        val fPass = field(mqttBox, "Mật khẩu", c.mqttPass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val fPub = field(mqttBox, "Topic LOGO! gửi lên (Publish topic trong Soft Comfort)", c.pubTopic, uri)
        val fSub = field(mqttBox, "Topic gửi lệnh xuống LOGO! (Subscribe topic)", c.subTopic, uri)
        val fStale = field(mqttBox, "Báo mất tín hiệu sau (giây không có dữ liệu)", c.staleSec.toString(), num)
        box.addView(mqttBox)

        fun showMode(isMqtt: Boolean) {
            modbusBox.visibility = if (isMqtt) View.GONE else View.VISIBLE
            mqttBox.visibility = if (isMqtt) View.VISIBLE else View.GONE
        }
        showMode(c.isMqtt)
        group.setOnCheckedChangeListener { _, id -> showMode(id == rMqtt.id) }

        heading(box, "Điểm điều khiển")
        val fConfirm = CheckBox(this)
        fConfirm.text = "Hỏi lại trước mỗi lệnh"
        fConfirm.isChecked = c.confirm
        box.addView(fConfirm)
        val fOut = field(box, "Tên ngõ ra, mỗi dòng một tên (Q1, Q2...)", c.outNames.joinToString("\n"), txt, 4)
        val fIn = field(box, "Tên ngõ vào, mỗi dòng một tên (I1, I2...)", c.inNames.joinToString("\n"), txt, 4)
        val fPar = field(
            box, "Thông số, mỗi dòng: Tên;VW;nhỏ nhất;lớn nhất;đơn vị;tag MQTT",
            c.params.joinToString("\n") { "${it.name};${it.vw};${it.min};${it.max};${it.unit};${it.tag}" }, txt, 3
        )
        val fSched = field(
            box, "Lịch bật/tắt, mỗi dòng: Tên;VW đầu;kiểu (4w | goi | hhmm)",
            c.scheds.joinToString("\n") { "${it.name};${it.vw};${it.fmt}" }, txt, 4
        )
        val fOutM = field(box, "Ngõ ra Q1 điều khiển bằng cờ M số", c.outCmdM.toString(), num)
        val fInM = field(box, "Nút Kích của I1 dùng cờ M số", c.inCmdM.toString(), num)
        val fPulse = field(box, "Độ dài xung nút Kích (ms)", c.pulseMs.toString(), num)
        val fModeM = field(box, "Cờ M chọn chế độ Tay/Tự động (0 = không dùng)", c.modeM.toString(), num)
        val fFb = field(
            box, "Ngõ vào phản hồi điện nhánh cho Q1, Q2... (vd 5,6,7,8; 0 = không có)",
            c.fbInputs.joinToString(","), txt
        )

        val scroll = ScrollView(this)
        scroll.addView(box)

        val dlg = AlertDialog.Builder(this)
            .setTitle("Cài đặt")
            .setView(scroll)
            .setPositiveButton("Lưu", null)
            .setNegativeButton("Hủy", null)
            .setNeutralButton("Mặc định", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val isMqtt = group.checkedRadioButtonId == rMqtt.id
                val ip = fIp.text.toString().trim()
                val broker = fBroker.text.toString().trim()
                if (!isMqtt && ip.isEmpty()) {
                    toast("Chưa nhập IP của LOGO!")
                    return@setOnClickListener
                }
                if (isMqtt && !(broker.startsWith("ssl://") || broker.startsWith("tcp://") ||
                            broker.startsWith("wss://") || broker.startsWith("ws://"))
                ) {
                    toast("Địa chỉ máy chủ phải bắt đầu bằng ssl:// (vd ssl://xxxx.hivemq.cloud:8883)")
                    return@setOnClickListener
                }
                val params = ArrayList<Param>()
                for (line in fPar.text.toString().lines()) {
                    if (line.isBlank()) continue
                    val p = line.split(";").map { it.trim() }
                    val vw = p.getOrNull(1)?.toIntOrNull()
                    if (vw == null || vw < 0 || vw % 2 != 0) {
                        toast("Dòng thông số sai: \"$line\". VW phải là số chẵn (0, 2, 4...)")
                        return@setOnClickListener
                    }
                    params.add(
                        Param(
                            p[0].ifEmpty { "VW$vw" }, vw,
                            p.getOrNull(2)?.toIntOrNull() ?: 0,
                            p.getOrNull(3)?.toIntOrNull() ?: 32767,
                            p.getOrNull(4) ?: "",
                            p.getOrNull(5)?.ifEmpty { null } ?: "VW$vw"
                        )
                    )
                }
                val scheds = ArrayList<Sched>()
                for (line in fSched.text.toString().lines()) {
                    if (line.isBlank()) continue
                    val p = line.split(";").map { it.trim() }
                    val vw = p.getOrNull(1)?.toIntOrNull()
                    val fmt = (p.getOrNull(2) ?: "4w").lowercase()
                    if (vw == null || vw < 0 || vw % 2 != 0 || fmt !in listOf("4w", "goi", "hhmm")) {
                        toast("Dòng lịch sai: \"$line\". VW là số chẵn, kiểu là 4w, goi hoặc hhmm")
                        return@setOnClickListener
                    }
                    scheds.add(Sched(p[0].ifEmpty { "Lịch VW$vw" }, vw, fmt))
                }
                fun names(e: EditText) = e.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val newCfg = AppConfig(
                    title = fTitle.text.toString().trim().ifEmpty { "Tủ điều khiển" },
                    mode = if (isMqtt) AppConfig.MODE_MQTT else AppConfig.MODE_MODBUS,
                    ip = ip.ifEmpty { c.ip },
                    port = fPort.text.toString().toIntOrNull() ?: 502,
                    unitId = fUnit.text.toString().toIntOrNull() ?: 1,
                    pollMs = (fPoll.text.toString().toIntOrNull() ?: 1000).coerceIn(300, 60000),
                    iBase = fIBase.text.toString().toIntOrNull() ?: 0,
                    qBase = fQBase.text.toString().toIntOrNull() ?: 8192,
                    mBase = fMBase.text.toString().toIntOrNull() ?: 8256,
                    broker = broker.ifEmpty { c.broker },
                    mqttUser = fUser.text.toString().trim(),
                    mqttPass = fPass.text.toString(),
                    pubTopic = fPub.text.toString().trim().ifEmpty { "LOGO_Pub" },
                    subTopic = fSub.text.toString().trim().ifEmpty { "LOGO_Sub" },
                    staleSec = (fStale.text.toString().toIntOrNull() ?: 40).coerceIn(15, 3600),
                    confirm = fConfirm.isChecked,
                    outNames = names(fOut).take(20),
                    inNames = names(fIn).take(24),
                    params = params,
                    scheds = scheds,
                    outCmdM = (fOutM.text.toString().toIntOrNull() ?: 1).coerceAtLeast(1),
                    inCmdM = (fInM.text.toString().toIntOrNull() ?: 11).coerceAtLeast(1),
                    pulseMs = (fPulse.text.toString().toIntOrNull() ?: 1000).coerceIn(100, 10000),
                    modeM = (fModeM.text.toString().toIntOrNull() ?: 0).coerceIn(0, 64),
                    fbInputs = fFb.text.toString().split(",", ";", " ")
                        .mapNotNull { it.trim().toIntOrNull() }.map { it.coerceIn(0, 24) }
                )
                applyConfig(newCfg)
                dlg.dismiss()
                toast("Đã lưu cài đặt")
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Khôi phục cài đặt mặc định?")
                    .setPositiveButton("Khôi phục") { _, _ ->
                        applyConfig(AppConfig())
                        dlg.dismiss()
                        toast("Đã khôi phục mặc định")
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }
        dlg.show()
    }


    private fun applyConfig(newCfg: AppConfig) {
        stopLink()
        AppConfig.save(this, newCfg)
        val old = cfg
        cfg = newCfg
        vals.clear()
        busy.clear()
        modbusOnline = false
        hasData = false
        lastOk = 0L
        lastMsg = 0L
        cacheTime = 0L
        lastError = ""
        mqttMsg = ""
        if (old.broker != newCfg.broker || old.pubTopic != newCfg.pubTopic || !newCfg.isMqtt) clearCache()
        else loadCache()
        render()
        if (running) startLink()
    }

    private fun toast(s: String) = jsToast(s)
}
