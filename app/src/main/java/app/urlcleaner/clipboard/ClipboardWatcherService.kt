package app.urlcleaner.clipboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import app.urlcleaner.app
import app.urlcleaner.data.SettingsRepository
import app.urlcleaner.util.UrlExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background clipboard watcher. Reads the clipboard on every change (permitted
 * because AccessibilityService bypasses Android 10+'s foreground-only restriction).
 *
 * The service subscribes to zero AccessibilityEvents — the only reason it exists
 * as an AccessibilityService is to obtain clipboard read permission.
 */
class ClipboardWatcherService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var settingsRepo: SettingsRepository? = null
    private var currentSettings: app.urlcleaner.data.Settings? = null
    private var activeBubble: CleanBubble? = null

    /** Loop guard: the last string we ourselves wrote to the clipboard. */
    @Volatile private var lastWritten: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChange()
    }

    override fun onServiceConnected() {
        // Opt out of all accessibility events — we only need the service context.
        serviceInfo = serviceInfo.apply {
            eventTypes = 0
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }

        val repo = applicationContext.app.settingsRepo
        settingsRepo = repo
        scope.launch {
            repo.settings.collect { currentSettings = it }
        }

        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        scope.cancel()
        activeBubble?.dismiss()
        activeBubble = null
        runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.removePrimaryClipChangedListener(clipListener)
        }
        super.onDestroy()
    }

    private fun handleClipboardChange() {
        val settings = currentSettings ?: return
        val mode = settings.clipboardWatchMode
        if (mode == ClipboardWatchMode.OFF) return

        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        if (text == lastWritten) return // ignore our own writes

        val url = UrlExtractor.firstUrl(text) ?: return
        scope.launch {
            val result = applicationContext.app
                .cleanerFor(settings.removeReferralMarketing)
                .clean(url)
            if (!result.wasChanged || result.isBlocked) return@launch

            when (mode) {
                ClipboardWatchMode.AUTO_CLEAN -> performClean(result.cleaned)
                ClipboardWatchMode.ASK -> showBubble(result.cleaned, settings)
                ClipboardWatchMode.OFF -> Unit
            }
        }
    }

    private fun performClean(cleaned: String) {
        lastWritten = cleaned
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", cleaned))
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "已清理 URL 追蹤碼", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBubble(cleaned: String, settings: app.urlcleaner.data.Settings) {
        if (!Settings.canDrawOverlays(this)) return
        activeBubble?.dismiss()

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val bubbleSizePx = (64 * metrics.density).toInt()
        val margin = (16 * metrics.density).toInt()
        val defaultX = metrics.widthPixels - bubbleSizePx - margin
        val defaultY = metrics.heightPixels / 2 - bubbleSizePx / 2

        activeBubble = CleanBubble(
            context = this,
            windowManager = wm,
            initialX = if (settings.floatingBubbleX == -1) defaultX else settings.floatingBubbleX,
            initialY = if (settings.floatingBubbleY == -1) defaultY else settings.floatingBubbleY,
            onClean = { performClean(cleaned) },
            onPositionChanged = { x, y ->
                scope.launch { settingsRepo?.setBubblePosition(x, y) }
            },
        )
        activeBubble?.show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        /** Returns true when this service is listed in the system's enabled a11y services. */
        fun isEnabled(context: android.content.Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = "${context.packageName}/${ClipboardWatcherService::class.java.name}"
            return flat.split(":").any { it.equals(component, ignoreCase = true) }
        }
    }
}
