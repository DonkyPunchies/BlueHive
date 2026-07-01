package com.example.bluehive.latestTrailersComponents.trailerViewer

import android.app.Application
import android.content.Context
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloaderImpl : Downloader() {

    companion object {
        /** Keep this in ONE place so extraction + ExoPlayer playback match. */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    // Persistent cookie jar (SharedPreferences) so consent/session cookies survive app restarts.
    private val cookieJar: CookieJar by lazy {
        PersistentCookieJar(getAppContext())
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder().apply {
                // Do NOT inject a static Cookie header. Let the CookieJar manage real cookies.
                if (original.header("User-Agent") == null) header("User-Agent", USER_AGENT)
                if (original.header("Accept-Language") == null) header("Accept-Language", "en-US,en;q=0.9")
                if (original.header("Accept") == null) {
                    header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                    )
                }
                if (original.header("Cache-Control") == null) header("Cache-Control", "no-cache")
                if (original.header("Pragma") == null) header("Pragma", "no-cache")
            }.build()
            chain.proceed(request)
        }
        .build()

    /** Expose the same OkHttpClient to ExoPlayer (OkHttpDataSource). */
    fun okHttpClient(): OkHttpClient = client

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = Request.Builder().url(url)

        // Add headers from NewPipe request
        headers.forEach { (name, values) ->
            values.forEach { value ->
                requestBuilder.addHeader(name, value)
            }
        }

        // Set method
        when (httpMethod) {
            "GET" -> requestBuilder.get()
            "POST" -> {
                val body = dataToSend?.toRequestBody(null, 0, dataToSend.size)
                    ?: ByteArray(0).toRequestBody(null, 0, 0)
                requestBuilder.post(body)
            }
            "HEAD" -> requestBuilder.head()
            "PUT" -> {
                val body = dataToSend?.toRequestBody(null, 0, dataToSend.size)
                    ?: ByteArray(0).toRequestBody(null, 0, 0)
                requestBuilder.put(body)
            }
            "DELETE" -> requestBuilder.delete()
            else -> throw UnsupportedOperationException("HTTP method $httpMethod not supported")
        }

        // ✅ Response.use {} guarantees the response is always closed
        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBodyString = response.body?.string() ?: ""

                val contentType = response.header("Content-Type")
                val finalUrl = response.request.url.toString()

                if (isLikelyCaptchaOrBlocked(response.code, contentType, finalUrl, responseBodyString)) {
                    throw ReCaptchaException("reCAPTCHA / block detected", url)
                }

                val responseHeaders = mutableMapOf<String, List<String>>()
                response.headers.names().forEach { name ->
                    responseHeaders[name] = response.headers.values(name)
                }

                return ExtractorResponse(
                    response.code,
                    response.message,
                    responseHeaders,
                    responseBodyString,
                    finalUrl
                )
            }
        } catch (e: IOException) {
            throw IOException("Network request failed: ${e.message}", e)
        }
    }

    private fun isLikelyCaptchaOrBlocked(
        code: Int,
        contentType: String?,
        finalUrl: String,
        body: String
    ): Boolean {
        // Rate-limit / bot challenge is commonly 429.
        if (code == 429) return true

        val urlLower = finalUrl.lowercase()

        // Classic Google/YouTube "unusual traffic" or consent interstitial flows.
        if (urlLower.contains("/sorry/")) return true
        if (urlLower.contains("consent.youtube.com")) return true

        // Only apply HTML heuristics to HTML responses.
        val ct = contentType?.lowercase() ?: ""
        if (!ct.contains("text/html")) return false

        val html = body.lowercase()

        // Strong indicators for real captcha/challenge pages.
        val strongSignals = listOf(
            "g-recaptcha",
            "recaptcha/api.js",
            "www.google.com/recaptcha",
            "data-sitekey",
            "our systems have detected unusual traffic",
            "unusual traffic from your computer network",
            "to continue, please verify",
            "sorry/index"
        )
        return strongSignals.any { html.contains(it) }
    }

    private fun getAppContext(): Context {
        val app = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication")
            currentApplication.invoke(null) as Application?
        }.getOrNull()

        return app?.applicationContext
            ?: throw IllegalStateException(
                "DownloaderImpl: Application context not available. " +
                        "Make sure this is called after Application.onCreate()."
            )
    }

    /**
     * Persistent CookieJar:
     * - keeps cookies in memory for speed
     * - persists only persistent cookies (expiry-based) to SharedPreferences
     */
    private class PersistentCookieJar(context: Context) : CookieJar {

        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private val store: MutableList<Cookie> = mutableListOf()

        init {
            loadFromDisk()
        }

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            var changed = false
            val now = System.currentTimeMillis()

            for (c in cookies) {
                if (c.expiresAt < now) {
                    changed = removeCookie(c) || changed
                    continue
                }
                changed = removeCookie(c) || changed
                store.add(c)
                changed = true
            }

            if (changed) persistToDisk()
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()

            var removed = false
            val it = store.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c.expiresAt < now) {
                    it.remove()
                    removed = true
                }
            }
            if (removed) persistToDisk()

            return store.filter { it.matches(url) }
        }

        private fun removeCookie(cookie: Cookie): Boolean {
            val idx = store.indexOfFirst {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (idx >= 0) {
                store.removeAt(idx)
                return true
            }
            return false
        }

        private fun loadFromDisk() {
            try {
                val raw = prefs.getString(KEY_COOKIES_JSON, null) ?: return
                val arr = JSONArray(raw)
                val now = System.currentTimeMillis()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val cookie = decodeCookie(obj) ?: continue
                    if (cookie.expiresAt >= now) store.add(cookie)
                }

                Log.d(TAG, "Loaded ${store.size} cookies from disk")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cookies: ${e.message}")
            }
        }

        private fun persistToDisk() {
            try {
                val persistent = store.filter { it.persistent }
                val arr = JSONArray()
                for (c in persistent) arr.put(encodeCookie(c))
                prefs.edit().putString(KEY_COOKIES_JSON, arr.toString()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist cookies: ${e.message}")
            }
        }

        private fun encodeCookie(c: Cookie): JSONObject =
            JSONObject().apply {
                put("name", c.name)
                put("value", c.value)
                put("expiresAt", c.expiresAt)
                put("domain", c.domain)
                put("path", c.path)
                put("secure", c.secure)
                put("httpOnly", c.httpOnly)
                put("hostOnly", c.hostOnly)
            }

        private fun decodeCookie(obj: JSONObject): Cookie? = runCatching {
            val name = obj.getString("name")
            val value = obj.getString("value")
            val expiresAt = obj.getLong("expiresAt")
            val domain = obj.getString("domain")
            val path = obj.getString("path")
            val secure = obj.optBoolean("secure", false)
            val httpOnly = obj.optBoolean("httpOnly", false)
            val hostOnly = obj.optBoolean("hostOnly", false)

            Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .path(path)
                .apply {
                    if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        }.getOrNull()

        companion object {
            private const val TAG = "PersistentCookieJar"
            private const val PREFS_NAME = "bluehive_okhttp_cookies"
            private const val KEY_COOKIES_JSON = "cookies_json_v1"
        }
    }
}
