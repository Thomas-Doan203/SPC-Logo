package vn.chieusang.logo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.SocketFactory

class MainActivity : Activity() {

    // Màu "mặt tủ điện"
    private val cEnclosure = Color.parseColor("#D7D7D1")
    private val cPanel = Color.parseColor("#C4C5BF")
    private val cPlate = Color.parseColor("#F6F6F1")
    private val cInk = Color.parseColor("#1F2326")
    private val cInkSoft = Color.parseColor("#555A5C")
    private val cLampOn = Color.parseColor("#22A447")
    private val cLampOff = Color.parseColor("#7D817C")
    private val cLampIn = Color.parseColor("#F0B400")
    private val cAlarm = Color.parseColor("#C62F28")
    private val cLine = Color.parseColor("#A9AAA4")
    private val cBtn = Color.parseColor("#2B3033")
    private val cRowOn = Color.parseColor("#CFE6CC")
    private val cCellOn = Color.parseColor("#EDE0AE")
    private val cRowWarn = Color.parseColor("#EFCFCA")

    // Hiệu ứng: màu đang hiển thị của từng ô, các hoạt ảnh đang chạy
    private val shownColor = HashMap<View, Int>()
    private val colorAnims = HashMap<View, ValueAnimator>()
    private val pulses = HashMap<Button, ObjectAnimator>()
    private var dotPulse: ObjectAnimator? = null

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

    private class OutRow(val row: View, val lamp: View, val sub: TextView, val btn: Button)
    private class ModeCard(val row: View, val value: TextView, val hint: TextView, val btn: Button)

    private var modeCard: ModeCard? = null
    private class InCell(val cell: View, val lamp: View, val btn: Button)
    private class ParamRow(val value: TextView, val btn: Button)

    private val outRows = ArrayList<OutRow>()
    private val inCells = ArrayList<InCell>()
    private val paramRows = ArrayList<ParamRow>()

    private lateinit var titleView: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var footer: TextView
    private lateinit var content: LinearLayout

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
        window.statusBarColor = cEnclosure
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildShell()
        buildContent()
        if (cfg.isMqtt) loadCache()
        render()
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

    private fun toggleMode() {
        val conf = cfg
        if (conf.modeM <= 0) return
        val next = manualMode() != true
        val q = if (next)
            "Chuyển sang chế độ TAY?\nĐèn sẽ theo nút bấm trên app, hẹn giờ tạm ngưng."
        else
            "Chuyển về chế độ TỰ ĐỘNG?\nĐèn sẽ chạy theo hẹn giờ của LOGO!."
        sendCommand("mode", q) { writeBit(conf.modeM, next) }
    }

    private fun toggleOutput(idx: Int) {
        val conf = cfg
        if (manualMode() == false) {
            toast("Đang ở chế độ Tự động. Chuyển sang chế độ Tay để bật/tắt bằng app.")
            return
        }
        val m = conf.outCmdM + idx
        val cur = bit("M$m") ?: bit("Q${idx + 1}") ?: false
        val next = !cur
        val name = conf.outNames[idx]
        sendCommand("q$idx", (if (next) "Bật " else "Tắt ") + "\"$name\"?") {
            writeBit(m, next)
        }
    }

    private fun pulseInput(idx: Int) {
        val conf = cfg
        val m = conf.inCmdM + idx
        val ms = conf.pulseMs.coerceIn(100, 10000).toLong()
        val name = conf.inNames[idx]
        sendCommand("i$idx", "Kích \"$name\" trong $ms ms?") {
            writeBit(m, true)
            try {
                Thread.sleep(ms)
            } finally {
                writeBit(m, false)
            }
        }
    }

