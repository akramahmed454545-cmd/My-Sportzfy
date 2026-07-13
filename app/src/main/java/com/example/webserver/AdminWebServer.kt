package com.example.webserver

import android.content.Context
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class AdminWebServer(
    private val context: Context,
    private val repository: AppRepository,
    private val onCustomizationChanged: () -> Unit = {}
) {
    companion object {
        private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    private fun isSessionValid(cookieHeader: String?, authHeader: String?): Boolean {
        if (authHeader != null) {
            val token = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                authHeader.substring("Bearer ".length).trim()
            } else {
                authHeader
            }
            val expiry = activeSessions[token]
            if (expiry != null) {
                if (System.currentTimeMillis() < expiry) {
                    activeSessions[token] = System.currentTimeMillis() + 86400000L // extend 24h
                    return true
                } else {
                    activeSessions.remove(token)
                }
            }
        }
        if (cookieHeader != null) {
            val cookies = cookieHeader.split(";")
            for (cookie in cookies) {
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "admin_session") {
                    val token = parts[1]
                    val expiry = activeSessions[token] ?: continue
                    if (System.currentTimeMillis() < expiry) {
                        activeSessions[token] = System.currentTimeMillis() + 86400000L // extend 24h
                        return true
                    } else {
                        activeSessions.remove(token)
                    }
                }
            }
        }
        return false
    }

    private fun handleLoginStatic(socket: Socket) {
        try {
            val html = context.assets.open("login.html").bufferedReader().use { it.readText() }
            sendResponse(socket, 200, html, "text/html")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error reading login.html", e)
            sendResponse(socket, 500, "Error loading login page: ${e.localizedMessage}", "text/plain")
        }
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start(port: Int = 8080): String {
        try {
            if (serverSocket != null) return "Server already running on port $port"
            isRunning = true
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }

            // Accept connections in a background thread
            executor.execute {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute {
                            handleClient(socket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e("AdminWebServer", "Error accepting connection", e)
                        }
                    }
                }
            }

            Log.d("AdminWebServer", "Socket Server started on port $port")
            return "http://localhost:$port"
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error starting web server", e)
            return "Error: ${e.localizedMessage}"
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        try {
            executor.shutdownNow()
        } catch (e: Exception) {}
    }

    private fun readHeaderLine(inputStream: java.io.InputStream): String? {
        val bos = java.io.ByteArrayOutputStream()
        var lastChar = -1
        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                if (bos.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                bos.write(b)
            }
            lastChar = b
        }
        return bos.toString(StandardCharsets.UTF_8.name())
    }

    private fun sendBinaryResponse(
        socket: Socket,
        status: Int,
        bytes: ByteArray,
        contentType: String
    ) {
        try {
            val statusText = when (status) {
                200 -> "OK"
                404 -> "Not Found"
                else -> "OK"
            }
            val out = socket.getOutputStream()
            val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8))
            writer.print("HTTP/1.1 $status $statusText\r\n")
            writer.print("Content-Type: $contentType\r\n")
            writer.print("Content-Length: ${bytes.size}\r\n")
            writer.print("Access-Control-Allow-Origin: *\r\n")
            writer.print("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
            writer.print("Access-Control-Allow-Headers: *\r\n")
            writer.print("Connection: close\r\n\r\n")
            writer.flush()
            out.write(bytes)
            out.flush()
            socket.close()
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error sending binary response", e)
        }
    }

    private fun cleanOldObsSegments(obsDir: java.io.File) {
        try {
            val now = System.currentTimeMillis()
            val files = obsDir.walk().filter { it.isFile && it.extension.equals("ts", ignoreCase = true) }
            for (file in files) {
                // Delete segment files older than 60 seconds to keep storage extremely low
                if (now - file.lastModified() > 60000) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error cleaning old HLS segments", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val inputStream = socket.getInputStream()
            val firstLine = readHeaderLine(inputStream) ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                sendResponse(socket, 400, "Bad Request", "text/plain")
                return
            }

            val method = parts[0]
            val rawPath = parts[1]

            // Split path and query
            val pathAndQuery = rawPath.split("?", limit = 2)
            val path = pathAndQuery[0]
            val query = if (pathAndQuery.size > 1) pathAndQuery[1] else null

            // Read headers to find Content-Length, Cookie, and Authorization
            var contentLength = 0
            var cookieHeader: String? = null
            var authHeader: String? = null
            while (true) {
                val headerLine = readHeaderLine(inputStream)
                if (headerLine.isNullOrEmpty()) break
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substring("Content-Length:".length).trim().toIntOrNull() ?: 0
                } else if (headerLine.startsWith("Cookie:", ignoreCase = true)) {
                    cookieHeader = headerLine.substring("Cookie:".length).trim()
                } else if (headerLine.startsWith("Authorization:", ignoreCase = true)) {
                    authHeader = headerLine.substring("Authorization:".length).trim()
                }
            }

            // Read body if content length > 0
            val bodyBytes = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val result = inputStream.read(bytes, read, contentLength - read)
                    if (result == -1) break
                    read += result
                }
                if (read < contentLength) {
                    bytes.copyOf(read)
                } else {
                    bytes
                }
            } else {
                ByteArray(0)
            }

            val body = String(bodyBytes, StandardCharsets.UTF_8)

            // Route the request
            if (method == "OPTIONS") {
                handleOptions(socket)
                return
            }

            if (path.startsWith("/obs/")) {
                if (method == "PUT" || method == "POST") {
                    val relativePath = path.substring("/obs/".length)
                    if (relativePath.contains("..") || relativePath.isEmpty()) {
                        sendResponse(socket, 400, "Bad Request", "text/plain")
                        return
                    }
                    val obsDir = java.io.File(context.cacheDir, "obs_stream")
                    val safeFile = java.io.File(obsDir, relativePath)
                    safeFile.parentFile?.mkdirs()
                    try {
                        java.io.FileOutputStream(safeFile).use { fos ->
                            fos.write(bodyBytes)
                        }
                        cleanOldObsSegments(obsDir)
                        sendResponse(socket, 200, "Created", "text/plain")
                    } catch (e: Exception) {
                        sendResponse(socket, 500, "Error: ${e.localizedMessage}", "text/plain")
                    }
                } else if (method == "GET") {
                    val relativePath = path.substring("/obs/".length)
                    if (relativePath.contains("..") || relativePath.isEmpty()) {
                        sendResponse(socket, 404, "Not Found", "text/plain")
                        return
                    }
                    val obsDir = java.io.File(context.cacheDir, "obs_stream")
                    val safeFile = java.io.File(obsDir, relativePath)
                    if (safeFile.exists() && safeFile.isFile) {
                        val ext = safeFile.extension.lowercase()
                        val contentType = when (ext) {
                            "m3u8" -> "application/x-mpegURL"
                            "ts" -> "video/MP2T"
                            "m4s" -> "video/iso.segment"
                            "mp4" -> "video/mp4"
                            else -> "application/octet-stream"
                        }
                        val fileBytes = safeFile.readBytes()
                        sendBinaryResponse(socket, 200, fileBytes, contentType)
                    } else {
                        sendResponse(socket, 404, "Not Found", "text/plain")
                    }
                } else {
                    sendResponse(socket, 405, "Method Not Allowed", "text/plain")
                }
                return
            }

            // Session protection checks
            val isWriteAction = method == "POST" || method == "PUT" || method == "DELETE"
            val isAdminPage = path == "/" || path == "/index.html"
            val isPublicWriteAction = path == "/api/analytics/heartbeat" || 
                                      path == "/api/analytics/track-view" || 
                                      path == "/api/stream-report" || 
                                      path == "/api/send-fcm-alert" ||
                                      path.startsWith("/api/secure/")

            val isAdminOnlyApi = path == "/api/upload-apk" || 
                                 path == "/api/channels/import-m3u" || 
                                 path == "/api/channels/clear" ||
                                 path == "/api/stream-health" ||
                                 path == "/api/stream-reports" ||
                                 path == "/api/analytics/dashboard" ||
                                 path == "/api/analytics/raw"

            if (isAdminPage) {
                if (!isSessionValid(cookieHeader, authHeader)) {
                    handleLoginStatic(socket)
                    return
                }
            } else if ((isAdminOnlyApi || (isWriteAction && path.startsWith("/api/"))) && !isPublicWriteAction) {
                if (path != "/api/login" && path != "/api/logout") {
                    if (!isSessionValid(cookieHeader, authHeader)) {
                        sendResponse(socket, 401, """{"error":"Unauthorized: Please login"}""")
                        return
                    }
                }
            }

            when (path) {
                "/", "/index.html" -> handleStatic(socket)
                "/web-app", "/web-app/index.html" -> handleWebVersion(socket)
                "/apk/sportzfy-latest.apk" -> handleApkDownload(socket)
                "/api/login" -> {
                    if (method != "POST") {
                        sendResponse(socket, 405, """{"error":"Method not allowed"}""")
                        return
                    }
                    val user = getJsonStringField(body, "username").trim()
                    val pass = getJsonStringField(body, "password")
                    if (user == "shafin.sportzfy" && pass == "Abcd#43214") {
                        val token = java.util.UUID.randomUUID().toString()
                        activeSessions[token] = System.currentTimeMillis() + 86400000L // 24 hours
                        val cookieVal = "admin_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400"
                        sendResponse(socket, 200, """{"success":true,"token":"$token"}""", "application/json", cookieVal)
                    } else {
                        try { Thread.sleep(1500) } catch (e: Exception) {}
                        sendResponse(socket, 401, """{"success":false,"error":"Invalid username or password"}""")
                    }
                }
                "/api/logout" -> {
                    if (cookieHeader != null) {
                        val cookies = cookieHeader.split(";")
                        for (cookie in cookies) {
                            val parts = cookie.trim().split("=", limit = 2)
                            if (parts.size == 2 && parts[0] == "admin_session") {
                                activeSessions.remove(parts[1])
                            }
                        }
                    }
                    val clearCookie = "admin_session=; Path=/; HttpOnly; Max-Age=0"
                    sendResponse(socket, 200, """{"success":true}""", "application/json", clearCookie)
                }
                "/api/app-update" -> handleAppUpdate(socket, method, body)
                "/api/upload-apk" -> handleUploadApk(socket, method, bodyBytes)
                "/api/matches" -> handleMatches(socket, method, query, body)
                "/api/channels" -> handleChannels(socket, method, query, body)
                "/api/channels/import-m3u" -> handleImportM3u(socket, method, query, body)
                "/api/channels/clear" -> handleClearChannels(socket)
                "/api/highlights" -> handleHighlights(socket, method, query, body)
                "/api/notices" -> handleNotices(socket, method, query, body)
                "/api/banner-ad" -> handleBannerAd(socket, method, query, body)
                "/api/ads-settings" -> handleAdsSettings(socket, method, query, body)
                "/api/customization" -> handleCustomization(socket, method, body)
                "/api/customization/upload-logo" -> handleUploadLogo(socket, method, query, bodyBytes)
                "/api/customization/clear-logo" -> handleClearLogo(socket, method, query)
                "/api/customization/logo-top" -> handleServeLogo(socket, "logo_top.png")
                "/api/customization/logo-loading" -> handleServeLogo(socket, "logo_loading.png")
                "/api/customization/logo-app" -> handleServeLogo(socket, "logo_app.png")
                "/api/customization/logo-android" -> handleServeLogo(socket, "logo_android.png")
                "/api/send-fcm-alert" -> {
                    if (method != "POST") {
                        sendResponse(socket, 405, """{"error":"Method not allowed"}""")
                    } else {
                        val matchId = getJsonStringField(body, "matchId")
                        val title = getJsonStringField(body, "title")
                        val sport = getJsonStringField(body, "sport")
                        val status = getJsonStringField(body, "status")
                        val bodyText = getJsonStringField(body, "body")
                        
                        triggerLocalSystemNotification(matchId, title, sport, status, bodyText)
                        sendResponse(socket, 200, """{"success":true,"message":"FCM push alert triggered successfully!"}""")
                    }
                }
                "/api/obs-status" -> {
                    val streamKey = getQueryParam(query, "streamKey").trim().lowercase()
                    val obsDir = java.io.File(context.cacheDir, "obs_stream")
                    val indexFile = java.io.File(obsDir, "$streamKey/index.m3u8")
                    val active = indexFile.exists() && indexFile.length() > 0
                    sendResponse(socket, 200, """{"active":$active}""")
                }
                else -> sendResponse(socket, 404, "Not Found", "text/plain")
            }
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error handling client", e)
        }
    }

    // CORS & Response Helpers
    private fun sendResponse(
        socket: Socket,
        status: Int,
        content: String,
        contentType: String = "application/json",
        cookieHeader: String? = null
    ) {
        try {
            val statusText = when (status) {
                200 -> "OK"
                204 -> "No Content"
                401 -> "Unauthorized"
                400 -> "Bad Request"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                500 -> "Internal Server Error"
                else -> "OK"
            }
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            val out = socket.getOutputStream()
            val writer = PrintWriter(OutputStreamWriter(out, StandardCharsets.UTF_8))

            writer.print("HTTP/1.1 $status $statusText\r\n")
            writer.print("Content-Type: $contentType; charset=UTF-8\r\n")
            writer.print("Content-Length: ${bytes.size}\r\n")
            writer.print("Access-Control-Allow-Origin: *\r\n")
            writer.print("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
            writer.print("Access-Control-Allow-Headers: Content-Type, Authorization, Cookie\r\n")
            writer.print("Access-Control-Allow-Credentials: true\r\n")
            if (cookieHeader != null) {
                writer.print("Set-Cookie: $cookieHeader\r\n")
            }
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.flush()

            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error sending response", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
        }
    }

    private fun handleOptions(socket: Socket) {
        sendResponse(socket, 204, "", "application/json")
    }

    private fun handleStatic(socket: Socket) {
        try {
            val html = context.assets.open("admin_index.html").bufferedReader().use { it.readText() }
            sendResponse(socket, 200, html, "text/html")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error reading admin_index.html", e)
            sendResponse(socket, 500, "Error loading admin console: ${e.localizedMessage}", "text/plain")
        }
    }


    private fun handleApkDownload(socket: Socket) {
        try {
            val uploadedFile = java.io.File(context.filesDir, "uploaded-latest.apk")
            val fileToServe = if (uploadedFile.exists() && uploadedFile.isFile) {
                uploadedFile
            } else {
                java.io.File(context.packageCodePath)
            }

            if (fileToServe.exists() && fileToServe.isFile) {
                val fileBytes = fileToServe.readBytes()
                sendBinaryResponse(socket, 200, fileBytes, "application/vnd.android.package-archive")
            } else {
                sendResponse(socket, 404, "APK File not found", "text/plain")
            }
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error sending APK download", e)
            sendResponse(socket, 500, "Error: ${e.localizedMessage}", "text/plain")
        }
    }

    private fun handleUploadApk(socket: Socket, method: String, bodyBytes: ByteArray) {
        if (method != "POST") {
            sendResponse(socket, 405, """{"success":false,"message":"Method Not Allowed"}""")
            return
        }
        if (bodyBytes.isEmpty()) {
            sendResponse(socket, 400, """{"success":false,"message":"Empty file body"}""")
            return
        }
        try {
            val file = java.io.File(context.filesDir, "uploaded-latest.apk")
            file.writeBytes(bodyBytes)
            sendResponse(socket, 200, """{"success":true,"message":"APK uploaded successfully"}""")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error writing uploaded APK", e)
            sendResponse(socket, 500, """{"success":false,"message":"Error saving file: ${e.localizedMessage}"}""")
        }
    }

    private fun handleAppUpdate(socket: Socket, method: String, body: String) {
        val sharedPrefs = context.getSharedPreferences("sportzfy_prefs", Context.MODE_PRIVATE)
        if (method == "POST") {
            try {
                val json = JSONObject(body)
                val versionCode = json.optInt("versionCode", 1)
                val versionName = json.optString("versionName", "1.0.0")
                val changelog = json.optString("changelog", "Initial Release")
                val apkUrl = json.optString("apkUrl", "")
                val isMandatory = json.optBoolean("isMandatory", false)

                sharedPrefs.edit().apply {
                    putInt("latest_version_code", versionCode)
                    putString("latest_version_name", versionName)
                    putString("latest_changelog", changelog)
                    putString("latest_apk_url", apkUrl)
                    putBoolean("latest_is_mandatory", isMandatory)
                }.apply()

                sendResponse(socket, 200, """{"success":true,"message":"Update settings saved successfully"}""")
            } catch (e: Exception) {
                sendResponse(socket, 400, """{"success":false,"message":"Error: ${e.localizedMessage}"}""")
            }
        } else {
            // GET
            val versionCode = sharedPrefs.getInt("latest_version_code", 1)
            val versionName = sharedPrefs.getString("latest_version_name", "1.0.0") ?: "1.0.0"
            val changelog = sharedPrefs.getString("latest_changelog", "No release notes.") ?: "No release notes."
            val apkUrl = sharedPrefs.getString("latest_apk_url", "") ?: ""
            val isMandatory = sharedPrefs.getBoolean("latest_is_mandatory", false)
            
            // Build absolute URL if apkUrl is empty
            val finalApkUrl = if (apkUrl.isEmpty() || apkUrl == "/apk/sportzfy-latest.apk") {
                val ip = NetworkUtils.getLocalIpAddress()
                "http://$ip:8080/apk/sportzfy-latest.apk"
            } else {
                apkUrl
            }

            val responseJson = JSONObject().apply {
                put("versionCode", versionCode)
                put("versionName", versionName)
                put("changelog", changelog)
                put("apkUrl", finalApkUrl)
                put("isMandatory", isMandatory)
            }
            sendResponse(socket, 200, responseJson.toString())
        }
    }

    private fun handleCustomization(socket: Socket, method: String, body: String) {
        val sharedPrefs = context.getSharedPreferences("sportzfy_prefs", Context.MODE_PRIVATE)
        if (method == "POST") {
            try {
                val json = JSONObject(body)
                val primaryColor = json.optString("primaryColor", "#00E5FF")
                val shadowColor = json.optString("shadowColor", "#FF1744")
                val bgColor = json.optString("bgColor", "#0B111E")
                val headerBgColor = json.optString("headerBgColor", "#0F1826")
                val appTitle = json.optString("appTitle", "Sportzfy")
                val loadingText = json.optString("loadingText", "Loading live streams...")
                val announcementText = json.optString("announcementText", "Welcome to the updated Sportzfy App! Enjoy seamless HD streams.")
                val announcementEnabled = json.optBoolean("announcementEnabled", false)
                val fontStyle = json.optString("fontStyle", "SansSerif")
                val noticeText = json.optString("noticeText", "This is our official announcement. Keep supporting us for the best sports streaming experience!")
                val copyrightText = json.optString("copyrightText", "Sportzfy respects intellectual property rights. All live television channels and match feeds are synced from public endpoints managed via our user console website. Please contact us via email if you find any infringing materials.")
                val joinUsUrl = json.optString("joinUsUrl", "https://t.me/sportzfy_live")
                val supportEmail = json.optString("supportEmail", "support@sportzfy.live")

                sharedPrefs.edit().apply {
                    putString("custom_primary_color", primaryColor)
                    putString("custom_shadow_color", shadowColor)
                    putString("custom_bg_color", bgColor)
                    putString("custom_header_bg_color", headerBgColor)
                    putString("custom_app_title", appTitle)
                    putString("custom_loading_text", loadingText)
                    putString("custom_announcement_text", announcementText)
                    putBoolean("custom_announcement_enabled", announcementEnabled)
                    putString("custom_font_style", fontStyle)
                    putString("custom_notice_text", noticeText)
                    putString("custom_copyright_text", copyrightText)
                    putString("custom_join_us_url", joinUsUrl)
                    putString("custom_support_email", supportEmail)
                }.apply()

                onCustomizationChanged()
                sendResponse(socket, 200, """{"success":true,"message":"Customization settings saved successfully"}""")
            } catch (e: Exception) {
                sendResponse(socket, 400, """{"success":false,"message":"Error: ${e.localizedMessage}"}""")
            }
        } else {
            // GET
            val primaryColor = sharedPrefs.getString("custom_primary_color", "#00E5FF") ?: "#00E5FF"
            val shadowColor = sharedPrefs.getString("custom_shadow_color", "#FF1744") ?: "#FF1744"
            val bgColor = sharedPrefs.getString("custom_bg_color", "#0B111E") ?: "#0B111E"
            val headerBgColor = sharedPrefs.getString("custom_header_bg_color", "#0F1826") ?: "#0F1826"
            val appTitle = sharedPrefs.getString("custom_app_title", "Sportzfy") ?: "Sportzfy"
            val loadingText = sharedPrefs.getString("custom_loading_text", "Loading live streams...") ?: "Loading live streams..."
            val announcementText = sharedPrefs.getString("custom_announcement_text", "Welcome to the updated Sportzfy App! Enjoy seamless HD streams.") ?: "Welcome to the updated Sportzfy App! Enjoy seamless HD streams."
            val announcementEnabled = sharedPrefs.getBoolean("custom_announcement_enabled", false)
            val fontStyle = sharedPrefs.getString("custom_font_style", "SansSerif") ?: "SansSerif"
            val noticeText = sharedPrefs.getString("custom_notice_text", "This is our official announcement. Keep supporting us for the best sports streaming experience!") ?: "This is our official announcement. Keep supporting us for the best sports streaming experience!"
            val copyrightText = sharedPrefs.getString("custom_copyright_text", "Sportzfy respects intellectual property rights. All live television channels and match feeds are synced from public endpoints managed via our user console website. Please contact us via email if you find any infringing materials.") ?: "Sportzfy respects intellectual property rights. All live television channels and match feeds are synced from public endpoints managed via our user console website. Please contact us via email if you find any infringing materials."
            val joinUsUrl = sharedPrefs.getString("custom_join_us_url", "https://t.me/sportzfy_live") ?: "https://t.me/sportzfy_live"
            val supportEmail = sharedPrefs.getString("custom_support_email", "support@sportzfy.live") ?: "support@sportzfy.live"

            val hasTopLogo = java.io.File(context.filesDir, "logo_top.png").exists()
            val hasLoadingLogo = java.io.File(context.filesDir, "logo_loading.png").exists()
            val hasAppLogo = java.io.File(context.filesDir, "logo_app.png").exists()
            val hasAndroidLogo = java.io.File(context.filesDir, "logo_android.png").exists()

            val responseJson = JSONObject().apply {
                put("success", true)
                put("primaryColor", primaryColor)
                put("shadowColor", shadowColor)
                put("bgColor", bgColor)
                put("headerBgColor", headerBgColor)
                put("appTitle", appTitle)
                put("loadingText", loadingText)
                put("announcementText", announcementText)
                put("announcementEnabled", announcementEnabled)
                put("fontStyle", fontStyle)
                put("noticeText", noticeText)
                put("copyrightText", copyrightText)
                put("joinUsUrl", joinUsUrl)
                put("supportEmail", supportEmail)
                put("hasTopLogo", hasTopLogo)
                put("hasLoadingLogo", hasLoadingLogo)
                put("hasAppLogo", hasAppLogo)
                put("hasAndroidLogo", hasAndroidLogo)
            }
            sendResponse(socket, 200, responseJson.toString())
        }
    }

    private fun handleUploadLogo(socket: Socket, method: String, query: String?, bodyBytes: ByteArray) {
        if (method != "POST") {
            sendResponse(socket, 405, """{"success":false,"message":"Method Not Allowed"}""")
            return
        }
        val type = getQueryParam(query, "type").trim().lowercase()
        if (type != "top" && type != "loading" && type != "app" && type != "android") {
            sendResponse(socket, 400, """{"success":false,"message":"Invalid logo type. Must be 'top', 'loading', 'app', or 'android'"}""")
            return
        }
        if (bodyBytes.isEmpty()) {
            sendResponse(socket, 400, """{"success":false,"message":"Empty logo file body"}""")
            return
        }
        try {
            val filename = when (type) {
                "top" -> "logo_top.png"
                "loading" -> "logo_loading.png"
                "android" -> "logo_android.png"
                else -> "logo_app.png"
            }
            val file = java.io.File(context.filesDir, filename)
            file.writeBytes(bodyBytes)
            onCustomizationChanged()
            sendResponse(socket, 200, """{"success":true,"message":"${type.replaceFirstChar { it.uppercase() }} logo uploaded successfully"}""")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error writing uploaded logo", e)
            sendResponse(socket, 500, """{"success":false,"message":"Error saving logo file: ${e.localizedMessage}"}""")
        }
    }

    private fun handleClearLogo(socket: Socket, method: String, query: String?) {
        if (method != "POST") {
            sendResponse(socket, 405, """{"success":false,"message":"Method Not Allowed"}""")
            return
        }
        val type = getQueryParam(query, "type").trim().lowercase()
        if (type != "top" && type != "loading" && type != "app" && type != "android") {
            sendResponse(socket, 400, """{"success":false,"message":"Invalid logo type. Must be 'top', 'loading', 'app', or 'android'"}""")
            return
        }
        try {
            val filename = when (type) {
                "top" -> "logo_top.png"
                "loading" -> "logo_loading.png"
                "android" -> "logo_android.png"
                else -> "logo_app.png"
            }
            val file = java.io.File(context.filesDir, filename)
            if (file.exists()) {
                file.delete()
            }
            onCustomizationChanged()
            sendResponse(socket, 200, """{"success":true,"message":"${type.replaceFirstChar { it.uppercase() }} logo cleared successfully"}""")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error clearing logo", e)
            sendResponse(socket, 500, """{"success":false,"message":"Error clearing logo file: ${e.localizedMessage}"}""")
        }
    }

    private fun handleServeLogo(socket: Socket, filename: String) {
        try {
            val file = java.io.File(context.filesDir, filename)
            if (file.exists() && file.isFile) {
                val fileBytes = file.readBytes()
                sendBinaryResponse(socket, 200, fileBytes, "image/png")
            } else {
                sendResponse(socket, 404, "Logo file not found", "text/plain")
            }
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error serving logo", e)
            sendResponse(socket, 500, "Error: ${e.localizedMessage}", "text/plain")
        }
    }

    private fun handleWebVersion(socket: Socket) {
        try {
            val html = context.assets.open("web_app_index.html").bufferedReader().use { it.readText() }
            sendResponse(socket, 200, html, "text/html")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error reading web_app_index.html", e)
            sendResponse(socket, 500, "Error loading web-app: ${e.localizedMessage}", "text/plain")
        }
    }


    private fun handleMatches(socket: Socket, method: String, query: String?, body: String) {
        try {
            when (method) {
                "GET" -> {
                    val matches = runBlocking {
                        var list: List<SportMatch> = emptyList()
                        try {
                            repository.allMatches.first { list = it; true }
                        } catch (e: Exception) {}
                        list
                    }
                    val json = matches.joinToString(prefix = "[", postfix = "]") { m ->
                        """{"id":${m.id},"title":"${escape(m.title)}","sport":"${escape(m.sport)}","team1Name":"${escape(m.team1Name)}","team1Logo":"${escape(m.team1Logo)}","team2Name":"${escape(m.team2Name)}","team2Logo":"${escape(m.team2Logo)}","time":"${escape(m.time)}","status":"${escape(m.status)}","streamUrl":"${escape(m.streamUrl)}"}"""
                    }
                    sendResponse(socket, 200, json)
                }
                "POST" -> {
                    val id = getJsonLongField(body, "id")
                    val title = getJsonStringField(body, "title")
                    val sport = getJsonStringField(body, "sport")
                    val team1Name = getJsonStringField(body, "team1Name")
                    val team1Logo = getJsonStringField(body, "team1Logo")
                    val team2Name = getJsonStringField(body, "team2Name")
                    val team2Logo = getJsonStringField(body, "team2Logo")
                    val time = getJsonStringField(body, "time")
                    val status = getJsonStringField(body, "status")
                    val streamUrl = getJsonStringField(body, "streamUrl")

                    val match = SportMatch(
                        id = if (id == 0L) 0L else id,
                        title = title,
                        sport = sport,
                        team1Name = team1Name,
                        team1Logo = team1Logo,
                        team2Name = team2Name,
                        team2Logo = team2Logo,
                        time = time,
                        status = status,
                        streamUrl = streamUrl
                    )

                    scope.launch {
                        if (id == 0L) {
                            repository.insertMatch(match)
                        } else {
                            repository.updateMatch(match)
                        }
                    }
                    sendResponse(socket, 200, """{"success":true}""")
                }
                "DELETE" -> {
                    val idStr = getQueryParam(query, "id")
                    val id = idStr.toLongOrNull()
                    if (id != null) {
                        scope.launch { repository.deleteMatchById(id) }
                        sendResponse(socket, 200, """{"success":true}""")
                    } else {
                        sendResponse(socket, 400, """{"error":"Missing ID"}""")
                    }
                }
                else -> sendResponse(socket, 405, """{"error":"Method not allowed"}""")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun handleChannels(socket: Socket, method: String, query: String?, body: String) {
        try {
            when (method) {
                "GET" -> {
                    val channels = runBlocking {
                        var list: List<LiveChannel> = emptyList()
                        try {
                            repository.allChannels.first { list = it; true }
                        } catch (e: Exception) {}
                        list
                    }
                    val json = channels.joinToString(prefix = "[", postfix = "]") { c ->
                        """{"id":${c.id},"name":"${escape(c.name)}","category":"${escape(c.category)}","logoUrl":"${escape(c.logoUrl)}","streamUrl":"${escape(c.streamUrl)}","streamUrl2":"${escape(c.streamUrl2)}","streamUrl3":"${escape(c.streamUrl3)}","streamUrl4":"${escape(c.streamUrl4)}","streamUrl5":"${escape(c.streamUrl5)}"}"""
                    }
                    sendResponse(socket, 200, json)
                }
                "POST" -> {
                    val id = getJsonLongField(body, "id")
                    val name = getJsonStringField(body, "name")
                    val category = getJsonStringField(body, "category")
                    val logoUrl = getJsonStringField(body, "logoUrl")
                    val streamUrl = getJsonStringField(body, "streamUrl")
                    val streamUrl2 = getJsonStringField(body, "streamUrl2")
                    val streamUrl3 = getJsonStringField(body, "streamUrl3")
                    val streamUrl4 = getJsonStringField(body, "streamUrl4")
                    val streamUrl5 = getJsonStringField(body, "streamUrl5")

                    val channel = LiveChannel(
                        id = if (id == 0L) 0L else id,
                        name = name,
                        category = category,
                        logoUrl = logoUrl,
                        streamUrl = streamUrl,
                        streamUrl2 = streamUrl2,
                        streamUrl3 = streamUrl3,
                        streamUrl4 = streamUrl4,
                        streamUrl5 = streamUrl5
                    )

                    scope.launch {
                        if (id == 0L) {
                            repository.insertChannel(channel)
                        } else {
                            repository.updateChannel(channel)
                        }
                    }
                    sendResponse(socket, 200, """{"success":true}""")
                }
                "DELETE" -> {
                    val idStr = getQueryParam(query, "id")
                    val id = idStr.toLongOrNull()
                    if (id != null) {
                        scope.launch { repository.deleteChannelById(id) }
                        sendResponse(socket, 200, """{"success":true}""")
                    } else {
                        sendResponse(socket, 400, """{"error":"Missing ID"}""")
                    }
                }
                else -> sendResponse(socket, 405, """{"error":"Method not allowed"}""")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun handleClearChannels(socket: Socket) {
        try {
            scope.launch {
                repository.deleteAllChannels()
            }
            sendResponse(socket, 200, """{"success":true}""")
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun cleanChanName(name: String?): String {
        if (name == null) return ""
        var clean = name.trim()
        
        // Remove emojis and flags
        clean = clean.replace(Regex("[\\u2700-\\u27BF]|[\\uE000-\\uF8FF]|\\uD83C[\\uDC00-\\uDFFF]|\\uD83D[\\uDC00-\\uDFFF]|[\\u2011-\\u26FF]|\\uD83E[\\uDD00-\\uDFFF]"), "")
        
        // Remove trailing country suffix
        clean = clean.replace(Regex("\\s+(Argentina|France|Germany|Itali|Italy|Brazil|Spain|USA|Bangladesh|Algeria|Mexico|Serbia|USA TV|India|BD)\\b", RegexOption.IGNORE_CASE), "")
        
        // Strip parenthesized things
        clean = clean.replace(Regex("\\s*\\(.*?\\)", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s*\\[.*?\\]", RegexOption.IGNORE_CASE), "")
        
        // Strip trailing backup indicator letters
        clean = clean.replace(Regex("\\s+-\\s+$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s+([B-E])\\b(\\s+1080|\\s+HD|\\s+-)*$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s+-\\s+([B-E])\\b.*?$", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("\\s+(HD|VIP|TV|Live|SD)\\b", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("[\\s\\-_]+$"), "")
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return clean
    }

    private fun getCategory(name: String, parsedGroup: String, defaultCategory: String): String {
        if (parsedGroup.isNotEmpty() && parsedGroup != "General") return parsedGroup
        val lower = name.lowercase()
        return when {
            lower.contains("sports") || lower.contains("sport") || lower.contains("cup") || 
            lower.contains("cricket") || lower.contains("ten") || lower.contains("willow") || 
            lower.contains("bein") || lower.contains("golf") || lower.contains("espn") || 
            lower.contains("tsn") || lower.contains("football") || lower.contains("nfl") || 
            lower.contains("wtc") || lower.contains("epl") || lower.contains("bfl") ||
            lower.contains("gp") || lower.contains("finals") -> "Sports"
            
            lower.contains("news") || lower.contains("khabar") || lower.contains("bbc") || 
            lower.contains("jazeera") || lower.contains("aaj tak") || lower.contains("cnbc") || 
            lower.contains("msnbc") || lower.contains("abc") || lower.contains("dw") || 
            lower.contains("wion") || lower.contains("independent") || lower.contains("somoy") || 
            lower.contains("dbc") || lower.contains("news18") || lower.contains("republic") || 
            lower.contains("times now") || lower.contains("kolkata tv") || lower.contains("zee 24") ||
            lower.contains("cbs") || lower.contains("cp 24") || lower.contains("business") ||
            lower.contains("weather") || lower.contains("accuweather") -> "News"
            
            lower.contains("kids") || lower.contains("cartoon") || lower.contains("nick") || 
            lower.contains("pogo") || lower.contains("disney") || lower.contains("toon") || 
            lower.contains("goggles") || lower.contains("moonbug") -> "Kids"
            
            lower.contains("quran") || lower.contains("islamic") || lower.contains("deen") || 
            lower.contains("eman") || lower.contains("madani") -> "Islamic"
            
            lower.contains("movie") || lower.contains("cinema") || lower.contains("max") || 
            lower.contains("goldmines") || lower.contains("epix") || lower.contains("hbo") || 
            lower.contains("action") || lower.contains("bollywood") || lower.contains("sphere") ||
            lower.contains("amc") || lower.contains("pix") -> "Movies"
            
            lower.contains("vip") -> "VIP"
            
            else -> defaultCategory
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private fun handleImportM3u(socket: Socket, method: String, query: String?, body: String) {
        try {
            if (method != "POST") {
                sendResponse(socket, 405, """{"error":"Method not allowed"}""")
                return
            }
            val m3uUrl = getJsonStringField(body, "url").trim()
            val rawTextBase64 = getJsonStringField(body, "rawTextBase64").trim()
            val defaultCategory = getJsonStringField(body, "defaultCategory").trim().ifEmpty { "IPTV" }
            
            if (m3uUrl.isEmpty() && rawTextBase64.isEmpty()) {
                sendResponse(socket, 400, """{"error":"Missing M3U URL or Raw Text"}""")
                return
            }

            scope.launch(Dispatchers.IO) {
                val existing = try {
                    repository.allChannels.first()
                } catch (e: Exception) {
                    emptyList<LiveChannel>()
                }

                val parsed = if (rawTextBase64.isNotEmpty()) {
                    val decodedBytes = try {
                        android.util.Base64.decode(rawTextBase64, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        java.util.Base64.getDecoder().decode(rawTextBase64)
                    }
                    val rawText = String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8)
                    parseM3uFromString(rawText, defaultCategory, existing)
                } else {
                    parseM3uFromUrl(m3uUrl, defaultCategory, existing)
                }

                if (parsed.isNotEmpty()) {
                    // Limit to first 1000 channels to guarantee high performance & instant loading
                    val limited = parsed.take(1000)
                    repository.insertAllChannels(limited)
                }
            }

            sendResponse(socket, 200, """{"success":true,"message":"Import started in background."}""")
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun parseM3uFromString(content: String, defaultCategory: String, existingChannels: List<LiveChannel>): List<LiveChannel> {
        val channelsMap = mutableMapOf<String, LiveChannel>()
        val importedUrls = mutableSetOf<String>()

        existingChannels.forEach { ch ->
            val baseKey = "${ch.category.lowercase()}:${ch.name.lowercase()}"
            channelsMap[baseKey] = ch
            if (ch.streamUrl.isNotEmpty()) importedUrls.add(ch.streamUrl)
            if (ch.streamUrl2.isNotEmpty()) importedUrls.add(ch.streamUrl2)
            if (ch.streamUrl3.isNotEmpty()) importedUrls.add(ch.streamUrl3)
            if (ch.streamUrl4.isNotEmpty()) importedUrls.add(ch.streamUrl4)
            if (ch.streamUrl5.isNotEmpty()) importedUrls.add(ch.streamUrl5)
        }

        val resultList = mutableListOf<LiveChannel>()

        try {
            val reader = java.io.BufferedReader(java.io.StringReader(content))
            var line: String?
            var currentMetadata: String? = null
            var simpleCounter = if (existingChannels.isNotEmpty()) existingChannels.maxOf { it.id } + 1 else 1L

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXTINF:")) {
                    currentMetadata = trimmed
                } else if (trimmed.startsWith("#EXTM3U") || trimmed.startsWith("#")) {
                    continue
                } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtmp://") || trimmed.startsWith("rtsp://")) {
                    var originalName = "Channel $simpleCounter"
                    var logoUrl = ""
                    var parsedGroup = ""

                    if (currentMetadata != null) {
                        val commaIdx = currentMetadata.lastIndexOf(',')
                        if (commaIdx != -1) {
                            originalName = currentMetadata.substring(commaIdx + 1).trim()
                        }

                        val logoRegex = """tvg-logo="([^"]*)"""".toRegex(RegexOption.IGNORE_CASE)
                        val logoMatch = logoRegex.find(currentMetadata)
                        if (logoMatch != null) {
                            logoUrl = logoMatch.groupValues[1]
                        }

                        val groupRegex = """group-title="([^"]*)"""".toRegex(RegexOption.IGNORE_CASE)
                        val groupMatch = groupRegex.find(currentMetadata)
                        if (groupMatch != null) {
                            parsedGroup = groupMatch.groupValues[1].trim()
                        }
                    }

                    val streamUrl = trimmed
                    currentMetadata = null

                    // Deduplicate URLs
                    if (importedUrls.contains(streamUrl)) {
                        continue
                    }
                    importedUrls.add(streamUrl)

                    val category = getCategory(originalName, parsedGroup, defaultCategory)
                    val baseName = cleanChanName(originalName).ifEmpty { originalName }
                    val baseKey = "${category.lowercase()}:${baseName.lowercase()}"

                    if (channelsMap.containsKey(baseKey)) {
                        val ch = channelsMap[baseKey]!!
                        val updatedCh = when {
                            ch.streamUrl2.isEmpty() -> ch.copy(streamUrl2 = streamUrl)
                            ch.streamUrl3.isEmpty() -> ch.copy(streamUrl3 = streamUrl)
                            ch.streamUrl4.isEmpty() -> ch.copy(streamUrl4 = streamUrl)
                            ch.streamUrl5.isEmpty() -> ch.copy(streamUrl5 = streamUrl)
                            else -> ch
                        }
                        channelsMap[baseKey] = updatedCh
                        resultList.removeAll { it.id == updatedCh.id }
                        resultList.add(updatedCh)
                    } else {
                        val newChan = LiveChannel(
                            id = simpleCounter++,
                            name = baseName,
                            category = category,
                            logoUrl = logoUrl,
                            streamUrl = streamUrl
                        )
                        channelsMap[baseKey] = newChan
                        resultList.add(newChan)
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error parsing pasted M3U text: ${e.localizedMessage}", e)
        }

        return resultList
    }

    private fun parseM3uFromUrl(urlStr: String, defaultCategory: String, existing: List<LiveChannel>): List<LiveChannel> {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    if (bodyString.isNotEmpty()) {
                        return parseM3uFromString(bodyString, defaultCategory, existing)
                    }
                } else {
                    Log.e("AdminWebServer", "Failed to fetch M3U playlist from $urlStr: response code ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error parsing M3U from URL: ${e.localizedMessage}", e)
        }
        return emptyList()
    }

    private fun handleHighlights(socket: Socket, method: String, query: String?, body: String) {
        try {
            when (method) {
                "GET" -> {
                    val highlights = runBlocking {
                        var list: List<Highlight> = emptyList()
                        try {
                            repository.allHighlights.first { list = it; true }
                        } catch (e: Exception) {}
                        list
                    }
                    val json = highlights.joinToString(prefix = "[", postfix = "]") { h ->
                        """{"id":${h.id},"title":"${escape(h.title)}","team1Name":"${escape(h.team1Name)}","team1Logo":"${escape(h.team1Logo)}","team2Name":"${escape(h.team2Name)}","team2Logo":"${escape(h.team2Logo)}","date":"${escape(h.date)}","streamUrl":"${escape(h.streamUrl)}"}"""
                    }
                    sendResponse(socket, 200, json)
                }
                "POST" -> {
                    val id = getJsonLongField(body, "id")
                    val title = getJsonStringField(body, "title")
                    val team1Name = getJsonStringField(body, "team1Name")
                    val team1Logo = getJsonStringField(body, "team1Logo")
                    val team2Name = getJsonStringField(body, "team2Name")
                    val team2Logo = getJsonStringField(body, "team2Logo")
                    val date = getJsonStringField(body, "date")
                    val streamUrl = getJsonStringField(body, "streamUrl")

                    val highlight = Highlight(
                        id = if (id == 0L) 0L else id,
                        title = title,
                        team1Name = team1Name,
                        team1Logo = team1Logo,
                        team2Name = team2Name,
                        team2Logo = team2Logo,
                        date = date,
                        streamUrl = streamUrl
                    )

                    scope.launch {
                        if (id == 0L) {
                            repository.insertHighlight(highlight)
                        } else {
                            repository.updateHighlight(highlight)
                        }
                    }
                    sendResponse(socket, 200, """{"success":true}""")
                }
                "DELETE" -> {
                    val idStr = getQueryParam(query, "id")
                    val id = idStr.toLongOrNull()
                    if (id != null) {
                        scope.launch { repository.deleteHighlightById(id) }
                        sendResponse(socket, 200, """{"success":true}""")
                    } else {
                        sendResponse(socket, 400, """{"error":"Missing ID"}""")
                    }
                }
                else -> sendResponse(socket, 405, """{"error":"Method not allowed"}""")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun handleNotices(socket: Socket, method: String, query: String?, body: String) {
        try {
            when (method) {
                "GET" -> {
                    val notices = runBlocking {
                        var list: List<Notice> = emptyList()
                        try {
                            repository.allNotices.first { list = it; true }
                        } catch (e: Exception) {}
                        list
                    }
                    val json = notices.joinToString(prefix = "[", postfix = "]") { n ->
                        """{"id":${n.id},"content":"${escape(n.content)}","active":${n.active}}"""
                    }
                    sendResponse(socket, 200, json)
                }
                "POST" -> {
                    val id = getJsonLongField(body, "id")
                    val content = getJsonStringField(body, "content")
                    val active = getJsonBoolField(body, "active")

                    val notice = Notice(
                        id = if (id == 0L) 0L else id,
                        content = content,
                        active = active
                    )

                    scope.launch {
                        if (id == 0L) {
                            repository.insertNotice(notice)
                        } else {
                            repository.updateNotice(notice)
                        }
                    }
                    sendResponse(socket, 200, """{"success":true}""")
                }
                "DELETE" -> {
                    val idStr = getQueryParam(query, "id")
                    val id = idStr.toLongOrNull()
                    if (id != null) {
                        scope.launch { repository.deleteNoticeById(id) }
                        sendResponse(socket, 200, """{"success":true}""")
                    } else {
                        sendResponse(socket, 400, """{"error":"Missing ID"}""")
                    }
                }
                else -> sendResponse(socket, 405, """{"error":"Method not allowed"}""")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun handleBannerAd(socket: Socket, method: String, query: String?, body: String) {
        try {
            when (method) {
                "GET" -> {
                    val banner = runBlocking {
                        var b: BannerAd? = null
                        try {
                            b = repository.getBannerAd()
                        } catch (e: Exception) {}
                        b
                    }
                    if (banner != null) {
                        val json = """{"id":${banner.id},"mediaType":"${escape(banner.mediaType)}","mediaUrl":"${escape(banner.mediaUrl)}","clickUrl":"${escape(banner.clickUrl)}","enabled":${banner.enabled}}"""
                        sendResponse(socket, 200, json)
                    } else {
                        sendResponse(socket, 200, """{"id":1,"mediaType":"image","mediaUrl":"","clickUrl":"","enabled":false}""")
                    }
                }
                "POST" -> {
                    val mediaType = getJsonStringField(body, "mediaType")
                    val mediaUrl = getJsonStringField(body, "mediaUrl")
                    val clickUrl = getJsonStringField(body, "clickUrl")
                    val enabled = getJsonBoolField(body, "enabled")

                    val banner = BannerAd(
                        id = 1,
                        mediaType = mediaType,
                        mediaUrl = mediaUrl,
                        clickUrl = clickUrl,
                        enabled = enabled
                    )

                    scope.launch {
                        repository.insertOrUpdateBannerAd(banner)
                    }
                    sendResponse(socket, 200, """{"success":true}""")
                }
                else -> sendResponse(socket, 405, """{"error":"Method not allowed"}""")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun handleAdsSettings(socket: Socket, method: String, query: String?, body: String) {
        try {
            val sharedPrefs = context.getSharedPreferences("sportzfy_prefs", Context.MODE_PRIVATE)
            when (method) {
                "GET" -> {
                    val savedJson = sharedPrefs.getString("ads_settings_json", null)
                    val responseJson = if (savedJson != null) {
                        JSONObject(savedJson)
                    } else {
                        val defaultJson = JSONObject().apply {
                            put("enabled", true)
                            put("targeting", "global")
                            put("targetCountries", JSONArray())
                            put("appOpenAd", JSONObject().apply {
                                put("enabled", false)
                                put("mediaType", "image")
                                put("mediaUrl", "")
                                put("clickUrl", "")
                                put("minTimeBound", 5)
                                put("allowSkip", true)
                            })
                            put("streamPreRollAd", JSONObject().apply {
                                put("enabled", false)
                                put("mediaType", "image")
                                put("mediaUrl", "")
                                put("clickUrl", "")
                                put("minTimeBound", 5)
                                put("allowSkip", true)
                            })
                            put("minimizePopupAd", JSONObject().apply {
                                put("enabled", false)
                                put("mediaType", "image")
                                put("mediaUrl", "")
                                put("clickUrl", "")
                            })
                        }
                        defaultJson
                    }

                    // Always merge/sync actual database bannerAd state into the returned ads-settings
                    val banner = runBlocking {
                        var b: BannerAd? = null
                        try {
                            b = repository.getBannerAd()
                        } catch (e: Exception) {}
                        b
                    }
                    val bannerJson = JSONObject().apply {
                        if (banner != null) {
                            put("enabled", banner.enabled)
                            put("mediaType", banner.mediaType)
                            put("mediaUrl", banner.mediaUrl)
                            put("clickUrl", banner.clickUrl)
                        } else {
                            put("enabled", false)
                            put("mediaType", "image")
                            put("mediaUrl", "")
                            put("clickUrl", "")
                        }
                    }
                    responseJson.put("bannerAd", bannerJson)

                    sendResponse(socket, 200, responseJson.toString(), "application/json")
                }
                "POST" -> {
                    val rootObj = JSONObject(body)
                    
                    // Save the raw or formatted string in SharedPreferences
                    sharedPrefs.edit().putString("ads_settings_json", rootObj.toString()).apply()

                    // Extract and update the BannerAd database entry if bannerAd object is present
                    if (rootObj.has("bannerAd")) {
                        val bObj = rootObj.getJSONObject("bannerAd")
                        val enabled = bObj.optBoolean("enabled", false)
                        val mediaType = bObj.optString("mediaType", "image")
                        val mediaUrl = bObj.optString("mediaUrl", "")
                        val clickUrl = bObj.optString("clickUrl", "")

                        val banner = BannerAd(
                            id = 1,
                            mediaType = mediaType,
                            mediaUrl = mediaUrl,
                            clickUrl = clickUrl,
                            enabled = enabled
                        )
                        scope.launch {
                            repository.insertOrUpdateBannerAd(banner)
                        }
                    }

                    sendResponse(socket, 200, """{"success":true}""", "application/json")
                }
                else -> sendResponse(socket, 405, """{"error":"Method not allowed"}""")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, """{"error":"${escape(e.localizedMessage ?: "Unknown Error")}"}""")
        }
    }

    private fun getQueryParam(query: String?, key: String): String {
        if (query.isNullOrEmpty()) return ""
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                if (k == key) {
                    return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }
            }
        }
        return ""
    }

    // Custom lightweight JSON helpers
    private fun getJsonStringField(json: String, key: String): String {
        val regex = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return regex.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun getJsonLongField(json: String, key: String): Long {
        val regex = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }

    private fun getJsonBoolField(json: String, key: String): Boolean {
        val regex = "\"$key\"\\s*:\\s*(true|false)".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toBoolean() ?: false
    }

    private fun escape(string: String?): String {
        if (string == null) return ""
        val sb = StringBuilder()
        for (i in string.indices) {
            val ch = string[i]
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 32 || ch.code > 126) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.first(block: (T) -> Boolean) {
        try {
            this.collect {
                if (block(it)) {
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        } catch (e: Exception) {
            // Cancellation expected
        }
    }

    private fun triggerLocalSystemNotification(
        matchId: String?,
        title: String,
        sport: String,
        status: String,
        bodyText: String?
    ) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sportzfy_live_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelName = "Sportzfy Match Alerts"
                val channelDescription = "Alerts when your subscribed matches go live"
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(channelId, channelName, importance).apply {
                    description = channelDescription
                    enableLights(true)
                    lightColor = 0xFF00E5FF.toInt()
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("matchId", matchId)
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                matchId?.hashCode() ?: 0,
                intent,
                pendingIntentFlags
            )

            val displayBody = if (!bodyText.isNullOrBlank()) bodyText else "A match you subscribed to is now live! Tap to start streaming now."
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("🔴 LIVE NOW: $title")
                .setContentText(displayBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$displayBody\nSport: $sport"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setColor(0xFF00E5FF.toInt())

            val notificationId = matchId?.toIntOrNull() ?: System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, builder.build())
            Log.d("AdminWebServer", "FCM Local Push Alert triggered for matchId: $matchId")
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error triggering local notification", e)
        }
    }
}