package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class SignageWebViewClient(
    private val context: Context,
    private val userAgent: String? = null,
    private val onPageFinishedCallback: (WebView?, String?) -> Unit = { _, _ -> }
) : WebViewClient() {
    private val cacheDir = File(context.cacheDir, "signage_media_cache").apply { 
        mkdirs()
        // Proactively clear any leftover temporary download files from previous incomplete sessions
        try {
            listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("SignageWebViewClient", "Error clearing stale temp files", e)
        }
    }

    companion object {
        private val downloadLocks = ConcurrentHashMap<String, Any>()
    }

    init {
        // Register the Service Worker interceptor globally for Android N and above (API level 24+)
        // This is extremely critical because PWA/react players use Service Worker fetches, 
        // which bypass regular WebViewClient.shouldInterceptRequest overrides completely.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        return shouldInterceptRequestInternal(request)
                    }
                })
                Log.d("SignageWebViewClient", "ServiceWorkerController Client successfully registered for global interception!")
            } catch (e: Exception) {
                Log.e("SignageWebViewClient", "Failed to register ServiceWorkerClient", e)
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback(view, url)
    }

    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        Log.e("SignageWebViewClient", "WebView render process gone! Reloading WebView content...")
        view?.post {
            view.reload()
        }
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return shouldInterceptRequestInternal(request)
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    private fun isStaticMediaRequest(request: WebResourceRequest): Boolean {
        val host = request.url.host ?: ""
        if (host.equals("media.slidetv.eu", ignoreCase = true)) {
            return true
        }

        val path = request.url.path ?: ""
        val lowerPath = path.lowercase()
        
        val extensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".mp4", ".webm", ".m4v", ".mov", ".wav", ".mp3")
        val matchesExtension = extensions.any { ext -> 
            lowerPath.endsWith(ext) || lowerPath.contains("$ext/") || lowerPath.contains("$ext?")
        }
        if (matchesExtension) return true

        val accept = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"] ?: ""
        if (accept.startsWith("image/", ignoreCase = true) || 
            accept.startsWith("video/", ignoreCase = true) || 
            accept.startsWith("audio/", ignoreCase = true)) {
            return true
        }
        
        return false
    }

    private fun isAppResourceRequest(request: WebResourceRequest): Boolean {
        val host = request.url.host ?: ""
        if (host.contains("slidetv.eu", ignoreCase = true)) {
            if (host.equals("media.slidetv.eu", ignoreCase = true)) {
                return false
            }
            return true
        }

        val path = request.url.path ?: ""
        val lowerPath = path.lowercase()
        val appExtensions = listOf(".html", ".js", ".css", ".json", ".woff", ".woff2", ".ttf", ".eot")
        return appExtensions.any { ext -> 
            lowerPath.endsWith(ext) || lowerPath.contains("$ext/") || lowerPath.contains("$ext?")
        }
    }

    /**
     * Intercepts both regular page requests and Service Worker background fetches.
     */
    fun shouldInterceptRequestInternal(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val method = request.method
        
        if (method.equals("GET", ignoreCase = true)) {
            try {
                if (isStaticMediaRequest(request)) {
                    return getStaticMediaResponse(request)
                } else if (isAppResourceRequest(request)) {
                    return getAppResourceResponse(request)
                }
            } catch (e: Exception) {
                Log.e("SignageWebViewClient", "Error caching/serving: $url", e)
            }
        }
        return null
    }

    private fun getStaticMediaResponse(request: WebResourceRequest): WebResourceResponse? {
        val urlString = request.url.toString()
        val path = request.url.path ?: ""
        val hash = if (path.length > 3) path.hashCode().toString() else urlString.hashCode().toString()
        
        val lock = downloadLocks.computeIfAbsent(hash) { Any() }
        
        val cacheFile: File? = synchronized(lock) {
            var existingFile = cacheDir.listFiles()?.find { 
                it.name.startsWith("$hash.") && !it.name.endsWith(".tmp") && it.length() > 0L 
            }
            if (existingFile == null || !existingFile.exists()) {
                existingFile = downloadAndCacheFile(urlString, hash, request)
            }
            existingFile
        }

        if (cacheFile != null && cacheFile.exists()) {
            val extension = cacheFile.name.substringAfterLast('.', "media")
            val mimeType = getMimeType(extension)
            return serveLocalFile(cacheFile, mimeType, request)
        }

        return null
    }

    private fun getAppResourceResponse(request: WebResourceRequest): WebResourceResponse? {
        val urlString = request.url.toString()
        val path = request.url.path ?: ""
        val hash = if (path.length > 3) path.hashCode().toString() else urlString.hashCode().toString()
        
        val lock = downloadLocks.computeIfAbsent(hash) { Any() }

        if (isNetworkAvailable()) {
            // Online: Network First
            val cacheFile: File? = synchronized(lock) {
                downloadAndCacheFile(urlString, hash, request)
            }
            if (cacheFile != null && cacheFile.exists()) {
                val extension = cacheFile.name.substringAfterLast('.', "media")
                val mimeType = getMimeType(extension)
                return serveLocalFileSimple(cacheFile, mimeType)
            }
        }

        // Offline / Failed download: Cache Fallback
        val cacheFile: File? = synchronized(lock) {
            cacheDir.listFiles()?.find { 
                it.name.startsWith("$hash.") && !it.name.endsWith(".tmp") && it.length() > 0L 
            }
        }
        if (cacheFile != null && cacheFile.exists()) {
            val extension = cacheFile.name.substringAfterLast('.', "media")
            val mimeType = getMimeType(extension)
            Log.d("SignageWebViewClient", "Serving app asset [200] from cache (offline fallback): $urlString")
            return serveLocalFileSimple(cacheFile, mimeType)
        }

        return null
    }

    private fun downloadAndCacheFile(urlString: String, hash: String, request: WebResourceRequest): File? {
        var currentUrl = urlString
        var redirectCount = 0
        val maxRedirects = 5

        while (redirectCount < maxRedirects) {
            try {
                val url = URL(currentUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false // Force manual redirect to handle cross-protocol and cookies
                
                // Copy browser headers
                request.requestHeaders.forEach { (key, value) ->
                    if (!key.equals("range", ignoreCase = true)) {
                        connection.setRequestProperty(key, value)
                    }
                }
                
                // Prioritize the actual WebView user-agent or use a modern fallback
                val ua = userAgent ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                connection.setRequestProperty("User-Agent", ua)

                // Add cookie headers dynamically
                if (!request.requestHeaders.containsKey("Cookie") && !request.requestHeaders.containsKey("cookie")) {
                    val cookies = CookieManager.getInstance().getCookie(currentUrl)
                    if (!cookies.isNullOrEmpty()) {
                        connection.setRequestProperty("Cookie", cookies)
                    }
                }

                connection.connect()
                val responseCode = connection.responseCode

                // Handle Redirects
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                    responseCode == 307 || responseCode == 308) {
                    
                    val newUrl = connection.getHeaderField("Location")
                    if (!newUrl.isNullOrEmpty()) {
                        currentUrl = if (newUrl.startsWith("http")) newUrl else {
                            val baseUri = request.url
                            val scheme = baseUri.scheme ?: "https"
                            val host = baseUri.host ?: ""
                            if (newUrl.startsWith("/")) {
                                "$scheme://$host$newUrl"
                            } else {
                                "$scheme://$host/$newUrl"
                            }
                        }
                        redirectCount++
                        continue
                    }
                }

                val contentType = connection.contentType ?: ""
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val extension = getExtensionFromContentType(contentType, urlString)
                    val tempFile = File(cacheDir, "$hash.$extension.tmp")
                    val finalFile = File(cacheDir, "$hash.$extension")
                    
                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    if (tempFile.renameTo(finalFile)) {
                        Log.d("SignageWebViewClient", "Cached perfectly: $urlString -> Size: ${finalFile.length()} bytes, Mime: $contentType")
                        return finalFile
                    } else {
                        tempFile.delete()
                    }
                    break
                } else {
                    Log.w("SignageWebViewClient", "Server responded with $responseCode for download of $urlString")
                    break
                }
            } catch (e: Exception) {
                Log.e("SignageWebViewClient", "Failed to cache resource from network: $urlString", e)
                break
            }
        }
        return null
    }

    private fun serveLocalFile(file: File, mimeType: String, request: WebResourceRequest): WebResourceResponse? {
        val fileLength = file.length()
        val rangeHeader = request.requestHeaders["Range"] ?: request.requestHeaders["range"]

        try {
            if (!rangeHeader.isNullOrEmpty() && rangeHeader.startsWith("bytes=")) {
                var start: Long = 0
                var end: Long = fileLength - 1
                
                val rangeValue = rangeHeader.substring(6)
                val parts = rangeValue.split("-")
                
                try {
                    if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                        start = parts[0].toLong()
                    }
                    if (parts.size > 1 && parts[1].isNotEmpty()) {
                        end = parts[1].toLong()
                    }
                } catch (e: Exception) {
                    // Ignore parsing exceptions, fallback to full length
                }

                if (start < 0) start = 0
                if (end >= fileLength) end = fileLength - 1
                if (start > end) start = end

                val contentLength = end - start + 1
                val fileStream = FileInputStream(file)
                skipFully(fileStream, start)
                val boundedStream = BoundedInputStream(fileStream, contentLength)

                val responseHeaders = mutableMapOf<String, String>()
                responseHeaders["Content-Type"] = mimeType
                responseHeaders["Content-Length"] = contentLength.toString()
                responseHeaders["Content-Range"] = "bytes $start-$end/$fileLength"
                responseHeaders["Accept-Ranges"] = "bytes"
                responseHeaders["Access-Control-Allow-Origin"] = "*" // Add CORS header for media
                responseHeaders["Cache-Control"] = "no-cache"

                Log.d("SignageWebViewClient", "Serving Range [206] sliced segment from cache: ${request.url} (bytes: $start-$end/$fileLength, Size: $contentLength bytes)")
                return WebResourceResponse(
                    mimeType,
                    "UTF-8",
                    206,
                    "Partial Content",
                    responseHeaders,
                    boundedStream
                )
            } else {
                val fileStream = FileInputStream(file)
                val responseHeaders = mutableMapOf<String, String>()
                responseHeaders["Content-Type"] = mimeType
                responseHeaders["Content-Length"] = fileLength.toString()
                responseHeaders["Accept-Ranges"] = "bytes"
                responseHeaders["Access-Control-Allow-Origin"] = "*" // Add CORS header for media
                responseHeaders["Cache-Control"] = "no-cache"

                Log.d("SignageWebViewClient", "Serving full file [200] from cache: ${request.url} (Size: $fileLength bytes)")
                return WebResourceResponse(
                    mimeType,
                    "UTF-8",
                    200,
                    "OK",
                    responseHeaders,
                    fileStream
                )
            }
        } catch (e: Exception) {
            Log.e("SignageWebViewClient", "Error serving cached file for ${request.url}", e)
        }
        return null
    }

    private fun serveLocalFileSimple(file: File, mimeType: String): WebResourceResponse? {
        try {
            val fileLength = file.length()
            val fileStream = FileInputStream(file)
            val responseHeaders = mutableMapOf<String, String>()
            responseHeaders["Content-Type"] = mimeType
            responseHeaders["Content-Length"] = fileLength.toString()
            responseHeaders["Access-Control-Allow-Origin"] = "*" // Add CORS header for static files
            responseHeaders["Cache-Control"] = "no-cache"

            return WebResourceResponse(
                mimeType,
                "UTF-8",
                200,
                "OK",
                responseHeaders,
                fileStream
            )
        } catch (e: Exception) {
            Log.e("SignageWebViewClient", "Error serving cached file", e)
        }
        return null
    }

    private fun skipFully(stream: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                break
            }
            remaining -= skipped
        }
    }

    private fun getExtensionFromContentType(contentType: String, urlString: String): String {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return when (mime) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-m4v" -> "m4v"
            "video/quicktime" -> "mov"
            "audio/mpeg" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            "text/html" -> "html"
            "application/javascript", "text/javascript" -> "js"
            "text/css" -> "css"
            "application/json" -> "json"
            "font/woff" -> "woff"
            "font/woff2" -> "woff2"
            "font/ttf" -> "ttf"
            "application/vnd.ms-fontobject" -> "eot"
            else -> {
                urlString.substringAfterLast('.', "media").substringBefore('?')
            }
        }
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "m4v" -> "video/x-m4v"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "html", "htm" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "eot" -> "application/vnd.ms-fontobject"
            else -> "application/octet-stream"
        }
    }

    private class BoundedInputStream(
        private val inputStream: java.io.InputStream,
        private var remain: Long
    ) : java.io.InputStream() {
        override fun read(): Int {
            if (remain <= 0) return -1
            val res = inputStream.read()
            if (res != -1) remain--
            return res
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remain <= 0) return -1
            val toRead = if (len > remain) remain.toInt() else len
            val res = inputStream.read(b, off, toRead)
            if (res != -1) remain -= res
            return res
        }

        override fun close() {
            inputStream.close()
        }
    }
}
