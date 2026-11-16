package studio.otherstuff.ambulando.core.transport

import org.junit.Assert.*
import org.junit.Test

class FipsProviderPinTest {
    private val pkg = "com.wingmanbefree.wingman_app"
    private val cert = "6c0da09b9e2375d657645f197419be7b8227a7434e0225512fc760cedf383c8c"
    private val pin = FipsProviderPin(pkg, cert)
    @Test fun productionPackageAndCurrentSignerMustMatchExactly() {
        assertTrue(pin.matches(pkg, listOf(cert), true))
        assertFalse(pin.matches(pkg + ".nightly", listOf(cert), true))
        assertFalse(pin.matches(pkg, listOf("00".repeat(32)), true))
        assertFalse(pin.matches(pkg, listOf(cert, "00".repeat(32)), true))
        assertFalse(pin.matches(pkg, emptyList(), true))
        assertFalse(pin.matches(pkg, listOf(cert), false))
    }
    private val nightly = FipsProviderPin("$pkg.nightly", "5edec981796544b29118b53d424ed5067b54cbb0b871d5c37b1cc9ccbc807be0")
    private val pins get() = listOf(pin, nightly)
    private fun identity(p: FipsProviderPin) = FipsProviderIdentity(p.packageName, p.authority, listOf(p.certificateSha256), true)
    @Test fun eachIndependentlyEnrolledProviderCanBeSelectedAlone() {
        for (p in pins) assertEquals(p, FipsProviderRoster.select(pins, listOf(identity(p))))
    }
    @Test fun bothTrustedProvidersAreAmbiguousRegardlessOfOrder() {
        for (order in listOf(pins, pins.reversed())) {
            val error = runCatching { FipsProviderRoster.select(order, pins.map(::identity)) }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("Keep only the intended"))
        }
    }
    @Test fun missingWrongCertificateAuthorityAndDebugAreNeverTrusted() {
        val bad = listOf(
            identity(pin).copy(currentSigners = listOf(nightly.certificateSha256)),
            identity(nightly).copy(currentSigners = listOf(cert)),
            identity(pin).copy(authority = nightly.authority),
            identity(pin).copy(packageName = "$pkg.debug"),
            identity(pin).copy(exported = false),
            identity(pin).copy(currentSigners = listOf(cert, nightly.certificateSha256)))
        for (actual in bad) assertTrue(runCatching { FipsProviderRoster.select(pins, listOf(actual)) }.isFailure)
        assertTrue(runCatching { FipsProviderRoster.select(pins, emptyList()) }.isFailure)
        assertEquals(nightly, FipsProviderRoster.select(pins, listOf(bad.first(), identity(nightly))))
    }
}
