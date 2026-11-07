package com.gooserelay.gooserelayvpn.util

import android.util.Base64
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class SubscriptionError {
    INVALID_URL, INSECURE_SCHEME, TOO_LARGE, EMPTY_FEED, HTTP_ERROR, REDIRECT, NETWORK
}

class SubscriptionFetchException(
    val error: SubscriptionError,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Downloads and parses a subscription feed: one profile per line as
 * `goose-relay://<base64>`, bare Base64 JSON, or raw JSON; `#` and `//`
 * lines are comments. A body that parses as one raw-JSON object is a
 * single profile; otherwise every non-comment line is parsed as raw JSON,
 * a share link, or bare Base64. A multi-line pretty-printed JSON object is
 * only recognized when the whole body is that object; in a mixed feed each
 * raw-JSON profile must be on one line.
 *
 * The subscription URL routinely embeds per-user tokens, so never log it.
 */
object SubscriptionFetcher {
    const val MAX_BYTES: Int = 2 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val TIMEOUT_MS = 15_000
    private const val SHARE_SCHEME = "goose-relay"

    data class FetchResult(val profiles: List<ProfileEntity>, val skippedLines: Int)

    /**
     * Blocking download + parse. Callers must hop to `Dispatchers.IO` first;
     * calling this from the main thread throws `NetworkOnMainThreadException`.
     */
    fun fetch(
        url: String,
        decodeBase64: (String) -> String? = ::androidDecodeBase64
    ): FetchResult {
        var current = requireHttpUrl(url)
        var redirects = 0
        while (true) {
            val connection = try {
                current.openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw SubscriptionFetchException(
                    SubscriptionError.NETWORK, "Could not open the subscription URL", e
                )
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "GET"

                val code = connection.responseCode
                if (code in 300..399) {
                    val next = resolveRedirect(current, connection.getHeaderField("Location"))
                    if (next == null || redirects >= MAX_REDIRECTS) {
                        throw SubscriptionFetchException(
                            SubscriptionError.REDIRECT, "Too many or invalid redirects"
                        )
                    }
                    redirects++
                    current = next
                    continue
                }
                if (code !in 200..299) {
                    throw SubscriptionFetchException(
                        SubscriptionError.HTTP_ERROR, "Subscription server returned HTTP $code"
                    )
                }

                val body = readCapped(connection.inputStream)
                val result = parseFeed(String(body, Charsets.UTF_8), decodeBase64)
                if (result.profiles.isEmpty()) {
                    throw SubscriptionFetchException(
                        SubscriptionError.EMPTY_FEED,
                        "Subscription contains no valid profiles"
                    )
                }
                return result
            } catch (e: SubscriptionFetchException) {
                throw e
            } catch (e: Exception) {
                throw SubscriptionFetchException(
                    SubscriptionError.NETWORK, "Could not download the subscription", e
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun requireHttpUrl(raw: String): URL {
        val url = try {
            URL(raw.trim())
        } catch (e: Exception) {
            throw SubscriptionFetchException(SubscriptionError.INVALID_URL, "Invalid URL", e)
        }
        val protocol = url.protocol.lowercase()
        if (protocol != "http" && protocol != "https") {
            throw SubscriptionFetchException(
                SubscriptionError.INSECURE_SCHEME, "Only http and https URLs are supported"
            )
        }
        return url
    }

    internal fun resolveRedirect(current: URL, location: String?): URL? {
        if (location.isNullOrBlank()) return null
        val resolved = try {
            URL(current, location)
        } catch (_: Exception) {
            return null
        }
        val protocol = resolved.protocol.lowercase()
        if (protocol != "http" && protocol != "https") return null
        return resolved
    }

    internal fun readCapped(stream: InputStream, maxBytes: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw SubscriptionFetchException(
                    SubscriptionError.TOO_LARGE,
                    "Subscription is larger than $maxBytes bytes"
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    internal fun parseFeed(
        text: String,
        decodeBase64: (String) -> String?
    ): FetchResult {
        val normalized = text.removePrefix("\uFEFF").trim()
        if (normalized.isEmpty()) return FetchResult(emptyList(), 0)
        if (normalized.startsWith("{")) {
            val profile = ProfileJsonParser.parse(normalized)
            if (profile != null) return FetchResult(listOf(profile), 0)
            // Not a single JSON document: fall through so a feed whose first line is
            // raw JSON and whose later lines are share links still parses line by line.
        }

        val profiles = mutableListOf<ProfileEntity>()
        var skipped = 0
        normalized.split("\n").forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                return@forEach
            }
            val profile = if (line.startsWith("{")) {
                ProfileJsonParser.parse(line)
            } else {
                parseShareLine(line, decodeBase64)
            }
            if (profile != null) profiles.add(profile) else skipped++
        }
        return FetchResult(profiles, skipped)
    }

    private fun parseShareLine(
        line: String,
        decodeBase64: (String) -> String?
    ): ProfileEntity? {
        val payload = stripShareScheme(line)
        val decoded = decodeBase64(payload) ?: return null
        return ProfileJsonParser.parse(decoded.removePrefix("\uFEFF"))
    }

    private fun stripShareScheme(line: String): String {
        val separator = line.indexOf("://")
        if (separator > 0 &&
            line.substring(0, separator).equals(SHARE_SCHEME, ignoreCase = true)
        ) {
            return line.substring(separator + 3)
        }
        return line
    }

    internal fun androidDecodeBase64(value: String): String? = try {
        String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
