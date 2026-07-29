package app.urlcleaner.cleaner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException

class ThreadsShareUrlResolverTest {

    @Test fun `resolves threads share wrapper to post url`() = runBlocking {
        val source = "https://www.threads.com/share/_hqU7fBcZ/"
        val destination =
            "https://www.threads.com/@kennyliang1204/post/DbV5aFqiVB7?xmt=tracking&slof=1"
        var requested: String? = null
        val resolver = ThreadsShareUrlResolver { url ->
            requested = url
            destination
        }

        assertEquals(destination, resolver.resolve(source))
        assertEquals(source, requested)
    }

    @Test fun `does not request ordinary urls`() = runBlocking {
        val source = "https://www.threads.com/@user/post/ABC?xmt=tracking"
        val resolver = ThreadsShareUrlResolver {
            throw AssertionError("ordinary URL must not trigger a network request")
        }

        assertEquals(source, resolver.resolve(source))
    }

    @Test fun `rejects redirects outside threads`() = runBlocking {
        val source = "https://www.threads.com/share/ABC/"
        val resolver = ThreadsShareUrlResolver {
            "https://attacker.example/@user/post/ABC?xmt=tracking"
        }

        assertEquals(source, resolver.resolve(source))
    }

    @Test fun `falls back to wrapper when lookup fails`() = runBlocking {
        val source = "https://www.threads.com/share/ABC/"
        val resolver = ThreadsShareUrlResolver {
            throw IOException("offline")
        }

        assertEquals(source, resolver.resolve(source))
    }

    @Test fun `resolved post is cleaned while original wrapper is preserved`() = runBlocking {
        val source = "https://www.threads.com/share/_hqU7fBcZ/"
        val destination =
            "https://www.threads.com/@kennyliang1204/post/DbV5aFqiVB7?xmt=tracking&slof=1"
        val rules = RulesParser.parse(
            File("src/main/assets/supplemental-rules.json").readText(),
        )
        val service = UrlCleaningService(
            cleaner = UrlCleaner(rules),
            redirectResolver = UrlRedirectResolver { destination },
        )

        val result = service.clean(source)

        assertEquals(source, result.original)
        assertEquals(
            "https://www.threads.com/@kennyliang1204/post/DbV5aFqiVB7",
            result.cleaned,
        )
        assertEquals(2, result.paramsRemoved)
        assertEquals(true, result.wasChanged)
    }
}
