package moe.antimony.hoshi.features.wordcolors

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Book word colours from kumapie (its `WordColours` provider): kumapie splits the text with its own parser and colours
 * each word as its subtitles do, from kuma3's cards and its known words. "d" green: an own card is due today;
 * "b" blue: mined, never reviewed; "u" red: unknown; known words keep the text colour. Japanese only; none without kumapie.
 */
internal object WordColors {
    const val Authority = "io.github.pedrubik2000.kumapie.mobile.words"

    /** The text's coloured words as JSON `[[start, length, state], ...]` (UTF-16 offsets into [text]). */
    fun runs(context: Context, text: String): String {
        val out = JSONArray()
        context.contentResolver.query(Uri.parse("content://$Authority/colours"), null, null, arrayOf(text), null)?.use { c ->
            while (c.moveToNext()) {
                val state = when (val s = c.getString(2)) { "k" -> continue; "d", "b" -> s; else -> "u" }
                out.put(JSONArray().put(c.getInt(0)).put(c.getInt(1)).put(state))
            }
        }
        return out.toString()
    }
}

/** `window.HoshiWordColors.request(text)`: kumapie is asked off the UI thread, the answer goes to `hoshiWordColors.apply`. */
internal class ReaderWordColorsBridge(private val webView: WebView) {
    private val context = webView.context.applicationContext

    @JavascriptInterface
    fun request(text: String) {
        Thread {
            val runs = runCatching { WordColors.runs(context, text) }
                .onFailure { Log.w("WordColors", "kumapie colours unavailable", it) }
                .getOrNull() ?: return@Thread
            val literal = JSONObject.quote(text)
            webView.post { webView.evaluateJavascript("window.hoshiWordColors && window.hoshiWordColors.apply($literal, $runs);", null) }
        }.start()
    }
}
