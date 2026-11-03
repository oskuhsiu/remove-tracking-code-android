package app.urlcleaner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.urlcleaner.app
import app.urlcleaner.cleaner.UrlCleaner
import app.urlcleaner.clipboard.ClipboardWatchMode
import app.urlcleaner.data.HistoryEntry
import app.urlcleaner.data.RulesRepository
import app.urlcleaner.data.Settings
import app.urlcleaner.data.ShareMode
import app.urlcleaner.util.UrlExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app.app

    val settings: StateFlow<Settings?> = appCtx.settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val history: StateFlow<List<HistoryEntry>> = appCtx.historyRepo.entries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _lastResult = MutableStateFlow<UrlCleaner.Result?>(null)
    val lastResult: StateFlow<UrlCleaner.Result?> = _lastResult.asStateFlow()

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    init {
        viewModelScope.launch {
            appCtx.loadRules()
            appCtx.historyRepo.load()
        }
    }

    fun cleanPasted(text: String) {
        val url = UrlExtractor.firstUrl(text) ?: return
        val removeRef = settings.value?.removeReferralMarketing ?: true
        val historyEnabled = settings.value?.historyEnabled ?: false
        viewModelScope.launch {
            val result = appCtx.cleanerFor(removeRef).clean(url)
            _lastResult.value = result
            if (historyEnabled && !result.isBlocked) {
                appCtx.historyRepo.append(result.original, result.cleaned)
            }
        }
    }

    fun clearLastResult() {
        _lastResult.value = null
    }

    fun setShareMode(mode: ShareMode) = viewModelScope.launch {
        appCtx.settingsRepo.setShareMode(mode)
    }

    fun setHistoryEnabled(enabled: Boolean) = viewModelScope.launch {
        appCtx.settingsRepo.setHistoryEnabled(enabled)
        if (!enabled) {
            // Clean slate when the user opts out, matches the privacy promise.
            appCtx.historyRepo.clearAll()
        }
    }

    fun setRemoveReferralMarketing(enabled: Boolean) = viewModelScope.launch {
        appCtx.settingsRepo.setRemoveReferralMarketing(enabled)
    }

    fun deleteHistoryEntry(entry: HistoryEntry) = viewModelScope.launch {
        appCtx.historyRepo.delete(entry)
    }

    fun clearHistory() = viewModelScope.launch {
        appCtx.historyRepo.clearAll()
    }

    fun updateRulesNow() {
        _updateStatus.value = "更新中…"
        viewModelScope.launch {
            val result = appCtx.rulesRepo.updateFromNetwork()
            when (result) {
                is RulesRepository.UpdateResult.Success -> {
                    appCtx.loadRules(forceReload = true)
                    appCtx.settingsRepo.setRulesMeta(result.version, System.currentTimeMillis())
                    _updateStatus.value = "已更新：${result.version} (${result.bytes} B)"
                }
                RulesRepository.UpdateResult.NotModified -> {
                    appCtx.settingsRepo.setRulesMeta(settings.value?.rulesVersion, System.currentTimeMillis())
                    _updateStatus.value = "已是最新版本"
                }
                is RulesRepository.UpdateResult.Failure -> {
                    _updateStatus.value = "更新失敗：${result.reason}"
                }
            }
        }
    }

    fun dismissUpdateStatus() { _updateStatus.value = null }

    fun setClipboardWatchMode(mode: ClipboardWatchMode) = viewModelScope.launch {
        appCtx.settingsRepo.setClipboardWatchMode(mode)
    }
}
