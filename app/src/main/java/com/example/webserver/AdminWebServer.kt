package com.example.webserver

import android.content.Context
import android.util.Log
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

class AdminWebServer(private val context: Context, private val repository: AppRepository) {
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

            // Read headers to find Content-Length
            var contentLength = 0
            while (true) {
                val headerLine = readHeaderLine(inputStream)
                if (headerLine.isNullOrEmpty()) break
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substring("Content-Length:".length).trim().toIntOrNull() ?: 0
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

            when (path) {
                "/", "/index.html" -> handleStatic(socket)
                "/web-app", "/web-app/index.html" -> handleWebVersion(socket)
                "/apk/sportzfy-latest.apk" -> handleApkDownload(socket)
                "/api/app-update" -> handleAppUpdate(socket, method, body)
                "/api/upload-apk" -> handleUploadApk(socket, method, bodyBytes)
                "/api/matches" -> handleMatches(socket, method, query, body)
                "/api/channels" -> handleChannels(socket, method, query, body)
                "/api/channels/import-m3u" -> handleImportM3u(socket, method, query, body)
                "/api/channels/clear" -> handleClearChannels(socket)
                "/api/highlights" -> handleHighlights(socket, method, query, body)
                "/api/notices" -> handleNotices(socket, method, query, body)
                "/api/banner-ad" -> handleBannerAd(socket, method, query, body)
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
        contentType: String = "application/json"
    ) {
        try {
            val statusText = when (status) {
                200 -> "OK"
                204 -> "No Content"
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
            writer.print("Access-Control-Allow-Headers: Content-Type\r\n")
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
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Sportzfy Live - Admin Console Panel</title>
                <script src="https://cdn.tailwindcss.com"></script>
                <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
                <style>
                    body { background-color: #0b111e; color: #f3f4f6; }
                    .card { background-color: #151f32; border: 1px solid #1e2d4a; }
                    .accent-blue { color: #00e5ff; }
                    .bg-accent-blue { background-color: #00e5ff; }
                </style>
            </head>
            <body class="font-sans antialiased pb-12">
                <nav class="bg-[#0f1826] border-b border-[#1e2d4a] py-4 px-6 mb-8 sticky top-0 z-50">
                    <div class="max-w-7xl mx-auto flex flex-col sm:flex-row justify-between items-center gap-4">
                        <div class="flex items-center space-x-3">
                            <span class="text-3xl font-extrabold tracking-wider text-transparent bg-clip-text bg-gradient-to-r from-cyan-400 to-blue-500">SPORTZFY</span>
                            <span class="bg-red-500 text-[10px] text-white font-bold px-2 py-0.5 rounded uppercase tracking-widest animate-pulse">Admin Panel</span>
                        </div>
                        <div class="flex items-center space-x-3">
                            <a href="/web-app" target="_blank" class="bg-gradient-to-r from-cyan-500 to-blue-500 hover:from-cyan-600 hover:to-blue-600 text-[#0b111e] px-4 py-2 rounded-xl text-xs font-bold transition flex items-center gap-1.5 shadow-md">
                                <i class="fa-solid fa-earth-americas text-sm"></i>
                                <span>Open Web App Version</span>
                            </a>
                            <div class="text-xs text-gray-400 flex items-center space-x-2 bg-[#0b111e] px-3 py-2 rounded-xl border border-[#1e2d4a]">
                                <span class="inline-block w-2 h-2 rounded-full bg-green-500 animate-ping"></span>
                                <span class="font-semibold text-gray-300">Live Sync Active</span>
                            </div>
                        </div>
                    </div>
                </nav>

                <main class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
                    <!-- Navigation Tabs -->
                    <div class="flex space-x-2 border-b border-[#1e2d4a] pb-px mb-8">
                        <button onclick="switchTab('broadcasts')" id="tab-broadcasts" class="px-6 py-3 text-sm font-bold border-b-2 border-cyan-400 text-cyan-400 transition flex items-center gap-2">
                            <i class="fa-solid fa-tower-broadcast"></i>
                            <span>Live Broadcasts</span>
                        </button>
                        <button onclick="switchTab('app-management')" id="tab-app-management" class="px-6 py-3 text-sm font-medium border-b-2 border-transparent text-gray-400 hover:text-white hover:border-gray-500 transition flex items-center gap-2">
                            <i class="fa-solid fa-gears"></i>
                            <span>App Management</span>
                        </button>
                    </div>

                    <!-- TAB 1 CONTENT: Live Broadcasts -->
                    <div id="content-broadcasts" class="space-y-8">
                        <!-- Quick Announcement Banner -->
                        <div class="card p-6 rounded-2xl mb-8 shadow-xl">
                        <h2 class="text-lg font-bold mb-3 flex items-center space-x-2 text-cyan-400">
                            <i class="fa-solid fa-bullhorn"></i>
                            <span>Global Notice Scrolling Banner</span>
                        </h2>
                        <div class="flex flex-col sm:flex-row gap-4 items-center">
                            <input id="noticeInput" type="text" class="flex-grow bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-3 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="Enter notice banner text here...">
                            <button onclick="saveNotice()" class="bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold px-6 py-3 rounded-xl transition duration-200 shadow-md text-sm w-full sm:w-auto">Update Announcement</button>
                        </div>
                    </div>

                    <!-- Manual Banner Ad Player Settings -->
                    <div class="card p-6 rounded-2xl mb-8 shadow-xl">
                        <h2 class="text-lg font-bold mb-3 flex items-center space-x-2 text-cyan-400">
                            <i class="fa-solid fa-rectangle-ad"></i>
                            <span>Manual Banner Ad Player & Redirection Ads</span>
                        </h2>
                        <p class="text-xs text-gray-400 mb-4">Specify a picture banner, GIF banner, or short video banner with a clickable redirection link. It will display directly below the video player in both Web and Android apps, acting like a premium pop-up ad setting.</p>
                        
                        <div class="grid grid-cols-1 md:grid-cols-4 gap-4 items-end">
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wide">Banner Type</label>
                                <select id="bannerMediaType" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-3 text-white focus:outline-none focus:border-cyan-400 text-sm">
                                    <option value="image">Static Picture Banner</option>
                                    <option value="gif">Animated GIF Banner</option>
                                    <option value="video">Short Video Banner</option>
                                </select>
                            </div>
                            <div class="md:col-span-2">
                                <label class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wide">Banner Media URL (Direct Link)</label>
                                <input id="bannerMediaUrl" type="text" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-3 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. https://domain.com/banner.gif or /images/banner.png">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wide">Status</label>
                                <select id="bannerEnabled" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-3 text-white focus:outline-none focus:border-cyan-400 text-sm font-semibold">
                                    <option value="true">🟢 Active (Enabled)</option>
                                    <option value="false">🔴 Inactive (Disabled)</option>
                                </select>
                            </div>
                        </div>
                        
                        <div class="grid grid-cols-1 md:grid-cols-4 gap-4 items-end mt-4">
                            <div class="md:col-span-3">
                                <label class="block text-xs font-semibold text-gray-400 mb-1.5 uppercase tracking-wide">Clickable Redirect Link (Redirection URL)</label>
                                <input id="bannerClickUrl" type="text" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-3 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. https://your-advertiser-site.com">
                            </div>
                            <div>
                                <button onclick="saveBannerAd()" class="bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold px-6 py-3 rounded-xl transition duration-200 shadow-md text-sm w-full">Update Banner Ad</button>
                            </div>
                        </div>
                    </div>


                    <!-- OBS Studio Live Broadcaster Receiver -->
                    <div class="card p-6 rounded-2xl mb-8 shadow-xl">
                        <div class="flex flex-col md:flex-row justify-between items-start md:items-center mb-4 gap-4">
                            <div>
                                <h2 class="text-xl font-bold flex items-center space-x-2 text-cyan-400">
                                    <i class="fa-solid fa-tower-broadcast text-cyan-400 animate-pulse"></i>
                                    <span>OBS Studio Live Broadcaster Receiver</span>
                                </h2>
                                <p class="text-xs text-gray-400 mt-1">Directly receive and stream from OBS Studio to the Android app!</p>
                            </div>
                            <div class="flex items-center space-x-2 bg-[#0b111e] px-4 py-2 rounded-xl border border-[#1e2d4a]">
                                <span id="obsIndicator" class="w-2.5 h-2.5 rounded-full bg-red-500"></span>
                                <span id="obsStatusText" class="text-xs font-bold text-gray-400 uppercase tracking-wider">Offline</span>
                            </div>
                        </div>

                        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
                            <!-- Stream Key Configuration -->
                            <div class="space-y-3">
                                <label class="block text-xs font-bold text-cyan-400 uppercase tracking-widest">1. Set Stream Key / ID</label>
                                <input id="obsStreamKey" type="text" value="live" oninput="updateObsUrls()" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-3 text-white focus:outline-none focus:border-cyan-400 text-sm font-semibold" placeholder="e.g. live, cricket, stream1">
                                <div class="p-3 bg-[#0b111e]/60 rounded-xl border border-[#1e2d4a]/60 text-[11px] space-y-1">
                                    <div class="text-gray-500 font-semibold">Playlist Location in App:</div>
                                    <div class="text-cyan-400 font-mono font-bold truncate" id="obsKeyUrl">/obs/live/index.m3u8</div>
                                </div>
                            </div>

                            <!-- Setup Instructions -->
                            <div class="lg:col-span-2 space-y-3">
                                <label class="block text-xs font-bold text-cyan-400 uppercase tracking-widest">2. Configure OBS Studio Settings</label>
                                <div class="bg-[#0b111e] p-4 rounded-xl border border-[#1e2d4a] text-xs text-gray-300 space-y-3 leading-relaxed">
                                    <p>🛡️ <strong class="text-cyan-300">Step A:</strong> In OBS Studio, go to <strong class="text-white">Settings</strong> ➔ <strong class="text-white">Stream</strong>.</p>
                                    <p>⚙️ <strong class="text-cyan-300">Step B:</strong> Set <strong class="text-white">Service</strong> to <strong class="text-cyan-400">HLS</strong> (do NOT use 'Custom...').</p>
                                    <p>🔗 <strong class="text-cyan-300">Step C:</strong> Choose <strong class="text-white">Custom Server</strong> and paste this exact Server URL:
                                        <div class="flex items-center mt-1 bg-[#151f32] p-2.5 rounded-xl border border-[#1e2d4a] gap-2">
                                            <code class="text-green-400 font-mono select-all flex-grow truncate text-xs" id="obsServerUrl">http://localhost:8080/obs/live/</code>
                                            <button onclick="copyObsUrl()" class="px-3 py-1.5 bg-cyan-500 text-black rounded-lg font-bold hover:bg-cyan-600 transition text-[11px] uppercase tracking-wide shrink-0">Copy</button>
                                        </div>
                                    </p>
                                    <p>🔑 <strong class="text-cyan-300">Step D:</strong> Keep <strong class="text-white">Stream Key</strong> completely blank or type any value, then save.</p>
                                    <p>📡 <strong class="text-cyan-300">Step E:</strong> Click <strong class="text-white">Start Streaming</strong> in OBS. The Android app automatically provides a premium <strong>Local OBS Broadcast</strong> player card on the home screen to watch the feed instantly!</p>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Dynamic Search & Filter Bar -->
                    <div class="card p-4 rounded-2xl mb-8 shadow-xl">
                        <div class="flex flex-col md:flex-row gap-4 items-center">
                            <div class="relative flex-grow w-full">
                                <span class="absolute inset-y-0 left-0 flex items-center pl-4 pointer-events-none">
                                    <i class="fa-solid fa-magnifying-glass text-cyan-400"></i>
                                </span>
                                <input id="adminSearchInput" type="text" oninput="filterAllLists()" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl pl-11 pr-4 py-3 text-white focus:outline-none focus:border-cyan-400 focus:ring-1 focus:ring-cyan-400 text-sm placeholder-gray-500 font-semibold" placeholder="Search & filter Matches, Channels, and Highlights dynamically...">
                            </div>
                            <button onclick="clearSearch()" class="px-5 py-3 bg-[#0b111e] border border-[#1e2d4a] text-gray-400 hover:text-white rounded-xl text-sm font-semibold transition shrink-0 w-full md:w-auto flex items-center justify-center gap-1.5">
                                <i class="fa-solid fa-eraser"></i>
                                <span>Clear Search</span>
                            </button>
                        </div>
                    </div>

                    <!-- Main Sections Grid -->
                    <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">
                        <!-- 1. LIVE MATCHES SECTION -->
                        <div class="card p-6 rounded-2xl shadow-xl flex flex-col">
                            <div class="flex justify-between items-center mb-6">
                                <h2 class="text-xl font-bold text-cyan-400 flex items-center space-x-2">
                                    <i class="fa-solid fa-circle-play text-red-500 animate-pulse"></i>
                                    <span>Live & Upcoming Matches</span>
                                </h2>
                                <button onclick="openMatchModal()" class="bg-cyan-500 text-[#0b111e] hover:bg-cyan-600 font-bold px-4 py-2 rounded-xl text-xs flex items-center space-x-1">
                                    <i class="fa-solid fa-plus"></i>
                                    <span>Add Match</span>
                                </button>
                            </div>
                            <div id="matchesList" class="space-y-4 flex-grow overflow-y-auto max-h-[500px] pr-2">
                                <!-- Dynamic Match Items -->
                            </div>
                        </div>

                        <!-- 2. LIVE TV CHANNELS SECTION -->
                        <div class="card p-6 rounded-2xl shadow-xl flex flex-col">
                            <div class="flex justify-between items-center mb-6">
                                <h2 class="text-xl font-bold text-cyan-400 flex items-center space-x-2">
                                    <i class="fa-solid fa-tv text-cyan-400"></i>
                                    <span>Live TV Channels</span>
                                </h2>
                                <button onclick="openChannelModal()" class="bg-cyan-500 text-[#0b111e] hover:bg-cyan-600 font-bold px-4 py-2 rounded-xl text-xs flex items-center space-x-1">
                                    <i class="fa-solid fa-plus"></i>
                                    <span>Add Channel</span>
                                </button>
                            </div>

                            <!-- M3U Playlist Bulk Importer UI -->
                            <div class="p-4 bg-[#0b111e]/80 rounded-xl border border-[#1e2d4a] mb-6 space-y-3">
                                <div class="flex justify-between items-center border-b border-[#1e2d4a]/40 pb-2">
                                    <div class="flex space-x-4">
                                        <button type="button" onclick="switchImportTab('url')" id="tabImportUrl" class="text-xs font-bold text-cyan-400 border-b-2 border-cyan-400 pb-1 flex items-center space-x-1 uppercase tracking-wider">
                                            <i class="fa-solid fa-link text-cyan-400 mr-1"></i>M3U URL
                                        </button>
                                        <button type="button" onclick="switchImportTab('paste')" id="tabImportPaste" class="text-xs font-semibold text-gray-400 hover:text-white pb-1 flex items-center space-x-1 uppercase tracking-wider">
                                            <i class="fa-solid fa-clipboard mr-1"></i>Paste Playlist
                                        </button>
                                    </div>
                                    <button type="button" onclick="clearAllChannels()" class="text-[10px] text-red-400 hover:text-red-300 font-semibold uppercase tracking-wider bg-red-500/10 px-2.5 py-1 rounded-lg border border-red-500/20">
                                        <i class="fa-solid fa-trash-can mr-1"></i>Clear All
                                    </button>
                                </div>

                                <!-- TAB 1: IMPORT VIA URL -->
                                <div id="importUrlSection" class="space-y-3">
                                    <div class="text-[11px] text-gray-400 leading-relaxed">
                                        Quick-import pre-validated channels from global open-source directories or enter your custom M3U playlist URL.
                                    </div>
                                    <div class="grid grid-cols-2 md:grid-cols-3 gap-1.5 pt-1">
                                        <button onclick="selectPreset('https://iptv-org.github.io/iptv/categories/sports.m3u', 'Sports')" class="bg-[#151f32] hover:bg-[#1e2d4a] border border-[#1e2d4a] text-gray-300 text-[10px] py-1.5 px-2 rounded-lg font-medium text-left truncate">
                                            ⚽ Sports (iptv-org)
                                        </button>
                                        <button onclick="selectPreset('https://iptv-org.github.io/iptv/categories/news.m3u', 'News')" class="bg-[#151f32] hover:bg-[#1e2d4a] border border-[#1e2d4a] text-gray-300 text-[10px] py-1.5 px-2 rounded-lg font-medium text-left truncate">
                                            📰 News (iptv-org)
                                        </button>
                                        <button onclick="selectPreset('https://romaxa55.github.io/world_ip_tv/index.m3u', 'World TV')" class="bg-[#151f32] hover:bg-[#1e2d4a] border border-[#1e2d4a] text-gray-300 text-[10px] py-1.5 px-2 rounded-lg font-medium text-left truncate">
                                            🌍 Romaxa Verified List
                                        </button>
                                        <button onclick="selectPreset('https://iptv-org.github.io/iptv/categories/movies.m3u', 'Movies')" class="bg-[#151f32] hover:bg-[#1e2d4a] border border-[#1e2d4a] text-gray-300 text-[10px] py-1.5 px-2 rounded-lg font-medium text-left truncate">
                                            🎬 Movies (iptv-org)
                                        </button>
                                        <button onclick="selectPreset('https://iptv-org.github.io/iptv/categories/documentary.m3u', 'Documentary')" class="bg-[#151f32] hover:bg-[#1e2d4a] border border-[#1e2d4a] text-gray-300 text-[10px] py-1.5 px-2 rounded-lg font-medium text-left truncate">
                                            🧠 Docu (iptv-org)
                                        </button>
                                        <button onclick="selectPreset('https://iptv-org.github.io/iptv/categories/kids.m3u', 'Kids')" class="bg-[#151f32] hover:bg-[#1e2d4a] border border-[#1e2d4a] text-gray-300 text-[10px] py-1.5 px-2 rounded-lg font-medium text-left truncate">
                                            🧸 Kids (iptv-org)
                                        </button>
                                    </div>
                                    <div class="flex gap-2 pt-2">
                                        <input id="m3uUrlInput" type="text" class="flex-grow bg-[#151f32] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-xs font-mono" placeholder="Custom .m3u URL here...">
                                        <input id="m3uCategoryInput" type="text" value="IPTV" class="w-24 bg-[#151f32] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-xs" placeholder="Category">
                                        <button onclick="importM3uPlaylist()" id="importBtn" class="bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold px-4 py-2 rounded-xl text-xs flex items-center space-x-1 shrink-0">
                                            <i class="fa-solid fa-cloud-arrow-down"></i>
                                            <span>Import</span>
                                        </button>
                                    </div>
                                </div>

                                <!-- TAB 2: IMPORT VIA PASTING RAW TEXT -->
                                <div id="importPasteSection" class="space-y-3 hidden">
                                    <div class="text-[11px] text-gray-400 leading-relaxed font-normal">
                                        Paste hundreds of M3U8 links directly. Supports standard <span class="text-cyan-400 font-semibold">M3U format</span>, comma-separated lists (<span class="text-cyan-400 font-semibold">Name, URL</span>), or raw <span class="text-cyan-400 font-semibold">URLs</span> (one per line).
                                    </div>
                                    <textarea id="m3uRawTextInput" rows="5" class="w-full bg-[#151f32] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-xs font-mono leading-relaxed" placeholder="#EXTM3U&#10;#EXTINF:-1 tvg-logo=&quot;logo_url&quot; group-title=&quot;Sports&quot;,Somoy TV&#10;https://example.com/stream.m3u8&#10;&#10;OR&#10;&#10;My Channel Name,https://example.com/live.m3u8"></textarea>
                                    <div class="flex gap-2 pt-1 justify-between items-center">
                                        <div class="flex items-center gap-1.5">
                                            <span class="text-[10px] font-semibold text-gray-400">Category:</span>
                                            <input id="m3uPasteCategoryInput" type="text" value="IPTV" class="w-28 bg-[#151f32] border border-[#1e2d4a] rounded-xl px-2.5 py-1.5 text-white focus:outline-none focus:border-cyan-400 text-[11px]" placeholder="Category">
                                        </div>
                                        <button onclick="importM3uRawText()" id="importPasteBtn" class="bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold px-5 py-2 rounded-xl text-xs flex items-center space-x-1.5 shadow-md">
                                            <i class="fa-solid fa-file-import"></i>
                                            <span>Import Raw Playlist</span>
                                        </button>
                                    </div>
                                </div>

                                <div id="importStatus" class="hidden text-[10px] font-bold text-green-400 mt-1"></div>
                            </div>

                            <div id="channelsList" class="space-y-4 flex-grow overflow-y-auto max-h-[500px] pr-2">
                                <!-- Dynamic Channel Items -->
                            </div>
                        </div>

                        <!-- 3. MATCH HIGHLIGHTS SECTION -->
                        <div class="card p-6 rounded-2xl shadow-xl flex flex-col lg:col-span-2">
                            <div class="flex justify-between items-center mb-6">
                                <h2 class="text-xl font-bold text-cyan-400 flex items-center space-x-2">
                                    <i class="fa-solid fa-clapperboard text-cyan-400"></i>
                                    <span>Match Highlights Feed</span>
                                </h2>
                                <button onclick="openHighlightModal()" class="bg-cyan-500 text-[#0b111e] hover:bg-cyan-600 font-bold px-4 py-2 rounded-xl text-xs flex items-center space-x-1">
                                    <i class="fa-solid fa-plus"></i>
                                    <span>Add Highlight</span>
                                </button>
                            </div>
                            <div id="highlightsList" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 overflow-y-auto max-h-[600px] pr-2">
                                <!-- Dynamic Highlight Items -->
                            </div>
                        </div>
                    </div>
                    </div> <!-- End of content-broadcasts -->

                    <!-- TAB 2 CONTENT: App Management -->
                    <div id="content-app-management" class="space-y-8 hidden">
                        <!-- App Version & Update Manager -->
                        <div class="card p-6 rounded-2xl shadow-xl">
                            <h2 class="text-lg font-bold mb-3 flex items-center space-x-2 text-cyan-400">
                                <i class="fa-solid fa-circle-arrow-up"></i>
                                <span>App Version & Update Manager (Connects Admin ➔ Web ➔ APK App)</span>
                            </h2>
                            <p class="text-xs text-gray-400 mb-6 leading-relaxed">
                                Configure the latest release settings for your Sportzfy application. Whenever users open their Android app, it will query these settings. If a newer version code is configured, they will be prompted with an elegant, automatic update dialog and changelog information to update directly from the website's hosted APK.
                            </p>

                            <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
                                <!-- Left: Config Settings Form -->
                                <div class="lg:col-span-2 border-r border-[#1e2d4a]/50 pr-0 lg:pr-6">
                                    <h3 class="text-sm font-bold text-gray-300 mb-4 flex items-center gap-2">
                                        <i class="fa-solid fa-sliders text-cyan-400"></i>
                                        <span>Release Meta Settings</span>
                                    </h3>
                                    <form id="updateSettingsForm" onsubmit="saveUpdateSettings(event)" class="space-y-4">
                                        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                                            <div>
                                                <label class="block text-xs font-semibold text-gray-400 mb-1">Latest Version Code</label>
                                                <input id="updateVersionCode" type="number" min="1" step="1" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-2.5 text-white focus:outline-none focus:border-cyan-400 text-sm font-semibold" placeholder="e.g. 2">
                                            </div>
                                            <div>
                                                <label class="block text-xs font-semibold text-gray-400 mb-1">Latest Version Name</label>
                                                <input id="updateVersionName" type="text" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-2.5 text-white focus:outline-none focus:border-cyan-400 text-sm font-semibold" placeholder="e.g. 1.1.0">
                                            </div>
                                        </div>
                                        <div>
                                            <label class="block text-xs font-semibold text-gray-400 mb-1">APK File Download URL (Defaults to local server host)</label>
                                            <input id="updateApkUrl" type="text" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-2.5 text-white focus:outline-none focus:border-cyan-400 text-sm font-mono" placeholder="e.g. /apk/sportzfy-latest.apk">
                                        </div>
                                        <div>
                                            <label class="block text-xs font-semibold text-gray-400 mb-1">Update Message / Release Notes / Changelog</label>
                                            <textarea id="updateChangelog" rows="3" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-4 py-2.5 text-white focus:outline-none focus:border-cyan-400 text-sm leading-relaxed" placeholder="e.g. Added premium live cricket channels, improved streaming player engine, bug fixes."></textarea>
                                        </div>
                                        
                                        <!-- Mandatory Update Checkbox -->
                                        <div class="flex items-center p-3.5 bg-[#0b111e]/40 rounded-xl border border-[#1e2d4a]/50">
                                            <label class="flex items-center space-x-3 cursor-pointer select-none">
                                                <input id="updateIsMandatory" type="checkbox" class="w-4 h-4 text-cyan-500 border-[#1e2d4a] bg-[#0b111e] rounded focus:ring-cyan-400">
                                                <div>
                                                    <span class="text-xs font-bold text-gray-200 block">Mandatory Update</span>
                                                    <span class="text-[10px] text-gray-400">If checked, the update dialogue inside the app cannot be dismissed or skipped.</span>
                                                </div>
                                            </label>
                                        </div>

                                        <div class="flex items-center justify-between pt-2">
                                            <div id="updateSaveStatus" class="hidden text-xs font-bold text-green-400"></div>
                                            <button type="submit" class="bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold px-6 py-2.5 rounded-xl transition duration-200 shadow-md text-sm flex items-center justify-center gap-2 ml-auto">
                                                <i class="fa-solid fa-floppy-disk"></i>
                                                <span>Save & Publish</span>
                                            </button>
                                        </div>
                                    </form>
                                </div>

                                <!-- Right: APK Uploader section -->
                                <div class="flex flex-col justify-between">
                                    <div>
                                        <h3 class="text-sm font-bold text-gray-300 mb-3 flex items-center gap-2">
                                            <i class="fa-solid fa-file-arrow-up text-cyan-400"></i>
                                            <span>Direct APK Uploader</span>
                                        </h3>
                                        <p class="text-xs text-gray-400 mb-4 leading-relaxed">
                                            Upload a newer version of the <b>Sportzfy APK</b> file directly to your local web server. The app will automatically host and serve this file for user download.
                                        </p>
                                        
                                        <div class="border-2 border-dashed border-[#1e2d4a] hover:border-cyan-400/50 rounded-2xl p-6 text-center bg-[#0b111e]/40 transition duration-200">
                                            <i class="fa-solid fa-box-open text-3xl text-gray-500 mb-3 block"></i>
                                            <label class="cursor-pointer bg-[#1e2d4a] hover:bg-[#253759] text-gray-200 hover:text-white px-4 py-2 rounded-xl text-xs font-bold inline-block transition shadow-sm mb-2">
                                                <span>Select APK File</span>
                                                <input id="apkFileInput" type="file" accept=".apk" class="hidden" onchange="onApkFileSelected()">
                                            </label>
                                            <p id="selectedApkInfo" class="text-[11px] text-gray-500 font-medium">No file selected</p>
                                        </div>
                                        
                                        <div id="apkUploadStatus" class="hidden text-xs font-bold text-yellow-400 mt-3"></div>
                                    </div>
                                    <div class="pt-4 border-t border-[#1e2d4a]/30 mt-4 lg:mt-0">
                                        <button onclick="uploadApkFile()" class="w-full bg-[#1e2d4a] hover:bg-cyan-500 hover:text-[#0b111e] text-cyan-400 font-bold py-2.5 rounded-xl transition duration-200 text-xs flex items-center justify-center gap-2 border border-cyan-400/20 hover:border-transparent">
                                            <i class="fa-solid fa-upload"></i>
                                            <span>Upload & Host APK</span>
                                        </button>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div> <!-- End of content-app-management -->
                </main>

                <!-- Modals -->
                <!-- Match Modal -->
                <div id="matchModal" class="fixed inset-0 bg-black/70 hidden z-50 flex items-center justify-center p-4">
                    <div class="card w-full max-w-lg rounded-2xl p-6 relative">
                        <h3 id="matchModalTitle" class="text-lg font-bold text-cyan-400 mb-4">Add Live Match</h3>
                        <button onclick="closeMatchModal()" class="absolute top-4 right-4 text-gray-400 hover:text-white"><i class="fa-solid fa-xmark text-xl"></i></button>
                        <form id="matchForm" onsubmit="submitMatch(event)" class="space-y-4">
                            <input type="hidden" id="matchId" value="">
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Match/Tournament Title</label>
                                <input type="text" id="matchTitle" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. Cricket || ICC Women World Twenty20" required>
                            </div>
                            <div class="grid grid-cols-2 gap-4">
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Sport Category</label>
                                    <select id="matchSport" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm">
                                        <option value="Cricket">Cricket</option>
                                        <option value="Football">Football</option>
                                        <option value="MotorSports">MotorSports</option>
                                        <option value="Wrestling">Wrestling</option>
                                    </select>
                                </div>
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Status</label>
                                    <select id="matchStatus" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm">
                                        <option value="Live">Live</option>
                                        <option value="Upcoming">Upcoming</option>
                                        <option value="Recent">Recent</option>
                                    </select>
                                </div>
                            </div>
                            <div class="grid grid-cols-2 gap-4">
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 1 Name</label>
                                    <input type="text" id="matchTeam1Name" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. PAK-W" required>
                                </div>
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 1 Logo Key/URL</label>
                                    <input type="text" id="matchTeam1Logo" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. pakistan" required>
                                </div>
                            </div>
                            <div class="grid grid-cols-2 gap-4">
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 2 Name</label>
                                    <input type="text" id="matchTeam2Name" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. NED-W" required>
                                </div>
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 2 Logo Key/URL</label>
                                    <input type="text" id="matchTeam2Logo" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. netherlands" required>
                                </div>
                            </div>
                            <div class="grid grid-cols-2 gap-4">
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Time Indicator</label>
                                    <input type="text" id="matchTime" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. 03:54:23 or 07:30 PM" required>
                                </div>
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Video Stream or Platform URL</label>
                                    <input type="text" id="matchStream" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. .m3u8, YouTube, Twitch, or Embed Link" required>
                                </div>
                            </div>
                            <button type="submit" class="w-full bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold py-2.5 rounded-xl text-sm transition duration-200">Save Match Feed</button>
                        </form>
                    </div>
                </div>

                <!-- Channel Modal -->
                <div id="channelModal" class="fixed inset-0 bg-black/70 hidden z-50 flex items-center justify-center p-4">
                    <div class="card w-full max-w-md rounded-2xl p-6 relative max-h-[90vh] overflow-y-auto">
                        <h3 id="channelModalTitle" class="text-lg font-bold text-cyan-400 mb-4">Add TV Channel</h3>
                        <button onclick="closeChannelModal()" class="absolute top-4 right-4 text-gray-400 hover:text-white"><i class="fa-solid fa-xmark text-xl"></i></button>
                        <form id="channelForm" onsubmit="submitChannel(event)" class="space-y-4">
                            <input type="hidden" id="channelId" value="">
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Channel Name</label>
                                <input type="text" id="channelName" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. Somoy TV" required>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Category Group</label>
                                <select id="channelCategory" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm">
                                    <option value="VIP">VIP</option>
                                    <option value="News">News</option>
                                    <option value="Entertainment">Entertainment</option>
                                    <option value="Local TV">Local TV</option>
                                    <option value="Sports">Sports</option>
                                    <option value="Bangladesh">Bangladesh</option>
                                    <option value="Documentary">Documentary</option>
                                    <option value="Music">Music</option>
                                    <option value="Cartoon">Cartoon</option>
                                </select>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Logo Tag / Image URL</label>
                                <input type="text" id="channelLogoUrl" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. somoy_tv or URL" required>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Primary Stream URL (Server 1)</label>
                                <input type="text" id="channelStreamUrl" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. .m3u8 stream, YouTube, Twitch, or Embed Link" required>
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Backup Stream URL (Server 2)</label>
                                <input type="text" id="channelStreamUrl2" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="Optional backup stream URL">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Backup Stream URL (Server 3)</label>
                                <input type="text" id="channelStreamUrl3" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="Optional backup stream URL">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Backup Stream URL (Server 4)</label>
                                <input type="text" id="channelStreamUrl4" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="Optional backup stream URL">
                            </div>
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Backup Stream URL (Server 5)</label>
                                <input type="text" id="channelStreamUrl5" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="Optional backup stream URL">
                            </div>
                            <button type="submit" class="w-full bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold py-2.5 rounded-xl text-sm transition duration-200">Save TV Channel</button>
                        </form>
                    </div>
                </div>

                <!-- Highlight Modal -->
                <div id="highlightModal" class="fixed inset-0 bg-black/70 hidden z-50 flex items-center justify-center p-4">
                    <div class="card w-full max-w-md rounded-2xl p-6 relative">
                        <h3 id="highlightModalTitle" class="text-lg font-bold text-cyan-400 mb-4">Add Highlight Clip</h3>
                        <button onclick="closeHighlightModal()" class="absolute top-4 right-4 text-gray-400 hover:text-white"><i class="fa-solid fa-xmark text-xl"></i></button>
                        <form id="highlightForm" onsubmit="submitHighlight(event)" class="space-y-4">
                            <input type="hidden" id="highlightId" value="">
                            <div>
                                <label class="block text-xs font-semibold text-gray-400 mb-1">Tournament Title</label>
                                <input type="text" id="highlightTitle" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. Football | FIFA World Cup" required>
                            </div>
                            <div class="grid grid-cols-2 gap-4">
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 1 Name</label>
                                    <input type="text" id="highlightTeam1Name" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. Mexico" required>
                                </div>
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 1 Logo Key</label>
                                    <input type="text" id="highlightTeam1Logo" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. mexico" required>
                                </div>
                            </div>
                            <div class="grid grid-cols-2 gap-4">
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 2 Name</label>
                                    <input type="text" id="highlightTeam2Name" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. South Africa" required>
                                </div>
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Team 2 Logo Key</label>
                                    <input type="text" id="highlightTeam2Logo" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. southafrica" required>
                                </div>
                            </div>
                            <div class="grid grid-cols-2 gap-4">
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Match Date</label>
                                    <input type="text" id="highlightDate" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. 11/06/2026" required>
                                </div>
                                <div>
                                    <label class="block text-xs font-semibold text-gray-400 mb-1">Highlight Video or Platform URL</label>
                                    <input type="text" id="highlightStreamUrl" class="w-full bg-[#0b111e] border border-[#1e2d4a] rounded-xl px-3 py-2 text-white focus:outline-none focus:border-cyan-400 text-sm" placeholder="e.g. .mp4 file, YouTube, Twitch, or Embed Link" required>
                                </div>
                            </div>
                            <button type="submit" class="w-full bg-cyan-500 hover:bg-cyan-600 text-[#0b111e] font-bold py-2.5 rounded-xl text-sm transition duration-200">Save Highlight</button>
                        </form>
                    </div>
                </div>

                <script>
                    const API_URL = '';

                    let allMatches = [];
                    let allChannels = [];
                    let allHighlights = [];

                    function filterAllLists() {
                        renderMatches();
                        renderChannels();
                        renderHighlights();
                    }

                    function clearSearch() {
                        const input = document.getElementById("adminSearchInput");
                        if (input) input.value = "";
                        filterAllLists();
                    }

                    function updateObsUrls() {
                        const key = document.getElementById("obsStreamKey").value.trim().toLowerCase() || "live";
                        const origin = window.location.origin;
                        const serverUrl = origin + "/obs/" + key + "/";
                        const streamUrl = "/obs/" + key + "/index.m3u8";
                        
                        document.getElementById("obsServerUrl").innerText = serverUrl;
                        document.getElementById("obsKeyUrl").innerText = streamUrl;
                    }

                    function copyObsUrl() {
                        const text = document.getElementById("obsServerUrl").innerText;
                        navigator.clipboard.writeText(text).then(() => {
                            alert("Copied OBS Stream Server URL to clipboard!");
                        }).catch(err => {
                            // Fallback
                            const el = document.createElement('textarea');
                            el.value = text;
                            document.body.appendChild(el);
                            el.select();
                            document.execCommand('copy');
                            document.body.removeChild(el);
                            alert("Copied OBS Stream Server URL!");
                        });
                    }

                    async function checkObsStatus() {
                        try {
                            const key = document.getElementById("obsStreamKey").value.trim().toLowerCase() || "live";
                            const res = await fetch(API_URL + '/api/obs-status?streamKey=' + key);
                            const data = await res.json();
                            const indicator = document.getElementById("obsIndicator");
                            const text = document.getElementById("obsStatusText");
                            
                            if (data.active) {
                                indicator.className = "w-2.5 h-2.5 rounded-full bg-green-500 animate-pulse";
                                text.className = "text-xs font-bold text-green-400 uppercase tracking-wider";
                                text.innerText = "Streaming Live";
                            } else {
                                indicator.className = "w-2.5 h-2.5 rounded-full bg-red-500";
                                text.className = "text-xs font-bold text-gray-400 uppercase tracking-wider";
                                text.innerText = "Offline";
                            }
                        } catch (e) {
                            console.error("Error checking OBS status", e);
                        }
                    }

                    document.addEventListener("DOMContentLoaded", () => {
                        fetchNotice();
                        fetchBannerAd();
                        fetchMatches();
                        fetchChannels();
                        fetchHighlights();
                        updateObsUrls();
                        fetchUpdateSettings();
                        setInterval(checkObsStatus, 2500);
                    });

                    function switchTab(tabId) {
                        const tabBroadcasts = document.getElementById('tab-broadcasts');
                        const tabAppManagement = document.getElementById('tab-app-management');
                        const contentBroadcasts = document.getElementById('content-broadcasts');
                        const contentAppManagement = document.getElementById('content-app-management');

                        if (tabId === 'broadcasts') {
                            tabBroadcasts.className = "px-6 py-3 text-sm font-bold border-b-2 border-cyan-400 text-cyan-400 transition flex items-center gap-2";
                            tabAppManagement.className = "px-6 py-3 text-sm font-medium border-b-2 border-transparent text-gray-400 hover:text-white hover:border-gray-500 transition flex items-center gap-2";
                            contentBroadcasts.classList.remove('hidden');
                            contentAppManagement.classList.add('hidden');
                        } else {
                            tabBroadcasts.className = "px-6 py-3 text-sm font-medium border-b-2 border-transparent text-gray-400 hover:text-white hover:border-gray-500 transition flex items-center gap-2";
                            tabAppManagement.className = "px-6 py-3 text-sm font-bold border-b-2 border-cyan-400 text-cyan-400 transition flex items-center gap-2";
                            contentBroadcasts.classList.add('hidden');
                            contentAppManagement.classList.remove('hidden');
                        }
                    }

                    async function fetchUpdateSettings() {
                        try {
                            const res = await fetch(API_URL + '/api/app-update');
                            const data = await res.json();
                            document.getElementById("updateVersionCode").value = data.versionCode || 1;
                            document.getElementById("updateVersionName").value = data.versionName || "1.0.0";
                            document.getElementById("updateApkUrl").value = data.apkUrl || "";
                            document.getElementById("updateChangelog").value = data.changelog || "";
                            document.getElementById("updateIsMandatory").checked = !!data.isMandatory;
                        } catch (e) {
                            console.error("Error fetching update settings", e);
                        }
                    }

                    async function saveUpdateSettings(e) {
                        e.preventDefault();
                        const statusDiv = document.getElementById("updateSaveStatus");
                        statusDiv.classList.add("hidden");
                        
                        const payload = {
                            versionCode: parseInt(document.getElementById("updateVersionCode").value) || 1,
                            versionName: document.getElementById("updateVersionName").value.trim() || "1.0.0",
                            apkUrl: document.getElementById("updateApkUrl").value.trim(),
                            changelog: document.getElementById("updateChangelog").value.trim() || "No release notes provided.",
                            isMandatory: document.getElementById("updateIsMandatory").checked
                        };

                        try {
                            const res = await fetch(API_URL + '/api/app-update', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(payload)
                            });
                            const result = await res.json();
                            if (result.success) {
                                statusDiv.innerText = "✓ Latest release settings saved and synced globally!";
                                statusDiv.className = "text-xs font-bold text-green-400 mt-2 block";
                            } else {
                                statusDiv.innerText = "✗ Error: " + result.message;
                                statusDiv.className = "text-xs font-bold text-red-400 mt-2 block";
                            }
                        } catch (e) {
                            statusDiv.innerText = "✗ Network Error: Could not save settings.";
                            statusDiv.className = "text-xs font-bold text-red-400 mt-2 block";
                        }
                        
                        setTimeout(() => statusDiv.classList.add("hidden"), 4000);
                    }

                    function onApkFileSelected() {
                        const fileInput = document.getElementById('apkFileInput');
                        const info = document.getElementById('selectedApkInfo');
                        if (fileInput.files && fileInput.files.length > 0) {
                            const file = fileInput.files[0];
                            info.innerText = file.name + " (" + (file.size / 1024 / 1024).toFixed(2) + " MB)";
                            info.className = "text-[11px] text-cyan-400 font-semibold";
                        } else {
                            info.innerText = "No file selected";
                            info.className = "text-[11px] text-gray-500 font-medium";
                        }
                    }

                    async function uploadApkFile() {
                        const fileInput = document.getElementById('apkFileInput');
                        const statusDiv = document.getElementById("apkUploadStatus");
                        if (!fileInput.files || fileInput.files.length === 0) {
                            alert("Please select an APK file to upload first.");
                            return;
                        }
                        const file = fileInput.files[0];
                        statusDiv.classList.remove("hidden");
                        statusDiv.innerText = "Uploading " + file.name + "... Please wait.";
                        statusDiv.className = "text-xs font-bold text-yellow-400 mt-3 block animate-pulse";
                        
                        try {
                            const res = await fetch(API_URL + '/api/upload-apk', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/vnd.android.package-archive'
                                },
                                body: file
                            });
                            const result = await res.json();
                            if (result.success) {
                                statusDiv.innerText = "✓ " + file.name + " uploaded and hosted successfully!";
                                statusDiv.className = "text-xs font-bold text-green-400 mt-3 block";
                                
                                // Auto-fill the APK File Download URL to point to this uploaded APK
                                document.getElementById("updateApkUrl").value = "/apk/sportzfy-latest.apk";
                            } else {
                                statusDiv.innerText = "✗ Upload failed: " + result.message;
                                statusDiv.className = "text-xs font-bold text-red-400 mt-3 block";
                            }
                        } catch (e) {
                            console.error(e);
                            statusDiv.innerText = "✗ Network Error during upload.";
                            statusDiv.className = "text-xs font-bold text-red-400 mt-3 block";
                        }
                        
                        setTimeout(() => statusDiv.classList.add("hidden"), 6000);
                    }

                    // Notice
                    async function fetchNotice() {
                        const res = await fetch(API_URL + '/api/notices');
                        const notices = await res.json();
                        if (notices.length > 0) {
                            document.getElementById("noticeInput").value = notices[0].content;
                            document.getElementById("noticeInput").dataset.id = notices[0].id;
                        }
                    }

                    async function saveNotice() {
                        const input = document.getElementById("noticeInput");
                        const content = input.value;
                        const id = input.dataset.id ? parseInt(input.dataset.id) : 0;
                        const res = await fetch(API_URL + '/api/notices', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ id, content, active: true })
                        });
                        const result = await res.json();
                        if (result.success) {
                            alert("Announcement banner updated and synced in app!");
                            fetchNotice();
                        }
                    }

                    // Banner Ad Control
                    async function fetchBannerAd() {
                        try {
                            const res = await fetch(API_URL + '/api/banner-ad');
                            const banner = await res.json();
                            document.getElementById("bannerMediaType").value = banner.mediaType || "image";
                            document.getElementById("bannerMediaUrl").value = banner.mediaUrl || "";
                            document.getElementById("bannerClickUrl").value = banner.clickUrl || "";
                            document.getElementById("bannerEnabled").value = banner.enabled ? "true" : "false";
                        } catch (e) {
                            console.error("Error fetching banner ad settings", e);
                        }
                    }

                    async function saveBannerAd() {
                        const mediaType = document.getElementById("bannerMediaType").value;
                        const mediaUrl = document.getElementById("bannerMediaUrl").value;
                        const clickUrl = document.getElementById("bannerClickUrl").value;
                        const enabled = document.getElementById("bannerEnabled").value === "true";

                        try {
                            const res = await fetch(API_URL + '/api/banner-ad', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ mediaType, mediaUrl, clickUrl, enabled })
                            });
                            const result = await res.json();
                            if (result.success) {
                                alert("Manual banner player settings updated successfully in both web and android apps!");
                                fetchBannerAd();
                            } else {
                                alert("Failed to update: " + result.error);
                            }
                        } catch (e) {
                            console.error("Error saving banner ad", e);
                            alert("Network error updating banner ad settings.");
                        }
                    }

                    // Matches
                    async function fetchMatches() {
                        try {
                            const res = await fetch(API_URL + '/api/matches');
                            allMatches = await res.json();
                            renderMatches();
                        } catch (e) {
                            console.error("Error fetching matches", e);
                        }
                    }

                    function renderMatches() {
                        const query = document.getElementById("adminSearchInput") ? document.getElementById("adminSearchInput").value.trim().toLowerCase() : "";
                        const container = document.getElementById("matchesList");
                        if (!container) return;
                        container.innerHTML = "";
                        
                        const filtered = allMatches.filter(m => {
                            return m.title.toLowerCase().includes(query) ||
                                   m.sport.toLowerCase().includes(query) ||
                                   m.team1Name.toLowerCase().includes(query) ||
                                   m.team2Name.toLowerCase().includes(query) ||
                                   m.streamUrl.toLowerCase().includes(query) ||
                                   (m.status && m.status.toLowerCase().includes(query));
                        });

                        if (filtered.length === 0) {
                            container.innerHTML = "<p class='text-gray-400 text-sm text-center py-6'>" + (allMatches.length === 0 ? "No live matches scheduled." : "No matching matches found.") + "</p>";
                            return;
                        }
                        filtered.forEach(m => {
                            const liveBadge = m.status === 'Live' ? 
                                '<span class="bg-red-500 text-white font-extrabold text-[10px] px-2 py-0.5 rounded-full flex items-center space-x-1 animate-pulse"><span class="w-1.5 h-1.5 rounded-full bg-white"></span><span>LIVE</span></span>' : 
                                '<span class="bg-gray-600 text-gray-300 font-bold text-[10px] px-2 py-0.5 rounded-full">UPCOMING</span>';

                            container.innerHTML += `
                                <div class="p-4 rounded-xl bg-[#0f1826] border border-[#1e2d4a] flex flex-col justify-between hover:border-cyan-400/50 transition">
                                    <div class="flex justify-between items-start mb-2">
                                        <span class="text-xs text-cyan-400 font-semibold tracking-wider uppercase">${'$'}{m.title}</span>
                                        <div class="flex items-center space-x-2">
                                            <span class="text-xs text-gray-400 font-medium">${'$'}{m.time}</span>
                                            ${'$'}{liveBadge}
                                        </div>
                                    </div>
                                    <div class="flex items-center justify-between py-2">
                                        <div class="flex items-center space-x-2">
                                            <div class="w-6 h-6 rounded-full bg-cyan-900/40 flex items-center justify-center text-[10px] font-bold uppercase text-cyan-300">${'$'}{m.team1Name.substring(0,3)}</div>
                                            <span class="text-sm font-bold text-gray-200">${'$'}{m.team1Name}</span>
                                        </div>
                                        <span class="text-xs font-bold text-gray-500">VS</span>
                                        <div class="flex items-center space-x-2 flex-row-reverse">
                                            <div class="w-6 h-6 rounded-full bg-cyan-900/40 flex items-center justify-center text-[10px] font-bold uppercase text-cyan-300 ml-2">${'$'}{m.team2Name.substring(0,3)}</div>
                                            <span class="text-sm font-bold text-gray-200">${'$'}{m.team2Name}</span>
                                        </div>
                                    </div>
                                    <div class="flex justify-between items-center mt-3 pt-2 border-t border-[#1e2d4a]/50">
                                        <span class="text-[10px] text-gray-500 truncate max-w-[200px]">${'$'}{m.streamUrl}</span>
                                        <div class="flex space-x-2">
                                            <button onclick='editMatch(${'$'}{JSON.stringify(m)})' class="bg-gray-700 hover:bg-gray-600 text-cyan-400 px-3 py-1 rounded-lg text-xs"><i class="fa-solid fa-pen"></i></button>
                                            <button onclick="deleteMatch(${'$'}{m.id})" class="bg-red-950/40 hover:bg-red-900 text-red-400 px-3 py-1 rounded-lg text-xs"><i class="fa-solid fa-trash-can"></i></button>
                                        </div>
                                    </div>
                                </div>
                            `;
                        });
                    }

                    function openMatchModal() {
                        document.getElementById("matchForm").reset();
                        document.getElementById("matchId").value = "";
                        document.getElementById("matchModalTitle").innerText = "Add Live Match";
                        document.getElementById("matchModal").classList.remove("hidden");
                    }

                    function closeMatchModal() {
                        document.getElementById("matchModal").classList.add("hidden");
                    }

                    function editMatch(m) {
                        document.getElementById("matchId").value = m.id;
                        document.getElementById("matchTitle").value = m.title;
                        document.getElementById("matchSport").value = m.sport;
                        document.getElementById("matchStatus").value = m.status;
                        document.getElementById("matchTeam1Name").value = m.team1Name;
                        document.getElementById("matchTeam1Logo").value = m.team1Logo;
                        document.getElementById("matchTeam2Name").value = m.team2Name;
                        document.getElementById("matchTeam2Logo").value = m.team2Logo;
                        document.getElementById("matchTime").value = m.time;
                        document.getElementById("matchStream").value = m.streamUrl;
                        document.getElementById("matchModalTitle").innerText = "Edit Live Match";
                        document.getElementById("matchModal").classList.remove("hidden");
                    }

                    async function submitMatch(e) {
                        e.preventDefault();
                        const payload = {
                            id: document.getElementById("matchId").value ? parseInt(document.getElementById("matchId").value) : 0,
                            title: document.getElementById("matchTitle").value,
                            sport: document.getElementById("matchSport").value,
                            status: document.getElementById("matchStatus").value,
                            team1Name: document.getElementById("matchTeam1Name").value,
                            team1Logo: document.getElementById("matchTeam1Logo").value,
                            team2Name: document.getElementById("matchTeam2Name").value,
                            team2Logo: document.getElementById("matchTeam2Logo").value,
                            time: document.getElementById("matchTime").value,
                            streamUrl: document.getElementById("matchStream").value
                        };

                        const res = await fetch(API_URL + '/api/matches', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(payload)
                        });
                        const result = await res.json();
                        if (result.success) {
                            closeMatchModal();
                            fetchMatches();
                        }
                    }

                    async function deleteMatch(id) {
                        if (!confirm("Are you sure you want to delete this match stream?")) return;
                        const res = await fetch(API_URL + `/api/matches?id=${'$'}{id}`, { method: 'DELETE' });
                        const result = await res.json();
                        if (result.success) {
                            fetchMatches();
                        }
                    }

                    // Channels
                    async function fetchChannels() {
                        try {
                            const res = await fetch(API_URL + '/api/channels');
                            allChannels = await res.json();
                            renderChannels();
                        } catch (e) {
                            console.error("Error fetching channels", e);
                        }
                    }

                    function renderChannels() {
                        const query = document.getElementById("adminSearchInput") ? document.getElementById("adminSearchInput").value.trim().toLowerCase() : "";
                        const container = document.getElementById("channelsList");
                        if (!container) return;
                        container.innerHTML = "";
                        
                        const filtered = allChannels.filter(c => {
                            return c.name.toLowerCase().includes(query) ||
                                   c.category.toLowerCase().includes(query) ||
                                   (c.streamUrl && c.streamUrl.toLowerCase().includes(query)) ||
                                   (c.streamUrl2 && c.streamUrl2.toLowerCase().includes(query)) ||
                                   (c.streamUrl3 && c.streamUrl3.toLowerCase().includes(query)) ||
                                   (c.streamUrl4 && c.streamUrl4.toLowerCase().includes(query)) ||
                                   (c.streamUrl5 && c.streamUrl5.toLowerCase().includes(query));
                        });

                        if (filtered.length === 0) {
                            container.innerHTML = "<p class='text-gray-400 text-sm text-center py-6'>" + (allChannels.length === 0 ? "No TV channels registered." : "No matching channels found.") + "</p>";
                            return;
                        }
                        filtered.forEach(c => {
                            container.innerHTML += `
                                <div class="p-4 rounded-xl bg-[#0f1826] border border-[#1e2d4a] flex justify-between items-center hover:border-cyan-400/50 transition">
                                    <div class="flex items-center space-x-3">
                                        <div class="w-10 h-10 rounded-xl bg-gradient-to-tr from-cyan-500 to-blue-600 flex items-center justify-center font-bold text-sm text-[#0b111e] uppercase">${'$'}{c.name.substring(0,2)}</div>
                                        <div>
                                            <h4 class="font-bold text-gray-200 text-sm">${'$'}{c.name}</h4>
                                            <span class="text-[10px] bg-cyan-500/10 text-cyan-400 font-semibold px-2 py-0.5 rounded-full">${'$'}{c.category}</span>
                                        </div>
                                    </div>
                                    <div class="flex space-x-2">
                                        <button onclick='editChannel(${'$'}{JSON.stringify(c)})' class="bg-gray-700 hover:bg-gray-600 text-cyan-400 px-3 py-1 rounded-lg text-xs"><i class="fa-solid fa-pen"></i></button>
                                        <button onclick="deleteChannel(${'$'}{c.id})" class="bg-red-950/40 hover:bg-red-900 text-red-400 px-3 py-1 rounded-lg text-xs"><i class="fa-solid fa-trash-can"></i></button>
                                    </div>
                                </div>
                            `;
                        });
                    }

                    function openChannelModal() {
                        document.getElementById("channelForm").reset();
                        document.getElementById("channelId").value = "";
                        document.getElementById("channelModalTitle").innerText = "Add TV Channel";
                        document.getElementById("channelModal").classList.remove("hidden");
                    }

                    function closeChannelModal() {
                        document.getElementById("channelModal").classList.add("hidden");
                    }

                    function editChannel(c) {
                        document.getElementById("channelId").value = c.id;
                        document.getElementById("channelName").value = c.name;
                        document.getElementById("channelCategory").value = c.category;
                        document.getElementById("channelLogoUrl").value = c.logoUrl;
                        document.getElementById("channelStreamUrl").value = c.streamUrl;
                        document.getElementById("channelStreamUrl2").value = c.streamUrl2 || "";
                        document.getElementById("channelStreamUrl3").value = c.streamUrl3 || "";
                        document.getElementById("channelStreamUrl4").value = c.streamUrl4 || "";
                        document.getElementById("channelStreamUrl5").value = c.streamUrl5 || "";
                        document.getElementById("channelModalTitle").innerText = "Edit TV Channel";
                        document.getElementById("channelModal").classList.remove("hidden");
                    }

                    async function submitChannel(e) {
                        e.preventDefault();
                        const payload = {
                            id: document.getElementById("channelId").value ? parseInt(document.getElementById("channelId").value) : 0,
                            name: document.getElementById("channelName").value,
                            category: document.getElementById("channelCategory").value,
                            logoUrl: document.getElementById("channelLogoUrl").value,
                            streamUrl: document.getElementById("channelStreamUrl").value,
                            streamUrl2: document.getElementById("channelStreamUrl2").value,
                            streamUrl3: document.getElementById("channelStreamUrl3").value,
                            streamUrl4: document.getElementById("channelStreamUrl4").value,
                            streamUrl5: document.getElementById("channelStreamUrl5").value
                        };

                        const res = await fetch(API_URL + '/api/channels', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(payload)
                        });
                        const result = await res.json();
                        if (result.success) {
                            closeChannelModal();
                            fetchChannels();
                        }
                    }

                    async function deleteChannel(id) {
                        if (!confirm("Are you sure you want to delete this channel?")) return;
                        const res = await fetch(API_URL + `/api/channels?id=${'$'}{id}`, { method: 'DELETE' });
                        const result = await res.json();
                        if (result.success) {
                            fetchChannels();
                        }
                    }

                    function switchImportTab(tab) {
                        const tabUrl = document.getElementById("tabImportUrl");
                        const tabPaste = document.getElementById("tabImportPaste");
                        const secUrl = document.getElementById("importUrlSection");
                        const secPaste = document.getElementById("importPasteSection");
                        const statusDiv = document.getElementById("importStatus");
                        
                        statusDiv.classList.add("hidden");
                        
                        if (tab === 'url') {
                            tabUrl.className = "text-xs font-bold text-cyan-400 border-b-2 border-cyan-400 pb-1 flex items-center space-x-1 uppercase tracking-wider";
                            tabPaste.className = "text-xs font-semibold text-gray-400 hover:text-white pb-1 flex items-center space-x-1 uppercase tracking-wider";
                            secUrl.classList.remove("hidden");
                            secPaste.classList.add("hidden");
                        } else {
                            tabUrl.className = "text-xs font-semibold text-gray-400 hover:text-white pb-1 flex items-center space-x-1 uppercase tracking-wider";
                            tabPaste.className = "text-xs font-bold text-cyan-400 border-b-2 border-cyan-400 pb-1 flex items-center space-x-1 uppercase tracking-wider";
                            secUrl.classList.add("hidden");
                            secPaste.classList.remove("hidden");
                        }
                    }

                    async function importM3uRawText() {
                        const rawText = document.getElementById("m3uRawTextInput").value.trim();
                        const defaultCategory = document.getElementById("m3uPasteCategoryInput").value.trim() || "IPTV";
                        if (!rawText) {
                            alert("Please paste your live stream playlist content or channel links.");
                            return;
                        }

                        const statusDiv = document.getElementById("importStatus");
                        statusDiv.innerText = "⏳ Processing and importing raw playlist text... Please wait.";
                        statusDiv.classList.remove("hidden", "text-red-400", "text-green-400");
                        statusDiv.classList.add("text-cyan-400");

                        const btn = document.getElementById("importPasteBtn");
                        btn.disabled = true;
                        btn.style.opacity = "0.5";

                        try {
                            const base64Text = btoa(unescape(encodeURIComponent(rawText)));
                            
                            const res = await fetch(API_URL + '/api/channels/import-m3u', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ rawTextBase64: base64Text, defaultCategory: defaultCategory })
                            });
                            const result = await res.json();
                            if (result.success) {
                                statusDiv.innerText = "✅ Bulk import successful! Channels are saved and loading.";
                                statusDiv.className = "text-[10px] font-bold text-green-400 mt-1";
                                document.getElementById("m3uRawTextInput").value = "";
                                setTimeout(() => {
                                    statusDiv.classList.add("hidden");
                                    fetchChannels();
                                }, 3000);
                            } else {
                                statusDiv.innerText = "❌ Error: " + (result.error || "Unknown error");
                                statusDiv.className = "text-[10px] font-bold text-red-400 mt-1";
                            }
                        } catch (e) {
                            console.error(e);
                            statusDiv.innerText = "❌ Connection failed. Check server/network and try again.";
                            statusDiv.className = "text-[10px] font-bold text-red-400 mt-1";
                        } finally {
                            btn.disabled = false;
                            btn.style.opacity = "1";
                        }
                    }

                    function selectPreset(url, category) {
                        document.getElementById("m3uUrlInput").value = url;
                        document.getElementById("m3uCategoryInput").value = category;
                    }

                    async function importM3uPlaylist() {
                        const url = document.getElementById("m3uUrlInput").value.trim();
                        const defaultCategory = document.getElementById("m3uCategoryInput").value.trim() || "IPTV";
                        if (!url) {
                            alert("Please enter or select an M3U playlist URL.");
                            return;
                        }

                        const statusDiv = document.getElementById("importStatus");
                        statusDiv.innerText = "⏳ Connecting and importing stream channels... Please wait.";
                        statusDiv.classList.remove("hidden", "text-red-400", "text-green-400");
                        statusDiv.classList.add("text-cyan-400");

                        const btn = document.getElementById("importBtn");
                        btn.disabled = true;
                        btn.style.opacity = "0.5";

                        try {
                            const res = await fetch(API_URL + '/api/channels/import-m3u', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ url: url, defaultCategory: defaultCategory })
                            });
                            const result = await res.json();
                            if (result.success) {
                                statusDiv.innerText = "✅ Bulk import initialized successfully! Channels are loading in the background.";
                                statusDiv.className = "text-[10px] font-bold text-green-400 mt-1";
                                setTimeout(() => {
                                    statusDiv.classList.add("hidden");
                                    fetchChannels();
                                }, 4000);
                            } else {
                                statusDiv.innerText = "❌ Error: " + (result.error || "Unknown error");
                                statusDiv.className = "text-[10px] font-bold text-red-400 mt-1";
                            }
                        } catch (e) {
                            console.error(e);
                            statusDiv.innerText = "❌ Connection failed. Check server/network and try again.";
                            statusDiv.className = "text-[10px] font-bold text-red-400 mt-1";
                        } finally {
                            btn.disabled = false;
                            btn.style.opacity = "1";
                        }
                    }

                    async function clearAllChannels() {
                        if (!confirm("Are you sure you want to clear ALL live channels in the database?")) return;
                        try {
                            const res = await fetch(API_URL + '/api/channels/clear');
                            const result = await res.json();
                            if (result.success) {
                                fetchChannels();
                            }
                        } catch (e) {
                            console.error(e);
                            alert("Failed to clear channels.");
                        }
                    }

                    // Highlights
                    async function fetchHighlights() {
                        try {
                            const res = await fetch(API_URL + '/api/highlights');
                            allHighlights = await res.json();
                            renderHighlights();
                        } catch (e) {
                            console.error("Error fetching highlights", e);
                        }
                    }

                    function renderHighlights() {
                        const query = document.getElementById("adminSearchInput") ? document.getElementById("adminSearchInput").value.trim().toLowerCase() : "";
                        const container = document.getElementById("highlightsList");
                        if (!container) return;
                        container.innerHTML = "";
                        
                        const filtered = allHighlights.filter(h => {
                            return h.title.toLowerCase().includes(query) ||
                                   h.team1Name.toLowerCase().includes(query) ||
                                   h.team2Name.toLowerCase().includes(query) ||
                                   h.streamUrl.toLowerCase().includes(query) ||
                                   (h.date && h.date.toLowerCase().includes(query));
                        });

                        if (filtered.length === 0) {
                            container.innerHTML = "<p class='text-gray-400 text-sm text-center py-6 col-span-full'>" + (allHighlights.length === 0 ? "No highlights available." : "No matching highlights found.") + "</p>";
                            return;
                        }
                        filtered.forEach(h => {
                            container.innerHTML += `
                                <div class="card p-4 rounded-2xl border border-[#1e2d4a] flex flex-col justify-between hover:border-cyan-400/40 transition">
                                    <div>
                                        <div class="flex justify-between items-center mb-2">
                                            <span class="text-[10px] text-cyan-400 font-bold tracking-wider uppercase">${'$'}{h.title}</span>
                                            <span class="text-[10px] text-gray-400 font-medium">${'$'}{h.date}</span>
                                        </div>
                                        <div class="flex justify-between items-center py-3 bg-[#0b111e]/50 px-3 rounded-xl mb-3">
                                            <div class="flex flex-col items-center">
                                                <span class="text-xs font-bold text-gray-300">${'$'}{h.team1Name}</span>
                                            </div>
                                            <span class="text-xs font-bold text-gray-500">VS</span>
                                            <div class="flex flex-col items-center">
                                                <span class="text-xs font-bold text-gray-300">${'$'}{h.team2Name}</span>
                                            </div>
                                        </div>
                                    </div>
                                    <div class="flex justify-between items-center pt-2 border-t border-[#1e2d4a]/50">
                                        <span class="text-[9px] text-gray-500 truncate max-w-[150px]">${'$'}{h.streamUrl}</span>
                                        <div class="flex space-x-1">
                                            <button onclick='editHighlight(${'$'}{JSON.stringify(h)})' class="bg-gray-700 hover:bg-gray-600 text-cyan-400 px-2 py-1 rounded-lg text-[10px]"><i class="fa-solid fa-pen"></i></button>
                                            <button onclick="deleteHighlight(${'$'}{h.id})" class="bg-red-950/40 hover:bg-red-900 text-red-400 px-2 py-1 rounded-lg text-[10px]"><i class="fa-solid fa-trash-can"></i></button>
                                        </div>
                                    </div>
                                </div>
                            `;
                        });
                    }

                    function openHighlightModal() {
                        document.getElementById("highlightForm").reset();
                        document.getElementById("highlightId").value = "";
                        document.getElementById("highlightModalTitle").innerText = "Add Highlight Clip";
                        document.getElementById("highlightModal").classList.remove("hidden");
                    }

                    function closeHighlightModal() {
                        document.getElementById("highlightModal").classList.add("hidden");
                    }

                    function editHighlight(h) {
                        document.getElementById("highlightId").value = h.id;
                        document.getElementById("highlightTitle").value = h.title;
                        document.getElementById("highlightTeam1Name").value = h.team1Name;
                        document.getElementById("highlightTeam1Logo").value = h.team1Logo;
                        document.getElementById("highlightTeam2Name").value = h.team2Name;
                        document.getElementById("highlightTeam2Logo").value = h.team2Logo;
                        document.getElementById("highlightDate").value = h.date;
                        document.getElementById("highlightStreamUrl").value = h.streamUrl;
                        document.getElementById("highlightModalTitle").innerText = "Edit Highlight Clip";
                        document.getElementById("highlightModal").classList.remove("hidden");
                    }

                    async function submitHighlight(e) {
                        e.preventDefault();
                        const payload = {
                            id: document.getElementById("highlightId").value ? parseInt(document.getElementById("highlightId").value) : 0,
                            title: document.getElementById("highlightTitle").value,
                            team1Name: document.getElementById("highlightTeam1Name").value,
                            team1Logo: document.getElementById("highlightTeam1Logo").value,
                            team2Name: document.getElementById("highlightTeam2Name").value,
                            team2Logo: document.getElementById("highlightTeam2Logo").value,
                            date: document.getElementById("highlightDate").value,
                            streamUrl: document.getElementById("highlightStreamUrl").value
                        };

                        const res = await fetch(API_URL + '/api/highlights', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(payload)
                        });
                        const result = await res.json();
                        if (result.success) {
                            closeHighlightModal();
                            fetchHighlights();
                        }
                    }

                    async function deleteHighlight(id) {
                        if (!confirm("Are you sure you want to delete this highlight?")) return;
                        const res = await fetch(API_URL + `/api/highlights?id=${'$'}{id}`, { method: 'DELETE' });
                        const result = await res.json();
                        if (result.success) {
                            fetchHighlights();
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
        sendResponse(socket, 200, html, "text/html")
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

    private fun handleWebVersion(socket: Socket) {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>Sportzfy - Live Streaming App</title>
                <script src="https://cdn.tailwindcss.com"></script>
                <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
                <script src="https://cdn.jsdelivr.net/npm/hls.js@1"></script>
                <style>
                    /* Modern Scrollbar styling */
                    ::-webkit-scrollbar {
                        width: 4px;
                        height: 4px;
                    }
                    ::-webkit-scrollbar-track {
                        background: #080c14;
                    }
                    ::-webkit-scrollbar-thumb {
                        background: #1e2d4a;
                        border-radius: 10px;
                    }
                    ::-webkit-scrollbar-thumb:hover {
                        background: #00e5ff;
                    }

                    body {
                        background-color: #05080f;
                        color: #f3f4f6;
                        font-family: system-ui, -apple-system, sans-serif;
                        margin: 0;
                        padding: 0;
                        overflow-x: hidden;
                    }

                    /* Mobile-app container effect on wider desktop screens */
                    .app-container {
                        max-width: 500px;
                        margin: 0 auto;
                        min-height: 100vh;
                        background-color: #080c14;
                        display: flex;
                        flex-direction: column;
                        box-shadow: 0 10px 30px rgba(0, 0, 0, 0.8);
                        border-left: 1px solid #131f37;
                        border-right: 1px solid #131f37;
                        position: relative;
                    }

                    /* Neon retro logo effect matching screenshot */
                    .neon-title {
                        color: #00E5FF;
                        text-shadow: 2px 2px 0px rgba(255, 0, 128, 0.7);
                        font-weight: 800;
                        letter-spacing: 0.05em;
                    }

                    /* Animated sliding drawer */
                    .drawer-transition {
                        transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1);
                    }

                    /* Custom sport category active states */
                    .category-circle {
                        width: 68px;
                        height: 68px;
                        border-radius: 50%;
                        background-color: #0f172a;
                        border: 2px solid #1e2d4a;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        transition: all 0.25s ease;
                        position: relative;
                        cursor: pointer;
                    }
                    .category-circle.active {
                        border-color: #00e5ff;
                        box-shadow: 0 0 12px rgba(0, 229, 255, 0.4);
                        transform: scale(1.05);
                    }

                    /* M3 active tab capsule highlight */
                    .m3-capsule {
                        position: relative;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        padding: 4px 0;
                    }
                    .m3-capsule-bg {
                        width: 64px;
                        height: 32px;
                        border-radius: 16px;
                        background-color: transparent;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        transition: all 0.2s ease;
                    }
                    .active-tab .m3-capsule-bg {
                        background-color: #00e5ff1b;
                        color: #00e5ff;
                    }
                    .active-tab span {
                        color: #00e5ff;
                        font-weight: 700;
                    }

                    /* Custom pulsing dot */
                    .live-pulse-dot {
                        width: 6px;
                        height: 6px;
                        border-radius: 50%;
                        background-color: #ff3b30;
                        animation: pulse-animation 1.5s infinite;
                    }
                    @keyframes pulse-animation {
                        0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(255, 59, 48, 0.7); }
                        70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(255, 59, 48, 0); }
                        100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(255, 59, 48, 0); }
                    }

                    /* Infinite horizontal scroll hide */
                    .no-scrollbar::-webkit-scrollbar {
                        display: none;
                    }
                    .no-scrollbar {
                        -ms-overflow-style: none;
                        scrollbar-width: none;
                    }

                    @keyframes marquee {
                        0% { transform: translate3d(100%, 0, 0); }
                        100% { transform: translate3d(-100%, 0, 0); }
                    }
                    .animate-marquee {
                        animation: marquee 22s linear infinite;
                        display: inline-block;
                    }
                </style>
            </head>
            <body class="font-sans antialiased">
                
                <!-- Backdrop -->
                <div id="drawerBackdrop" class="fixed inset-0 bg-black/60 z-40 hidden transition-opacity duration-300 opacity-0" onclick="toggleDrawer(false)"></div>

                <!-- Left Drawer Menu (Sidebar) -->
                <div id="drawerMenu" class="fixed top-0 left-0 h-full w-[280px] bg-[#080c14] border-r border-[#1a263d] z-50 transform -translate-x-full drawer-transition flex flex-col">
                    <div class="p-6 border-b border-[#1e2d4a] flex items-center justify-between">
                        <span class="text-3xl font-extrabold neon-title">Sportzfy</span>
                        <button onclick="toggleDrawer(false)" class="text-gray-400 hover:text-white p-1 rounded-full"><i class="fa-solid fa-arrow-left"></i></button>
                    </div>
                    <div class="flex-grow overflow-y-auto p-4 space-y-4">
                        <!-- Highlighted Download APK card inside Drawer -->
                        <a href="/apk/sportzfy-latest.apk" class="block bg-gradient-to-br from-cyan-900/30 to-blue-900/30 border border-cyan-500/40 p-4 rounded-2xl shadow-lg hover:border-cyan-400 transition group text-center">
                            <div class="flex items-center justify-center space-x-2 text-cyan-400 font-extrabold text-sm mb-1">
                                <i class="fa-brands fa-android text-lg animate-bounce"></i>
                                <span>Get the Official App</span>
                            </div>
                            <p class="text-[10px] text-gray-400 leading-normal">Download the Sportzfy Android APK to stream ad-free in high quality on your phone!</p>
                            <span class="inline-block mt-2 bg-cyan-500 text-black text-[10px] font-bold px-4 py-1.5 rounded-full uppercase tracking-widest group-hover:bg-cyan-400 transition">Download Now</span>
                        </a>

                        <div class="space-y-1">
                            <div onclick="showDrawerToast('Network Stream')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-circle-play text-cyan-400 text-sm"></i>
                                <span>Network Stream</span>
                            </div>
                            <div onclick="showDrawerToast('Remote Sync Panel')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-arrows-rotate text-cyan-400 text-sm"></i>
                                <span>Remote Sync Panel</span>
                            </div>
                            <div onclick="showDrawerToast('Playlists')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-list text-cyan-400 text-sm"></i>
                                <span>Playlists</span>
                            </div>
                            <div onclick="showDrawerToast('Floating Player')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-window-restore text-cyan-400 text-sm"></i>
                                <span>Floating Player</span>
                            </div>
                            <div onclick="showDrawerToast('Video Quality Setting')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-gear text-cyan-400 text-sm"></i>
                                <span>Video Quality Setting</span>
                            </div>
                            <div onclick="showDrawerToast('Select Streaming Player')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-circle-chevron-right text-cyan-400 text-sm"></i>
                                <span>Select Streaming Player</span>
                            </div>
                            <div onclick="showDrawerToast('Crash Log')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-wrench text-cyan-400 text-sm"></i>
                                <span>Crash Log Dialog</span>
                            </div>
                            <div onclick="showDrawerToast('Notice Board')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-bell text-cyan-400 text-sm"></i>
                                <span>Notice</span>
                            </div>
                            <div onclick="showDrawerToast('Join Us')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-user-group text-cyan-400 text-sm"></i>
                                <span>Join Us</span>
                            </div>
                            <div onclick="showDrawerToast('Copyright Information')" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-info-circle text-cyan-400 text-sm"></i>
                                <span>Copyright</span>
                            </div>
                            <a href="/apk/sportzfy-latest.apk" class="flex items-center space-x-3 px-3 py-3 rounded-xl hover:bg-[#0f172a] text-gray-300 hover:text-white transition cursor-pointer text-xs font-semibold">
                                <i class="fa-solid fa-share-nodes text-cyan-400 text-sm"></i>
                                <span>Share Our App</span>
                            </a>
                        </div>
                    </div>
                    <div class="p-4 border-t border-[#1e2d4a] text-center text-[10px] text-gray-500 font-semibold">
                        Sportzfy v3.5 - Web Player Edition
                    </div>
                </div>

                <!-- App Container -->
                <div class="app-container">
                    <!-- App Header -->
                    <header class="bg-[#0c1222] border-b border-[#131f37] h-[56px] px-4 flex items-center justify-between sticky top-0 z-30">
                        <div class="flex items-center space-x-4">
                            <button onclick="toggleDrawer(true)" class="text-gray-300 hover:text-white text-lg focus:outline-none"><i class="fa-solid fa-bars"></i></button>
                            <span class="text-2xl neon-title">Sportzfy</span>
                        </div>
                        <div class="flex items-center space-x-4 text-gray-300">
                            <button onclick="toggleSearch()" class="hover:text-[#00E5FF] text-lg focus:outline-none"><i class="fa-solid fa-magnifying-glass"></i></button>
                            <button onclick="showDrawerToast('Favorites')" class="hover:text-[#00E5FF] text-lg focus:outline-none"><i class="fa-solid fa-star"></i></button>
                            <a href="/apk/sportzfy-latest.apk" class="bg-gradient-to-r from-cyan-500 to-blue-500 text-black font-extrabold text-[10px] px-3 py-1.5 rounded-full flex items-center space-x-1 hover:brightness-110 transition shadow-md">
                                <i class="fa-brands fa-android text-xs"></i>
                                <span>GET APP</span>
                            </a>
                        </div>
                    </header>

                    <!-- Search Field (Collapsible) -->
                    <div id="searchBar" class="hidden bg-[#0c1222] border-b border-[#131f37] p-3 transition-all duration-300">
                        <div class="relative">
                            <input id="searchInput" type="text" oninput="handleSearch()" class="w-full bg-[#05080f] text-white border border-[#1e2d4a] rounded-xl pl-10 pr-4 py-2 text-xs focus:outline-none focus:border-cyan-400" placeholder="Search matches, channels or highlights...">
                            <span class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-gray-500"><i class="fa-solid fa-magnifying-glass text-xs"></i></span>
                            <button onclick="clearSearch()" class="absolute inset-y-0 right-0 pr-3 flex items-center text-gray-500 hover:text-white"><i class="fa-solid fa-circle-xmark text-xs"></i></button>
                        </div>
                    </div>

                    <!-- Ad-Free Ticker Text Banner -->
                    <div class="bg-[#05080f] py-1 px-4 border-b border-[#131f37] overflow-hidden relative h-[24px] flex items-center">
                        <div class="whitespace-nowrap inline-block animate-marquee text-[10px] text-[#00E5FF] italic font-semibold">
                            (Ads Free). Keep supporting us for the best sports streaming experience! Install the Android app for full screen support.
                        </div>
                    </div>

                    <!-- Interactive Video Player Area -->
                    <div id="playerSection" class="hidden bg-[#0c1222] border-b border-[#131f37] p-3 transition-all">
                        <div class="flex justify-between items-center mb-2">
                            <div class="flex items-center space-x-1.5">
                                <span class="live-pulse-dot"></span>
                                <h2 id="nowPlayingTitle" class="text-xs font-bold text-cyan-400 truncate max-w-[320px]">Streaming Live</h2>
                            </div>
                            <button onclick="closePlayer()" class="text-gray-400 hover:text-white bg-gray-800 p-1 rounded-full"><i class="fa-solid fa-xmark text-xs"></i></button>
                        </div>
                        <div class="bg-black rounded-xl overflow-hidden aspect-video relative flex items-center justify-center border border-[#1e2d4a]">
                            <video id="videoElement" controls class="w-full h-full object-contain hidden"></video>
                            <iframe id="iframeElement" class="w-full h-full border-0 hidden" allowfullscreen allow="autoplay; encrypted-media"></iframe>
                            <div id="playerLoading" class="absolute inset-0 bg-black/80 flex items-center justify-center hidden">
                                <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-cyan-400"></div>
                            </div>
                        </div>
                        <p class="text-[9px] text-gray-500 mt-1 text-center font-medium">💡 Stream will play instantly. Supports HLS (.m3u8), YouTube, and Twitch embeds.</p>
                        
                        <!-- Manual Clickable Banner Ad / Player -->
                        <div id="webBannerAdContainer" class="hidden mt-3 border border-dashed border-[#1e2d4a] rounded-xl overflow-hidden bg-black/40 p-1 relative group">
                            <a id="webBannerAdLink" href="#" target="_blank" class="block w-full h-full">
                                <div class="relative w-full flex justify-center items-center overflow-hidden rounded-lg min-h-[45px]">
                                    <!-- Render Image/GIF -->
                                    <img id="webBannerImage" class="hidden w-full h-auto max-h-[160px] object-contain transition-transform duration-300 group-hover:scale-[1.02]" src="" alt="Advertisement">
                                    <!-- Render Video -->
                                    <video id="webBannerVideo" class="hidden w-full h-auto max-h-[160px] object-contain" loop muted autoplay playsinline></video>
                                    
                                    <!-- Click Indicator Overlay -->
                                    <div class="absolute top-2 right-2 bg-black/70 text-[9px] text-cyan-400 font-bold px-2 py-0.5 rounded-md uppercase tracking-wider flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                                        <span>Visit Link</span>
                                        <i class="fa-solid fa-arrow-up-right-from-square"></i>
                                    </div>
                                </div>
                            </a>
                        </div>
                    </div>

                    <!-- App Content Scroller -->
                    <div class="flex-grow overflow-y-auto pb-[72px]" id="appContent">
                        
                        <!-- TAB 1: HOME -->
                        <div id="tabContent-home" class="p-4 space-y-4">
                            <!-- Categories Horizontal Bar -->
                            <div class="flex space-x-3 overflow-x-auto no-scrollbar py-2">
                                <!-- All Circle -->
                                <div class="flex flex-col items-center space-y-1.5 shrink-0" onclick="filterSport('All')">
                                    <div id="circle-All" class="category-circle active">
                                        <span class="text-xs font-extrabold text-white">ALL</span>
                                        <span id="badge-All" class="absolute -top-1 -right-1 bg-red-500 text-[9px] text-white font-extrabold px-1.5 py-0.5 rounded-full border border-[#080c14] hidden">0</span>
                                    </div>
                                    <span id="label-All" class="text-[10px] font-bold text-cyan-400">All</span>
                                </div>
                                
                                <!-- Cricket Circle -->
                                <div class="flex flex-col items-center space-y-1.5 shrink-0" onclick="filterSport('Cricket')">
                                    <div id="circle-Cricket" class="category-circle">
                                        <span class="text-2xl">🏏</span>
                                        <span id="badge-Cricket" class="absolute -top-1 -right-1 bg-red-500 text-[9px] text-white font-extrabold px-1.5 py-0.5 rounded-full border border-[#080c14] hidden">0</span>
                                    </div>
                                    <span id="label-Cricket" class="text-[10px] font-semibold text-gray-400">Cricket</span>
                                </div>

                                <!-- Football Circle -->
                                <div class="flex flex-col items-center space-y-1.5 shrink-0" onclick="filterSport('Football')">
                                    <div id="circle-Football" class="category-circle">
                                        <span class="text-2xl">⚽</span>
                                        <span id="badge-Football" class="absolute -top-1 -right-1 bg-red-500 text-[9px] text-white font-extrabold px-1.5 py-0.5 rounded-full border border-[#080c14] hidden">0</span>
                                    </div>
                                    <span id="label-Football" class="text-[10px] font-semibold text-gray-400">Football</span>
                                </div>

                                <!-- MotorSports Circle -->
                                <div class="flex flex-col items-center space-y-1.5 shrink-0" onclick="filterSport('MotorSports')">
                                    <div id="circle-MotorSports" class="category-circle">
                                        <span class="text-2xl">🏎️</span>
                                        <span id="badge-MotorSports" class="absolute -top-1 -right-1 bg-red-500 text-[9px] text-white font-extrabold px-1.5 py-0.5 rounded-full border border-[#080c14] hidden">0</span>
                                    </div>
                                    <span id="label-MotorSports" class="text-[10px] font-semibold text-gray-400">MotorSport</span>
                                </div>

                                <!-- Wrestling Circle -->
                                <div class="flex flex-col items-center space-y-1.5 shrink-0" onclick="filterSport('Wrestling')">
                                    <div id="circle-Wrestling" class="category-circle">
                                        <span class="text-2xl">🤼</span>
                                        <span id="badge-Wrestling" class="absolute -top-1 -right-1 bg-red-500 text-[9px] text-white font-extrabold px-1.5 py-0.5 rounded-full border border-[#080c14] hidden">0</span>
                                    </div>
                                    <span id="label-Wrestling" class="text-[10px] font-semibold text-gray-400">Wrestling</span>
                                </div>
                            </div>

                            <!-- Status Filter Pills -->
                            <div class="flex space-x-2 py-1 overflow-x-auto no-scrollbar">
                                <button onclick="filterStatus('Recent')" id="pill-Recent" class="px-4 py-1.5 bg-[#0f172a] hover:bg-[#1e2d4a] text-xs font-semibold rounded-full border border-transparent text-gray-400 transition shrink-0">Recent</button>
                                <button onclick="filterStatus('Live')" id="pill-Live" class="px-4 py-1.5 bg-[#0f172a] hover:bg-[#1e2d4a] text-xs font-semibold rounded-full border border-transparent text-gray-400 transition shrink-0">Live</button>
                                <button onclick="filterStatus('Upcoming')" id="pill-Upcoming" class="px-4 py-1.5 bg-[#0f172a] hover:bg-[#1e2d4a] text-xs font-semibold rounded-full border border-transparent text-gray-400 transition shrink-0">Upcoming</button>
                                <button onclick="filterStatus('All')" id="pill-All" class="px-4 py-1.5 bg-[#00E5FF]/10 text-[#00E5FF] border border-[#00E5FF]/40 text-xs font-extrabold rounded-full transition shrink-0">All Matches</button>
                            </div>

                            <!-- Live Matches List -->
                            <div id="matchesContainer" class="space-y-4">
                                <!-- Dynamic matches cards -->
                            </div>
                        </div>

                        <!-- TAB 2: CATEGORIES (LIVE TV) -->
                        <div id="tabContent-categories" class="p-4 space-y-5 hidden">
                            <h2 class="text-lg font-bold text-white tracking-wide">Live TV & Categories</h2>
                            <div id="categoriesContainer" class="space-y-6">
                                <!-- Dynamic categories groups and cards -->
                            </div>
                        </div>

                        <!-- TAB 3: HIGHLIGHTS -->
                        <div id="tabContent-highlights" class="p-4 space-y-4 hidden">
                            <h2 class="text-lg font-bold text-white tracking-wide">Match Highlights Feed</h2>
                            <div id="highlightsContainer" class="space-y-4">
                                <!-- Dynamic highlights cards -->
                            </div>
                        </div>

                    </div>

                    <!-- M3 Bottom Navigation Bar -->
                    <nav class="fixed bottom-0 left-1/2 -translate-x-1/2 w-full max-w-[500px] bg-[#0c1222]/95 backdrop-blur-md border-t border-l border-r border-[#131f37] h-[64px] flex justify-around items-center z-30 px-2">
                        <button onclick="switchTab('home')" id="btnTab-home" class="flex flex-col items-center justify-center text-gray-400 hover:text-white transition group flex-grow max-w-[120px] active-tab">
                            <div class="m3-capsule">
                                <div class="m3-capsule-bg">
                                    <i class="fa-solid fa-house text-lg"></i>
                                </div>
                                <span class="text-[10px] mt-1 font-semibold tracking-wide">Home</span>
                            </div>
                        </button>

                        <button onclick="switchTab('categories')" id="btnTab-categories" class="flex flex-col items-center justify-center text-gray-400 hover:text-white transition group flex-grow max-w-[120px]">
                            <div class="m3-capsule">
                                <div class="m3-capsule-bg">
                                    <i class="fa-solid fa-list-ul text-lg"></i>
                                </div>
                                <span class="text-[10px] mt-1 font-semibold tracking-wide">Categories</span>
                            </div>
                        </button>

                        <button onclick="switchTab('highlights')" id="btnTab-highlights" class="flex flex-col items-center justify-center text-gray-400 hover:text-white transition group flex-grow max-w-[120px]">
                            <div class="m3-capsule">
                                <div class="m3-capsule-bg">
                                    <i class="fa-solid fa-play text-lg"></i>
                                </div>
                                <span class="text-[10px] mt-1 font-semibold tracking-wide">Highlights</span>
                            </div>
                        </button>
                    </nav>

                    <!-- Slide Toast Alert -->
                    <div id="toastAlert" class="fixed bottom-20 left-1/2 transform -translate-x-1/2 bg-[#00E5FF] text-[#080c14] text-xs font-bold px-4 py-2.5 rounded-full shadow-2xl z-50 pointer-events-none transition duration-300 opacity-0 scale-90">
                        Action triggered successfully!
                    </div>
                </div>

                <script>
                    let activeSport = 'All';
                    let activeStatus = 'All';
                    let activeTab = 'home';
                    let searchQuery = '';
                    
                    let allMatches = [];
                    let allChannels = [];
                    let allHighlights = [];
                    
                    let hlsPlayer = null;

                    document.addEventListener('DOMContentLoaded', () => {
                        fetchMatches();
                        fetchChannels();
                        fetchHighlights();
                        fetchBannerAd();
                    });

                    async function fetchBannerAd() {
                        try {
                            const res = await fetch('/api/banner-ad');
                            const banner = await res.json();
                            const container = document.getElementById('webBannerAdContainer');
                            const img = document.getElementById('webBannerImage');
                            const video = document.getElementById('webBannerVideo');
                            const link = document.getElementById('webBannerAdLink');

                            if (banner && banner.enabled && banner.mediaUrl) {
                                link.href = banner.clickUrl || '#';
                                img.classList.add('hidden');
                                video.classList.add('hidden');

                                if (banner.mediaType === 'video') {
                                    video.src = banner.mediaUrl;
                                    video.classList.remove('hidden');
                                } else {
                                    img.src = banner.mediaUrl;
                                    img.classList.remove('hidden');
                                }
                                container.classList.remove('hidden');
                            } else {
                                container.classList.add('hidden');
                            }
                        } catch (e) {
                            console.error("Error fetching banner ad", e);
                        }
                    }

                    // Toggle left drawer menu
                    function toggleDrawer(open) {
                        const drawer = document.getElementById('drawerMenu');
                        const backdrop = document.getElementById('drawerBackdrop');
                        if (open) {
                            backdrop.classList.remove('hidden');
                            setTimeout(() => {
                                backdrop.classList.add('opacity-100');
                                drawer.classList.remove('-translate-x-full');
                            }, 10);
                        } else {
                            drawer.classList.add('-translate-x-full');
                            backdrop.classList.remove('opacity-100');
                            setTimeout(() => {
                                backdrop.classList.add('hidden');
                            }, 300);
                        }
                    }

                    // Show toast notifications inside drawer / buttons
                    function showDrawerToast(message) {
                        const toast = document.getElementById('toastAlert');
                        toast.innerText = message + " is built in the Android App! Install the APK above.";
                        toast.classList.remove('opacity-0', 'scale-90');
                        toast.classList.add('opacity-100', 'scale-100');
                        setTimeout(() => {
                            toast.classList.add('opacity-0', 'scale-90');
                            toast.classList.remove('opacity-100', 'scale-100');
                        }, 3000);
                        toggleDrawer(false);
                    }

                    // Search Bar toggles
                    function toggleSearch() {
                        const bar = document.getElementById('searchBar');
                        bar.classList.toggle('hidden');
                        if (!bar.classList.contains('hidden')) {
                            document.getElementById('searchInput').focus();
                        }
                    }

                    function clearSearch() {
                        document.getElementById('searchInput').value = '';
                        handleSearch();
                    }

                    function handleSearch() {
                        searchQuery = document.getElementById('searchInput').value.trim().toLowerCase();
                        renderMatches();
                        renderCategories();
                        renderHighlights();
                    }

                    // Tab Switching
                    function switchTab(tabId) {
                        activeTab = tabId;
                        ['home', 'categories', 'highlights'].forEach(id => {
                            const content = document.getElementById('tabContent-' + id);
                            const btn = document.getElementById('btnTab-' + id);
                            if (id === tabId) {
                                content.classList.remove('hidden');
                                btn.classList.add('active-tab');
                            } else {
                                content.classList.add('hidden');
                                btn.classList.remove('active-tab');
                            }
                        });
                        // Scroll to top
                        document.getElementById('appContent').scrollTop = 0;
                    }

                    // API Fetching
                    async function fetchMatches() {
                        try {
                            const res = await fetch('/api/matches');
                            allMatches = await res.json();
                            updateCategoryBadges();
                            renderMatches();
                        } catch (e) { console.error("Error matches", e); }
                    }

                    async function fetchChannels() {
                        try {
                            const res = await fetch('/api/channels');
                            allChannels = await res.json();
                            renderCategories();
                        } catch (e) { console.error("Error channels", e); }
                    }

                    async function fetchHighlights() {
                        try {
                            const res = await fetch('/api/highlights');
                            allHighlights = await res.json();
                            renderHighlights();
                        } catch (e) { console.error("Error highlights", e); }
                    }

                    // Update counts on horizontal scroll circles
                    function updateCategoryBadges() {
                        const categories = ['All', 'Cricket', 'Football', 'MotorSports', 'Wrestling'];
                        categories.forEach(sport => {
                            const badge = document.getElementById('badge-' + sport);
                            let count = 0;
                            if (sport === 'All') {
                                count = allMatches.length;
                            } else {
                                count = allMatches.filter(m => m.sport.toLowerCase() === sport.toLowerCase()).length;
                            }
                            if (badge) {
                                if (count > 0) {
                                    badge.innerText = count;
                                    badge.classList.remove('hidden');
                                } else {
                                    badge.classList.add('hidden');
                                }
                            }
                        });
                    }

                    // Sport Filtering
                    function filterSport(sport) {
                        activeSport = sport;
                        const sportsList = ['All', 'Cricket', 'Football', 'MotorSports', 'Wrestling'];
                        sportsList.forEach(s => {
                            const circle = document.getElementById('circle-' + s);
                            const label = document.getElementById('label-' + s);
                            if (s === sport) {
                                circle.classList.add('active');
                                label.classList.add('text-cyan-400');
                                label.classList.remove('text-gray-400');
                                label.style.fontWeight = '700';
                            } else {
                                circle.classList.remove('active');
                                label.classList.remove('text-cyan-400');
                                label.classList.add('text-gray-400');
                                label.style.fontWeight = '600';
                            }
                        });
                        renderMatches();
                    }

                    // Status Filtering
                    function filterStatus(status) {
                        activeStatus = status;
                        const statuses = ['Recent', 'Live', 'Upcoming', 'All'];
                        statuses.forEach(s => {
                            const pill = document.getElementById('pill-' + s);
                            if (s === status) {
                                pill.className = "px-4 py-1.5 bg-[#00E5FF]/10 text-[#00E5FF] border border-[#00E5FF]/40 text-xs font-extrabold rounded-full transition shrink-0";
                            } else {
                                pill.className = "px-4 py-1.5 bg-[#0f172a] hover:bg-[#1e2d4a] text-xs font-semibold rounded-full border border-transparent text-gray-400 transition shrink-0";
                            }
                        });
                        renderMatches();
                    }

                    // Render functions
                    function renderMatches() {
                        const container = document.getElementById('matchesContainer');
                        container.innerHTML = '';

                        // 1. Filter by active sport
                        let filtered = activeSport === 'All' 
                            ? allMatches 
                            : allMatches.filter(m => m.sport.toLowerCase() === activeSport.toLowerCase());

                        // 2. Filter by status
                        if (activeStatus !== 'All') {
                            filtered = filtered.filter(m => {
                                const status = m.status.toLowerCase();
                                if (activeStatus === 'Live') return status === 'live';
                                if (activeStatus === 'Recent') return status === 'completed' || status === 'ended';
                                if (activeStatus === 'Upcoming') return status !== 'live' && status !== 'completed' && status !== 'ended';
                                return true;
                            });
                        }

                        // 3. Filter by search query
                        if (searchQuery) {
                            filtered = filtered.filter(m => 
                                m.title.toLowerCase().includes(searchQuery) ||
                                m.sport.toLowerCase().includes(searchQuery) ||
                                m.team1Name.toLowerCase().includes(searchQuery) ||
                                m.team2Name.toLowerCase().includes(searchQuery)
                            );
                        }

                        if (filtered.length === 0) {
                            container.innerHTML = '<div class="py-12 text-center text-gray-500">' +
                                '<i class="fa-solid fa-circle-info text-2xl mb-2 text-cyan-500/50"></i>' +
                                '<p class="text-xs font-semibold">No matches match your criteria.</p>' +
                            '</div>';
                            return;
                        }

                        filtered.forEach(match => {
                            const isLive = match.status.toLowerCase() === 'live';
                            
                            const timeHtml = isLive 
                                ? '<span class="text-[#00E5FF] text-[10px] font-bold">03:54:23</span>' 
                                : '<span class="text-[#00E5FF] text-[10px] font-semibold">' + match.time + '</span>';

                            const badgeHtml = isLive 
                                ? '<span class="bg-red-500 text-white text-[9px] font-black px-2 py-0.5 rounded-md flex items-center space-x-1 animate-pulse"><span class="w-1.5 h-1.5 rounded-full bg-white"></span><span>LIVE</span></span>' 
                                : '<span class="bg-gray-700 text-gray-300 text-[9px] font-bold px-2 py-0.5 rounded-md">UPCOMING</span>';

                            const card = document.createElement('div');
                            card.className = "bg-[#0c1424] border border-[#1a263d] rounded-2xl p-4 flex flex-col justify-between cursor-pointer hover:border-[#00e5ff]/50 transition duration-300 shadow-md";
                            card.onclick = () => playStream(match.streamUrl, match.team1Name + " vs " + match.team2Name + " - " + match.title);
                            
                            // Construct teams badges with initials
                            const t1Initial = match.team1Name.substring(0, 3).toUpperCase();
                            const t2Initial = match.team2Name.substring(0, 3).toUpperCase();

                            card.innerHTML = '<div class="flex justify-between items-center mb-3">' +
                                '<span class="text-[10px] font-bold text-gray-400 uppercase tracking-wider">' + match.sport + ' || ' + match.title + '</span>' +
                                '<div class="flex items-center space-x-2">' +
                                    timeHtml +
                                    badgeHtml +
                                '</div>' +
                            '</div>' +
                            '<div class="flex items-center justify-between py-2 px-1">' +
                                '<div class="flex items-center space-x-3 w-[45%]">' +
                                    '<div class="w-10 h-10 rounded-full bg-gradient-to-tr from-cyan-600 to-blue-600 flex items-center justify-center text-white text-xs font-black border border-cyan-400/20 shrink-0 shadow-inner">' +
                                        '<span class="text-[10px]">' + t1Initial + '</span>' +
                                    '</div>' +
                                    '<span class="text-xs font-bold text-white truncate">' + match.team1Name + '</span>' +
                                '</div>' +
                                '<span class="text-[11px] font-extrabold text-gray-500 tracking-wider">VS</span>' +
                                '<div class="flex items-center justify-end space-x-3 w-[45%] text-right">' +
                                    '<span class="text-xs font-bold text-white truncate">' + match.team2Name + '</span>' +
                                    '<div class="w-10 h-10 rounded-full bg-gradient-to-tr from-rose-600 to-red-600 flex items-center justify-center text-white text-xs font-black border border-red-400/20 shrink-0 shadow-inner">' +
                                        '<span class="text-[10px]">' + t2Initial + '</span>' +
                                    '</div>' +
                                '</div>' +
                            '</div>';
                            container.appendChild(card);
                        });
                    }

                    // Render Live TV categories
                    function renderCategories() {
                        const container = document.getElementById('categoriesContainer');
                        container.innerHTML = '';

                        // Group channels by category
                        const categories = {};
                        allChannels.forEach(ch => {
                            const cat = ch.category || 'GENERAL';
                            if (!categories[cat]) categories[cat] = [];
                            categories[cat].push(ch);
                        });

                        const categoryNames = Object.keys(categories);
                        if (categoryNames.length === 0) {
                            container.innerHTML = '<div class="py-12 text-center text-gray-500">' +
                                '<i class="fa-solid fa-tv text-2xl mb-2 text-cyan-500/50"></i>' +
                                '<p class="text-xs font-semibold">No categories registered yet.</p>' +
                            '</div>';
                            return;
                        }

                        categoryNames.forEach(catName => {
                            let channelsList = categories[catName];
                            if (searchQuery) {
                                channelsList = channelsList.filter(c => c.name.toLowerCase().includes(searchQuery));
                            }
                            if (channelsList.length === 0) return;

                            const section = document.createElement('div');
                            section.className = "space-y-3";
                            
                            section.innerHTML = '<h3 class="text-xs font-black text-[#00E5FF] uppercase tracking-widest">' + catName + '</h3>';
                            
                            const grid = document.createElement('div');
                            grid.className = "grid grid-cols-3 gap-3";

                            channelsList.forEach(ch => {
                                const card = document.createElement('div');
                                card.className = "bg-[#0c1424] border border-[#1a263d] rounded-2xl p-3 flex flex-col items-center justify-between text-center cursor-pointer hover:border-[#00e5ff]/50 transition duration-300 aspect-square shadow-sm";
                                card.onclick = () => playStream(ch.streamUrl, ch.name);

                                // First two letters of name
                                const prefix = ch.name.substring(0, 2).toUpperCase();

                                card.innerHTML = '<div class="w-10 h-10 rounded-xl bg-[#080c14] border border-[#1a263d] flex items-center justify-center shrink-0 shadow-inner">' +
                                    '<span class="text-cyan-400 font-extrabold text-xs tracking-wider">' + prefix + '</span>' +
                                '</div>' +
                                '<div class="text-[10px] font-bold text-white line-clamp-2 leading-tight px-0.5">' + ch.name + '</div>' +
                                '<div class="flex items-center space-x-1">' +
                                    '<span class="live-pulse-dot"></span>' +
                                    '<span class="text-[8px] font-black text-red-500 uppercase tracking-widest">LIVE</span>' +
                                '</div>';
                                grid.appendChild(card);
                            });

                            section.appendChild(grid);
                            container.appendChild(section);
                        });
                    }

                    // Render Highlights Screen
                    function renderHighlights() {
                        const container = document.getElementById('highlightsContainer');
                        container.innerHTML = '';

                        let filtered = allHighlights;
                        if (searchQuery) {
                            filtered = allHighlights.filter(hl => 
                                hl.title.toLowerCase().includes(searchQuery) ||
                                hl.team1Name.toLowerCase().includes(searchQuery) ||
                                hl.team2Name.toLowerCase().includes(searchQuery)
                            );
                        }

                        if (filtered.length === 0) {
                            container.innerHTML = '<div class="py-12 text-center text-gray-500">' +
                                '<i class="fa-solid fa-clapperboard text-2xl mb-2 text-cyan-500/50"></i>' +
                                '<p class="text-xs font-semibold">No highlights clip match your search.</p>' +
                            '</div>';
                            return;
                        }

                        filtered.forEach(hl => {
                            const card = document.createElement('div');
                            card.className = "bg-[#0c1424] border border-[#1a263d] rounded-2xl p-4 flex flex-col justify-between cursor-pointer hover:border-[#00e5ff]/50 transition duration-300 shadow-md";
                            card.onclick = () => playStream(hl.streamUrl, hl.team1Name + " vs " + hl.team2Name + " Highlights");

                            card.innerHTML = '<div class="flex justify-between items-center mb-2">' +
                                '<div class="flex items-center space-x-1.5">' +
                                    '<i class="fa-solid fa-star text-cyan-400 text-xs"></i>' +
                                    '<span class="text-[10px] font-bold text-gray-400 uppercase tracking-wider">' + (hl.title || 'Football | Match Highlight') + '</span>' +
                                '</div>' +
                                '<span class="text-[9px] font-semibold text-gray-500">' + (hl.date || 'Today') + '</span>' +
                            '</div>' +
                            '<div class="flex justify-between items-center py-1">' +
                                '<span class="text-xs font-extrabold text-white truncate max-w-[150px]">' + hl.team1Name + '</span>' +
                                '<span class="text-[10px] font-black text-rose-500">VS</span>' +
                                '<span class="text-xs font-extrabold text-white truncate max-w-[150px] text-right">' + hl.team2Name + '</span>' +
                            '</div>';
                            container.appendChild(card);
                        });
                    }

                    // Play stream function
                    function playStream(url, title) {
                        if (!url) {
                            alert("Invalid stream URL!");
                            return;
                        }

                        document.getElementById('nowPlayingTitle').innerText = title;
                        document.getElementById('playerSection').classList.remove('hidden');
                        
                        // Scroll playing stream area into view smoothly
                        document.getElementById('playerSection').scrollIntoView({ behavior: 'smooth', block: 'start' });

                        const video = document.getElementById('videoElement');
                        const iframe = document.getElementById('iframeElement');
                        const loading = document.getElementById('playerLoading');

                        loading.classList.remove('hidden');

                        if (hlsPlayer) {
                            hlsPlayer.destroy();
                            hlsPlayer = null;
                        }

                        try { video.pause(); } catch(e){}
                        video.src = "";
                        video.classList.add('hidden');
                        
                        iframe.src = "";
                        iframe.classList.add('hidden');

                        const youtubeRegex = /(?:youtube\.com\/(?:[^\/]+\/.+\/|(?:v|e(?:mbed)?)\/|.*[?&]v=)|youtu\.be\/|youtube\.com\/live\/)([a-zA-Z0-9_-]{11})/;
                        const twitchRegex = /(?:twitch\.tv\/)([a-zA-Z0-9_-]+)/;

                        const ytMatch = url.match(youtubeRegex);
                        const twitchMatch = url.match(twitchRegex);

                        if (ytMatch) {
                            const videoId = ytMatch[1];
                            iframe.src = "https://www.youtube.com/embed/" + videoId + "?autoplay=1&rel=0";
                            iframe.classList.remove('hidden');
                            loading.classList.add('hidden');
                        } else if (twitchMatch) {
                            const channelName = twitchMatch[1];
                            iframe.src = "https://player.twitch.tv/?channel=" + channelName + "&parent=" + window.location.hostname + "&autoplay=true";
                            iframe.classList.remove('hidden');
                            loading.classList.add('hidden');
                        } else if (url.includes('/embed/') || url.includes('iframe') || url.includes('embed.twitch.tv')) {
                            iframe.src = url;
                            iframe.classList.remove('hidden');
                            loading.classList.add('hidden');
                        } else {
                            video.classList.remove('hidden');
                            if (Hls.isSupported() && url.endsWith('.m3u8')) {
                                hlsPlayer = new Hls();
                                hlsPlayer.loadSource(url);
                                hlsPlayer.attachMedia(video);
                                hlsPlayer.on(Hls.Events.MANIFEST_PARSED, function() {
                                    loading.classList.add('hidden');
                                    video.play().catch(e => console.log('Autoplay blocked:', e));
                                });
                                hlsPlayer.on(Hls.Events.ERROR, function() {
                                    loading.classList.add('hidden');
                                });
                            } else {
                                video.src = url;
                                video.onloadedmetadata = function() {
                                    loading.classList.add('hidden');
                                    video.play().catch(e => console.log('Autoplay blocked:', e));
                                };
                                video.onerror = function() {
                                    loading.classList.add('hidden');
                                };
                            }
                        }
                    }

                    function closePlayer() {
                        const video = document.getElementById('videoElement');
                        const iframe = document.getElementById('iframeElement');
                        try { video.pause(); } catch(e){}
                        video.src = "";
                        iframe.src = "";
                        if (hlsPlayer) {
                            hlsPlayer.destroy();
                            hlsPlayer = null;
                        }
                        document.getElementById('playerSection').classList.add('hidden');
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
        sendResponse(socket, 200, html, "text/html")
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
                val parsed = if (rawTextBase64.isNotEmpty()) {
                    val decodedBytes = try {
                        android.util.Base64.decode(rawTextBase64, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        java.util.Base64.getDecoder().decode(rawTextBase64)
                    }
                    val rawText = String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8)
                    parseM3uFromString(rawText, defaultCategory)
                } else {
                    parseM3uFromUrl(m3uUrl, defaultCategory)
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

    private fun parseM3uFromString(content: String, defaultCategory: String): List<LiveChannel> {
        val channels = mutableListOf<LiveChannel>()
        try {
            val reader = java.io.BufferedReader(java.io.StringReader(content))
            var line: String?
            var currentMetadata: String? = null
            var simpleCounter = 1
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXTINF:")) {
                    currentMetadata = trimmed
                } else if (trimmed.startsWith("#EXTM3U")) {
                    continue
                } else if (trimmed.startsWith("#")) {
                    continue
                } else {
                    if (currentMetadata != null) {
                        var name = "Unnamed Channel"
                        var logoUrl = ""
                        var category = defaultCategory

                        val commaIdx = currentMetadata.lastIndexOf(',')
                        if (commaIdx != -1) {
                            name = currentMetadata.substring(commaIdx + 1).trim()
                        }

                        val logoRegex = """tvg-logo="([^"]*)"""".toRegex()
                        val logoMatch = logoRegex.find(currentMetadata)
                        if (logoMatch != null) {
                            logoUrl = logoMatch.groupValues[1]
                        }

                        val groupRegex = """group-title="([^"]*)"""".toRegex()
                        val groupMatch = groupRegex.find(currentMetadata)
                        if (groupMatch != null) {
                            val parsedCat = groupMatch.groupValues[1].trim()
                            if (parsedCat.isNotEmpty()) {
                                category = parsedCat
                            }
                        }

                        name = name.removeSurrounding("\"").trim()
                        if (name.isEmpty()) name = "Channel $simpleCounter"
                        
                        if (trimmed.startsWith("http") || trimmed.startsWith("rtmp") || trimmed.startsWith("rtsp")) {
                            channels.add(
                                LiveChannel(
                                    name = name,
                                    category = category,
                                    logoUrl = logoUrl,
                                    streamUrl = trimmed
                                )
                            )
                            simpleCounter++
                        }
                        currentMetadata = null
                    } else {
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtmp://") || trimmed.startsWith("rtsp://")) {
                            channels.add(
                                LiveChannel(
                                    name = "Channel $simpleCounter",
                                    category = defaultCategory,
                                    logoUrl = "",
                                    streamUrl = trimmed
                                )
                            )
                            simpleCounter++
                        } else {
                            val parts = trimmed.split(",").map { it.trim() }
                            if (parts.size >= 2) {
                                val name = parts[0].removeSurrounding("\"").trim()
                                val url = parts[1].removeSurrounding("\"").trim()
                                if (name.isNotEmpty() && (url.startsWith("http") || url.startsWith("rtmp") || url.startsWith("rtsp"))) {
                                    val cat = if (parts.size >= 3 && parts[2].isNotEmpty()) parts[2] else defaultCategory
                                    val logo = if (parts.size >= 4 && parts[3].isNotEmpty()) parts[3] else ""
                                    channels.add(
                                        LiveChannel(
                                            name = name,
                                            category = cat,
                                            logoUrl = logo,
                                            streamUrl = url
                                        )
                                    )
                                    simpleCounter++
                                }
                            }
                        }
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("AdminWebServer", "Error parsing pasted M3U text: ${e.localizedMessage}", e)
        }
        return channels
    }

    private fun parseM3uFromUrl(urlStr: String, defaultCategory: String): List<LiveChannel> {
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
                        return parseM3uFromString(bodyString, defaultCategory)
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
}