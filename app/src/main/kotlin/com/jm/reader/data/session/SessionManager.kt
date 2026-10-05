package com.jm.reader.data.session

import android.content.Context
import android.content.SharedPreferences
import com.jm.reader.ui.strings.UiLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Stores login/session/host state. Mirrors what the web app keeps in localStorage.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jm_session", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_LANG = "TW"
        private const val KEY_JWT = "jwttoken"
        private const val KEY_MEMBER = "memberInfo"
        private const val KEY_LANG = "lang"
        private const val KEY_API_URL = "apiUrl"
        private const val KEY_AUTH_EXPIRY = "authExpiry"
        private const val KEY_IMG_HOST = "imgHost"
    }

    // --- Auth ---

    var jwtToken: String?
        get() = prefs.getString(KEY_JWT, null)
        set(value) = prefs.edit().putString(KEY_JWT, value).apply()

    var memberJson: String?
        get() = prefs.getString(KEY_MEMBER, null)
        set(value) = prefs.edit().putString(KEY_MEMBER, value).apply()

    var language: UiLanguage
        get() = runCatching {
            UiLanguage.valueOf(prefs.getString(KEY_LANG, UiLanguage.ZH_TW.name) ?: UiLanguage.ZH_TW.name)
        }.getOrDefault(UiLanguage.ZH_TW)
        set(value) = prefs.edit().putString(KEY_LANG, value.name).apply()

    /** Server-side language param sent on GET requests (TW/CN only). */
    val apiLang: String
        get() = language.apiLang

    /** The `s` field of memberInfo used as the AVS cookie value. */
    val avsSession: String?
        get() = memberJson?.let { runCatching { JSONObject(it).optString("s") }.getOrNull() }

    /**
     * The app API does not hand out a bearer token: `/login` returns the member payload whose
     * `s` field is then sent back as the `AVS` cookie, and that cookie is what authenticates
     * every later request. So a session counts as logged in when we hold that payload, not
     * when a `jwttoken` happens to be present (the mobile API never sends one).
     */
    val isLoggedIn: Boolean
        get() = memberJson?.isNotBlank() == true && (!avsSession.isNullOrBlank() || !jwtToken.isNullOrBlank())

    private val _loggedIn = MutableStateFlow(isLoggedIn)

    /** Emits on every login / logout so screens refresh without being recreated. */
    val loggedInFlow: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun saveAuth(token: String, memberData: JSONObject) {
        jwtToken = token
        memberJson = memberData.toString()
        prefs.edit().putLong(KEY_AUTH_EXPIRY, System.currentTimeMillis() + 60 * 60 * 1000L).apply()
        _loggedIn.value = isLoggedIn
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_JWT)
            .remove(KEY_MEMBER)
            .remove(KEY_AUTH_EXPIRY)
            .apply()
        _loggedIn.value = false
    }

    /** Clears auth when the 1-hour web-app expiry has passed. */
    fun isAuthExpired(): Boolean {
        val expiry = prefs.getLong(KEY_AUTH_EXPIRY, 0L)
        return expiry != 0L && System.currentTimeMillis() > expiry
    }

    // --- Host ---

    var apiUrl: String?
        get() = prefs.getString(KEY_API_URL, null)
        set(value) = prefs.edit().putString(KEY_API_URL, value).apply()

    var imgHost: String
        get() = prefs.getString(KEY_IMG_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IMG_HOST, value).apply()
}
