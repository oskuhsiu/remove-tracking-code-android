package app.urlcleaner.cleaner

import java.net.URLDecoder

/**
 * Applies a [RuleSet] (ClearURLs format) to strip tracking parameters from a URL.
 *
 * Flow per call:
 *  1. redirections (recurse into captured target; max depth 5)
 *  2. for each matching provider, if not excepted:
 *      - completeProvider → mark blocked, keep scanning other providers
 *      - rawRules (regex replace on full URL)
 *      - rules (+ referralMarketing if enabled) strip matching query keys
 *  3. tidy dangling `?`/`&` left by rawRules
 */
class UrlCleaner(
    private val ruleSet: RuleSet,
    private val removeReferralMarketing: Boolean = true,
) {
    data class Result(
        val original: String,
        val cleaned: String,
        val paramsRemoved: Int,
        val isBlocked: Boolean,
        val wasChanged: Boolean,
    )

    fun clean(url: String): Result = cleanInternal(url.trim(), depth = 0)

    private fun cleanInternal(url: String, depth: Int): Result {
        if (depth > MAX_REDIRECT_DEPTH) return unchanged(url)

        // Phase 1: redirections
        for (provider in ruleSet.providers) {
            if (!provider.urlPattern.containsMatchIn(url)) continue
            if (matchesAny(provider.exceptions, url)) continue
            val redirected = firstRedirectionTarget(provider, url) ?: continue
            if (redirected != url && redirected.startsWith("http", ignoreCase = true)) {
                return cleanInternal(redirected, depth + 1)
            }
        }

        // Phase 2: rawRules + query strip
        var current = url
        var removed = 0
        var blocked = false

        for (provider in ruleSet.providers) {
            if (!provider.urlPattern.containsMatchIn(current)) continue
            if (matchesAny(provider.exceptions, current)) continue

            if (provider.completeProvider) {
                blocked = true
                continue
            }

            for (rr in provider.rawRules) {
                val before = current
                current = rr.replace(current, "")
                if (current != before) removed++
            }

            val keyRegexes = when {
                removeReferralMarketing && provider.referralMarketing.isNotEmpty() ->
                    provider.rules + provider.referralMarketing
                else -> provider.rules
            }
            if (keyRegexes.isNotEmpty()) {
                val (stripped, n) = stripQueryKeys(current, keyRegexes)
                current = stripped
                removed += n
            }
        }

        current = tidy(current)

        return Result(
            original = url,
            cleaned = if (blocked) "" else current,
            paramsRemoved = removed,
            isBlocked = blocked,
            wasChanged = blocked || current != url,
        )
    }

    private fun firstRedirectionTarget(provider: Provider, url: String): String? {
        for (r in provider.redirections) {
            val m = r.find(url) ?: continue
            if (m.groupValues.size < 2) continue
            val raw = m.groupValues[1]
            if (raw.isEmpty()) continue
            var decoded = decode(raw)
            // Some ClearURLs redirections capture a double-encoded target; decode once more if
            // the first pass didn't land us on a URL.
            if (!decoded.startsWith("http", ignoreCase = true)) decoded = decode(decoded)
            if (decoded.startsWith("http", ignoreCase = true)) return decoded
        }
        return null
    }

    private fun stripQueryKeys(url: String, keyRegexes: List<Regex>): Pair<String, Int> {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url to 0
        val fIdx = url.indexOf('#')
        if (fIdx >= 0 && fIdx < qIdx) return url to 0
        val beforeQuery = url.substring(0, qIdx)
        val queryEnd = if (fIdx >= 0) fIdx else url.length
        val queryStr = url.substring(qIdx + 1, queryEnd)
        val fragment = if (fIdx >= 0) url.substring(fIdx) else ""
        if (queryStr.isEmpty()) return url to 0

        var removed = 0
        val kept = queryStr.split('&').filter { param ->
            if (param.isEmpty()) return@filter false
            val eq = param.indexOf('=')
            val key = if (eq >= 0) param.substring(0, eq) else param
            // Match decoded names too, without re-encoding any retained query bytes.
            // Keep the raw match for ClearURLs rules that explicitly include escapes.
            val decodedKey = decode(key)
            val strip = keyRegexes.any { it.matches(key) || it.matches(decodedKey) }
            if (strip) removed++
            !strip
        }
        if (removed == 0) return url to 0

        val newQuery = kept.joinToString("&")
        val rebuilt = buildString {
            append(beforeQuery)
            if (newQuery.isNotEmpty()) append('?').append(newQuery)
            append(fragment)
        }
        return rebuilt to removed
    }

    private fun tidy(url: String): String {
        // Remove empty query segments left by rawRules, without touching paths,
        // fragments, or literal question marks inside retained parameter values.
        val fragmentIndex = url.indexOf('#')
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val beforeFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val queryIndex = beforeFragment.indexOf('?')
        if (queryIndex < 0) return url
        val query = beforeFragment.substring(queryIndex + 1)
            .split('&').filter { it.isNotEmpty() }.joinToString("&")
        return beforeFragment.substring(0, queryIndex) +
            (if (query.isEmpty()) "" else "?$query") + fragment
    }

    private fun matchesAny(patterns: List<Regex>, url: String): Boolean =
        patterns.any { it.containsMatchIn(url) }

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun unchanged(url: String) = Result(url, url, 0, false, false)

    companion object {
        private const val MAX_REDIRECT_DEPTH = 5
    }
}
