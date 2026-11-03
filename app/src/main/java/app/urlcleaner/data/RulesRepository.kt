package app.urlcleaner.data

import android.content.Context
import app.urlcleaner.cleaner.RuleSet
import app.urlcleaner.cleaner.RulesParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Loads ClearURLs rules in this order:
 *   1. app-private cache (`filesDir/rules.json`) if it exists
 *   2. bundled asset (`assets/clearurls-rules.json`)
 * Then merges a small [SUPPLEMENTAL_ASSET] on top so we can ship rules for sites
 * ClearURLs doesn't cover yet (e.g. newer social share tokens). Supplemental rules are never
 * overwritten by network updates.
 *
 * And updates the cache on demand via [updateFromNetwork].
 */
class RulesRepository(private val context: Context) {

    private val cacheFile = File(context.filesDir, "rules.json")

    data class LoadedRules(val ruleSet: RuleSet, val sourceLabel: String)

    sealed class UpdateResult {
        data class Success(val version: String, val bytes: Int) : UpdateResult()
        data object NotModified : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    suspend fun load(): LoadedRules = withContext(Dispatchers.IO) {
        val (mainJson, label) = if (cacheFile.exists()) {
            cacheFile.readText() to "cache"
        } else {
            context.assets.open(MAIN_ASSET).bufferedReader().use { it.readText() } to "bundled"
        }
        val supplementalJson = runCatching {
            context.assets.open(SUPPLEMENTAL_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
        val merged = RulesParser.parse(mainJson)
        val combined = if (supplementalJson != null) {
            val extra = RulesParser.parse(supplementalJson)
            RuleSet(merged.providers + extra.providers)
        } else {
            merged
        }
        LoadedRules(combined, label)
    }

    suspend fun updateFromNetwork(): UpdateResult = withContext(Dispatchers.IO) {
        val conn = (URL(RULES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext UpdateResult.Failure("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val hash = sha256(body)
            val prevHash = if (cacheFile.exists()) sha256(cacheFile.readText()) else null
            if (hash == prevHash) return@withContext UpdateResult.NotModified
            cacheFile.writeText(body)
            UpdateResult.Success(version = hash.take(12), bytes = body.toByteArray().size)
        } catch (t: Throwable) {
            UpdateResult.Failure(t.message ?: t::class.java.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAIN_ASSET = "clearurls-rules.json"
        private const val SUPPLEMENTAL_ASSET = "supplemental-rules.json"
        private const val RULES_URL = "https://rules2.clearurls.xyz/data.min.json"
    }
}
