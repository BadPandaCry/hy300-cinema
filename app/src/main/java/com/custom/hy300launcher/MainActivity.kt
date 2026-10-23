package com.custom.hy300launcher

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : Activity() {

    data class AppItem(val label: String, val packageName: String, val icon: Drawable)
    private class AppViewHolder(val card: LinearLayout, val iconView: ImageView, val titleView: TextView)

    private lateinit var gridView: GridView
    private lateinit var remoteBadge: TextView
    private lateinit var btnLampa: LinearLayout
    private lateinit var btnYoutube: LinearLayout
    private lateinit var btnBoost: TextView
    private lateinit var btnInputService: TextView
    private lateinit var btnKeystone: TextView
    private lateinit var btnWifi: TextView
    private lateinit var btnSettings: TextView

    private val appList = mutableListOf<AppItem>()
    private var lastPackageSignature: String = ""
    private var appAdapter: BaseAdapter? = null

    private var serverSocket: ServerSocket? = null
    private var serverExecutor: ExecutorService? = null
    @Volatile private var isServerRunning = false
    private var remotePinCode: String = "0000"

    companion object {
        private const val ID_BTN_BOOST = 1001
        private const val ID_BTN_KEYSTONE = 1002
        private const val ID_BTN_WIFI = 1003
        private const val ID_BTN_SETTINGS = 1004
        private const val ID_BTN_LAMPA = 1005
        private const val ID_BTN_YOUTUBE = 1006
        private const val ID_GRID_APPS = 1007
        private const val ID_BTN_INPUT = 1008
        private const val REMOTE_PORT = 8080
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("hy300_prefs", Context.MODE_PRIVATE)
        remotePinCode = prefs.getString("remote_pin", null) ?: run {
            val generated = Random.nextInt(1000, 9999).toString()
            prefs.edit().putString("remote_pin", generated).apply()
            generated
        }

        val bgGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#07090E"), Color.parseColor("#111622"), Color.parseColor("#05070B"))
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgGradient
            setPadding(dp(32), dp(20), dp(32), dp(16))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
        }

        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "hh:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        }

        remoteBadge = TextView(this).apply {
            text = buildRemoteBadgeText()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
        }

        clockBox.addView(clock)
        clockBox.addView(remoteBadge)

        btnBoost = createPillButton(ID_BTN_BOOST, "⚡ ОЗУ") { optimizeMemory() }
        btnInputService = createPillButton(ID_BTN_INPUT, "⌨️ Ввод") { openAccessibilitySettings() }
        btnKeystone = createPillButton(ID_BTN_KEYSTONE, "📐 Трапеция") { openProjectorKeystone() }
        btnWifi = createPillButton(ID_BTN_WIFI, "📶 Wi-Fi") { safeStartIntent(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        btnSettings = createPillButton(ID_BTN_SETTINGS, "⚙️ Настройки") { safeStartIntent(Intent(Settings.ACTION_SETTINGS)) }

        topBar.addView(clockBox)
        topBar.addView(btnBoost)
        topBar.addView(btnInputService)
        topBar.addView(btnKeystone)
        topBar.addView(btnWifi)
        topBar.addView(btnSettings)

        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        }

        btnLampa = createHeroBanner(ID_BTN_LAMPA, "ОНЛАЙН КИНОТЕАТР", "🍿 Lampa TV", "Фильмы, Сериалы, Торренты", "#2A1B0E", "#121620", "#F59E0B", dp(16)) { openLampa() }
        btnYoutube = createHeroBanner(ID_BTN_YOUTUBE, "ВИДЕО БЕЗ РЕКЛАМЫ", "▶️ YouTube", "SmartTube • Без рекламы", "#2B1115", "#121620", "#F43F5E", 0) { openYouTube() }

        setupExplicitFocusGraph()

        heroRow.addView(btnLampa)
        heroRow.addView(btnYoutube)

        val sectionTitle = TextView(this).apply {
            text = "ВСЕ ПРИЛОЖЕНИЯ"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }

        gridView = GridView(this).apply {
            id = ID_GRID_APPS
            numColumns = 6
            verticalSpacing = dp(12)
            horizontalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position in appList.indices) launchPackage(appList[position].packageName)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateGridSelectionVisuals(parent, view) }
                override fun onNothingSelected(parent: AdapterView<*>?) { updateGridSelectionVisuals(parent, null) }
            }
        }

        root.addView(topBar)
        root.addView(heroRow)
        root.addView(sectionTitle)
        root.addView(gridView)
        setContentView(root)

        loadInstalledApps(true)
        btnLampa.requestFocus()
        startPocoRemoteServer()
    }

    private fun setupExplicitFocusGraph() {
        btnBoost.nextFocusRightId = ID_BTN_INPUT
        btnBoost.nextFocusDownId = ID_BTN_LAMPA
        btnInputService.nextFocusLeftId = ID_BTN_BOOST
        btnInputService.nextFocusRightId = ID_BTN_KEYSTONE
        btnInputService.nextFocusDownId = ID_BTN_LAMPA
        btnKeystone.nextFocusLeftId = ID_BTN_INPUT
        btnKeystone.nextFocusRightId = ID_BTN_WIFI
        btnKeystone.nextFocusDownId = ID_BTN_LAMPA
        btnWifi.nextFocusLeftId = ID_BTN_KEYSTONE
        btnWifi.nextFocusRightId = ID_BTN_SETTINGS
        btnWifi.nextFocusDownId = ID_BTN_YOUTUBE
        btnSettings.nextFocusLeftId = ID_BTN_WIFI
        btnSettings.nextFocusDownId = ID_BTN_YOUTUBE
        btnLampa.nextFocusUpId = ID_BTN_BOOST
        btnLampa.nextFocusRightId = ID_BTN_YOUTUBE
        btnLampa.nextFocusDownId = ID_GRID_APPS
        btnYoutube.nextFocusUpId = ID_BTN_WIFI
        btnYoutube.nextFocusLeftId = ID_BTN_LAMPA
        btnYoutube.nextFocusDownId = ID_GRID_APPS
    }

    override fun onResume() {
        super.onResume()
        remoteBadge.text = buildRemoteBadgeText()
        val a11yActive = RemoteAccessibilityService.instance != null
        btnInputService.text = if (a11yActive) "⌨️ Ввод: ВКЛ" else "⌨️ Ввод: ВЫКЛ"
        loadInstalledApps(false)
        if (currentFocus == null) btnLampa.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPocoRemoteServer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    private fun buildRemoteBadgeText(): String {
        val ip = getLocalIpAddress()
        val a11y = if (RemoteAccessibilityService.instance != null) "✅ Ввод активен" else "⚠️ Включи «⌨️ Ввод»"
        return if (ip != null) "📱 http://$ip:$REMOTE_PORT  |  🔑 PIN: $remotePinCode  |  $a11y" else "📱 Wi-Fi не подключен"
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Включи «HY300 Пульт и Ввод текста» в списке служб", Toast.LENGTH_LONG).show()
        safeStartIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun safeStartIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Раздел недоступен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPackage(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) safeStartIntent(intent)
        else Toast.makeText(this, "Не удалось запустить", Toast.LENGTH_SHORT).show()
    }

    private fun optimizeMemory() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memBefore = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memBefore)
            for (app in packageManager.getInstalledApplications(0)) {
                if (app.packageName != packageName && !app.packageName.startsWith("com.android.systemui")) {
                    try { am.killBackgroundProcesses(app.packageName) } catch (_: Exception) {}
                }
            }
            System.gc()
            val memAfter = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memAfter)
            val availMb = memAfter.availMem / (1024 * 1024)
            val freed = ((memAfter.availMem - memBefore.availMem) / (1024 * 1024)).coerceAtLeast(0)
            Toast.makeText(this, "⚡ Освобождено: $freed МБ (Свободно: $availMb МБ)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Память оптимизирована", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLampa() {
        for (pkg in listOf("top.rootu.lampa", "ru.lampa.tv", "com.lampa.app")) {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { safeStartIntent(intent); return }
        }
        safeStartIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://lampa.mx")))
    }

    private fun openYouTube() {
        for (pkg in listOf("com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "org.smarttube.stable", "com.google.android.youtube.tv", "com.google.android.youtube")) {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { safeStartIntent(intent); return }
        }
        safeStartIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/tv")))
    }

    private fun openProjectorKeystone() {
        val known = listOf(
            ComponentName("com.softwinner.TvdSettings", "com.softwinner.TvdSettings.ProjectionSettingsActivity"),
            ComponentName("com.softwinner.TvdSettings", "com.softwinner.TvdSettings.KeystoneActivity"),
            ComponentName("com.android.tv.settings", "com.android.tv.settings.ProjectionActivity")
        )
        for (comp in known) {
            try {
                startActivity(Intent().apply { component = comp; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                return
            } catch (_: Exception) {}
        }
        safeStartIntent(Intent(Settings.ACTION_SETTINGS))
    }

    private fun loadInstalledApps(forceReload: Boolean) {
        val pm = packageManager
        val foundApps = mutableMapOf<String, AppItem>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            for (info in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN, null).apply { addCategory(cat) }, 0)) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == packageName || foundApps.containsKey(pkg)) continue
                val label = info.loadLabel(pm)?.toString() ?: pkg
                val icon = info.loadIcon(pm) ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, theme)
                foundApps[pkg] = AppItem(label, pkg, icon)
            }
        }
        val sorted = foundApps.values.sortedBy { it.label.lowercase() }
        val sig = sorted.joinToString("|") { it.packageName }
        if (!forceReload && sig == lastPackageSignature && appAdapter != null) return
        lastPackageSignature = sig
        appList.clear()
        appList.addAll(sorted)
        if (appAdapter == null) {
            appAdapter = createGridAdapter()
            gridView.adapter = appAdapter
        } else {
            appAdapter?.notifyDataSetChanged()
        }
    }

    private fun createGridAdapter(): BaseAdapter {
        return object : BaseAdapter() {
            override fun getCount(): Int = appList.size
            override fun getItem(p: Int): Any = appList[p]
            override fun getItemId(p: Int): Long = p.toLong()
            override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
                val holder: AppViewHolder
                val view: View
                if (convertView == null) {
                    val card = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(14), dp(10), dp(12))
                        background = createAppTileDrawable(false)
                    }
                    val iconView = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    }
                    val titleView = TextView(this@MainActivity).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(Color.parseColor("#CBD5E1"))
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    }
                    card.addView(iconView)
                    card.addView(titleView)
                    holder = AppViewHolder(card, iconView, titleView)
                    card.tag = holder
                    view = card
                } else {
                    view = convertView
                    holder = convertView.tag as AppViewHolder
                }
                val item = appList[p]
                holder.iconView.setImageDrawable(item.icon)
                holder.titleView.text = item.label
                val isSel = gridView.hasFocus() && gridView.selectedItemPosition == p
                applyTileVisualState(holder, isSel)
                return view
            }
        }
    }

    private fun updateGridSelectionVisuals(parent: AdapterView<*>?, selectedView: View?) {
        val gv = parent ?: return
        for (i in 0 until gv.childCount) {
            val child = gv.getChildAt(i)
            val holder = child?.tag as? AppViewHolder ?: continue
            applyTileVisualState(holder, gv.hasFocus() && (child === selectedView))
        }
    }

    private fun applyTileVisualState(holder: AppViewHolder, focused: Boolean) {
        holder.card.background = createAppTileDrawable(focused)
        holder.titleView.setTextColor(if (focused) Color.WHITE else Color.parseColor("#CBD5E1"))
        val scale = if (focused) 1.06f else 1.0f
        holder.card.scaleX = scale
        holder.card.scaleY = scale
    }

    private fun startPocoRemoteServer() {
        if (isServerRunning) return
        isServerRunning = true
        val executor = Executors.newFixedThreadPool(4)
        serverExecutor = executor
        Thread {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(REMOTE_PORT))
                serverSocket = socket
                while (isServerRunning) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    executor.execute { handleClientSocket(client) }
                }
            } catch (_: Exception) { isServerRunning = false }
        }.apply { isDaemon = true; start() }
    }

    private fun stopPocoRemoteServer() {
        isServerRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { serverExecutor?.shutdownNow() } catch (_: Exception) {}
        serverExecutor = null
    }

    private fun isPrivateLanAddress(socket: Socket): Boolean {
        val addr = socket.inetAddress as? Inet4Address ?: return false
        return addr.isSiteLocalAddress || addr.isLoopbackAddress
    }

    private fun handleClientSocket(client: Socket) {
        try {
            client.soTimeout = 3000
            if (!isPrivateLanAddress(client)) {
                sendHttpResponse(client, "403 Forbidden", "text/plain; charset=utf-8", "Forbidden")
                return
            }
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) line = reader.readLine()

            if (method != "GET") {
                sendHttpResponse(client, "405 Method Not Allowed", "text/plain; charset=utf-8", "Method Not Allowed")
                return
            }

            if (path.startsWith("/cmd")) {
                val statusMsg = handleRemoteCommand(path)
                if (statusMsg == "UNAUTHORIZED") sendHttpResponse(client, "401 Unauthorized", "text/plain; charset=utf-8", "PIN")
                else sendHttpResponse(client, "200 OK", "text/plain; charset=utf-8", statusMsg)
            } else {
                sendHttpResponse(client, "200 OK", "text/html; charset=utf-8", getRemoteHtml())
            }
        } catch (_: Exception) {
        } finally { try { client.close() } catch (_: Exception) {} }
    }

    private fun sendHttpResponse(client: Socket, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length:${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun handleRemoteCommand(path: String): String {
        val uri = Uri.parse("http://localhost$path")
        if (uri.getQueryParameter("pin") != remotePinCode) return "UNAUTHORIZED"
        val action = uri.getQueryParameter("a") ?: ""
        val textVal = uri.getQueryParameter("t") ?: ""

        when (action) {
            "up" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "ok" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_CENTER)
            "back" -> {
                val handled = RemoteAccessibilityService.instance?.triggerGlobalBack() ?: false
                if (!handled) dispatchLocalDpadKey(KeyEvent.KEYCODE_BACK)
            }
            "home" -> {
                val handled = RemoteAccessibilityService.instance?.triggerGlobalHome() ?: false
                if (!handled) runOnUiThread { safeStartIntent(Intent(this, MainActivity::class.java)) }
            }
            "volup" -> adjustVol(AudioManager.ADJUST_RAISE)
            "voldown" -> adjustVol(AudioManager.ADJUST_LOWER)
            "lampa" -> runOnUiThread { openLampa() }
            "yt" -> runOnUiThread { openYouTube() }
            "keystone" -> runOnUiThread { openProjectorKeystone() }
            "boost" -> runOnUiThread { optimizeMemory() }
            "text", "copy" -> {
                if (textVal.isNotBlank()) {
                    runOnUiThread {
                        val injected = RemoteAccessibilityService.instance?.injectText(textVal) ?: false
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("HY300", textVal))
                        if (injected) {
                            Toast.makeText(this, "⌨️ Введено: $textVal", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "📋 В буфере: $textVal (Включи «⌨️ Ввод» для автовставки)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            "ytsearch" -> {
                if (textVal.isNotBlank()) {
                    runOnUiThread {
                        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                            putExtra("query", textVal)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        val ytPackages = listOf(
                            "com.liskovsoft.smarttubetv.beta",
                            "com.liskovsoft.smarttubetv",
                            "org.smarttube.stable"
                        )
                        var launched = false
                        for (pkg in ytPackages) {
                            if (packageManager.getLaunchIntentForPackage(pkg) != null) {
                                searchIntent.setPackage(pkg)
                                try {
                                    startActivity(searchIntent)
                                    launched = true
                                    break
                                } catch (_: Exception) {}
                            }
                        }
                        if (!launched) {
                            safeStartIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(textVal))))
                        }
                    }
                }
            }
        }
        return "OK"
    }

    private fun dispatchLocalDpadKey(keyCode: Int) {
        runOnUiThread {
            try {
                val rootView = window?.decorView ?: return@runOnUiThread
                rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                rootView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            } catch (_: Exception) {}
        }
    }

    private fun adjustVol(dir: Int) {
        runOnUiThread {
            try {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            } catch (_: Exception) {}
        }
    }

    private fun getRemoteHtml(): String = """
        <!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <title>HY300 Smart Remote</title>
        <style>
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent;}
            body{background:radial-gradient(circle at top,#131B2E 0%,#07090F 100%);color:#F8FAFC;font-family:-apple-system,BlinkMacSystemFont,sans-serif;text-align:center;margin:0;padding:14px;user-select:none;min-height:100vh;}
            .card{background:rgba(22,29,43,0.75);border:1px solid #263147;border-radius:18px;padding:12px;margin-bottom:10px;box-shadow:0 8px 20px rgba(0,0,0,0.4);}
            .header{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;padding:0 4px;}
            .brand{font-size:15px;font-weight:800;color:#38BDF8;letter-spacing:0.8px;}
            .status{font-size:12px;font-weight:600;color:#94A3B8;background:#111622;padding:6px 12px;border-radius:20px;border:1px solid #1E293B;}
            .row{display:flex;gap:8px;justify-content:center;margin-bottom:8px;}
            .row:last-child{margin-bottom:0;}
            button{background:#161D2B;color:#F1F5F9;border:1.5px solid #28354E;border-radius:14px;padding:13px 10px;font-size:14px;font-weight:700;flex:1;transition:transform 0.08s,background 0.15s;}
            button:active{transform:scale(0.96);background:#25324B;border-color:#38BDF8;}
            .lampa{background:linear-gradient(135deg,#331F0E,#1A1412);border-color:#F59E0B;color:#FDE68A;}
            .yt{background:linear-gradient(135deg,#351219,#1A1216);border-color:#F43F5E;color:#FECDD3;}
            .boost{background:linear-gradient(135deg,#0D2E2B,#111C24);border-color:#10B981;color:#A7F3D0;}
            .send-btn{background:linear-gradient(135deg,#0284C7,#0369A1);border-color:#38BDF8;color:#fff;}
            .dpad{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;max-width:260px;margin:6px auto;}
            .dpad button{height:64px;font-size:20px;border-radius:18px;}
            .ok{background:linear-gradient(135deg,#0EA5E9,#0284C7);border-color:#7DD3FC;color:#fff;font-size:17px !important;box-shadow:0 0 15px rgba(14,165,233,0.3);}
            input{width:100%;padding:13px 14px;border-radius:13px;border:1.5px solid #334155;background:#0B0F19;color:#fff;font-size:15px;outline:none;}
            input:focus{border-color:#38BDF8;}
            .pin-box{max-width:110px;text-align:center;letter-spacing:3px;font-weight:800;color:#38BDF8;padding:8px;}
        </style></head><body>
        <div class="header">
            <div class="brand">🎬 HY300 CINEMA</div>
            <input id="pin" class="pin-box" type="tel" maxlength="4" placeholder="PIN">
            <div id="st" class="status">Введи PIN</div>
        </div>

        <div class="card">
            <div class="row">
                <input id="txt" placeholder="Нажми на поиск в Lampa и пиши тут..." onkeydown="if(event.key==='Enter')st()">
            </div>
            <div class="row">
                <button class="send-btn" onclick="st()">⌨️ Вставить в поле</button>
                <button class="yt" onclick="sendText('ytsearch')">🔍 YouTube поиск</button>
            </div>
        </div>

        <div class="card">
            <div class="row">
                <button class="lampa" onclick="c('lampa')">🍿 Lampa TV</button>
                <button class="yt" onclick="c('yt')">▶️ SmartTube</button>
            </div>
            <div class="row">
                <button class="boost" onclick="c('boost')">⚡ Очистить ОЗУ</button>
                <button onclick="c('keystone')">📐 Трапеция</button>
            </div>
        </div>

        <div class="card">
            <div class="dpad">
                <div></div><button onclick="c('up')">▲</button><div></div>
                <button onclick="c('left')">◄</button><button class="ok" onclick="c('ok')">OK</button><button onclick="c('right')">►</button>
                <div></div><button onclick="c('down')">▼</button><div></div>
            </div>
            <div class="row" style="margin-top:10px;">
                <button onclick="c('back')">🔙 Назад</button>
                <button onclick="c('home')">🏠 Домой</button>
            </div>
            <div class="row">
                <button onclick="c('voldown')">🔉 Тише</button>
                <button onclick="c('volup')">🔊 Громче</button>
            </div>
        </div>

        <script>
            var pinInput = document.getElementById('pin');
            var stEl = document.getElementById('st');
            pinInput.value = localStorage.getItem('hy300_pin') || '';
            pinInput.addEventListener('input', function(){ localStorage.setItem('hy300_pin', pinInput.value.trim()); });
            function getPin(){ var p = pinInput.value.trim(); localStorage.setItem('hy300_pin', p); return encodeURIComponent(p); }
            function c(a){
                fetch('/cmd?pin=' + getPin() + '&a=' + encodeURIComponent(a)).then(function(r){
                    stEl.textContent = (r.status === 401) ? '❌ PIN неверный' : '✅ ОК';
                    stEl.style.color = (r.status === 401) ? '#F43F5E' : '#10B981';
                }).catch(function(){ stEl.textContent = '⚠️ Нет связи'; stEl.style.color = '#F59E0B'; });
            }
            function st(){
                var el = document.getElementById('txt');
                var v = el.value.trim();
                if(!v) return;
                fetch('/cmd?pin=' + getPin() + '&a=text&t=' + encodeURIComponent(v)).then(function(r){
                    if(r.status === 200){ el.value = ''; stEl.textContent = '⌨️ Введено!'; stEl.style.color = '#38BDF8'; }
                    else if(r.status === 401){ stEl.textContent = '❌ PIN неверный'; stEl.style.color = '#F43F5E'; }
                }).catch(function(){ stEl.textContent = '⚠️ Нет связи'; stEl.style.color = '#F59E0B'; });
            }
            function sendText(mode){
                var el = document.getElementById('txt');
                var v = el.value.trim();
                if(!v) return;
                fetch('/cmd?pin=' + getPin() + '&a=' + encodeURIComponent(mode) + '&t=' + encodeURIComponent(v)).then(function(r){
                    if(r.status === 200){ el.value = ''; stEl.textContent = '🔍 Запущено!'; stEl.style.color = '#10B981'; }
                    else if(r.status === 401){ stEl.textContent = '❌ PIN неверный'; stEl.style.color = '#F43F5E'; }
                });
            }
        </script></body></html>
    """.trimIndent()

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun createHeroBanner(
        viewId: Int, badge: String, title: String, subtitle: String,
        colorStart: String, colorEnd: String, accentHex: String,
        rightMarginPx: Int, onClick: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            id = viewId
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            isFocusable = true
            isClickable = true
            background = createBannerDrawable(false, colorStart, colorEnd, accentHex)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = rightMarginPx }
        }
        val badgeView = TextView(this).apply {
            text = badge; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f; setTextColor(Color.parseColor(accentHex))
        }
        val titleView = TextView(this).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        val subView = TextView(this).apply {
            text = subtitle; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        card.addView(badgeView); card.addView(titleView); card.addView(subView)
        card.setOnFocusChangeListener { v, hasFocus ->
            v.background = createBannerDrawable(hasFocus, colorStart, colorEnd, accentHex)
            v.animate().scaleX(if (hasFocus) 1.03f else 1.0f).scaleY(if (hasFocus) 1.03f else 1.0f).setDuration(120).start()
        }
        card.setOnClickListener { onClick() }
        return card
    }

    private fun createBannerDrawable(focused: Boolean, startHex: String, endHex: String, accentHex: String): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(if (focused) dp(3) else dp(1), Color.parseColor(if (focused) accentHex else "#1E293B"))
        }
    }

    private fun createPillButton(viewId: Int, title: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            id = viewId
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(14), dp(10), dp(14), dp(10)); isFocusable = true; isClickable = true; background = createGlassDrawable(false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = createGlassDrawable(hasFocus)
                setTextColor(if (hasFocus) Color.WHITE else Color.parseColor("#E2E8F0"))
                v.animate
