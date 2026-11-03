package app.urlcleaner

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.urlcleaner.data.ShareMode
import app.urlcleaner.util.ShareActions
import app.urlcleaner.util.UrlExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Transparent activity targeted by ACTION_SEND. Does the work then finishes; no UI.
 *
 * Trickier than it looks because we need the rules loaded before we can clean,
 * so on cold start we block briefly on the rules load coroutine before dispatching.
 */
class ShareHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as UrlCleanerApp

        val incoming = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        val url = UrlExtractor.firstUrl(incoming)
        if (url == null) {
            Toast.makeText(this, getString(R.string.toast_no_url_in_share), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val settings = app.settingsRepo.settings.first()

            val cleaner = app.cleanerFor(settings.removeReferralMarketing)
            val result = cleaner.clean(url)

            if (settings.historyEnabled && !result.isBlocked) {
                app.historyRepo.append(result.original, result.cleaned)
            }

            val ctx = this@ShareHandlerActivity
            if (result.isBlocked) {
                ShareActions.toastAfterCleaning(ctx, result.paramsRemoved, isBlocked = true, forceShow = true)
            } else {
                ShareActions.copyToClipboard(ctx, result.cleaned)
                ShareActions.toastAfterCleaning(ctx, result.paramsRemoved, isBlocked = false)
                if (settings.shareMode == ShareMode.ReShare) {
                    ShareActions.reShare(ctx, result.cleaned, excludeSelf = true)
                }
            }
            finish()
        }
    }
}
