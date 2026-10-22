package com.custom.hy300launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    data class AppItem(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    private class AppViewHolder(
        val card: LinearLayout,
        val iconView: ImageView,
        val titleView: TextView
    )

    private lateinit var gridView: GridView
    private lateinit var remoteBadge: TextView
    private lateinit var btnLampa: LinearLayout
    private lateinit var btnYoutube: LinearLayout
    private lateinit var btnBoost: TextView
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
        private const val REMOTE_PORT = 8080
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Генерируем или восстанавливаем 4-значный PIN-код для защиты веб-пульта
        val prefs = getSharedPreferences("hy300_prefs", Context.MODE_PRIVATE)
        remotePinCode = prefs.getString("remote_pin", null) ?: run {
            val generated = Random.nextInt(1000, 9999).toString()
            prefs.edit().putString("remote_pin", generated).apply()
            generated
        }

        val bgGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#07090E"),
                Color.parseColor("#111622"),
                Color.parseColor("#05070B")
            )
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgGradient
            setPadding(dp(32), dp(20), dp(32), dp(16))
        }

        // --- 1. ВЕРХНЯЯ ПАНЕЛЬ ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
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

        btnBoost = createPillButton(ID_BTN_BOOST, "⚡ Разгрузить ОЗУ") { optimizeMemory() }
        btnKeystone = createPillButton(ID_BTN_KEYSTONE, "📐 Трапеция") { openProjectorKeystone() }
        btnWifi = createPillButton(ID_BTN_WIFI, "📶 Wi-Fi") { safeStartIntent(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        btnSettings = createPillButton(ID_BTN_SETTINGS, "⚙️ Настройки") { safeStartIntent(Intent(Settings.ACTION_SETTINGS)) }

        topBar.addView(clockBox)
        topBar.addView(btnBoost)
        topBar.addView(btnKeystone)
        topBar.addView(btnWifi)
        topBar.addView(btnSettings)

        // --- 2. ГЛАВНЫЕ КАРТОЧКИ: LAMPA И YOUTUBE ---
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }

        btnLampa = createHeroBanner(
            viewId = ID_BTN_LAMPA,
            badge = "ОНЛАЙН КИНОТЕАТР",
            title = "🍿 Lampa TV",
            subtitle = "Фильмы, Сериалы, HDRezka и Торренты",
            colorStart = "#2A1B0E",
            colorEnd = "#121620",
            accentHex = "#F59E0B",
            rightMarginPx = dp(16)
        ) { openLampa() }

        btnYoutube = createHeroBanner(
            viewId = ID_BTN_YOUTUBE,
            badge = "ВИДЕО БЕЗ РЕКЛАМЫ",
            title = "▶️ YouTube",
            subtitle = "SmartTube • Клипы, обзоры и шоу",
            colorStart = "#2B1115",
            colorEnd = "#121620",
            accentHex = "#F43F5E",
            rightMarginPx = 0
        ) { openYouTube() }

        setupExplicitFocusGraph()

        heroRow.addView(btnLampa)
        heroRow.addView(btnYoutube)

        // --- 3. ЗАГОЛОВОК И СЕТКА ПРИЛОЖЕНИЙ ---
        val sectionTitle = TextView(this).apply {
            text = "ВСЕ ПРИЛОЖЕНИЯ"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                if (position in appList.indices) {
                    launchPackage(appList[position].packageName)
                }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateGridSelectionVisuals(parent, view)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    updateGridSelectionVisuals(parent, null)
                }
            }
            setOnFocusChangeListener { v, hasFocus ->
                val gv = v as GridView
                if (hasFocus) {
                    if (gv.selectedItemPosition == AdapterView.INVALID_POSITION && appList.isNotEmpty()) {
                        gv.setSelection(0)
                    }
                    updateGridSelectionVisuals(gv, gv.selectedView)
                } else {
                    updateGridSelectionVisuals(gv, null)
                }
            }
        }

        root.addView(topBar)
        root.addView(heroRow)
        root.addView(sectionTitle)
        root.addView(gridView)
        setContentView(root)

        loadInstalledApps(forceReload = true)
        btnLampa.requestFocus()
        startPocoRemoteServer()
    }

    private fun setupExplicitFocusGraph() {
        // Верхний ряд кнопок
        btnBoost.nextFocusRightId = ID_BTN_KEYSTONE
        btnBoost.nextFocusDownId = ID_BTN_LAMPA

        btnKeystone.nextFocusLeftId = ID_BTN_BOOST
        btnKeystone.nextFocusRightId = ID_BTN_WIFI
        btnKeystone.nextFocusDownId = ID_BTN_LAMPA

        btnWifi.nextFocusLeftId = ID_BTN_KEYSTONE
        btnWifi.nextFocusRightId = ID_BTN_SETTINGS
        btnWifi.nextFocusDownId = ID_BTN_YOUTUBE

        btnSettings.nextFocusLeftId = ID_BTN_WIFI
        btnSettings.nextFocusDownId = ID_BTN_YOUTUBE

        // Главные карточки Lampa и YouTube
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
        loadInstalledApps(forceReload = false)
        // Восстанавливаем фокус, если он потерялся после выхода из другого приложения
        if (currentFocus == null) {
            btnLampa.requestFocus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPocoRemoteServer()
    }

    // В домашнем лаунчере кнопка "Назад" не должна закрывать рабочий стол
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ничего не делаем (остаёмся на главном экране)
    }

    private fun buildRemoteBadgeText(): String {
        val ip = getLocalIpAddress()
        return if (ip != null) {
            "📱 Пульт: http://$ip:$REMOTE_PORT  |  🔑 PIN: $remotePinCode"
        } else {
            "📱 Пульт: Wi-Fi не подключен"
        }
    }

    private fun safeStartIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Раздел недоступен на данной прошивке", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPackage(pkg: String) {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            safeStartIntent(intent)
        } else {
            Toast.makeText(this, "Не удалось запустить $pkg", Toast.LENGTH_SHORT).show()
        }
    }

    // --- ЧЕСТНАЯ ОПТИМИЗАЦИЯ ОЗУ БЕЗ ROOT ---
    private fun optimizeMemory() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memBefore = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memBefore)

            // Запрашиваем у системы выгрузку фоновых процессов для всех сторонних приложений
            val packages = packageManager.getInstalledApplications(0)
            for (app in packages) {
                if (app.packageName != packageName && !app.packageName.startsWith("com.android.systemui")) {
                    try {
                        am.killBackgroundProcesses(app.packageName)
                    } catch (_: Exception) {}
                }
            }

            System.gc()

            val memAfter = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memAfter)

            val availMb = memAfter.availMem / (1024 * 1024)
            val totalMb = memAfter.totalMem / (1024 * 1024)
            val freedMb = ((memAfter.availMem - memBefore.availMem) / (1024 * 1024)).coerceAtLeast(0)

            val msg = if (freedMb > 0) {
                "⚡ Освобождено: $freedMb МБ (Свободно ОЗУ: $availMb из $totalMb МБ)"
            } else {
                "⚡ Память уже в норме (Свободно ОЗУ: $availMb из $totalMb МБ)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось запросить очистку памяти", Toast.LENGTH_SHORT).show()
        }
    }

    // --- ЗАПУСК LAMPA, YOUTUBE И НАСТРОЕК ТРАПЕЦИИ HY300 ---
    private fun openLampa() {
        val candidates = listOf(
            "top.rootu.lampa",
            "ru.lampa.tv",
            "com.lampa.app",
            "app.lampa.tv"
        )
        for (pkg in candidates) {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                safeStartIntent(intent)
                return
            }
        }

        // Если приложение ещё не установлено — показываем понятный диалог с выбором
        AlertDialog.Builder(this)
            .setTitle("Приложение Lampa не установлено")
            .setMessage("Для максимальной скорости установите APK-файл Lampa (top.rootu.lampa) с флешки.\n\nХотите пока открыть облачную веб-версию Lampa в браузере?")
            .setPositiveButton("Открыть веб-версию") { _, _ ->
                safeStartIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://lampa.mx")))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openYouTube() {
        val candidates = listOf(
            "com.liskovsoft.smarttubetv.beta",
            "com.liskovsoft.smarttubetv",
            "org.smarttube.stable",
            "org.smarttube.beta",
            "com.teamsmart.videomanager.tv",
            "com.google.android.youtube.tv",
            "com.google.android.youtube"
        )
        for (pkg in candidates) {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                safeStartIntent(intent)
                return
            }
        }

        AlertDialog.Builder(this)
            .setTitle("SmartTube / YouTub            .setMessage("Рекомендуется установить легкий клиент SmartTube с флешки. Открыть веб-версию YouTube TV?")
            .setPositiveButton("Открыть") { _, _ ->
                safeStartIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/tv")))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openProjectorKeystone() {
        // 1. Известные системные компоненты трапеции на прошивках Magcubic HY300 (Allwinner H713)
        val knownComponents = listOf(
            ComponentName("com.softwinner.TvdSettings", "com.softwinner.TvdSettings.ProjectionSettingsActivity"),
            ComponentName("com.softwinner.TvdSettings", "com.softwinner.TvdSettings.KeystoneActivity"),
            ComponentName("com.softwinner.TvdSettings", "com.softwinner.TvdSettings.DisplaySettingsActivity"),
            ComponentName("com.android.tv.settings", "com.android.tv.settings.ProjectionActivity"),
            ComponentName("com.android.settings", "com.android.settings.ProjectionSettings")
        )
        for (comp in knownComponents) {
            try {
                val i = Intent().apply {
                    component = comp
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
                return
            } catch (_: Exception) {}
        }

        // 2. Динамический поиск экспортированных Activity с ключевыми словами трапеции/проекции
        try {
            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            val keywords = listOf("keystone", "projection", "trapezoid", "projector")
            for (pkg in packages) {
                val activities = pkg.activities ?: continue
                for (act in activities) {
                    if (!act.exported) continue
                    val actName = act.name.lowercase()
                    if (keywords.any { actName.contains(it) }) {
                        try {
                            val i = Intent().apply {
                                component = ComponentName(act.packageName, act.name)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(i)
                            return
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Безопасный fallback в общие настройки, если прошивка закрыла прямой доступ к Activity
        Toast.makeText(
            this,
            "Прямой экран трапеции скрыт прошивкой — выберите раздел проекции в общих настройках",
            Toast.LENGTH_LONG
        ).show()
        safeStartIntent(Intent(Settings.ACTION_SETTINGS))
    }

    // --- ЗАГРУЗКА И ОТОБРАЖЕНИЕ СПИСКА ПРИЛОЖЕНИЙ (С КЭШИРОВАНИЕМ И VIEWHOLDER) ---
    private fun loadInstalledApps(forceReload: Boolean) {
        val pm = packageManager
        val foundApps = mutableMapOf<String, AppItem>()

        val categories = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (cat in categories) {
            val queryIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(cat) }
            val resolveList = try {
                pm.queryIntentActivities(queryIntent, 0)
            } catch (_: Exception) {
                emptyList()
            }
            for (info in resolveList) {
                val activityInfo = info.activityInfo ?: continue
                val pkg = activityInfo.packageName ?: continue
                if (pkg == packageName || foundApps.containsKey(pkg)) continue

                val label = try {
                    info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                } catch (_: Exception) {
                    pkg
                }
                val icon = try {
                    info.loadIcon(pm) ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, theme)
                } catch (_: Exception) {
                    resources.getDrawable(android.R.drawable.sym_def_app_icon, theme)
                }
                foundApps[pkg] = AppItem(label, pkg, icon)
            }
        }

        val sorted = foundApps.values.sortedBy { it.label.lowercase() }
        val signature = sorted.joinToString("|") { it.packageName }

        // Не пересоздаём адаптер без нужды, чтобы не сбивать фокус пульта при onResume()
        if (!forceReload && signature == lastPackageSignature && appAdapter != null) {
            return
        }
        lastPackageSignature = signature
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
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) }
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

                val isSelected = gridView.hasFocus() && gridView.selectedItemPosition == p
                applyTileVisualState(holder, isSelected)

                return view
            }
        }
    }

    private fun updateGridSelectionVisuals(parent: AdapterView<*>?, selectedView: View?) {
        val gv = parent ?: return
        for (i in 0 until gv.childCount) {
            val child = gv.getChildAt(i)
            val holder = child?.tag as? AppViewHolder ?: continue
            val isSelected = gv.hasFocus() && (child === selectedView)
            applyTileVisualState(holder, isSelected)
        }
    }

    private fun applyTileVisualState(holder: AppViewHolder, focused: Boolean) {
        holder.card.background = createAppTileDrawable(focused)
        holder.titleView.setTextColor(if (focused) Color.WHITE else Color.parseColor("#CBD5E1"))
        val scale = if (focused) 1.06f else 1.0f
        holder.card.scaleX = scale
        holder.card.scaleY = scale
    }

    // --- ВСТРОЕННЫЙ БЕЗОПАСНЫЙ ВЕБ-ПУЛЬТ ДЛЯ POCO (С ПУЛОМ ПОТОКОВ И PIN-АВТОРИЗАЦИЕЙ) ---
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
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    executor.execute { handleClientSocket(client) }
                }
            } catch (_: Exception) {
                isServerRunning = false
            }
        }.apply {
            isDaemon = true
            start()
        }
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
                sendHttpResponse(client, "403 Forbidden", "text/plain; charset=utf-8", "Доступ разрешен только из локальной домашней сети")
                return
            }

            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            // Читаем заголовки до конца
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                line = reader.readLine()
            }

            if (method != "GET") {
                sendHttpResponse(client, "405 Method Not Allowed", "text/plain; charset=utf-8", "Only GET supported")
                return
            }

            if (path.startsWith("/cmd")) {
                val statusMsg = handleRemoteCommand(path)
                if (statusMsg == "UNAUTHORIZED") {
                    sendHttpResponse(client, "401 Unauthorized", "text/plain; charset=utf-8", "Неверный PIN-код")
                } else {
                    sendHttpResponse(client, "200 OK", "text/plain; charset=utf-8", statusMsg)
                }
            } else {
                sendHttpResponse(client, "200 OK", "text/html; charset=utf-8", getRemoteHtml())
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun sendHttpResponse(client: Socket, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun handleRemoteCommand(path: String): String {
        // Uri.parse().getQueryParameter() сам корректно декодирует UTF-8 и спецсимволы (% и +)
        val uri = Uri.parse("http://localhost$path")
        val pin = uri.getQueryParameter("pin") ?: ""
        if (pin != remotePinCode) {
            return "UNAUTHORIZED"
        }

        val action = uri.getQueryParameter("a") ?: ""
        val textVal = uri.getQueryParameter("t") ?: ""

        when (action) {
            "up" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "ok" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_DPAD_CENTER)
            "back" -> dispatchLocalDpadKey(KeyEvent.KEYCODE_BACK)
            "home" -> runOnUiThread {
                safeStartIntent(Intent(this, MainActivity::class.java))
            }
            "volup" -> adjustVol(AudioManager.ADJUST_RAISE)
            "voldown" -> adjustVol(AudioManager.ADJUST_LOWER)
            "lampa" -> runOnUiThread { openLampa() }
            "yt" -> runOnUiThread { openYouTube() }
            "keystone" -> runOnUiThread { openProjectorKeystone() }
            "boost" -> runOnUiThread { optimizeMemory() }
            "copy" -> {
                if (textVal.isNotBlank()) {
                    runOnUiThread {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("HY300_Remote", textVal))
                        Toast.makeText(
                            this,
                            "📋 Скопировано в буфер проектора: «$textVal»",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            "ytsearch" -> {
                if (textVal.isNotBlank()) {
                    runOnUiThread {
                        val searchUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(textVal))
                        safeStartIntent(Intent(Intent.ACTION_VIEW, searchUri))
                    }
                }
            }
        }
        return "OK"
    }

    // Честная навигация внутри окна лаунчера без root-прав
    private fun dispatchLocalDpadKey(keyCode: Int) {
        runOnUiThread {
            try {
                val rootView = window?.decorView ?: return@runOnUiThread
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                rootView.dispatchKeyEvent(downEvent)
                rootView.dispatchKeyEvent(upEvent)
            } catch (_: Exception) {}
        }
    }

    private fun adjustVol(dir: Int) {
        runOnUiThread {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            } catch (_: Exception) {}
        }
    }

    private fun getRemoteHtml(): String = """
        <!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <title>Poco HY300 Remote</title>
        <style>
            *{box-sizing:border-box;-webkit-tap-highlight-color:transparent;}
            body{background:#090C14;color:#fff;font-family:sans-serif;text-align:center;margin:0;padding:14px;user-select:none;}
            h3{margin:2px 0 10px;color:#38BDF8;font-size:15px;letter-spacing:1px;}
            .status{font-size:12px;color:#94A3B8;margin-bottom:10px;}
            .row{display:flex;gap:8px;justify-content:center;margin-bottom:8px;}
            button{background:#161D2B;color:#fff;border:2px solid #263147;border-radius:14px;padding:13px;font-size:14px;font-weight:bold;flex:1;transition:transform 0.08s;}
            button:active{background:#25324B;border-color:#38BDF8;transform:scale(0.95);}
            .lampa{background:#2A1B0E;border-color:#F59E0B;}
            .yt{background:#2B1115;border-color:#F43F5E;}
            .boost{background:#0F292A;border-color:#10B981;}
            .dpad{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px;max-width:250px;margin:10px auto;}
            .dpad button{height:62px;font-size:19px;}
            .ok{background:#1E293B;border-color:#38BDF8;}
            input{flex:2;padding:12px;border-radius:12px;border:1px solid #334155;background:#111622;color:#fff;font-size:14px;outline:none;}
            .pin-box{max-width:140px;text-align:center;letter-spacing:2px;font-weight:bold;color:#38BDF8;}
            .hint{font-size:11px;color:#64748B;margin-top:8px;line-height:1.35;}
        </style></head><body>
        <h3>📱 ПУЛЬТ MAGCUBIC HY300</h3>
        <div class="row">
            <input id="pin" class="pin-box" type="tel" maxlength="4" placeholder="PIN с экрана">
            <div id="st" class="status" style="align-self:center;">Введите 4 цифры PIN</div>
        </div>
        <div class="row">
            <button class="lampa" onclick="c('lampa')">🍿 Lampa</button>
            <button class="yt" onclick="c('yt')">▶️ YouTube</button>
        </div>
        <div class="row">
            <button class="boost" onclick="c('boost')">⚡ Разгрузить ОЗУ</button>
            <button onclick="c('keystone')">📐 Трапеция</button>
        </div>
        <div class="row">
            <input id="txt" placeholder="Текст / название фильма...">
        </div>
        <div class="row">
            <button onclick="sendText('copy')">📋 В буфер ТВ</button>
            <button onclick="sendText('ytsearch')">🔍 Найти в YouTube</button>
        </div>
        <div class="dpad">
            <div></div><button onclick="c('up')">▲</button><div></div>
            <button onclick="c('left')">◄</button><button class="ok" onclick="c('ok')">OK</button><button onclick="c('right')">►</button>
            <div></div><button onclick="c('down')">▼</button><div></div>
        </div>
        <div class="row">
            <button onclick="c('back')">🔙 Назад</button>
            <button onclick="c('home')">🏠 В лаунчер</button>
        </div>
        <div class="row">
            <button onclick="c('voldown')">🔉 Тише</button>
            <button onclick="c('volup')">🔊 Громче</button>
        </div>
        <div class="hint">
            Джойстик управляет курсором внутри лаунчера. Кнопки Громкости, Домой, Lampa, YouTube и Буфер обмена работают глобально поверх любых приложений.
        </div>
        <script>
            var pinInput = document.getElementById('pin');
            var stEl = document.getElementById('st');
            pinInput.value = localStorage.getItem('hy300_pin') || '';
            pinInput.addEventListener('input', function(){
                localStorage.setItem('hy300_pin', pinInput.value.trim());
            });
            function getPin(){
                var p = pinInput.value.trim();
                localStorage.setItem('hy300_pin', p);
                return encodeURIComponent(p);
            }
            function c(a){
                fetch('/cmd?pin=' + getPin() + '&a=' + encodeURIComponent(a))
                .then(function(r){
                    stEl.textContent = (r.status === 401) ? '❌ Неверный PIN' : '✅ Команда отправлена';
                    stEl.style.color = (r.status === 401) ? '#F43F5E' : '#10B981';
                }).catch(function(){
                    stEl.textContent = '⚠️ Нет связи с проектором';
                    stEl.style.color = '#F59E0B';
                });
            }
            function sendText(mode){
                var el = document.getElementById('txt');
                var v = el.value.trim();
                if(!v) return;
                fetch('/cmd?pin=' + getPin() + '&a=' + encodeURIComponent(mode) + '&t=' + encodeURIComponent(v))
                .then(function(r){
                    if(r.status === 200){ el.value = ''; stEl.textContent = '✅ Текст передан'; stEl.style.color = '#10B981'; }
                    else if(r.status === 401){ stEl.textContent = '❌ Неверный PIN'; stEl.style.color = '#F43F5E'; }
                });
            }
        </script></body></html>
    """.trimIndent()

    // Точный выбор локального Wi-Fi / Ethernet IPv4-адреса (исключая VPN, cellular и виртуальные мосты)
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var fallbackAddress: String? = null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                val name = intf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") || name.startsWith("dummy")) continue

                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                            return ip
                        }
                        if (fallbackAddress == null) {
                            fallbackAddress = ip
                        }
                    }
                }
            }
            return fallbackAddress
        } catch (_: Exception) {
            return null
        }
    }

    // --- ОФОРМЛЕНИЕ UI-КОМПОНЕНТОВ В DP ---
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = rightMarginPx
            }
        }
        val badgeView = TextView(this).apply {
            text = badge
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(Color.parseColor(accentHex))
        }
        val titleView = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val subView = TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        card.addView(badgeView)
        card.addView(titleView)
        card.addView(subView)
        card.setOnFocusChangeListener { v, hasFocus ->
            v.background = createBannerDrawable(hasFocus, colorStart, colorEnd, accentHex)
            v.animate()
                .scaleX(if (hasFocus) 1.03f else 1.0f)
                .scaleY(if (hasFocus) 1.03f else 1.0f)
                .setDuration(120)
                .start()
        }
        card.setOnClickListener { onClick() }
        return card
    }

    private fun createBannerDrawable(focused: Boolean, startHex: String, endHex: String, accentHex: String): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(startHex), Color.parseColor(endHex))
        ).apply {
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
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isFocusable = true
            isClickable = true
            background = createGlassDrawable(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(10) }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = createGlassDrawable(hasFocus)
                setTextColor(if (hasFocus) Color.WHITE else Color.parseColor("#E2E8F0"))
                v.animate()
                    .scaleX(if (hasFocus) 1.05f else 1.0f)
                    .scaleY(if (hasFocus) 1.05f else 1.0f)
                    .setDuration(110)
                    .start()
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