package moe.antimony.hoshi.features.wordcolors

import org.junit.Assert.assertEquals
import org.junit.Test

class WordColorsKeysTest {
    @Test
    fun japaneseWordFieldsGiveSpellingAndReadingKeys() {
        assertEquals(listOf("日本語\tにほんご", "日本語"), WordColors.japaneseKeys(mapOf("Word" to "日本[にほん] 語[ご]:0-")))
        assertEquals(listOf("届く\tとどく", "届く"), WordColors.japaneseKeys(mapOf("Word" to "<b>届[とど]く</b>")))
        assertEquals(listOf("みんな\tみんな", "みんな"), WordColors.japaneseKeys(mapOf("Word" to "みんな:3-")))
        assertEquals(listOf("上手\tじょうず", "上手"), WordColors.japaneseKeys(mapOf("Word" to "上手", "Reading" to "ジョウズ")))
        // Hoshi's lookup gives the same key for the dictionary form.
        assertEquals("上手\tじょうず", WordColors.japaneseKey("上手", "じょうず"))
    }

    @Test
    fun spacedWordFieldsGiveTheWrittenForm() {
        assertEquals(listOf("ist"), WordColors.spacedKeys(mapOf("Word" to "Ist[→ sein]")))
        assertEquals(listOf("wenn's"), WordColors.spacedKeys(mapOf("Word" to "Wenn&#39;s")))
        assertEquals(emptyList<String>(), WordColors.spacedKeys(mapOf("Sentence" to "x")))
    }
}
