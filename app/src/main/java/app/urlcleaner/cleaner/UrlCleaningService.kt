package app.urlcleaner.cleaner

/**
 * Resolves supported server-side redirect wrappers before applying static rules.
 */
class UrlCleaningService(
    private val cleaner: UrlCleaner,
    private val redirectResolver: UrlRedirectResolver,
) {
    suspend fun clean(url: String): UrlCleaner.Result {
        val original = url.trim()
        val resolved = redirectResolver.resolve(original)
        val result = cleaner.clean(resolved)
        return if (resolved == original) {
            result
        } else {
            result.copy(original = original, wasChanged = true)
        }
    }
}
