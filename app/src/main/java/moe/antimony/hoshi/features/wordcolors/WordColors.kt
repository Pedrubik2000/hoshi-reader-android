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

    /**
     * The characters' names in [text] that have a picture in kumapie's character dictionary, as its subtitles show them:
     * JSON `{runs: [[start, name], ...], pictures: {name: data URI}}`. Only [title]'s characters (the book's: the bear
     * クマ in くまクマ熊ベアー got another show's クマ); a kumapie that doesn't know titles ignores it.
     */
    fun faces(context: Context, text: String, title: String): String {
        val runs = JSONArray()
        val pictures = JSONObject()
        context.contentResolver.query(Uri.parse("content://$Authority/faces"), null, null, arrayOf(text, title), null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2)
                if (!pictures.has(name)) {
                    val bytes = context.contentResolver.openInputStream(Uri.parse("content://$Authority/face?name=" + Uri.encode(name) + "&work=" + Uri.encode(title)))
                        ?.use { it.readBytes() } ?: continue
                    pictures.put(name, "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                }
                runs.put(JSONArray().put(c.getInt(0)).put(name)) // where the name starts: the face goes before it (user, 2026-11-14)
            }
        }
        return JSONObject().put("runs", runs).put("pictures", pictures).toString()
    }

    /** Has kumapie split [text] (the next chapter) into its cache, so its colours come at once when it opens. */
    fun split(context: Context, text: String) {
        val extras = android.os.Bundle().apply { putString("text", text) }
        runCatching { context.contentResolver.call(Uri.parse("content://$Authority"), "split", null, extras) }
            .onFailure { Log.w("WordColors", "kumapie split failed", it) }
    }

    /** Loads kumapie's splitter (~7 s when its process is cold) before a book asks for colours. */
    fun warmUp(context: Context) {
        Thread { runCatching { runs(context, "日本語") } }.start()
    }

    /**
     * The popup's kumapie rating row and "Mark known": [bodyJson] `{action, word, reading, text?, ease?, ms?, known?, rated?}`
     * goes to kumapie's provider `call` ("state", "rate", "undo", "reload", "mark"); answers
     * `{state?, marked?, rated?: {notes, cards, undo}, undone?}`, or `null`
     * when kumapie can't (not installed, no permission).
     */
    fun kumapie(context: Context, bodyJson: String): String = runCatching {
        val body = JSONObject(bodyJson)
        val extras = android.os.Bundle().apply {
            putString("word", body.optString("word"))
            putString("reading", body.optString("reading"))
            putInt("ease", body.optInt("ease"))
            putString("text", body.optString("text"))
            putBoolean("known", body.optBoolean("known"))
            putLong("ms", body.optLong("ms"))
            body.optJSONObject("rated")?.let { r ->
                val notes = r.getJSONArray("notes")
                putLongArray("notes", LongArray(notes.length()) { notes.getLong(it) })
                putInt("cards", r.getInt("cards"))
                putString("undo", r.getString("undo"))
            }
        }
        val out = context.contentResolver.call(Uri.parse("content://$Authority"), body.getString("action"), null, extras)
            ?: return@runCatching "null"
        JSONObject().apply {
            out.getString("state")?.let { put("state", it) }
            out.getLongArray("notes")?.let { notes ->
                put("rated", JSONObject().put("notes", JSONArray(notes.toList())).put("cards", out.getInt("cards")).put("undo", out.getString("undo")))
            }
            if (out.containsKey("undone")) put("undone", out.getBoolean("undone"))
            if (out.containsKey("marked")) put("marked", out.getBoolean("marked"))
        }.toString()
    }.onFailure { Log.w("WordColors", "kumapie call failed", it) }.getOrDefault("null")

    /**
     * The popup's AI sense box: [bodyJson] `{word, reading, check}` plus the tapped [sentence] go to kumapie's provider
     * `call` "sense"; its `json` answer is passed on as is (blocks on a PC request: never on the UI thread).
     */
    fun aiSense(context: Context, bodyJson: String, sentence: String): String = runCatching {
        val body = JSONObject(bodyJson)
        val extras = android.os.Bundle().apply {
            putString("word", body.optString("word"))
            putString("reading", body.optString("reading"))
            putString("sentence", sentence)
            putBoolean("check", body.optBoolean("check"))
        }
        context.contentResolver.call(Uri.parse("content://$Authority"), "sense", null, extras)?.getString("json")
            ?: "{\"error\":true}"
    }.onFailure { Log.w("WordColors", "kumapie sense failed", it) }.getOrDefault("{\"error\":true}")
}

/**
 * `window.HoshiWordColors.request(text)`: kumapie is asked off the UI thread, the answer goes to `hoshiWordColors.apply`.
 * `next()` is the next chapter's href ("" at the end), `split(text)` has kumapie split it ahead.
 */
internal class ReaderWordColorsBridge(
    private val webView: WebView,
    private val bookTitle: () -> String,
    private val nextChapterHref: () -> String?,
) {
    private val context = webView.context.applicationContext

    @JavascriptInterface
    fun next(): String = nextChapterHref().orEmpty()

    @JavascriptInterface
    fun split(text: String) {
        Thread { WordColors.split(context, text) }.start()
    }

    @JavascriptInterface
    fun request(text: String) {
        Thread {
            val runs = runCatching { WordColors.runs(context, text) }
                .onFailure { Log.w("WordColors", "kumapie colours unavailable", it) }
                .getOrNull() ?: return@Thread
            val literal = JSONObject.quote(text)
            webView.post { webView.evaluateJavascript("window.hoshiWordColors && window.hoshiWordColors.apply($literal, $runs);", null) }
            // After the colours (kumapie has the split by now); none with an older kumapie or no character dictionary.
            val faces = runCatching { WordColors.faces(context, text, bookTitle()) }.getOrNull() ?: return@Thread
            webView.post { webView.evaluateJavascript("window.hoshiWordColors && window.hoshiWordColors.applyFaces($literal, $faces);", null) }
        }.start()
    }
}
