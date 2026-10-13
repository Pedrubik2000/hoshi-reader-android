package moe.antimony.hoshi.features.wordcolors

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.features.anki.AnkiDroidPackages
import moe.antimony.hoshi.profiles.ProfileRepository
import org.json.JSONArray

/**
 * Colours book words as kumapie does (kumapie-tv `CardIndex`): a word's own cards are the cards of its language's
 * Mined deck in kuma3, matched by the note's Word field. "d" green: one is due today; "b" blue: mined, never reviewed;
 * "u" red: no own card, or still learning; known words keep the text colour. Needs kuma3's `kuma3/due`
 * (a plain AnkiDroid answers no colours).
 */
@Singleton
internal class WordColors @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dictionary: DictionaryRepository,
    private val profiles: ProfileRepository,
) {
    private class Card(val id: Long, val type: Int)
    private class Word(val start: Int, val length: Int, val keys: List<String>)

    @Volatile private var byKey: Map<String, List<Card>> = emptyMap()
    @Volatile private var due: Map<Long, String>? = null
    @Volatile private var readAt = 0L
    @Volatile private var readLanguage = ""
    private val segmented = LruCache<String, List<Word>>(8)

    /** The text's coloured words as JSON `[[start, length, state], ...]` (UTF-16 offsets); "[]" without kuma3. */
    fun runs(text: String): String {
        val language = profiles.currentEffectiveContentLanguageProfile.dictionaryLanguageId
        refresh(language)
        if (due == null) return "[]"
        val words = segmented.get("$language\n$text") ?: segment(text, language).also { segmented.put("$language\n$text", it) }
        val out = JSONArray()
        for (w in words) {
            val state = state(w.keys)
            if (state != "k") out.put(JSONArray().put(w.start).put(w.length).put(state))
        }
        return out.toString()
    }

    /** Longest dictionary match at each position (as the lookup popup scans), with the keys a Word field gives. */
    private fun segment(text: String, language: String): List<Word> {
        val words = ArrayList<Word>()
        var i = 0
        while (i < text.length) {
            if (!Character.isLetter(text.codePointAt(i))) { i += Character.charCount(text.codePointAt(i)); continue }
            val hit = runCatching { dictionary.lookup(text.substring(i, minOf(text.length, i + ScanLength)), 1, ScanLength) }
                .getOrNull()?.firstOrNull()
            val length = hit?.matched?.length ?: 0
            if (hit == null || length == 0) { i++; continue }
            val keys = if (language == ContentLanguageProfile.JapaneseLanguageId) {
                listOf(japaneseKey(hit.term.expression, hit.term.reading), hit.term.expression)
            } else {
                listOf(hit.matched.lowercase(), hit.term.expression.lowercase())
            }
            words += Word(i, length, keys)
            i += length
        }
        return words
    }

    /** kumapie's `CardIndex.state` over the word's keys: the first key with own cards decides. */
    private fun state(keys: List<String>): String {
        val due = due ?: return "k"
        val own = keys.firstNotNullOfOrNull { byKey[it] } ?: return "u"
        return when {
            own.any { due[it.id] == "review" } -> "d"
            own.any { it.type == 2 } -> "k"
            own.all { it.type == 0 } -> "b"
            else -> "u"
        }
    }

    /** Re-reads the Mined deck's notes and cards and kuma3's queue, at most once a minute. */
    @Synchronized
    private fun refresh(language: String) {
        val now = System.currentTimeMillis()
        if (language == readLanguage && now - readAt < RefreshMillis) return
        readAt = now
        readLanguage = language
        val pkg = AnkiDroidPackages.resolve(context)
        if (pkg == null || context.checkSelfPermission(AnkiDroidPackages.permission(pkg)) != PackageManager.PERMISSION_GRANTED) {
            due = null
            return
        }
        runCatching {
            val search = "\"deck:${MinedDecks[language] ?: return}\""
            val keysOf = notes(pkg, search).mapValues { (_, fields) ->
                if (language == ContentLanguageProfile.JapaneseLanguageId) japaneseKeys(fields) else spacedKeys(fields)
            }.filterValues { it.isNotEmpty() }
            val cards = HashMap<String, MutableList<Card>>()
            query(pkg, "cards", arrayOf("_id", "note_id", "type"), search) { c ->
                keysOf[c.getLong(1)]?.forEach { key -> cards.getOrPut(key) { ArrayList() } += Card(c.getLong(0), c.getInt(2)) }
            }
            byKey = cards
            due = HashMap<Long, String>().also { map -> query(pkg, "kuma3/due", null, null) { map[it.getLong(0)] = it.getString(1) } }
        }.onFailure {
            Log.w("WordColors", "kuma3 cards unreadable", it)
            due = null
        }
    }

    /** Note id -> fields by name. */
    private fun notes(pkg: String, search: String): Map<Long, Map<String, String>> {
        val fieldNames = HashMap<Long, List<String>>()
        val out = HashMap<Long, Map<String, String>>()
        query(pkg, "notes", arrayOf("_id", "mid", "flds"), search) { c ->
            val names = fieldNames.getOrPut(c.getLong(1)) {
                var n = emptyList<String>()
                query(pkg, "models/${c.getLong(1)}", arrayOf("field_names"), null) { m -> n = m.getString(0).split('\u001f') }
                n
            }
            out[c.getLong(0)] = names.zip(c.getString(2).split('\u001f')).toMap()
        }
        return out
    }

    private inline fun query(pkg: String, path: String, projection: Array<String>?, selection: String?, row: (android.database.Cursor) -> Unit) {
        context.contentResolver.query(Uri.parse("content://$pkg.flashcards/$path"), projection, selection, null, null)
            ?.use { c -> while (c.moveToNext()) row(c) }
            ?: error("no cursor for $path")
    }

    companion object {
        private const val ScanLength = 16
        private const val RefreshMillis = 60_000L
        /** Pedro's kuma3 decks, as kumapie's `Lang.deck`. */
        private val MinedDecks = mapOf("ja" to "Japanese Mined", "en" to "English Mined", "de" to "Deutsch Mined")

        /** German, English: the Word field before any "[" ("ist[→ sein]" -> "ist"), lowercase. */
        internal fun spacedKeys(fields: Map<String, String>): List<String> =
            listOfNotNull(fields["Word"]?.let { plain(it).substringBefore('[').trim().lowercase() }?.takeIf { it.isNotEmpty() })

        /**
         * Japanese, as kumapie: "spelling\treading", then the spelling alone. Word fields like `日本[にほん] 語[ご]:0-`,
         * `人[*ひと]:2-`, `みんな:3-`, or a Word + Reading pair.
         */
        internal fun japaneseKeys(fields: Map<String, String>): List<String> {
            val field = fields["Word"] ?: return emptyList()
            val s = plain(field).replace(Regex(""":[0-9A-Za-z\-]*"""), "").replace(Regex("""[/*\\-]"""), "")
            val ruby = Regex("""([^\s\[\]]+)\[([^\]]*)]""")
            val spelling = s.replace(ruby, "$1").replace(Regex("""\s"""), "")
            if (spelling.isEmpty()) return emptyList()
            val reading = fields["Reading"]?.let(::plain)?.trim()?.takeIf { it.isNotEmpty() }
                ?: s.replace(ruby, "$2").replace(Regex("""\s"""), "")
            return listOf(japaneseKey(spelling, reading), spelling)
        }

        internal fun japaneseKey(written: String, reading: String): String =
            written + "\t" + buildString { for (c in reading.ifBlank { written }) append(if (c in 'ァ'..'ヶ') c - 0x60 else c) }

        private fun plain(field: String): String = field.replace(Regex("<[^>]*>"), "")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface WordColorsEntryPoint {
    fun wordColors(): WordColors
}

/** `window.HoshiWordColors.request(text)`: colours worked out off the UI thread, answered to `hoshiWordColors.apply`. */
internal class ReaderWordColorsBridge(private val webView: android.webkit.WebView) {
    private val wordColors = dagger.hilt.android.EntryPointAccessors
        .fromApplication(webView.context.applicationContext, WordColorsEntryPoint::class.java).wordColors()

    @android.webkit.JavascriptInterface
    fun request(text: String) {
        Thread {
            val runs = runCatching { wordColors.runs(text) }.onFailure { Log.w("WordColors", "colouring failed", it) }
                .getOrNull() ?: return@Thread
            val literal = org.json.JSONObject.quote(text)
            webView.post { webView.evaluateJavascript("window.hoshiWordColors && window.hoshiWordColors.apply($literal, $runs);", null) }
        }.start()
    }
}
