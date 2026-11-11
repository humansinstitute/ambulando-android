# Saving attachments on Android

In Files, open a file and choose **Save to Files**. Android asks for a destination and suggests the original display
name. Saving works before or after downloading into Ambulando’s private cache. It does not open a viewer, launch an
installer or require an app that accepts shared files. APKs use `application/vnd.android.package-archive`; generic
metadata falls back to a known filename extension or `application/octet-stream`.

The save action uses the existing signed Tower file-object API and checksum validation, then streams the verified
bytes unchanged into the document selected by the user. It does not grant Tower credentials to the destination.
Canceling the picker does not fetch bytes. Retrieval and provider failures appear in file details; a partially created
document is removed where the provider supports deletion. A provider that refuses deletion may leave a partial file.
No broad storage or package-install permission is needed.

**Download** retains its existing meaning: obtain a verified copy in Ambulando’s private cache. **Preview** shows images
and text locally, with **Open in…** as an explicit external-viewer action (including PDFs). **Share** opens Android’s
share sheet. Save remains available separately from these actions. The file’s server access checks still apply.

The synthetic emulator regression drives Files, cancels Android DocumentsUI before retrieval, then saves APK and binary
attachments through the real picker and compares all exported bytes and filenames. JVM tests cover picker MIME/title,
unchanged binary copying, unavailable destinations, write errors, visible cancellation/retrieval errors, signed retrieval,
cache reuse, changed-checksum refusal and removed access. These checks do not establish GrapheneOS or physical-device
behavior. Device/provider-specific validation remains necessary on the target phone.
