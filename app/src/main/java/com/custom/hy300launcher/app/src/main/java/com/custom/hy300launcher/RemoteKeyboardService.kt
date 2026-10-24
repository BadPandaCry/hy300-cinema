package com.custom.hy300launcher

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RemoteKeyboardService : InputMethodService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreateInputView(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val label = TextView(this).apply {
            text = "📱 HY300 Wi-Fi Клавиатура: отправь текст с телефона"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPaste = createBarButton("📋 Вставить буфер") {
            pasteFromClipboard()
        }

        val btnEnter = createBarButton("🔍 Enter / Поиск") {
            sendEnterAction()
        }

        val btnSwitch = createBarButton("🌐 Сменить клаву") {
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            } catch (_: Exception) {}
        }

        bar.addView(label)
        bar.addView(btnPaste)
        bar.addView(btnEnter)
        bar.addView(btnSwitch)
        return bar
    }

    private fun createBarButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun pasteFromClipboard(): Boolean {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!clipText.isNullOrEmpty()) {
                commitRemoteText(clipText)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun runOnMainSync(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try { block() } catch (_: Exception) { false }
        }
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(block())
            } catch (_: Exception) {
                result.set(false)
            } finally {
                latch.countDown()
            }
        }
        return try {
            latch.await(1500, TimeUnit.MILLISECONDS)
            result.get()
        } catch (_: Exception) {
            false
        }
    }

    fun commitRemoteText(text: String): Boolean {
        if (text.isEmpty()) return false
        return runOnMainSync {
            val ic: InputConnection = currentInputConnection ?: return@runOnMainSync false
            var batchStarted = false
            try {
                batchStarted = ic.beginBatchEdit()
                ic.commitText(text, 1)
            } catch (_: Exception) {
                false
            } finally {
                if (batchStarted) {
                    try { ic.endBatchEdit() } catch (_: Exception) {}
                }
            }
        }
    }

    fun sendEnterAction(): Boolean {
        return runOnMainSync {
            val ic: InputConnection = currentInputConnection ?: return@runOnMainSync false
            val info: EditorInfo? = currentInputEditorInfo
            val actionId = if (info != null) {
                info.imeOptions and EditorInfo.IME_MASK_ACTION
            } else {
                EditorInfo.IME_ACTION_UNSPECIFIED
            }

            var handled = false
            if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                try {
                    handled = ic.performEditorAction(actionId)
                } catch (_: Exception) {
                    handled = false
                }
            }

            if (!handled) {
                try {
                    handled = ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
                } catch (_: Exception) {
                    handled = false
                }
            }

            if (!handled) {
                try {
                    val downOk = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    val upOk = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    handled = downOk && upOk
                } catch (_: Exception) {
                    handled = false
                }
            }

            handled
        }
    }

    fun sendKey(keyCode: Int): Boolean {
        return runOnMainSync {
            val ic: InputConnection = currentInputConnection ?: return@runOnMainSync false
            try {
                val downOk = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                val upOk = ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                downOk && upOk
            } catch (_: Exception) {
                false
            }
        }
    }

    companion object {
        @Volatile
        var instance: RemoteKeyboardService? = null
    }
}
