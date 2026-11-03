package app.urlcleaner.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class UrlCleanerTest {

    @Test fun `removes fbclid from facebook`() {
        val r = cleaner.clean("https://www.facebook.com/some/page/?fbclid=IwAR0abcdef")
        assertEquals("https://www.facebook.com/some/page/", r.cleaned)
        assertTrue(r.wasChanged)
        assertFalse(r.isBlocked)
    }

    @Test fun `unwraps facebook l_php redirect`() {
        val wrapped = "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2Fpage%3Fa%3D1&h=AT"
        val r = cleaner.clean(wrapped)
        assertTrue("got ${r.cleaned}", r.cleaned.startsWith("https://example.com/page"))
    }

    @Test fun `removes igshid from instagram`() {
        val r = cleaner.clean("https://www.instagram.com/p/abc123/?igshid=MzRlODBiNWFlZA%3D%3D")
        assertEquals("https://www.instagram.com/p/abc123/", r.cleaned)
    }

    @Test fun `cleans reddit share params`() {
        val r = cleaner.clean("https://www.reddit.com/r/kotlin/comments/xyz/?share_id=abc&utm_medium=android_app&utm_source=share")
        assertEquals("https://www.reddit.com/r/kotlin/comments/xyz/", r.cleaned)
    }

    @Test fun `cleans youtube share`() {
        val r = cleaner.clean("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc123&pp=xyz")
        // v= must survive. si and pp are tracking.
        assertTrue("got ${r.cleaned}", r.cleaned.contains("v=dQw4w9WgXcQ"))
        assertFalse(r.cleaned.contains("si="))
        assertFalse(r.cleaned.contains("pp="))
    }

    @Test fun `cleans amazon tag`() {
        val r = cleaner.clean("https://www.amazon.com/dp/B00TEST/?tag=somepartner-20&ref=nav_logo")
        assertFalse("should strip tag: ${r.cleaned}", r.cleaned.contains("tag="))
        assertTrue(r.cleaned.startsWith("https://www.amazon.com/dp/B00TEST"))
    }

    @Test fun `cleans x share`() {
        val r = cleaner.clean("https://x.com/someone/status/1234567890?s=20&t=abc")
        assertFalse(r.cleaned.contains("s=20"))
        assertFalse(r.cleaned.contains("t=abc"))
        assertTrue(r.cleaned.startsWith("https://x.com/someone/status/1234567890"))
    }

    @Test fun `cleans reported x share after previous filtering`() {
        val r = cleaner.clean("https://x.com/zenok__/status/2113589461628117389?a=siAS4aBSnDtBCzOOr9U&s=19")
        assertEquals("https://x.com/zenok__/status/2113589461628117389", r.cleaned)
        assertEquals(2, r.paramsRemoved)
    }

    @Test fun `cleans reported instagram reel after previous filtering`() {
        val r = cleaner.clean("https://www.instagram.com/reel/Deny7rGSsB3/?dlrf=bDRwZmVnMWE0NnU0")
        assertEquals("https://www.instagram.com/reel/Deny7rGSsB3/", r.cleaned)
        assertEquals(1, r.paramsRemoved)
    }

    @Test fun `cleans newer instagram share tokens and preserves content selection`() {
        val url = "https://www.instagram.com/p/ABC123/?igsh=old&igsi=1&stkn=2&mdxt=3&cplk=4&obrf=5&vrfl=6&rpxt=7&dlrf=8&ig_rid=9&click_source=share&si=10&img_index=3&comment_id=123&open_comments=1&story_media_id=456&hl=zh-tw#comments"
        val expected = "https://www.instagram.com/p/ABC123/?img_index=3&comment_id=123&open_comments=1&story_media_id=456&hl=zh-tw#comments"
        assertEquals(expected, cleaner.clean(url).cleaned)
    }

    @Test fun `cleans new x share token across status hosts and paths`() {
        listOf(
            "https://x.com/user/status/123",
            "https://mobile.twitter.com/user/status/123/photo/1",
            "https://www.x.com/i/web/status/123",
            "https://twitter.com/i/status/123",
        ).forEach { base ->
            val r = cleaner.clean("$base?a=token&s=19&t=old&lang=zh-tw#photo")
            assertEquals(base, "$base?lang=zh-tw#photo", r.cleaned)
            assertEquals(3, r.paramsRemoved)
        }
    }

    @Test fun `new share rules also work with an older upstream cache`() {
        val supplemental = RulesParser.parse(File("src/main/assets/supplemental-rules.json").readText())
        val olderCacheCleaner = UrlCleaner(supplemental)
        assertEquals(
            "https://x.com/zenok__/status/2113589461628117389",
            olderCacheCleaner.clean("https://x.com/zenok__/status/2113589461628117389?a=share&s=19&t=old").cleaned,
        )
        assertEquals(
            "https://www.instagram.com/reel/Deny7rGSsB3/",
            olderCacheCleaner.clean("https://www.instagram.com/reel/Deny7rGSsB3/?igsh=old&dlrf=new&rpxt=next").cleaned,
        )
    }

    @Test fun `new tracking names remain untouched on unrelated domains`() {
        listOf(
            "https://example.com/user/status/123?a=required&dlrf=value&rpxt=value",
            "https://x.com.example.org/user/status/123?a=required",
            "https://instagram.com.example.org/p/ABC/?dlrf=required",
            "https://x.com/search?q=kotlin&f=live&a=required",
            "https://www.tiktok.com/search?q=kotlin&sec_user_id=required",
            "https://www.reddit.com/search?q=kotlin&entry_point=required",
            "https://open.spotify.com.example.org/track/123?pi=required",
            "https://youtube.com.example.org/watch?v=abc&source_ve_path=required",
        ).forEach { url -> assertEquals(url, cleaner.clean(url).cleaned) }
    }

    @Test fun `cleans additional social share trackers while keeping functional parameters`() {
        val cases = listOf(
            "https://www.tiktok.com/@user/video/123?is_copy_url=1&is_from_webapp=1&sender_device=pc&sec_user_id=share&share_link_id=id&share_app_id=1&share_author_id=2&share_region=TW&social_share_type=4&trackParams=x&ug_btm=y&web_id=z&tt_from=copy&lang=zh-Hant" to
                "https://www.tiktok.com/@user/video/123?lang=zh-Hant",
            "https://open.spotify.com/track/123?si=share&pi=phone&sp_cid=campaign&dlsi=token&context=spotify%3Aplaylist%3A456" to
                "https://open.spotify.com/track/123?context=spotify%3Aplaylist%3A456",
            "https://www.reddit.com/r/kotlin/comments/123/title/?entry_point=share&target_user=abc&post_index=1&post_fullname=t3_123&sort=new" to
                "https://www.reddit.com/r/kotlin/comments/123/title/?sort=new",
            "https://www.youtube.com/watch?v=abc&t=90&list=playlist&index=2&embeds_referring_euri=origin&embeds_referring_origin=source&source_ve_path=share" to
                "https://www.youtube.com/watch?v=abc&t=90&list=playlist&index=2",
        )
        cases.forEach { (url, expected) ->
            val r = cleaner.clean(url)
            assertEquals(url, expected, r.cleaned)
            assertEquals("cleaning twice must be stable: $url", expected, cleaner.clean(r.cleaned).cleaned)
        }
    }

    @Test fun `unwraps google search redirect`() {
        val r = cleaner.clean("https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Farticle&sa=U&ved=xyz")
        assertTrue("got ${r.cleaned}", r.cleaned.startsWith("https://example.com/article"))
    }

    @Test fun `cleans threads xmt and slof`() {
        val r = cleaner.clean("https://www.threads.com/@u/post/X?xmt=AQABCDEF&slof=1")
        assertEquals("https://www.threads.com/@u/post/X", r.cleaned)
    }

    @Test fun `cleans threads net alternate tld`() {
        val r = cleaner.clean("https://www.threads.net/@u/post/X?xmt=abc")
        assertEquals("https://www.threads.net/@u/post/X", r.cleaned)
    }

    @Test fun `cleans tiktok params`() {
        val r = cleaner.clean("https://www.tiktok.com/@user/video/123?_r=1&_t=abc&is_copy_url=1")
        assertFalse(r.cleaned.contains("_r="))
        assertFalse(r.cleaned.contains("_t="))
    }

    @Test fun `cleans linkedin trk`() {
        val r = cleaner.clean("https://www.linkedin.com/in/someone?trk=abc&trkCampaign=xyz")
        assertFalse(r.cleaned.contains("trk="))
    }

    @Test fun `cleans spotify si`() {
        val r = cleaner.clean("https://open.spotify.com/track/123?si=abcdef1234567890")
        assertFalse(r.cleaned.contains("si="))
    }

    @Test fun `strips generic utm params via globalRules`() {
        val r = cleaner.clean("https://somewhere-random.example.org/article?utm_source=newsletter&utm_medium=email&utm_campaign=spring&keep=yes")
        assertTrue("kept: ${r.cleaned}", r.cleaned.contains("keep=yes"))
        assertFalse(r.cleaned.contains("utm_"))
    }

    @Test fun `strips gclid fbclid mc_cid hsenc globally`() {
        val r = cleaner.clean("https://example.com/a?gclid=1&fbclid=2&mc_cid=3&_hsenc=4&keep=me")
        assertTrue(r.cleaned.contains("keep=me"))
        listOf("gclid", "fbclid", "mc_cid", "_hsenc").forEach {
            assertFalse("$it should be gone from ${r.cleaned}", r.cleaned.contains("$it="))
        }
    }

    @Test fun `strips encoded tracking keys without rewriting kept parameters`() {
        val r = cleaner.clean("https://example.com/a?%75tm_source=share&%66bclid=token&q=a%2Bb+c&filter%5B0%5D=one%26two#section")
        assertEquals("https://example.com/a?q=a%2Bb+c&filter%5B0%5D=one%26two#section", r.cleaned)
        assertEquals(2, r.paramsRemoved)
    }

    @Test fun `preserves malformed query escapes while removing tracking`() {
        val r = cleaner.clean("https://example.com/a?bad%ZZ=value%ZZ&utm_source=share")
        assertEquals("https://example.com/a?bad%ZZ=value%ZZ", r.cleaned)
    }

    @Test fun `does not treat fragment query as tracking parameters`() {
        val url = "https://example.com/#/search?utm_source=route&keep=1"
        val r = cleaner.clean(url)
        assertEquals(url, r.cleaned)
        assertFalse(r.wasChanged)
    }

    @Test fun `preserves fragment query bytes after removing real tracking`() {
        val r = cleaner.clean("https://example.com/?utm_source=share#/search?&q=one&&two&")
        assertEquals("https://example.com/#/search?&q=one&&two&", r.cleaned)
    }

    @Test fun `decodes keys once and keeps duplicates and tracking text in values`() {
        val r = cleaner.clean("https://example.com/?utm_source=one&%75tm_source=two&q=utm_source%3Dkept&q=second&%2575tm_source=keep")
        assertEquals("https://example.com/?q=utm_source%3Dkept&q=second&%2575tm_source=keep", r.cleaned)
        assertEquals(2, r.paramsRemoved)
    }

    @Test fun `query cleanup preserves literal question marks and path separators`() {
        val r = cleaner.clean("https://example.com/path&&part?q=why?&utm_source=share")
        assertEquals("https://example.com/path&&part?q=why?", r.cleaned)
        val clean = "https://example.com/path&&part#anchor?&"
        assertEquals(clean, cleaner.clean(clean).cleaned)
    }

    @Test fun `encoded referral keys respect the removal toggle`() {
        val url = "https://example.com/?%72ef=campaign&keep=1"
        assertEquals("https://example.com/?keep=1", cleaner.clean(url).cleaned)
        val off = UrlCleaner(ruleSet, removeReferralMarketing = false)
        assertEquals(url, off.clean(url).cleaned)
        assertEquals("https://www.instagram.com/p/ABC/", off.clean("https://www.instagram.com/p/ABC/?dlrf=share").cleaned)
    }

    @Test fun `no change when url is clean`() {
        val clean = "https://example.com/path"
        val r = cleaner.clean(clean)
        assertEquals(clean, r.cleaned)
        assertFalse(r.wasChanged)
        assertEquals(0, r.paramsRemoved)
    }

    @Test fun `no change when only kept params present`() {
        val url = "https://example.com/search?q=hello&page=2"
        val r = cleaner.clean(url)
        assertEquals(url, r.cleaned)
        assertFalse(r.wasChanged)
    }

    @Test fun `preserves fragment`() {
        val r = cleaner.clean("https://example.com/a?utm_source=x&keep=1#section")
        assertEquals("https://example.com/a?keep=1#section", r.cleaned)
    }

    @Test fun `drops dangling question mark after removing last param`() {
        val r = cleaner.clean("https://example.com/a?utm_source=x")
        assertEquals("https://example.com/a", r.cleaned)
    }

    @Test fun `http and https both covered`() {
        val r = cleaner.clean("http://www.facebook.com/p?fbclid=x")
        assertFalse(r.cleaned.contains("fbclid"))
    }

    @Test fun `referral marketing opt-in can be disabled`() {
        val off = UrlCleaner(ruleSet, removeReferralMarketing = false)
        val withRefRule = "https://aliexpress.com/item/123.html?aff_platform=api-new-link-generate&aff_trace_key=abc"
        val rOn = cleaner.clean(withRefRule)
        val rOff = off.clean(withRefRule)
        // At minimum, the "off" result should not have removed fewer or equal, never MORE.
        assertTrue(rOn.paramsRemoved >= rOff.paramsRemoved)
    }

    @Test fun `counts removed params`() {
        val r = cleaner.clean("https://example.com/a?utm_source=x&utm_medium=y&gclid=z")
        assertTrue("expected >=1 removals, got ${r.paramsRemoved}", r.paramsRemoved >= 1)
    }

    @Test fun `cleans yahoo news guce consent params`() {
        val r = cleaner.clean("https://tw.news.yahoo.com/article-title-123.html?guccounter=1&guce_referrer=aHR0cHM6Ly9leGFtcGxlLmNvbQ&guce_referrer_sig=AQAAA")
        assertEquals("https://tw.news.yahoo.com/article-title-123.html", r.cleaned)
        assertTrue(r.wasChanged)
    }

    @Test fun `cleans yahoo soc_src and ncid`() {
        val r = cleaner.clean("https://news.yahoo.com/some-article.html?soc_src=social-sh&soc_trk=tw&ncid=txtlnkusaolp00000058")
        assertEquals("https://news.yahoo.com/some-article.html", r.cleaned)
    }

    @Test fun `cleans yahoo dot-tsrc`() {
        val r = cleaner.clean("https://finance.yahoo.com/news/foo.html?.tsrc=fin-srch")
        assertEquals("https://finance.yahoo.com/news/foo.html", r.cleaned)
    }

    @Test fun `cleans engadget guccounter`() {
        val r = cleaner.clean("https://www.engadget.com/some-post-120000123.html?guccounter=1")
        assertEquals("https://www.engadget.com/some-post-120000123.html", r.cleaned)
    }

    @Test fun `cleans bbc at_medium and at_campaign`() {
        val r = cleaner.clean("https://www.bbc.com/news/articles/abc123?at_medium=custom7&at_campaign=64&at_custom1=part&keep=yes")
        assertTrue(r.cleaned.contains("keep=yes"))
        assertFalse(r.cleaned.contains("at_medium"))
        assertFalse(r.cleaned.contains("at_campaign"))
        assertFalse(r.cleaned.contains("at_custom1"))
    }

    @Test fun `cleans daily mail ito`() {
        val r = cleaner.clean("https://www.dailymail.co.uk/news/article-12345/headline.html?ito=social-facebook_news&keep=1")
        assertTrue(r.cleaned.contains("keep=1"))
        assertFalse(r.cleaned.contains("ito="))
    }

    @Test fun `cleans nyt smid and smtyp`() {
        val r = cleaner.clean("https://www.nytimes.com/2024/01/01/world/article.html?smid=tw-share&smtyp=cur")
        assertEquals("https://www.nytimes.com/2024/01/01/world/article.html", r.cleaned)
    }

    @Test fun `cleans xtor at-internet tracker`() {
        val r = cleaner.clean("https://www.example-news.fr/article?xtor=RSS-200&keep=2")
        assertTrue(r.cleaned.contains("keep=2"))
        assertFalse(r.cleaned.contains("xtor="))
    }

    @Test fun `cleans webtrends WT dotted params`() {
        val r = cleaner.clean("https://www.example.com/page?WT.mc_id=email&WT.tsrc=newsletter&keep=ok")
        assertTrue(r.cleaned.contains("keep=ok"))
        assertFalse("WT.mc_id leaked: ${r.cleaned}", r.cleaned.contains("WT.mc_id"))
        assertFalse(r.cleaned.contains("WT.tsrc"))
    }

    @Test fun `cleans intcmp internal campaign`() {
        val r = cleaner.clean("https://www.bloomberg.com/news/articles/2024-01-01/foo?intcmp=storyline&keep=1")
        assertTrue(r.cleaned.contains("keep=1"))
        assertFalse(r.cleaned.contains("intcmp"))
    }

    @Test fun `cleans tw udn from`() {
        val r = cleaner.clean("https://udn.com/news/story/7314/8888888?from=udn-ch1003_breaknews")
        assertEquals("https://udn.com/news/story/7314/8888888", r.cleaned)
    }

    @Test fun `cleans tw ettoday from and ercamp`() {
        val r = cleaner.clean("https://www.ettoday.net/news/20240101/1234567.htm?from=fb_ettoday&ercamp=hot")
        assertEquals("https://www.ettoday.net/news/20240101/1234567.htm", r.cleaned)
    }

    @Test fun `cleans chinatimes chdtv and ctrss`() {
        val r = cleaner.clean("https://www.chinatimes.com/realtimenews/20240101001234-260407?chdtv&ctrss=01")
        assertEquals("https://www.chinatimes.com/realtimenews/20240101001234-260407", r.cleaned)
    }

    @Test fun `cleans wsj mod and st`() {
        val r = cleaner.clean("https://www.wsj.com/articles/foo-bar-12345?mod=Searchresults_pos1&st=mystory")
        assertEquals("https://www.wsj.com/articles/foo-bar-12345", r.cleaned)
    }

    @Test fun `cleans wapo itid and wpisrc`() {
        val r = cleaner.clean("https://www.washingtonpost.com/world/2024/01/01/foo/?itid=hp_top_table_main&wpisrc=nl_daily202")
        assertEquals("https://www.washingtonpost.com/world/2024/01/01/foo/", r.cleaned)
    }

    @Test fun `cleans bloomberg leadSource and sref`() {
        val r = cleaner.clean("https://www.bloomberg.com/news/articles/2024-01-01/foo?leadSource=uverify&sref=abc")
        assertEquals("https://www.bloomberg.com/news/articles/2024-01-01/foo", r.cleaned)
    }

    @Test fun `cleans ft segmentid`() {
        val r = cleaner.clean("https://www.ft.com/content/abc-123?segmentid=1234&shareType=enterprise")
        assertEquals("https://www.ft.com/content/abc-123", r.cleaned)
    }

    @Test fun `cleans substack r and s`() {
        val r = cleaner.clean("https://someone.substack.com/p/article-name?r=abc123&s=w")
        assertEquals("https://someone.substack.com/p/article-name", r.cleaned)
    }

    @Test fun `cleans pinterest invite_code`() {
        val r = cleaner.clean("https://www.pinterest.com/pin/123456/?invite_code=abc&sender=999&nic_v2=1")
        assertEquals("https://www.pinterest.com/pin/123456/", r.cleaned)
    }

    @Test fun `cleans sina wm and tj`() {
        val r = cleaner.clean("https://news.sina.com.cn/c/2024-01-01/doc-abc.shtml?wm=3049_0015&tj=cxvertical")
        assertEquals("https://news.sina.com.cn/c/2024-01-01/doc-abc.shtml", r.cleaned)
    }

    @Test fun `cleans 163 clickfrom`() {
        val r = cleaner.clean("https://www.163.com/news/article/abc.html?clickfrom=w_index_xinwen&f=xc")
        assertEquals("https://www.163.com/news/article/abc.html", r.cleaned)
    }

    @Test fun `cleans douyin sec_uid and share params`() {
        val r = cleaner.clean("https://www.douyin.com/video/12345?sec_uid=ABC&u_code=99&did=111&iid=222&share_app_name=douyin")
        assertEquals("https://www.douyin.com/video/12345", r.cleaned)
    }

    @Test fun `cleans toutiao share_iid`() {
        val r = cleaner.clean("https://www.toutiao.com/article/12345?share_iid=abc&tt_from=weixin&wxshare_count=1")
        assertEquals("https://www.toutiao.com/article/12345", r.cleaned)
    }

    @Test fun `cleans jd distribute_id`() {
        val r = cleaner.clean("https://item.jd.com/12345.html?cu=true&distribute_id=abc&pinId=xyz")
        assertEquals("https://item.jd.com/12345.html", r.cleaned)
    }

    @Test fun `cleans baijiahao from preserves id`() {
        val r = cleaner.clean("https://baijiahao.baidu.com/s?id=1234567890&from=share&fr=spider")
        assertEquals("https://baijiahao.baidu.com/s?id=1234567890", r.cleaned)
    }

    @Test fun `cleans zhihu utm_psn and share_text_id`() {
        val r = cleaner.clean("https://zhuanlan.zhihu.com/p/123456?utm_psn=abc&share_text_id=xyz")
        assertEquals("https://zhuanlan.zhihu.com/p/123456", r.cleaned)
    }

    @Test fun `cleans bilibili spm_id_from and vd_source`() {
        val r = cleaner.clean("https://www.bilibili.com/video/BV1234567/?spm_id_from=333.999&vd_source=abcdef&share_source=copy_web")
        assertEquals("https://www.bilibili.com/video/BV1234567/", r.cleaned)
    }

    @Test fun `cleans cnet ftag`() {
        val r = cleaner.clean("https://www.cnet.com/tech/foo/?ftag=CAD-03-10abi2f")
        assertEquals("https://www.cnet.com/tech/foo/", r.cleaned)
    }

    @Test fun `cleans foxnews icid and cmpid`() {
        val r = cleaner.clean("https://www.foxnews.com/politics/x?icid=foxnews-home&cmpid=email")
        assertEquals("https://www.foxnews.com/politics/x", r.cleaned)
    }

    companion object {
        private lateinit var ruleSet: RuleSet
        private lateinit var cleaner: UrlCleaner

        @JvmStatic
        @BeforeClass
        fun loadRules() {
            val main = RulesParser.parse(File("src/main/assets/clearurls-rules.json").readText())
            val supp = RulesParser.parse(File("src/main/assets/supplemental-rules.json").readText())
            ruleSet = RuleSet(main.providers + supp.providers)
            cleaner = UrlCleaner(ruleSet)
            require(ruleSet.providers.isNotEmpty()) { "rules failed to load" }
        }
    }
}
