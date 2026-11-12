# Notices

## Source code

The source code in this repository is released under the MIT licence in [LICENSE](LICENSE).

## Ambulando brand artwork (not covered by the MIT licence)

The Ambulando name and the "perspective passage" artwork — the app icon (`app/src/main/res/mipmap-*`), the in-app
mark (`app/src/main/res/drawable-nodpi/passage_mark.png`) and the store listing icon — are the project owner's brand
assets. They are used in this app with the owner's permission and are **not** licensed under the MIT licence or any
other open licence. Forks and derivative apps must replace them with their own artwork.

The icons are proportional raster resamples of the approved master artwork on its original white backing; no geometry
or colour was redrawn, and non-pixel PNG metadata was removed.

## Bundled third-party components

- The embedded board and document editors: see [web-editors/LICENSES.md](web-editors/LICENSES.md) (MIT and SIL Open
  Font License 1.1 components) and [web-editors/web/sol/PROVENANCE.md](web-editors/web/sol/PROVENANCE.md).
- Android and Kotlin libraries resolved by Gradle (see `gradle/libs.versions.toml`), each under its own licence:
  AndroidX and Jetpack Compose (Apache 2.0), Kotlin standard library, kotlinx.coroutines and kotlinx.serialization
  (Apache 2.0), OkHttp (Apache 2.0), ACINQ secp256k1-kmp with libsecp256k1 (Apache 2.0 / MIT), the bundled SQLite
  driver (Apache 2.0; SQLite itself is public domain), and Bouncy Castle 1.86 (Bouncy Castle Licence,
  https://www.bouncycastle.org/licence.html), used for the NIP-44 ChaCha20 stream primitive.

- UnifiedPush Android connector 3.3.5 and Google Tink cryptography (Apache 2.0), used for local RFC8291 Web Push decryption. No Firebase or Play Services SDK is bundled. Upstream: https://codeberg.org/UnifiedPush/android-connector and https://github.com/tink-crypto/tink-java .

## Garmin companion integration

The phone companion includes Garmin Connect IQ Companion App SDK 2.2.0
(`com.garmin.connectiq:ciq-companion-app-sdk`), copyright Garmin International Ltd.,
under the Garmin CONNECT IQ SDK License Agreement and CONNECT IQ Application
Developer Agreement specified by its Maven POM. This dependency is not covered
by Ambulando's MIT license. Terms: https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html .
The watch app source is Ambulando code; Garmin SDK tools and device definitions
are obtained separately from Garmin and are not included in the source export.
