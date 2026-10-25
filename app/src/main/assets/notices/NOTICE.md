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
  driver (Apache 2.0; SQLite itself is public domain).
