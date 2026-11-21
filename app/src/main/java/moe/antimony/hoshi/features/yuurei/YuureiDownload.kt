package moe.antimony.hoshi.features.yuurei

import java.net.URI
import java.net.URLDecoder

internal const val YuureiHomeUrl = "https://yuurei.moe/"
private const val YuureiDownloadHost = "dl.yuurei.moe"
private const val EpubExtension = ".epub"
private const val MaxFileNameBytes = 200

internal fun isYuureiDownloadHost(host: String?): Boolean = host.equals(YuureiDownloadHost, ignoreCase = true)

/** Hosts the Yuurei screen keeps inside its WebView: the site and its Discord login. */
internal fun isYuureiWebViewHost(host: String?): Boolean {
    val lower = host?.lowercase() ?: return false
    return listOf("yuurei.moe", "discord.com", "discordapp.com").any { lower == it || lower.endsWith(".$it") }
}

/**
 * The EPUB file name to save a Yuurei download link under, or null when Hoshi must not import it:
 * only https links on dl.yuurei.moe (any port) whose path ends in .epub. The name comes from the
 * `filename` query parameter, else the last path segment.
 */
internal fun yuureiEpubFileName(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || !isYuureiDownloadHost(uri.host)) return null
    val path = uri.rawPath.orEmpty()
    if (!path.endsWith(EpubExtension, ignoreCase = true)) return null
    val queryName = uri.rawQuery.orEmpty().split('&')
        .firstOrNull { it.startsWith("filename=") }
        ?.substringAfter('=')
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
    val name = (queryName?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'))
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace("\u0000", "")
        .trim()
    if (!name.endsWith(EpubExtension, ignoreCase = true)) return null
    var base = name.dropLast(EpubExtension.length).trim()
    while (base.toByteArray().size > MaxFileNameBytes) {
        base = base.substring(0, base.offsetByCodePoints(base.length, -1))
    }
    return if (base.isBlank()) null else "$base$EpubExtension"
}