    private fun editParam(k: Int) {
        if (!isOnline()) {
            toast("LOGO! đang offline")
            return
        }
        val p = cfg.params[k]
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        input.setText(vals[paramKey(p)]?.toInt()?.toString() ?: "")
        input.setSelectAllOnFocus(true)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(p.name + if (p.unit.isNotEmpty()) " (${p.unit})" else "")
            .setMessage("Từ ${p.min} đến ${p.max}")
            .setView(box)
            .setPositiveButton("Ghi") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < p.min || v > p.max) {
                    toast("Giá trị phải từ ${p.min} đến ${p.max}")
                } else {
                    sendCommand("p$k", null) { writeParam(p, v) }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ---------------------------------------------------------------- giao diện

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(1), strokeColor)
        return d
    }

    private fun circle(color: Int, sizeDp: Int, ringDp: Int): View {
        val v = View(this)
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        d.setStroke(dp(ringDp), Color.parseColor("#9A9C97"))
        v.background = d
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    /** Đổi màu nền mượt (350 ms). [pop] = nảy nhẹ khi chuyển sang trạng thái sáng. */
    private fun fadeColor(v: View, d: GradientDrawable, color: Int, pop: Boolean = false) {
        val old = shownColor[v]
        if (old == color) return
        shownColor[v] = color
        colorAnims.remove(v)?.cancel()
        if (old == null) {
            d.setColor(color)
            return
        }
        val a = ValueAnimator.ofArgb(old, color)
        a.duration = 350
        a.addUpdateListener { d.setColor(it.animatedValue as Int) }
        a.start()
        colorAnims[v] = a
        if (pop) {
            v.scaleX = 0.82f
            v.scaleY = 0.82f
            v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(4f)).setDuration(420).start()
        }
    }

    private fun setLamp(v: View, color: Int) {
        fadeColor(v, v.background as GradientDrawable, color, pop = color == cLampOn || color == cLampIn)
    }

    /** Nút nhún xuống khi chạm, bật lại khi thả. */
    private fun addPressEffect(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.92f).scaleY(0.92f).setInterpolator(DecelerateInterpolator()).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(280).start()
            }
            false
        }
    }

    /** Bấm: rung nhẹ rồi thực hiện. */
    private fun View.onTap(action: () -> Unit) {
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    /** Chữ đổi nội dung thì nảy nhẹ để người dùng thấy. */
    private fun setTextPop(t: TextView, s: String) {
        if (t.text.toString() == s) return
        val first = t.text.isNullOrEmpty() || t.text.toString() == "–"
        t.text = s
        if (!first) {
            t.scaleX = 1.18f
            t.scaleY = 1.18f
            t.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(360).start()
        }
    }

    private fun setStatusPulse(on: Boolean) {
        if (on && dotPulse == null) {
            val a = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.25f)
            a.duration = 600
            a.repeatMode = ValueAnimator.REVERSE
            a.repeatCount = ValueAnimator.INFINITE
            a.start()
            dotPulse = a
        } else if (!on && dotPulse != null) {
            dotPulse?.cancel()
            dotPulse = null
            statusDot.alpha = 1f
        }
    }

    private fun animateEntrance() {
        for (i in 0 until content.childCount) {
            val c = content.getChildAt(i)
            c.alpha = 0f
            c.translationY = dp(18).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(i * 35L)
                .setDuration(320).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun pushButton(label: String, minWidthDp: Int, heightDp: Int): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.typeface = Typeface.DEFAULT_BOLD
        val shape = rounded(cBtn, 6)
        b.background = RippleDrawable(ColorStateList.valueOf(Color.argb(90, 255, 255, 255)), shape, null)
        b.tag = shape
        addPressEffect(b)
        b.minWidth = dp(minWidthDp)
        b.minimumWidth = dp(minWidthDp)
        b.minHeight = dp(heightDp)
        b.minimumHeight = dp(heightDp)
        b.stateListAnimator = null
        b.setPadding(dp(12), 0, dp(12), 0)
        return b
    }

    /** [pulse] = nút nhấp nháy nhẹ trong lúc chờ LOGO! phản hồi. */
    private fun setButton(b: Button, label: String, color: Int, textColor: Int, enabled: Boolean, pulse: Boolean = false) {
        b.text = label
        val shape = b.tag as? GradientDrawable
        if (shape != null) fadeColor(b, shape, color)
        b.setTextColor(textColor)
        b.isEnabled = enabled
        if (pulse) {
            if (!pulses.containsKey(b)) {
                val a = ObjectAnimator.ofFloat(b, "alpha", 1f, 0.5f)
                a.duration = 450
                a.repeatMode = ValueAnimator.REVERSE
                a.repeatCount = ValueAnimator.INFINITE
                a.start()
                pulses[b] = a
            }
        } else {
            pulses.remove(b)?.cancel()
            b.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun sectionTitle(s: String): TextView {
        val t = text(s, 16f, cInkSoft, true)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), dp(18), dp(4), dp(8))
        t.layoutParams = lp
        return t
    }

    private fun buildShell() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cEnclosure)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(18), dp(12), dp(10), dp(10))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        titleView = text(cfg.title, 21f, cInk, true)
        left.addView(titleView)
        val st = LinearLayout(this)
        st.orientation = LinearLayout.HORIZONTAL
        st.gravity = Gravity.CENTER_VERTICAL
        statusDot = circle(cLampOff, 11, 0)
        st.addView(statusDot)
        statusText = text("Đang kết nối…", 14f, cInkSoft)
        statusText.setPadding(dp(7), 0, 0, 0)
        st.addView(statusText)
        left.addView(st)
        header.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val settings = Button(this)
        settings.text = "Cài đặt"
        settings.isAllCaps = false
        settings.setTextColor(cInk)
        settings.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(50, 0, 0, 0)), rounded(cPlate, 6, cLine), null
        )
        settings.stateListAnimator = null
        settings.minHeight = dp(44)
        settings.minimumHeight = dp(44)
        addPressEffect(settings)
        settings.onTap { showSettings() }
        header.addView(settings)
        root.addView(header)

        val line = View(this)
        line.setBackgroundColor(cLine)
        root.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        val scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14), dp(4), dp(14), dp(28))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun buildContent() {
        val conf = cfg
        titleView.text = conf.title
        content.removeAllViews()
        outRows.clear()
        inCells.clear()
        paramRows.clear()
        modeCard = null
        for (a in pulses.values) a.cancel()
        pulses.clear()
        for (a in colorAnims.values) a.cancel()
        colorAnims.clear()
        shownColor.clear()

        // Chế độ Tự động / Tay
        if (conf.modeM > 0) {
            content.addView(sectionTitle("Chế độ điều khiển"))
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(cPanel, 6, cLine)
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val plate = LinearLayout(this)
            plate.orientation = LinearLayout.VERTICAL
            plate.background = rounded(cPlate, 3, cLine)
            plate.setPadding(dp(10), dp(6), dp(10), dp(6))
            val value = text("–", 20f, cInk, true)
            plate.addView(value)
            val hint = text("", 13f, cInkSoft)
            plate.addView(hint)
            val plp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            plp.rightMargin = dp(12)
            row.addView(plate, plp)
            val btn = pushButton("Chuyển", 96, 52)
            btn.onTap { toggleMode() }
            row.addView(btn)
            content.addView(row)
            modeCard = ModeCard(row, value, hint, btn)
        }

        // Ngõ ra
        content.addView(sectionTitle("Ngõ ra (đèn)"))
        conf.outNames.forEachIndexed { idx, name ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(cPanel, 6, cLine)
            row.setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            row.layoutParams = lp

            val lamp = circle(cLampOff, 44, 5)
            row.addView(lamp)

            val plate = LinearLayout(this)
            plate.orientation = LinearLayout.VERTICAL
            plate.background = rounded(cPlate, 3, cLine)
            plate.setPadding(dp(10), dp(6), dp(10), dp(6))
            val nameView = text(name, 17f, cInk, true)
            nameView.maxLines = 1
            plate.addView(nameView)
            val sub = text("Q${idx + 1}", 13f, cInkSoft)
            plate.addView(sub)
            val plp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            plp.setMargins(dp(12), 0, dp(12), 0)
            row.addView(plate, plp)

            val btn = pushButton("Bật", 84, 52)
            btn.onTap { toggleOutput(idx) }
            row.addView(btn)
            content.addView(row)
            outRows.add(OutRow(row, lamp, sub, btn))
        }

        // Ngõ vào, 4 ô mỗi hàng
        if (conf.inNames.isNotEmpty()) {
            content.addView(sectionTitle("Ngõ vào"))
            val hint = text(
                "Đèn là trạng thái ngõ vào thật. Nút Kích gửi xung vào cờ M tương ứng trong chương trình.",
                13f, cInkSoft
            )
            hint.setPadding(dp(4), 0, dp(4), dp(8))
            content.addView(hint)
            var rowLayout: LinearLayout? = null
            conf.inNames.forEachIndexed { idx, name ->
                if (idx % 4 == 0) {
                    val r = LinearLayout(this)
                    r.orientation = LinearLayout.HORIZONTAL
                    val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    rlp.bottomMargin = dp(8)
                    content.addView(r, rlp)
                    rowLayout = r
                }
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.background = rounded(cPanel, 6, cLine)
                cell.setPadding(dp(4), dp(10), dp(4), dp(6))
                val lamp = circle(cLampOff, 20, 0)
                cell.addView(lamp)
                val nv = text(name, 13f, cInk, true)
                nv.maxLines = 1
                nv.gravity = Gravity.CENTER
                nv.setPadding(0, dp(4), 0, 0)
                cell.addView(nv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                cell.addView(text("I${idx + 1}", 12f, cInkSoft))
                val btn = pushButton("Kích", 0, 38)
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                btn.onTap { pulseInput(idx) }
                val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
                blp.topMargin = dp(4)
                cell.addView(btn, blp)
                val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                clp.setMargins(dp(3), 0, dp(3), 0)
                rowLayout?.addView(cell, clp)
                inCells.add(InCell(cell, lamp, btn))
            }
            val rest = (4 - conf.inNames.size % 4) % 4
            repeat(rest) {
                val sp = View(this)
                val slp = LinearLayout.LayoutParams(0, 1, 1f)
                slp.setMargins(dp(3), 0, dp(3), 0)
                rowLayout?.addView(sp, slp)
            }
        }

        // Thông số
        if (conf.params.isNotEmpty()) {
            content.addView(sectionTitle("Thông số"))
            conf.params.forEachIndexed { k, p ->
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.background = rounded(cPanel, 6, cLine)
                row.setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(10)
                row.layoutParams = lp
                val plate = LinearLayout(this)
                plate.orientation = LinearLayout.VERTICAL
                plate.background = rounded(cPlate, 3, cLine)
                plate.setPadding(dp(10), dp(6), dp(10), dp(6))
                plate.addView(text("${p.name} · VW${p.vw}", 15f, cInk, true))
                val value = text("–", 24f, cInk, true)
                plate.addView(value)
                val plp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                plp.rightMargin = dp(12)
                row.addView(plate, plp)
                val btn = pushButton("Sửa", 72, 46)
                btn.onTap { editParam(k) }
                row.addView(btn)
                content.addView(row)
                paramRows.add(ParamRow(value, btn))
            }
        }

        footer = text("", 13f, cInkSoft)
        footer.setPadding(dp(4), dp(14), dp(4), 0)
        content.addView(footer)
        animateEntrance()
    }

    private fun render() {
        val conf = cfg
        val online = isOnline()
        val time = SimpleDateFormat("HH:mm:ss dd/MM", Locale("vi", "VN"))

        // Dòng trạng thái
        if (conf.isMqtt) {
            when {
                online -> {
                    setLamp(statusDot, cLampOn)
                    statusText.text = "LOGO! online · qua cloud"
                }
                mqttConnected && lastMsg == 0L -> {
                    setLamp(statusDot, cLampIn)
                    statusText.text = "Đã nối máy chủ, chờ LOGO!…"
                }
                mqttConnected -> {
                    setLamp(statusDot, cAlarm)
                    statusText.text = "LOGO! mất tín hiệu"
                }
                else -> {
                    setLamp(statusDot, cAlarm)
                    statusText.text = "Máy chủ: chưa kết nối"
                }
            }
        } else {
            when {
                online -> {
                    setLamp(statusDot, cLampOn)
                    statusText.text = "LOGO! online · ${conf.ip}"
                }
                hasData -> {
                    setLamp(statusDot, cAlarm)
                    statusText.text = "Mất kết nối · ${conf.ip}"
                }
                else -> {
                    setLamp(statusDot, cLampIn)
                    statusText.text = "Đang kết nối ${conf.ip}…"
                }
            }
        }

        setStatusPulse(shownColor[statusDot] == cLampIn)

        val manual = manualMode()
        modeCard?.let { mc ->
            val known = bit("M${conf.modeM}") != null
            when {
                !known -> {
                    setTextPop(mc.value, "Chưa có dữ liệu")
                    mc.hint.text = "Cờ M${conf.modeM}"
                }
                manual == true -> {
                    setTextPop(mc.value, "TAY")
                    mc.hint.text = "Đèn theo nút bấm trên app"
                }
                else -> {
                    setTextPop(mc.value, "TỰ ĐỘNG")
                    mc.hint.text = "Đèn theo hẹn giờ của LOGO!"
                }
            }
            mc.value.setTextColor(if (manual == true) cAlarm else cInk)
            fadeColor(mc.row, mc.row.background as GradientDrawable, if (manual == true) cRowWarn else cPanel)
            if (busy.contains("mode")) setButton(mc.btn, "Chờ…", cLampIn, cInk, false, true)
            else setButton(mc.btn, if (manual == true) "Về Tự động" else "Sang Tay", cBtn, Color.WHITE, online)
        }

        outRows.forEachIndexed { idx, r ->
            val q = bit("Q${idx + 1}")
            val mKnown = bit("M${conf.outCmdM + idx}")
            val m = mKnown ?: false
            val b = busy.contains("q$idx")
            val fbNo = conf.fbFor(idx)
            val fb = if (fbNo > 0) bit("I$fbNo") else null
            setLamp(r.lamp, if (q == true) cLampOn else cLampOff)

            var warn = false
            val state = when {
                q == null -> "chưa có dữ liệu"
                q && fb == false -> { warn = true; "ĐANG BẬT nhưng không có điện nhánh (I$fbNo)" }
                !q && fb == true -> { warn = true; "ĐANG TẮT nhưng nhánh vẫn có điện (I$fbNo)" }
                q -> "đèn đang SÁNG"
                else -> "đèn đang TẮT"
            }
            val extra = when {
                q == null -> ""
                manual == false -> " · tự động"
                manual == true && m != q -> " · đã ra lệnh " + (if (m) "bật" else "tắt") + ", chờ"
                manual == true -> " · tay"
                q && mKnown == false -> " · bật từ tủ"
                else -> ""
            }
            r.sub.text = "Q${idx + 1} · $state$extra"
            r.sub.setTextColor(if (warn) cAlarm else cInkSoft)
            fadeColor(
                r.row, r.row.background as GradientDrawable,
                when {
                    warn -> cRowWarn
                    q == true -> cRowOn
                    else -> cPanel
                }
            )

            val cmdOn = mKnown ?: (q ?: false)
            when {
                b -> setButton(r.btn, "Chờ…", cLampIn, cInk, false, true)
                manual == false -> setButton(r.btn, "Tự động", cLampOff, Color.WHITE, false)
                cmdOn -> setButton(r.btn, "Tắt", cAlarm, Color.WHITE, online)
                else -> setButton(r.btn, "Bật", cBtn, Color.WHITE, online)
            }
        }

        inCells.forEachIndexed { idx, c ->
            val v = bit("I${idx + 1}") == true
            val b = busy.contains("i$idx") || bit("M${conf.inCmdM + idx}") == true
            setLamp(c.lamp, if (v) cLampIn else cLampOff)
            fadeColor(c.cell, c.cell.background as GradientDrawable, if (v) cCellOn else cPanel)
            if (b) setButton(c.btn, "Đang kích", cLampIn, cInk, false, true)
            else setButton(c.btn, "Kích", cBtn, Color.WHITE, online)
        }

        conf.params.forEachIndexed { k, p ->
            val r = paramRows.getOrNull(k) ?: return@forEachIndexed
            val v = vals[paramKey(p)]?.toInt()
            setTextPop(r.value, if (v == null) "–" else if (p.unit.isEmpty()) "$v" else "$v ${p.unit}")
            if (busy.contains("p$k")) setButton(r.btn, "Chờ…", cLampIn, cInk, false, true)
            else setButton(r.btn, "Sửa", cBtn, Color.WHITE, online)
        }

        val err = if (conf.isMqtt) (if (!online && !mqttConnected) mqttMsg else "") else (if (!online) lastError else "")
        footer.text = buildString {
            if (lastOk > 0) {
                append(if (cacheTime > 0 && cacheTime == lastOk) "Giá trị lưu lần trước, lúc " else "Dữ liệu lúc ")
                append(time.format(Date(lastOk)))
            } else {
                append("Chưa nhận dữ liệu từ LOGO!")
            }
            if (conf.isMqtt && lastMsg > 0) append("\nTín hiệu LOGO! gần nhất: ").append(time.format(Date(lastMsg)))
            if (conf.isMqtt && online && lastOk == 0L) append("\nLOGO! chỉ gửi biến khi có thay đổi. Bấm thử một nút hoặc chờ tủ đổi trạng thái.")
            if (err.isNotEmpty()) append("\n").append(err)
        }
        footer.setTextColor(if (err.isNotEmpty()) cAlarm else cInkSoft)
    }

    // ---------------------------------------------------------------- cài đặt

    private fun label(parent: LinearLayout, s: String, topDp: Int = 10) {
        val l = text(s, 13f, cInkSoft)
        l.setPadding(0, dp(topDp), 0, dp(2))
        parent.addView(l)
    }

    private fun heading(parent: LinearLayout, s: String) {
        val h = text(s, 16f, cInk, true)
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
        buildContent()
        render()
        if (running) startLink()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
