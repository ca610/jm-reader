package com.jm.reader.data.net

import com.jm.reader.data.model.int
import com.jm.reader.data.model.str
import com.jm.reader.data.model.toJsonObjectOrNull
import com.jm.reader.data.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Low-level API client that mirrors the JMComic3 web app's HttpUtil:
 *  - GET requests append query params + `lang`; POST requests send urlencoded FormBody
 *  - Every request carries `Tokenparam` = "<unixSecs>,2.0.30" and `Token` = md5("<unixSecs>185Hcomic3PAPP7R")
 *  - Logged-in requests add `Authorization: Bearer <jwt>` and `Cookie: AVS=<s>`
 *  - Responses come back as `{ "code": 200, "data": "<base64 AES-ECB ciphertext>" }` and are decrypted
 *    with key = ASCII(md5("<unixSecs><secret>")) (the unixSecs sent in this request).
 */
class ApiClient(
    private val session: SessionManager,
    private val http: OkHttpClient,
) {
    companion object {
        const val APP_VERSION = "2.0.30"
        const val TOKEN_SECRET = "185Hcomic3PAPP7R"
        const val CONTENT_SECRET = "18comicAPPContent"

        /** Endpoints whose responses are encrypted with md5(secret) (no time prefix). */
        private val AD_PATHS = listOf("ad_content_all", "advertise_all")
    }

    sealed class Result {
        data class Success(val code: Int, val obj: JSONObject? = null, val arr: JSONArray? = null) : Result()
        data class ApiFailure(val code: Int, val message: String?) : Result()
        data class NetworkFailure(val message: String) : Result()
    }

    suspend fun get(path: String, params: Map<String, Any?> = emptyMap()): Result =
        request("GET", path, params)

    suspend fun post(path: String, params: Map<String, Any?> = emptyMap()): Result =
        request("POST", path, params)

    private suspend fun request(method: String, path: String, params: Map<String, Any?>): Result =
        withContext(Dispatchers.IO) {
            val base = session.apiUrl ?: return@withContext Result.NetworkFailure("API 主機尚未設定")
            val time = System.currentTimeMillis() / 1000L
            val url = if (method == "GET") buildGetUrl(base, path, params) else joinUrl(base, path)
            val request = buildRequest(method, url, params, time)

            var attempt = 0
            while (attempt < 3) {
                attempt++
                try {
                    http.newCall(request).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.code == 401) {
                            // Token expired / invalid - drop the stale session so the UI shows logged-out.
                            session.clearAuth()
                        }
                        if (!resp.isSuccessful && resp.code != 401) {
                            if (attempt < 3) {
                                delay(400L)
                                continue
                            }
                            return@withContext Result.ApiFailure(resp.code, "HTTP ${resp.code}")
                        }
                        return@withContext parseResponse(body, url, time)
                    }
                } catch (e: IOException) {
                    if (attempt < 3) {
                        delay(600L * attempt)
                        continue
                    }
                    return@withContext Result.NetworkFailure(e.message ?: "網路錯誤")
                }
            }
            Result.NetworkFailure("請求失敗")
        }

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = path.trimStart('/')
        return "$b/$p"
    }

    private fun buildGetUrl(base: String, path: String, params: Map<String, Any?>): String {
        val url = joinUrl(base, path)
        val pairs = params.filter { (_, v) -> v != null && v != "" && v != Unit }
            .map { (k, v) -> "${enc(k)}=${enc(v.toString())}" }
        val query = (pairs + "lang=${enc(session.apiLang)}").joinToString("&")
        return if (query.isNotEmpty()) "$url?$query" else url
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun buildRequest(method: String, url: String, params: Map<String, Any?>, time: Long): Request {
        val tokenParam = "$time,$APP_VERSION"
        val token = Crypto.md5Hex("$time$TOKEN_SECRET")
        val headers = Headers.Builder()
            .add("Tokenparam", tokenParam)
            .add("Token", token)
        // Only send credentials we actually hold. The app API authenticates with the AVS cookie
        // (the member payload's `s` field), so an empty Authorization header is never useful.
        session.jwtToken?.takeIf { it.isNotBlank() }?.let { headers.add("Authorization", "Bearer $it") }
        session.avsSession?.takeIf { it.isNotBlank() }?.let { headers.add("Cookie", "AVS=$it") }
        val requestHeaders = headers.build()

        val builder = Request.Builder().url(url).headers(requestHeaders)
        if (method == "POST") {
            val fb = FormBody.Builder()
            params.filter { (_, v) -> v != null }.forEach { (k, v) -> fb.add(k, v.toString()) }
            builder.post(fb.build())
        }
        return builder.build()
    }

    private fun parseResponse(body: String, url: String, time: Long): Result {
        val envelope = body.toJsonObjectOrNull() ?: return Result.NetworkFailure("響應格式錯誤")
        val code = envelope.int("code", -1)
        val dataRaw = envelope.opt("data")

        // If `data` is not a string, the body wasn't encrypted (edge case).
        if (dataRaw !is String) {
            return Result.Success(code, dataRaw as? JSONObject)
        }

        val isAd = AD_PATHS.any { url.contains(it) }
        val secrets = listOf(TOKEN_SECRET, CONTENT_SECRET)
        for (secret in secrets) {
            val keyHex = if (isAd) Crypto.md5Hex(secret) else Crypto.md5Hex("$time$secret")
            val plain = Crypto.aesEcbDecrypt(dataRaw, keyHex) ?: continue
            val value = runCatching { JSONTokener(plain).nextValue() }.getOrNull()
            when (value) {
                is JSONObject -> return Result.Success(code, value)
                is JSONArray -> return Result.Success(code, null, value)
                else -> return Result.ApiFailure(code, "unexpected response payload")
            }
        }
        // Could not decrypt the payload - surface the error instead of masking it as empty success.
        return Result.ApiFailure(code, "response decrypt failed")
    }
}
