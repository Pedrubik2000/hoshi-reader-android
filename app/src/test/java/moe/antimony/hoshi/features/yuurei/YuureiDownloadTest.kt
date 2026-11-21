package moe.antimony.hoshi.features.yuurei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YuureiDownloadTest {
    @Test
    fun acceptsOnlyEpubLinksOnTheDownloadHostAndNamesThemSafely() {
        val link = "https://dl.yuurei.moe:31316/downloads/objects/ab/0123abcd.epub"
        assertEquals(
            "Re:ゼロ 1.epub",
            yuureiEpubFileName("$link?md5=x&expires=1&filename=Re%3A%E3%82%BC%E3%83%AD%201.epub"),
        )
        assertEquals("0123abcd.epub", yuureiEpubFileName("$link?md5=x&expires=1"))
        assertEquals("evil.epub", yuureiEpubFileName("$link?filename=..%2F..%2Fevil.epub"))
        assertEquals("あ".repeat(66) + ".epub", yuureiEpubFileName("$link?filename=${"あ".repeat(100)}.epub"))

        assertNull(yuureiEpubFileName("$link?filename=book.pdf"))
        assertNull(yuureiEpubFileName("$link?filename=.epub"))
        assertNull(yuureiEpubFileName("https://dl.yuurei.moe/downloads/objects/ab/0123abcd.cbz?filename=a.epub"))
        assertNull(yuureiEpubFileName("https://yuurei.moe/downloads/objects/ab/0123abcd.epub"))
        assertNull(yuureiEpubFileName("https://dl.yuurei.moe.example.com/a.epub"))
        assertNull(yuureiEpubFileName("http://dl.yuurei.moe/a.epub"))
    }
}
