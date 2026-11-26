package studio.otherstuff.ambulando.core.onboarding

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import studio.otherstuff.ambulando.core.transport.TowerError
import studio.otherstuff.ambulando.core.transport.TowerOrigin

/**
 * A capped-group invitation code: a bearer secret. Its text never appears in [toString], logs, errors, diagnostics or
 * analytics. The raw value is read only for the create-intent body and the device-only draft.
 */
class OnboardingInviteCode private constructor(val raw: String) {
    override fun toString() = "inv_••••"
    override fun equals(other: Any?) = other is OnboardingInviteCode && other.raw == raw
    override fun hashCode() = raw.hashCode()

    companion object {
        private val SHAPE = Regex("^[A-Za-z0-9_-]+$")

        /** NStack issues `inv_` + base64url; the server accepts 8..200 characters. */
        fun of(text: String): OnboardingInviteCode? {
            val value = text.trim()
            return if (value.startsWith("inv_") && value.length in 8..200 && SHAPE.matches(value)) OnboardingInviteCode(value) else null
        }
    }
}

/**
 * What a pasted invitation contains. NStack defines no invitation link format (only the code), so a pasted https link is
 * accepted only as a claim about which Tower to use: the origin is a suggestion the person must confirm, and it is checked
 * against that Tower's own descriptor before anything is signed. Nothing is ever sent to it automatically.
 */
class OnboardingInviteInput(val code: OnboardingInviteCode, val suggestedOrigin: TowerOrigin?) {
    override fun toString() = "OnboardingInviteInput(${suggestedOrigin ?: "code only"})"

    companion object {
        fun parse(text: String): OnboardingInviteInput? {
            val value = text.trim()
            OnboardingInviteCode.of(value)?.let { return OnboardingInviteInput(it, null) }
            val url = value.takeIf { it.startsWith("https://", ignoreCase = true) }?.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val fragmentItems = url.fragment?.split('&')?.mapNotNull { pair ->
                pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") }
            } ?: emptyList()
            val queryItems = url.queryParameterNames.flatMap { name -> url.queryParameterValues(name).map { name to (it ?: "") } }
            val invites = (queryItems + fragmentItems).filter { it.first == "invite" }
            if (invites.size != 1) return null
            val code = OnboardingInviteCode.of(invites[0].second) ?: return null
            val origin = try { TowerOrigin("https://${url.host}" + if (url.port != 443) ":${url.port}" else "") } catch (_: TowerError) { return null }
            return OnboardingInviteInput(code, origin)
        }
    }
}
