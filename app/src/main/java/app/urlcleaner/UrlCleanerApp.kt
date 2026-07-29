package app.urlcleaner

import android.app.Application
import app.urlcleaner.cleaner.RuleSet
import app.urlcleaner.cleaner.ThreadsShareUrlResolver
import app.urlcleaner.cleaner.UrlCleaner
import app.urlcleaner.cleaner.UrlCleaningService
import app.urlcleaner.data.HistoryRepository
import app.urlcleaner.data.RulesRepository
import app.urlcleaner.data.SettingsRepository

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

    @Volatile var currentRuleSet: RuleSet = RuleSet(emptyList())
    private val redirectResolver = ThreadsShareUrlResolver()

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        historyRepo = HistoryRepository(this)
        rulesRepo = RulesRepository(this)
    }

    fun cleanerFor(removeReferralMarketing: Boolean): UrlCleaningService =
        UrlCleaningService(
            UrlCleaner(currentRuleSet, removeReferralMarketing),
            redirectResolver,
        )
}

val android.content.Context.app: UrlCleanerApp
    get() = applicationContext as UrlCleanerApp
