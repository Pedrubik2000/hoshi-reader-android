package moe.antimony.hoshi.features.yuurei

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults

private const val LogTag = "Yuurei"

/** Everything from '?' or '#' on is dropped: signed links and session data never reach logcat. */
private fun String?.stripQuery(): String = orEmpty().substringBefore('?').substringBefore('#')

/** scheme://host/path for http(s); scheme://host only for app links (intent:, discord:, market:). */
private fun Uri.forLog(): String =
    if (scheme == "http" || scheme == "https") "$scheme://$host${path.orEmpty()}".stripQuery() else "$scheme://${host.orEmpty()}"

private fun String?.forLog(): String = if (this == null) "null" else Uri.parse(this).forLog()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YuureiView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: YuureiViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var canGoBack by remember { mutableStateOf(false) }
    // ponytail: the page reloads on rotation; keep WebView state in a saved Bundle if that bothers.
    val currentOnClose by rememberUpdatedState(onClose)
    val webView = remember {
        WebView(context).apply {
            // Without this the WebView wraps its content and pages laid out with 100vh (Discord's login) render blank.
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            applyHoshiWebViewSecurityDefaults()
            settings.domStorageEnabled = true // the site's login session lives in local storage
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) // Discord captcha iframes
            Log.i(LogTag, "webview=${WebView.getCurrentWebViewPackage()?.versionName} ua=${settings.userAgentString}")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val branch = when {
                        !request.isForMainFrame -> "subframe"
                        // Caught before the WebView requests it, so the file is fetched once, by us.
                        isYuureiDownloadHost(url.host) -> "download"
                        url.scheme == "https" && isYuureiWebViewHost(url.host) -> "inside"
                        else -> "external"
                    }
                    Log.i(
                        LogTag,
                        "override ${url.forLog()} main=${request.isForMainFrame} gesture=${request.hasGesture()} " +
                            "redirect=${request.isRedirect} -> $branch",
                    )
                    return when (branch) {
                        "subframe", "inside" -> false
                        "download" -> {
                            viewModel.download(url.toString())
                            true
                        }
                        else -> {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, url))
                            } catch (_: ActivityNotFoundException) {
                                Log.w(LogTag, "no app for ${url.forLog()}")
                            }
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    Log.i(LogTag, "page started ${url.forLog()}")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(LogTag, "page finished ${url.forLog()}")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    Log.w(
                        LogTag,
                        "error ${error.errorCode} ${error.description?.toString().stripQuery()} " +
                            "main=${request.isForMainFrame} ${request.url.forLog()}",
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    Log.w(LogTag, "http ${errorResponse.statusCode} main=${request.isForMainFrame} ${request.url.forLog()}")
                }

                @SuppressLint("WebViewClientOnReceivedSslError") // logs, then keeps the default cancel
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    Log.w(LogTag, "ssl ${error.primaryError} ${Uri.parse(error.url).host}")
                    super.onReceivedSslError(view, handler, error)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.e(LogTag, "renderer gone didCrash=${detail.didCrash()}")
                    currentOnClose()
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.i(
                        LogTag,
                        "console ${message.messageLevel()} ${message.message().stripQuery().take(300)} " +
                            "@ ${message.sourceId().forLog()}:${message.lineNumber()}",
                    )
                    return true
                }
            }
            // Fallback when the link reaches the WebView as a download instead of a navigation.
            setDownloadListener { url, _, _, _, _ -> viewModel.download(url) }
            loadUrl(YuureiHomeUrl)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView.onPause()
                    CookieManager.getInstance().flush()
                }
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CookieManager.getInstance().flush()
            webView.destroy()
        }
    }

    BackHandler(enabled = canGoBack) { webView.goBack() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolve(context))
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.yuurei_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (state.activeDownloads > 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
