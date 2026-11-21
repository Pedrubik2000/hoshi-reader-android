package moe.antimony.hoshi.features.yuurei

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.bookshelf.BookshelfRepository
import moe.antimony.hoshi.ui.UiText
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

internal data class YuureiUiState(
    val activeDownloads: Int = 0,
    val message: UiText? = null,
)

@HiltViewModel
internal class YuureiViewModel @Inject constructor(
    private val repository: BookshelfRepository,
    @param:CacheDir private val cacheDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val _uiState = MutableStateFlow(YuureiUiState())
    val uiState: StateFlow<YuureiUiState> = _uiState

    // Signed links are single-use per click; both WebView hooks call download(), so a URL seen once is never fetched again.
    private val handledUrls = mutableSetOf<String>()
    private val importMutex = Mutex()

    fun download(url: String) {
        if (!handledUrls.add(url)) return
        val name = yuureiEpubFileName(url)
        if (name == null) {
            showMessage(UiText.Resource(R.string.yuurei_epub_only))
            return
        }
        _uiState.update { it.copy(activeDownloads = it.activeDownloads + 1) }
        // App scope: closing the screen must not throw away a download the site already counted.
        appScope.launch {
            val file = File(File(cacheDir, "yuurei"), name)
            var fetched = false
            val message = try {
                importMutex.withLock {
                    withContext(ioDispatcher) { fetchOnce(url, file) }
                    fetched = true
                    if (!withContext(ioDispatcher) { file.isZip() }) {
                        UiText.Resource(R.string.yuurei_epub_only)
                    } else {
                        repository.importBook(Uri.fromFile(file))
                        UiText.Resource(R.string.yuurei_imported_format, name.dropLast(".epub".length))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                UiText.Resource(if (fetched) R.string.bookshelf_import_failed else R.string.yuurei_download_failed)
            } finally {
                withContext(NonCancellable + ioDispatcher) { file.delete() }
                _uiState.update { it.copy(activeDownloads = it.activeDownloads - 1) }
            }
            showMessage(message)
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun showMessage(message: UiText) {
        _uiState.update { it.copy(message = message) }
    }
}

/** One GET, no cookie, no redirect follow, no retry of our own. Errors never carry the signed URL. */
private fun fetchOnce(url: String, target: File) {
    target.parentFile?.mkdirs()
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
    } finally {
        connection.disconnect()
    }
}

private fun File.isZip(): Boolean {
    val header = ByteArray(4)
    val read = inputStream().use { it.read(header) }
    return read == 4 && header.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
}
