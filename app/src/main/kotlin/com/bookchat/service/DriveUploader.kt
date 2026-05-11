package com.bookchat.service

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveUploader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val driveScope = "oauth2:https://www.googleapis.com/auth/drive.file"

    // Set when getToken throws UserRecoverableAuthException — launch this to show consent screen
    @Volatile var lastAuthIntent: Intent? = null

    suspend fun upload(file: File, accountName: String, folderId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (accountName.isBlank() || folderId.isBlank()) {
                return@withContext Result.failure(Exception("Drive account or folder not configured"))
            }

            val token = getToken(accountName) ?: return@withContext Result.failure(
                Exception(if (lastAuthIntent != null) "Drive authorization required — tap Authorize in Settings" else "Drive auth failed")
            )

            val mimeType = when (file.extension.lowercase()) {
                "epub" -> "application/epub+zip"
                "mobi" -> "application/x-mobipocket-ebook"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }

            val metadata = JSONObject().apply {
                put("name", file.name)
                put("parents", org.json.JSONArray().put(folderId))
            }.toString()

            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(file.asRequestBody(mimeType.toMediaType()))
                .build()

            val request = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        file.delete()
                        Result.success(Unit)
                    } else {
                        val body = response.body?.string() ?: ""
                        Result.failure(Exception("Drive upload failed ${response.code}: $body"))
                    }
                }
            }.getOrElse { e -> Result.failure(Exception(e.message ?: "Upload failed")) }
        }

    suspend fun testAuth(accountName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getToken(accountName)
        if (token != null) Result.success(Unit)
        else Result.failure(Exception(if (lastAuthIntent != null) "Authorization required" else "Auth failed"))
    }

    private fun getToken(accountName: String): String? {
        lastAuthIntent = null
        return runCatching {
            GoogleAuthUtil.getToken(context, Account(accountName, "com.google"), driveScope)
        }.getOrElse { e ->
            if (e is UserRecoverableAuthException) lastAuthIntent = e.intent
            null
        }
    }

    /** Extract folder ID from a Drive sharing URL or return as-is if already an ID. */
    fun parseFolderId(input: String): String {
        val urlMatch = Regex("""/folders/([a-zA-Z0-9_-]+)""").find(input)
        return urlMatch?.groupValues?.get(1) ?: input.trim()
    }
}
