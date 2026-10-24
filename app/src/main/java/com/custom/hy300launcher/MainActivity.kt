package com.custom.hy300launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
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
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
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
    private lateinit var btnPrank: LinearLayout
    private lateinit var btnBoost: TextView
    private lateinit var btnInputService: TextView
    private lateinit var btnKeystone: TextView
    private lateinit var btnWifi: TextView
    private lateinit var btnSettings: TextView

    private var loveDialog: Dialog? = null
    private var sleepDialog: Dialog? = null

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
        private const val ID_BTN_PRANK = 1009
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
        btnInputService = createPillButton(ID_BTN_INPUT, "⌨️ Клавиатура") { setupWifiKeyboard() }
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

        btnLampa = createHeroBanner(ID_BTN_LAMPA, "ОНЛАЙН КИНОТЕАТР", "🍿 Lampa TV", "Фильмы, Сериалы, Торренты", "#2A1B0E", "#121620", "#F59E0B", dp(12)) { openLampa() }
        btnYoutube = createHeroBanner(ID_BTN_YOUTUBE, "ВИДЕО БЕЗ РЕКЛАМЫ", "▶️ YouTube", "SmartTube • 1080p", "#2B1115", "#121620", "#F43F5E", dp(12)) { openYouTube() }
        btnPrank = createHeroBanner(ID_BTN_PRANK, "VIP КАТАЛОГ 18+", "🔥 PornHub", "Эксклюзивный доступ • 4K", "#1F1408", "#0A0A0C", "#FF9000", 0) { showDianaLoveScreen() }

        setupExplicitFocusGraph()

        heroRow.addView(btnLampa)
        heroRow.addView(btnYoutube)
        heroRow.addView(btnPrank)

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
        handleIncomingCommandIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingCommandIntent(intent)
    }

    private fun handleIncomingCommandIntent(intent: Intent?) {
        when (intent?.getStringExtra("special_overlay")) {
            "diana_love" -> showDianaLoveScreen()
            "sleep_blackout" -> toggleSleepBlackout()
        }
    }

    private fun setupExplicitFocusGraph() {
        btnBoost.nextFocusRightId = ID_BTN_INPUT
        btnBoost.nextFocusDownId = ID_BTN_LAMPA
        btnInputService.nextFocusLeftId = ID_BTN_BOOST
        btnInputService.nextFocusRightId = ID_BTN_KEYSTONE
        btnInputService.nextFocusDownId = ID_BTN_LAMPA
        btnKeystone.nextFocusLeftId = ID_BTN_INPUT
        btnKeystone.nextFocusRightId = ID_BTN_WIFI
        btnKeystone.nextFocusDownId = ID_BTN_YOUTUBE
        btnWifi.nextFocusLeftId = ID_BTN_KEYSTONE
        btnWifi.nextFocusRightId = ID_BTN_SETTINGS
        btnWifi.nextFocusDownId = ID_BTN_YOUTUBE
        btnSettings.nextFocusLeftId = ID_BTN_WIFI
        btnSettings.nextFocusDownId = ID_BTN_PRANK

        btnLampa.nextFocusUpId = ID_BTN_BOOST
        btnLampa.nextFocusRightId = ID_BTN_YOUTUBE
        btnLampa.nextFocusDownId = ID_GRID_APPS

        btnYoutube.nextFocusUpId = ID_BTN_WIFI
        btnYoutube.nextFocusLeftId = ID_BTN_LAMPA
        btnYoutube.nextFocusRightId = ID_BTN_PRANK
        btnYoutube.nextFocusDownId = ID_GRID_APPS

        btnPrank.nextFocusUpId = ID_BTN_SETTINGS
        btnPrank.nextFocusLeftId = ID_BTN_YOUTUBE
        btnPrank.nextFocusDownId = ID_GRID_APPS
    }

    override fun onResume() {
        super.onResume()
        remoteBadge.text = buildRemoteBadgeText()
        btnInputService.text = if (isWifiKeyboardSelected()) "⌨️ Клава: ВКЛ" else "⌨️ Клава: ВЫКЛ"
        loadInstalledApps(false)
        if (currentFocus == null) btnLampa.requestFocus()
    }

    override fun onDestroy() {
        loveDialog?.dismiss()
        sleepDialog?.dismiss()
        super.onDestroy()
        stopPocoRemoteServer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (loveDialog?.isShowing == true) {
            loveDialog?.dismiss()
            return
        }
        if (sleepDialog?.isShowing == true) {
            sleepDialog?.dismiss()
            return
        }
    }

    private fun triggerDianaLoveFromRemote() {
        runOnUiThread {
            if (!hasWindowFocus()) {
                val bringToFront = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("special_overlay", "diana_love")
                }
                startActivity(bringToFront)
            } else {
                showDianaLoveScreen()
            }
        }
    }

    private fun showDianaLoveScreen() {
        sleepDialog?.dismiss()
        if (loveDialog?.isShowing == true) {
            loveDialog?.dismiss()
            return
        }

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val pinkBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#FF758F"),
                Color.parseColor("#FF4D6D"),
                Color.parseColor("#C9184A")
            )
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pinkBg
            setPadding(dp(40), dp(40), dp(40), dp(40))
            isFocusable = true
            isClickable = true
            setOnClickListener { dialog.dismiss() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK)
                ) {
                    dialog.dismiss()
                    true
                } else false
            }
        }

        val heartsTop = TextView(this).apply {
            text = "💖 ✨ 💕 ✨ 💖"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(20)
            }
        }

        val mainLoveText = TextView(this).apply {
            text = "Я люблю тебя,\nДиана ❤️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 54f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(18f, 0f, 6f, Color.parseColor("#800F2F"))
        }

        val subText = TextView(this).apply {
            text = "Ты самая лучшая на свете! 🥰\n(Нажми ОК, чтобы вернуться к фильмам)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#FFE5EC"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(28)
            }
        }

        container.addView(heartsTop)
        container.addView(mainLoveText)
        container.addView(subText)

        dialog.setContentView(container)
        dialog.show()
        container.requestFocus()
        loveDialog = dialog
    }

    private fun triggerPowerOffOrSleep() {
        Thread {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 26"))
            } catch (_: Exception) {}
        }.start()

        RemoteKeyboardService.instance?.sendKey(KeyEvent.KEYCODE_SLEEP)
        RemoteKeyboardService.instance?.sendKey(KeyEvent.KEYCODE_POWER)

        runOnUiThread {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } catch (_: Exception) {}

            if (!hasWindowFocus()) {
                val bringToFront = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("special_overlay", "sleep_blackout")
                }
                startActivity(bringToFront)
            } else {
                toggleSleepBlackout()
            }
        }
    }

    private fun toggleSleepBlackout() {
        loveDialog?.dismiss()
        if (sleepDialog?.isShowing == true) {
            sleepDialog?.dismiss()
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) {}
            return
        }

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            val lp = attributes
            lp.screenBrightness = 0.0f
            attributes = lp
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        val blackView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                try {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
            }
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    dialog.dismiss()
                    try {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: Exception) {}
                    true
                } else false
            }
        }

        dialog.setContentView(blackView)
        dialog.show()
        blackView.requestFocus()
        sleepDialog = dialog
        Toast.makeText(this, "🌙 Спящий режим (нажми любую кнопку для пробуждения)", Toast.LENGTH_SHORT).show()
    }

    private fun isWifiKeyboardEnabled(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    private fun isWifiKeyboardSelected(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
            current.contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun setupWifiKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!isWifiKeyboardEnabled()) {
            Toast.makeText(this, "Шаг 1: Включи тумблер «HY300 Wi-Fi Клавиатура»", Toast.LENGTH_LONG).show()
            safeStartIntent(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } else {
            Toast.makeText(this, "Шаг 2: Выбери «HY300 Wi-Fi Клавиатура»", Toast.LENGTH_LONG).show()
            imm.showInputMethodPicker()
        }
    }

    private fun buildRemoteBadgeText(): String {
        val ip = getLocalIpAddress()
        val kbStatus = if (isWifiKeyboardSelected()) "✅ Wi-Fi Клава активна" else "⚠️ Нажми «⌨️ Клава»"
        return if (ip != null) "📱 http://$ip:$REMOTE_PORT  |  🔑 PIN: $remotePinCode  |  $kbStatus" else "📱 Wi-Fi не подключен"
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
                when (statusMsg) {
                    "UNAUTHORIZED" -> sendHttpResponse(client, "401 Unauthorized", "text/plain; charset=utf-8", "PIN")
                    "ERR_NO_IME", "ERR_NO_FIELD", "ERR_ENTER" -> sendHttpResponse(client, "409 Conflict", "text/plain; charset=utf-8", statusMsg)
                    else -> sendHttpResponse(client, "200 OK", "text/plain; charset=utf-8", statusMsg)
                }
            } else {
                sendHttpResponse(client, "200 OK", "text/html; charset=utf-8", getRemoteHtml())
            }
        } catch (_: Exception) {
        } finally { try { client.close() } catch (_: Exception) {} }
    }

    private fun sendHttpResponse(client: Socket, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = client.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun parseQueryParams(path: String): Map<String, String> {
        val qIndex = path.indexOf('?')
        if (qIndex < 0 || qIndex == path.length - 1) return emptyMap()
        val rawQuery = path.substring(qIndex + 1)
        val result = mutableMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
            val rawVal = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
            val key = URLDecoder.decode(rawKey.replace("+", "%2B"), "UTF-8")
            val value = URLDecoder.decode(rawVal.replace("+", "%2B"), "UTF-8")
            result[key] = value
        }
        return result
    }

    private fun copyToSystemClipboard(text: String) {
        runOnUiThread {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("HY300", text))
            } catch (_: Exception) {}
        }
    }

    private fun handleRemoteCommand(path: String): String {
        val params = parseQueryParams(path)
        if (params["pin"] != remotePinCode) return "UNAUTHORIZED"
        val action = params["a"] ?: ""
        val textVal = params["t"] ?: ""

        when (action) {
            "up" -> if (RemoteKeyboardService.instance?.sendKey(KeyEvent.KEYCODE_DPAD_UP) != true) dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> if (RemoteKeyboardService.instance?.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) != true) dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> if (RemoteKeyboardService.instance?.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) != true) dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> if (RemoteKeyboardService.instance?.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) != true) dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "ok" -> {
                if (loveDialog?.isShowing == true || sleepDialog?.isShowing == true) {
                    runOnUiThread { loveDialog?.dismiss(); sleepDialog?.dismiss() }
                } else if (RemoteKeyboardService.instance?.sendEnterAction() != true) {
                    dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_CENTER)
                }
            }
            "back" -> {
                if (loveDialog?.isShowing == true || sleepDialog?.isShowing == true) {
                    runOnUiThread { loveDialog?.dismiss(); sleepDialog?.dismiss() }
                } else if (RemoteKeyboardService.instance?.sendKey(KeyEvent.KEYCODE_BACK) != true) {
                    dispatchLocalDpadKey(KeyEvent.KEYCODE_BACK)
                }
            }
            "home" -> runOnUiThread {
                loveDialog?.dismiss()
                sleepDialog?.dismiss()
                safeStartIntent(Intent(this, MainActivity::class.java))
            }
            "volup" -> adjustVol(AudioManager.ADJUST_RAISE)
            "voldown" -> adjustVol(AudioManager.ADJUST_LOWER)
            "lampa" -> runOnUiThread { openLampa() }
            "yt" -> runOnUiThread { openYouTube() }
            "prank" -> triggerDianaLoveFromRemote()
            "power" -> triggerPowerOffOrSleep()
            "keystone" -> runOnUiThread { openProjectorKeystone() }
            "boost" -> runOnUiThread { optimizeMemory() }
            "text" -> {
                if (textVal.isEmpty()) return "ERR_NO_FIELD"
                copyToSystemClipboard(textVal)

                val ime = RemoteKeyboardService.instance
                if (ime == null || !isWifiKeyboardSelected()) {
                    runOnUiThread {
                        Toast.makeText(this, "📋 В буфере: $textVal (Включи «⌨️ Клава» на главном экране!)", Toast.LENGTH_LONG).show()
                    }
                    return "ERR_NO_IME"
                }

                val committed = ime.commitRemoteText(textVal)
                return if (committed) {
                    runOnUiThread {
                        Toast.makeText(this, "⌨️ Текст вставлен: $textVal", Toast.LENGTH_SHORT).show()
                    }
                    "OK_TEXT"
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "⚠️ Открой поле поиска на проекторе (текст скопирован в буфер)", Toast.LENGTH_LONG).show()
                    }
                    "ERR_NO_FIELD"
                }
            }
            "enter" -> {
                val ime = RemoteKeyboardService.instance
                if (ime == null || !isWifiKeyboardSelected()) return "ERR_NO_IME"
                val ok = ime.sendEnterAction()
                return if (ok) "OK_ENTER" else "ERR_ENTER"
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
                return "OK"
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
            .header{display:flex;align-items:center;justify-content:space-between;gap:6px;margin-bottom:10px;padding:0 2px;}
            .brand{font-size:14px;font-weight:800;color:#38BDF8;letter-spacing:0.6px;white-space:nowrap;}
            .status{font-size:11px;font-weight:600;color:#94A3B8;background:#111622;padding:6px 10px;border-radius:20px;border:1px solid #1E293B;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
            .row{display:flex;gap:8px;justify-content:center;margin-bottom:8px;}
            .row:last-child{margin-bottom:0;}
            button{background:#161D2B;color:#F1F5F9;border:1.5px solid #28354E;border-radius:14px;padding:13px 10px;font-size:14px;font-weight:700;flex:1;transition:transform 0.08s,background 0.15s;}
            button:active{transform:scale(0.96);background:#25324B;border-color:#38BDF8;}
            .lampa{background:linear-gradient(135deg,#331F0E,#1A1412);border-color:#F59E0B;color:#FDE68A;}
            .yt{background:linear-gradient(135deg,#351219,#1A1216);border-color:#F43F5E;color:#FECDD3;}
            .ph-btn{background:#09090B;border:2px solid #FF9000;color:#FFFFFF;font-weight:900;letter-spacing:0.4px;}
            .ph-badge{background:#FF9000;color:#000000;padding:2px 6px;border-radius:5px;margin-left:3px;font-weight:900;}
            .pwr-btn{background:linear-gradient(135deg,#7F1D1D,#450A0A);border-color:#EF4444;color:#FECACA;max-width:86px;padding:8px 10px;font-size:12px;}
            .boost{background:linear-gradient(135deg,#0D2E2B,#111C24);border-color:#10B981;color:#A7F3D0;}
            .send-btn{background:linear-gradient(135deg,#0284C7,#0369A1);border-color:#38BDF8;color:#fff;}
            .enter-btn{background:linear-gradient(135deg,#0D9488,#0F766E);border-color:#2DD4BF;color:#fff;}
            .dpad{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;max-width:260px;margin:6px auto;}
            .dpad button{height:64px;font-size:20px;border-radius:18px;}
            .ok{background:linear-gradient(135deg,#0EA5E9,#0284C7);border-color:#7DD3FC;color:#fff;font-size:17px !important;box-shadow:0 0 15px rgba(14,165,233,0.3);}
            input{width:100%;padding:13px 14px;border-radius:13px;border:1.5px solid #334155;background:#0B0F19;color:#fff;font-size:15px;outline:none;}
            input:focus{border-color:#38BDF8;}
            .pin-box{max-width:85px;text-align:center;letter-spacing:2px;font-weight:800;color:#38BDF8;padding:7px;}
        </style></head><body>
        <div class="header">
            <div class="brand">🎬 HY300</div>
            <input id="pin" class="pin-box" type="tel" maxlength="4" placeholder="PIN">
            <div id="st" class="status">Готов</div>
            <button class="pwr-btn" onclick="c('power')">🔴 Выкл</button>
        </div>

        <div class="card">
            <div class="row">
                <input id="txt" placeholder="Открой поле поиска на проекторе и пиши тут..." onkeydown="if(event.key==='Enter'){event.preventDefault();st();}">
            </div>
            <div class="row">
                <button class="send-btn" onclick="st()">⌨️ Вставить текст</button>
                <button class="enter-btn" onclick="sendEnter()">⏎ Enter (Найти)</button>
            </div>
            <div class="row">
                <button class="yt" onclick="sendText('ytsearch')">🔍 Прямой поиск в SmartTube</button>
            </div>
        </div>

        <div class="card">
            <div class="row">
                <button class="lampa" onclick="c('lampa')">🍿 Lampa TV</button>
                <button class="yt" onclick="c('yt')">▶️ SmartTube</button>
            </div>
            <div class="row">
                <button class="ph-btn" onclick="c('prank')">Porn<span class="ph-badge">Hub</span> 18+</button>
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
            function setStatus(msg, color){ stEl.textContent = msg; stEl.style.color = color; }
            function c(a){
                fetch('/cmd?pin=' + getPin() + '&a=' + encodeURIComponent(a)).then(function(r){
                    if(r.status === 401) setStatus('❌ PIN', '#F43F5E');
                    else if(a === 'prank') setStatus('💖 Для Дианы!', '#FF4D6D');
                    else if(a === 'power') setStatus('🌙 Сон / Выкл', '#A7F3D0');
                    else setStatus('✅ ОК', '#10B981');
                }).catch(function(){ setStatus('⚠️ Нет связи', '#F59E0B'); });
            }
            function st(){
                var el = document.getElementById('txt');
                var v = el.value;
                if(!v) return;
                fetch('/cmd?pin=' + getPin() + '&a=text&t=' + encodeURIComponent(v)).then(function(r){
                    return r.text().then(function(body){ return {status: r.status, body: body}; });
                }).then(function(res){
                    if(res.status === 200 && res.body === 'OK_TEXT'){
                        el.value = '';
                        setStatus('⌨️ Вставлено!', '#38BDF8');
                    } else if(res.status === 401){
                        setStatus('❌ PIN', '#F43F5E');
                    } else if(res.body === 'ERR_NO_IME'){
                        setStatus('⚠️ Вкл Клаву на ТВ', '#F59E0B');
                    } else if(res.body === 'ERR_NO_FIELD'){
                        setStatus('⚠️ Открой поиск', '#F59E0B');
                    } else {
                        setStatus('⚠️ Ошибка', '#F59E0B');
                    }
                }).catch(function(){ setStatus('⚠️ Нет связи', '#F59E0B'); });
            }
            function sendEnter(){
                fetch('/cmd?pin=' + getPin() + '&a=enter').then(function(r){
                    return r.text().then(function(body){ return {status: r.status, body: body}; });
                }).then(function(res){
                    if(res.status === 200 && res.body === 'OK_ENTER'){
                        setStatus('⏎ Enter!', '#10B981');
                    } else if(res.status === 401){
                        setStatus('❌ PIN', '#F43F5E');
                    } else if(res.body === 'ERR_NO_IME'){
                        setStatus('⚠️ Вкл Клаву на ТВ', '#F59E0B');
                    } else {
                        setStatus('⚠️ Поле закрыто', '#F59E0B');
                    }
                }).catch(function(){ setStatus('⚠️ Нет связи', '#F59E0B'); });
            }
            function sendText(mode){
                var el = document.getElementById('txt');
                var v = el.value;
                if(!v) return;
                fetch('/cmd?pin=' + getPin() + '&a=' + encodeURIComponent(mode) + '&t=' + encodeURIComponent(v)).then(function(r){
                    if(r.status === 200){ el.value = ''; setStatus('🔍 YouTube!', '#10B981'); }
                    else if(r.status === 401){ setStatus('❌ PIN', '#F43F5E'); }
                }).catch(function(){ setStatus('⚠️ Нет связи', '#F59E0B'); });
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
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isFocusable = true
            isClickable = true
            background = createBannerDrawable(false, colorStart, colorEnd, accentHex)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = rightMarginPx }
        }
        val badgeView = TextView(this).apply {
            text = badge; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f; setTextColor(Color.parseColor(accentHex))
        }
        val titleView = TextView(this).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        val subView = TextView(this).apply {
            text = subtitle; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Color.parseColor("#94A3B8"))
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
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true
            isClickable = true
            background = createGlassDrawable(false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = createGlassDrawable(hasFocus)
                setTextColor(if (hasFocus) Color.WHITE else Color.parseColor("#E2E8F0"))
                v.animate().scaleX(if (hasFocus) 1.05f else 1.0f).scaleY(if (hasFocus) 1.05f else 1.0f).setDuration(110).start()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createGlassDrawable(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(30).toFloat()
            setColor(Color.parseColor(if (focused) "#1E293B" else "#131824"))
            setStroke(if (focused) dp(2) else dp(1), Color.parseColor(if (focused) "#38BDF8" else "#222B3B"))
        }
    }

    private fun createAppTileDrawable(focused: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor(if (focused) "#1E293B" else "#111520"))
            setStroke(if (focused) dp(2) else dp(1), Color.parseColor(if (focused) "#38BDF8" else "#1E2536"))
        }
    }
}
