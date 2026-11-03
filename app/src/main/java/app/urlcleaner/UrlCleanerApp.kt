package app.urlcleaner

import android.app.Application
import app.urlcleaner.cleaner.RuleSet
import app.urlcleaner.cleaner.ThreadsShareUrlResolver
import app.urlcleaner.cleaner.UrlCleaner
import app.urlcleaner.cleaner.UrlCleaningService
import app.urlcleaner.data.HistoryRepository
import app.urlcleaner.data.RulesRepository
import app.urlcleaner.data.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the repos as process-wide singletons. Deliberately simple — this app has
 * exactly one user, one DataStore, one rules file; DI frameworks would be overkill.
 */
class UrlCleanerApp : Application() {

    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var historyRepo: HistoryRepository
        private set
    lateinit var rulesRepo: RulesRepository
        private set

    private var currentRuleSet: RuleSet? = null
    private val rulesMutex = Mutex()
    private val redirectResolver = ThreadsShareUrlResolver()

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        historyRepo = HistoryRepository(this)
        rulesRepo = RulesRepository(this)
    }

    suspend fun loadRules(forceReload: Boolean = false): RuleSet = rulesMutex.withLock {
        val current = currentRuleSet
        if (current != null && !forceReload) {
            current
        } else {
            rulesRepo.load().ruleSet.also { currentRuleSet = it }
        }
    }

    suspend fun cleanerFor(removeReferralMarketing: Boolean): UrlCleaningService =
        UrlCleaningService(
            UrlCleaner(loadRules(), removeReferralMarketing),
            redirectResolver,
        )
}

val android.content.Context.app: UrlCleanerApp
    get() = applicationContext as UrlCleanerApp
