package com.claudecode.mobile

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long
)

class OAuthManager(context: Context) {

    companion object {
        private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val AUTH_URL = "https://claude.ai/oauth/authorize"
        private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"
        private const val REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"
        private const val SCOPES = "user:inference user:profile"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("claude_oauth", 0)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var codeVerifier: String? = null

    val isLoggedIn: Boolean
        get() = prefs.getString("access_token", null) != null

    val accessToken: String?
        get() = prefs.getString("access_token", null)

    fun buildAuthUrl(): String {
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        // Also persist it in case the app is killed during auth
        prefs.edit().putString("code_verifier", verifier).apply()

        val challenge = generateCodeChallenge(verifier)
        val state = generateRandomString(32)
        prefs.edit().putString("oauth_state", state).apply()

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    fun exchangeCode(authorizationCode: String): OAuthTokens {
        val verifier = codeVerifier ?: prefs.getString("code_verifier", null)
            ?: throw IllegalStateException("No code verifier found - restart OAuth flow")

        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorizationCode.trim())
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response from token endpoint")

        if (!response.isSuccessful) {
            throw Exception("Token exchange failed (${response.code}): $body")
        }

        val json = JSONObject(body)
        val tokens = OAuthTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAt = System.currentTimeMillis() + (json.optLong("expires_in", 28800) * 1000)
        )

        saveTokens(tokens)
        return tokens
    }

    fun refreshTokens(): OAuthTokens {
        val refreshToken = prefs.getString("refresh_token", null)
            ?: throw Exception("No refresh token - please log in again")

        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(formBody)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response from token endpoint")

        if (!response.isSuccessful) {
            // Refresh token expired - need full re-auth
            if (response.code == 400 || response.code == 401) {
                clearTokens()
                throw Exception("Session expired - please log in again")
            }
            throw Exception("Token refresh failed (${response.code}): $body")
        }

        val json = JSONObject(body)
        val tokens = OAuthTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.optString("refresh_token", refreshToken),
            expiresAt = System.currentTimeMillis() + (json.optLong("expires_in", 28800) * 1000)
        )

        saveTokens(tokens)
        return tokens
    }

    fun isTokenExpired(): Boolean {
        val expiresAt = prefs.getLong("expires_at", 0)
        // Refresh 5 minutes before expiry
        return System.currentTimeMillis() > (expiresAt - 300_000)
    }

    fun getValidAccessToken(): String {
        if (isTokenExpired()) {
            val refreshed = refreshTokens()
            return refreshed.accessToken
        }
        return accessToken ?: throw Exception("Not logged in")
    }

    fun clearTokens() {
        prefs.edit().clear().apply()
        codeVerifier = null
    }

    private fun saveTokens(tokens: OAuthTokens) {
        prefs.edit()
            .putString("access_token", tokens.accessToken)
            .putString("refresh_token", tokens.refreshToken)
            .putLong("expires_at", tokens.expiresAt)
            .apply()
    }

    private fun generateCodeVerifier(): String = generateRandomString(64)

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
