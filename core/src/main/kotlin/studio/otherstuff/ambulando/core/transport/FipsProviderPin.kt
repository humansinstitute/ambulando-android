package studio.otherstuff.ambulando.core.transport

/** Explicit reviewed current signer; no package-name, history or debug trust fallback. */
data class FipsProviderPin(val packageName: String, val certificateSha256: String) {
    init {
        require(packageName == "com.wingmanbefree.wingman_app" || packageName == "com.wingmanbefree.wingman_app.nightly")
        require(certificateSha256.matches(Regex("[0-9a-f]{64}")))
    }
    val authority: String get() = "$packageName.fips.shared"
    fun matches(actualPackage: String, currentSigners: List<String>, exported: Boolean): Boolean =
        exported && actualPackage == packageName && currentSigners.size == 1 && currentSigners.single() == certificateSha256
}

/** Authority and current signer are independently checked for each enrolled package. */
data class FipsProviderIdentity(val packageName: String, val authority: String, val currentSigners: List<String>, val exported: Boolean)

object FipsProviderRoster {
    fun select(pins: List<FipsProviderPin>, installed: List<FipsProviderIdentity>): FipsProviderPin {
        val trusted = pins.filter { pin -> installed.any { actual ->
            actual.authority == pin.authority && pin.matches(actual.packageName, actual.currentSigners, actual.exported)
        } }.distinct()
        require(trusted.isNotEmpty()) { "provider missing or its current signing certificate is not enrolled" }
        require(trusted.size == 1) {
            "Both trusted Nostr Browser providers are installed. Keep only the intended stable or nightly provider installed, then restart Ambulando and verify the connection again"
        }
        return trusted.single()
    }
}
