package studio.otherstuff.ambulando.app.web

import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** The device's WebView cannot provide what a web surface needs; the screen shows [message] instead of the surface. */
class WebSurfaceUnavailable(message: String) : Exception(message)

/**
 * Separate WebView profiles (cookies, storage, cache, service workers) per kind of web content, the analogue of the
 * iPhone app's separate website data stores: the in-app browser keeps its own persistent profile, and the embedded
 * editors and HTML artefacts never share it.
 *
 * Isolation fails closed: on a WebView without multi-profile support no web surface is created at all (the caller
 * shows [UNSUPPORTED_MESSAGE] with what to update). Sharing the default profile and clearing it afterwards would not be
 * isolation, so it is never done.
 */
object WebProfiles {
    const val BROWSER = "browser"
    const val EDITORS = "editors"
    const val ARTEFACTS = "artefacts"

    const val UNSUPPORTED_MESSAGE = "This part of Ambulando needs a newer Android System WebView, which keeps websites, " +
        "editors and artefacts separate from each other. Update Android System WebView (or Chrome) from your app store " +
        "and open Ambulando again."

    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Must be called before the view loads anything. Throws [WebSurfaceUnavailable] when isolation is impossible. */
    fun assign(view: WebView, name: String) {
        if (!supported) throw WebSurfaceUnavailable(UNSUPPORTED_MESSAGE)
        ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(view, name)
    }

    /** Deletes a profile's cookies and storage (no live view may be using it). */
    fun clear(name: String) {
        if (!supported) return
        runCatching { ProfileStore.getInstance().deleteProfile(name) }
    }
}
