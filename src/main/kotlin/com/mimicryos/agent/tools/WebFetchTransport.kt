package com.mimicryos.agent.tools

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration

data class WebFetchResponse(
    val statusCode: Int,
    val contentType: String,
    val body: String,
    val finalUrl: String,
    val truncated: Boolean = false,
)

interface WebFetchTransport {
    fun fetch(url: String, timeoutMs: Int): WebFetchResponse
}

object WebFetchPolicy {
    const val MAX_BYTES = 512 * 1024
    const val MAX_CHARS = 120_000

    fun validate(url: String) {
        val uri = try { URI(url) } catch (e: Exception) { throw IllegalArgumentException("Invalid URL: ${e.message}") }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "WebFetch only supports http and https URLs" }
        require(!uri.host.isNullOrBlank()) { "WebFetch URL must include a host" }
        require(uri.userInfo == null) { "WebFetch URL must not include credentials" }
        require(uri.port == -1 || uri.port in 1..65535) { "WebFetch URL has an invalid port" }
        val host = uri.host
        val addresses = try { InetAddress.getAllByName(host) } catch (e: Exception) { throw IllegalArgumentException("Cannot resolve WebFetch host: $host") }
        require(addresses.isNotEmpty()) { "WebFetch host has no addresses: $host" }
        require(addresses.all(::isPublicAddress)) { "WebFetch refuses private, loopback, link-local or reserved addresses" }
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress ||
            address.isMulticastAddress || address.isSiteLocalAddress
        ) return false
        val b = address.address
        if (b.isEmpty()) return false
        if (b[0] == 0.toByte()) return false
        if (b[0] == 10.toByte()) return false
        if (b[0] == 127.toByte()) return false
        if (b[0] == 192.toByte() && b[1] == 168.toByte()) return false
        if (b[0] == 172.toByte() && b[1] >= 16 && b[1] <= 31) return false
        if (b[0] == 169.toByte() && b[1] == 254.toByte()) return false
        if (b[0] == 100.toByte() && b[1] >= 64 && b[1] <= 127) return false
        if ((b[0].toInt() and 0xfe) == 0xfc) return false // IPv6 unique local
        if (b[0] == 0xfe.toByte() && (b[1].toInt() and 0xc0) == 0x80) return false // IPv6 link local
        return true
    }
}

class JdkWebFetchTransport : WebFetchTransport {
    override fun fetch(url: String, timeoutMs: Int): WebFetchResponse {
        WebFetchPolicy.validate(url)
        val effectiveTimeout = timeoutMs.coerceIn(1_000, 60_000)
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(effectiveTimeout.toLong()))
            .build()
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(effectiveTimeout.toLong()))
            .header("User-Agent", "mimicry-claw/1.0 (+WebFetch)")
            .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,application/xml,*/*;q=0.5")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()
        if (status in 300..399) throw IllegalStateException("WebFetch does not follow redirects (HTTP $status)")
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        response.body().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val allowed = WebFetchPolicy.MAX_BYTES - total
                if (allowed <= 0) break
                bytes.write(buffer, 0, minOf(read, allowed))
                total += read
                if (total >= WebFetchPolicy.MAX_BYTES) break
            }
            val charset = charsetFrom(contentType)
            val raw = String(bytes.toByteArray(), charset).take(WebFetchPolicy.MAX_CHARS)
            if (status !in 200..299) {
                throw IllegalStateException("WebFetch failed (HTTP $status): ${raw.take(500)}")
            }
            return WebFetchResponse(status, contentType, raw, url, total >= WebFetchPolicy.MAX_BYTES)
        }
    }

    private fun charsetFrom(contentType: String): Charset {
        val match = Regex("charset\\s*=\\s*\"?([^;\\s\"]+)", RegexOption.IGNORE_CASE).find(contentType)
        return match?.let {
            try { Charset.forName(it.groupValues[1]) } catch (_: Exception) { StandardCharsets.UTF_8 }
        } ?: StandardCharsets.UTF_8
    }
}

object HtmlTextExtractor {
    private val scriptStyle = Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>")
    private val blocks = Regex("(?i)</?(?:br|p|div|li|tr|h[1-6]|section|article|pre|table)[^>]*>")
    private val tags = Regex("(?s)<[^>]*>")
    private val entities = Regex("&(amp|lt|gt|quot|apos|nbsp|#39|#x27);")

    fun extract(html: String): String {
        var text = scriptStyle.replace(html, " ")
        text = blocks.replace(text, "\n")
        text = tags.replace(text, " ")
        text = entities.replace(text) { match ->
            when (match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot", "apos", "#39", "#x27" -> "\""
                "nbsp" -> " "
                else -> match.value
            }
        }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(WebFetchPolicy.MAX_CHARS)
    }
}
