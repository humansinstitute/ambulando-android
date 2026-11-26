package studio.otherstuff.ambulando.app.browser

import android.content.Context
import studio.otherstuff.ambulando.core.browser.NIP07Grant
import studio.otherstuff.ambulando.core.browser.NIP07Grants

/** Tiny local capability store. Synchronous commit orders grant/revoke writes before returning to the broker. */
class BrowserConsentPersistence(context: Context, accountNpub: String) {
    private val preferences = context.getSharedPreferences("browser-operation-consent", Context.MODE_PRIVATE)
    private val key = "${NIP07Grants.STATE_KEY}:$accountNpub"
    fun load(): Set<NIP07Grant> = NIP07Grants.decode(preferences.getString(key, null))
    fun save(grants: Set<NIP07Grant>) {
        check(preferences.edit().putString(key, NIP07Grants.encode(grants)).commit()) { "Browser permission could not be saved" }
    }
}
