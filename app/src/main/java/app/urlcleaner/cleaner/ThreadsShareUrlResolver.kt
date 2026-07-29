package app.urlcleaner.cleaner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

fun interface UrlRedirectResolver {
    suspend fun resolve(url: String): String
}

/**
 * Resolves Threads' opaque `/share/<code>` wrappers to their real post URL.
 *
 * Both the request and redirect target are deliberately restricted to known
 * Threads hosts and paths so arbitrary URLs never trigger network requests.
 */
class ThreadsShareUrlResolver(
    private val fetchRedirectLocation: suspend (String) -> String? = ::requestRedirectLocation,
) : UrlRedirectResolver {

    override suspend fun resolve(url: String): String {
        val source = acceptedUri(url, SHARE_PATH) ?: return url
        val location = try {
            fetchRedirectLocation(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return url
        val target = runCatching { source.resolve(location) }.getOrNull() ?: return url
        return acceptedUri(target.toASCIIString(), POST_PATH)?.toASCIIString() ?: url
    }

    private fun acceptedUri(url: String, pathPattern: Regex): URI? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host?.lowercase() !in THREADS_HOSTS) return null
        if (uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        if (!pathPattern.matches(uri.rawPath.orEmpty())) return null
        return uri
    }

    companion object {
        private val THREADS_HOSTS = setOf(
            "threads.com",
            "www.threads.com",
            "threads.net",
            "www.threads.net",
        )
        private val SHARE_PATH = Regex("""/share/[A-Za-z0-9_-]+/?""")
        private val POST_PATH = Regex("""/@[^/]+/post/[A-Za-z0-9_-]+/?""")
    }
}

private suspend fun requestRedirectLocation(url: String): String? =
    withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Accept", "text/html")
        }
        try {
            if (connection.responseCode in 300..399) {
                connection.getHeaderField("Location")
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }
