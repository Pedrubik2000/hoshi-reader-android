package moe.antimony.hoshi.features.anki

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.SecurityException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

class AndroidAnkiContentApi @Inject constructor(
    @ApplicationContext
    context: Context,
) : AnkiContentApi {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    /** The installed AnkiDroid build to mine into: kuma3 first ([AnkiDroidPackages]). */
    private val pkg: String get() = AnkiDroidPackages.resolve(appContext) ?: "com.ichi2.anki"
    private fun uri(path: String): Uri = Uri.parse("content://$pkg.flashcards/$path")

    override fun deckList(): Map<Long, String> =
        readAnkiApi(AnkiFetchFailure.DeckListUnavailable) {
            resolver.query(uri("decks"), arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
                buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
            } ?: throw AnkiFetchException(AnkiFetchFailure.DeckListUnavailable)
        }

    override fun modelList(): Map<Long, String> =
        readAnkiApi(AnkiFetchFailure.ModelListUnavailable) {
            resolver.query(uri("models"), arrayOf("_id", "name"), null, null, null)?.use { c ->
                buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1)) }
            } ?: throw AnkiFetchException(AnkiFetchFailure.ModelListUnavailable)
        }

    override fun fieldList(modelId: Long): List<String> =
        readAnkiApi(AnkiFetchFailure.ModelFieldsUnavailable) {
            resolver.query(uri("models/$modelId"), arrayOf("field_names"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).split(FieldSeparator) else null
            } ?: throw AnkiFetchException(AnkiFetchFailure.ModelFieldsUnavailable)
        }

    override fun findDuplicateNotes(
        deck: AnkiDeck,
        modelId: Long,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkAllModels: Boolean,
    ): Boolean {
        if (key.isBlank()) return false
        val checksum = ankiFirstFieldChecksum(key)
        if (checksum == 0L) return false
        val scopeDeckIds = ankiDuplicateScopeDeckIds(
            decksById = deckList(),
            selectedDeck = deck,
            duplicateScope = duplicateScope,
        )
        val cursor = resolver.query(
            uri("notes_v2"),
            arrayOf("_id", "csum"),
            ankiDuplicateNoteSelection(
                modelId = modelId,
                checksum = checksum,
                checkAllModels = checkAllModels,
            ),
            null,
            null,
        ) ?: return false

        cursor.use {
            while (it.moveToNext()) {
                if (duplicateScope == AnkiDuplicateScope.Collection) return true
                if (noteHasCardInDeck(it.getLong(0), scopeDeckIds)) return true
            }
        }
        return false
    }

    override fun addNote(
        modelId: Long,
        deckId: Long,
        fields: Array<String>,
        tags: Set<String>,
    ): Long? {
        val noteUri = resolver.insert(uri("notes"), ContentValues().apply {
            put("mid", modelId)
            put("flds", fields.joinToString(FieldSeparator))
            put("tags", tags.joinToString(" "))
        }) ?: return null
        val noteId = noteUri.lastPathSegment?.toLongOrNull() ?: return null
        // New cards land in the note type's last deck; move them, as AnkiDroid's AddContentApi does.
        val ords = resolver.query(uri("notes/$noteId/cards"), arrayOf("ord"), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        }.orEmpty()
        ords.forEach { ord ->
            resolver.update(uri("notes/$noteId/cards/$ord"), ContentValues().apply { put("deck_id", deckId) }, null, null)
        }
        return noteId
    }

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? {
        val added = resolver.insert(uri("media"), ContentValues().apply {
            put("file_uri", uriString)
            put("preferred_name", ankiPreferredMediaName(preferredName))
        }) ?: return null
        val name = added.lastPathSegment ?: return null
        // Same text AnkiDroid's AddContentApi answers.
        return if (mimeType.startsWith("audio/")) "[sound:$name]" else "<img src=\"$name\" />"
    }

    override fun sync(): Boolean =
        runCatching {
            appContext.startActivity(ankiDroidSyncIntent(pkg))
            true
        }.recoverCatching { error ->
            when (error) {
                is ActivityNotFoundException -> logAnkiApiFailure("AnkiDroid sync activity is unavailable.", error)
                else -> logAnkiApiFailure("Unable to start AnkiDroid sync.", error)
            }
            false
        }.getOrDefault(false)

    override fun openNotes(query: String): Boolean =
        runCatching {
            val spec = ankiDroidBrowserIntentSpec(
                query = query,
                packageName = pkg,
            )
            appContext.startActivity(
                Intent().apply {
                    setClassName(spec.packageName, spec.activityClassName)
                    spec.stringExtras.forEach { (key, value) -> putExtra(key, value) }
                    spec.booleanExtras.forEach { (key, value) -> putExtra(key, value) }
                    if (spec.newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.recoverCatching { error ->
            logAnkiApiFailure("Unable to open the AnkiDroid card browser.", error)
            false
        }.getOrDefault(false)

    override fun isAvailable(): Boolean =
        AnkiDroidPackages.resolve(appContext) != null

    private inline fun <T> readAnkiApi(fallbackFailure: AnkiFetchFailure, block: () -> T): T {
        if (!isAvailable()) throw AnkiFetchException(AnkiFetchFailure.ApiUnavailable)
        return try {
            block()
        } catch (error: AnkiFetchException) {
            throw error
        } catch (error: SecurityException) {
            logAnkiApiFailure("AnkiDroid database permission denied.", error)
            throw AnkiFetchException(AnkiFetchFailure.PermissionDenied, cause = error)
        } catch (error: Throwable) {
            logAnkiApiFailure("AnkiDroid API request failed.", error)
            throw AnkiFetchException(fallbackFailure, cause = error)
        }
    }

    private fun noteHasCardInDeck(noteId: Long, deckIds: Set<Long>): Boolean {
        if (deckIds.isEmpty()) return false
        val cursor = resolver.query(uri("notes/$noteId/cards"), arrayOf("deck_id"), null, null, null) ?: return false
        cursor.use {
            while (it.moveToNext()) {
                if (it.getLong(0) in deckIds) return true
            }
        }
        return false
    }

    private companion object {
        const val FieldSeparator = "\u001f"
    }
}

/**
 * AnkiDroid builds Hoshi can mine into, in order of preference: kuma3 Anki, its debug build, Play Store AnkiDroid.
 * Each has its own provider (`<package>.flashcards`) and permission. Keep in step with the manifest.
 */
internal object AnkiDroidPackages {
    val All = listOf("io.github.pedrubik2000.kuma3", "com.ichi2.anki.debug", "com.ichi2.anki")

    fun resolve(context: Context): String? =
        All.firstOrNull { context.packageManager.resolveContentProvider("$it.flashcards", 0) != null }

    fun permission(packageName: String) = "$packageName.permission.READ_WRITE_DATABASE"

    /** The permission to ask for: the installed build's, else Play Store AnkiDroid's. */
    fun permission(context: Context) = permission(resolve(context) ?: "com.ichi2.anki")
}

private fun logAnkiApiFailure(message: String, error: Throwable) {
    runCatching { Log.w("AndroidAnkiContentApi", message, error) }
}

internal fun ankiDroidSyncIntent(packageName: String = "com.ichi2.anki"): Intent =
    ankiDroidSyncIntentSpec(packageName).let { spec ->
        Intent(spec.action).apply {
            setPackage(spec.packageName)
            spec.categories.forEach(::addCategory)
            flags = spec.flags
        }
    }

internal data class AnkiDroidSyncIntentSpec(
    val action: String,
    val packageName: String,
    val categories: Set<String>,
    val flags: Int,
)

internal fun ankiDroidSyncIntentSpec(packageName: String = "com.ichi2.anki"): AnkiDroidSyncIntentSpec =
    AnkiDroidSyncIntentSpec(
        action = "com.ichi2.anki.DO_SYNC",
        packageName = packageName,
        categories = setOf(Intent.CATEGORY_DEFAULT),
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_HISTORY or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION,
    )

internal data class AnkiDroidBrowserIntentSpec(
    val activityClassName: String,
    val stringExtras: Map<String, String>,
    val booleanExtras: Map<String, Boolean>,
    val packageName: String,
    val newTask: Boolean,
)

internal fun ankiDroidBrowserIntentSpec(
    query: String,
    packageName: String = "com.ichi2.anki",
): AnkiDroidBrowserIntentSpec =
    AnkiDroidBrowserIntentSpec(
        activityClassName = "com.ichi2.anki.CardBrowser",
        // A URI search takes precedence over these extras and retains the last deck filter.
        // Keep the requested duplicate scope in the query, not in the browser selection.
        stringExtras = mapOf("search_query" to query),
        booleanExtras = mapOf("all_decks" to true),
        packageName = packageName,
        newTask = true,
    )

internal fun ankiDuplicateNoteSelection(
    modelId: Long,
    checksum: Long,
    checkAllModels: Boolean,
): String {
    val checksumSelection = String.format(
        Locale.US,
        "%s in (%d)",
        "csum",
        checksum,
    )
    return if (checkAllModels) {
        checksumSelection
    } else {
        String.format(
            Locale.US,
            "%s=%d and %s",
            "mid",
            modelId,
            checksumSelection,
        )
    }
}

internal fun ankiPreferredMediaName(preferredName: String): String {
    val fileName = preferredName.substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        .ifBlank { "hoshi_media" }
}

internal fun ankiDuplicateScopeDeckIds(
    decksById: Map<Long, String>,
    selectedDeck: AnkiDeck,
    duplicateScope: AnkiDuplicateScope,
): Set<Long> =
    when (duplicateScope) {
        AnkiDuplicateScope.Collection -> emptySet()
        AnkiDuplicateScope.Deck -> setOf(selectedDeck.id)
        AnkiDuplicateScope.DeckRoot -> {
            val rootName = selectedDeck.name.substringBefore("::")
            decksById.mapNotNull { (id, name) ->
                id.takeIf { name == rootName || name.startsWith("$rootName::") }
            }.toSet()
        }
    }

internal fun ankiFirstFieldChecksum(data: String): Long {
    // Anki normalizes note fields to NFC before stripping HTML and computing csum.
    val strippedData = Normalizer.normalize(data, Normalizer.Form.NFC).stripHtmlMedia()
    val digest = MessageDigest.getInstance("SHA1")
        .digest(strippedData.toByteArray(StandardCharsets.UTF_8))
    val hex = BigInteger(1, digest).toString(16).padStart(40, '0')
    return hex.substring(0, 8).toLong(16)
}

private val StyleRegex = Regex("(?is)<style.*?>.*?</style>")
private val ScriptRegex = Regex("(?is)<script.*?>.*?</script>")
private val TagRegex = Regex("<.*?>")
private val ImgRegex = Regex("<img src=[\"']?([^\"'>]+)[\"']? ?/?>", RegexOption.IGNORE_CASE)
private val HtmlEntityRegex = Regex("&#?\\w+;")

private fun String.stripHtmlMedia(): String =
    ImgRegex.replace(this) { " ${it.groupValues[1]} " }
        .replace(StyleRegex, "")
        .replace(ScriptRegex, "")
        .replace(TagRegex, "")
        .decodeHtmlEntities()

private fun String.decodeHtmlEntities(): String =
    HtmlEntityRegex.replace(replace("&nbsp;", " ")) { match ->
        when (val entity = match.value) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&quot;" -> "\""
            "&#39;", "&apos;" -> "'"
            else -> entity.decodeNumericHtmlEntity() ?: entity
        }
    }

private fun String.decodeNumericHtmlEntity(): String? {
    val value = removePrefix("&#").removeSuffix(";")
    val codePoint = when {
        value.startsWith("x", ignoreCase = true) -> value.drop(1).toIntOrNull(16)
        value.all(Char::isDigit) -> value.toIntOrNull()
        else -> null
    } ?: return null
    return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
}
