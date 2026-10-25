# Fonte

A small, private Android app updater. It installs and updates apps straight from their source:
GitHub, GitLab, Codeberg / Forgejo / Gitea, F-Droid and IzzyOnDroid.

Android 15 or later. Kotlin, Jetpack Compose, Material 3 Expressive. No native code, no WebView,
no dynamic code loading, so it runs with GrapheneOS "restrict dynamic code loading" (memory and
storage) turned on.

## Security

- HTTPS only, also after redirects. Only system CAs; a CA that the user or an admin adds is not trusted.
- The GitHub token is optional, encrypted with an Android Keystore key (StrongBox when present), and
  sent only to api.github.com. A redirect to another host drops it.
- Before an install, the APK must:
  - match the SHA-256 digest that GitHub publishes for the asset;
  - be a valid signed APK with native code for this device;
  - keep the package name the app had;
  - be signed with the pinned certificate. The pin is set at the first install or when an installed app
    is adopted, and follows APK signature v3 key rotation.
- Sessions request update ownership (Android 14): other installers need your consent to update apps
  that Fonte installed. The package source is "store", so Android's restricted settings do not apply.
- Release notes are plain text. Nothing is rendered as HTML.
- No analytics, no telemetry, no account, no backups (`allowBackup=false`, all data excluded from
  device transfer). MTE in sync mode where the hardware has it.

## Speed

- The network is the platform's HttpEngine (Cronet, Android 14+), with HTTP/3, HTTP/2 multiplexing
  and Brotli. It adds nothing to the APK.
- All apps are checked in parallel on shared connections. Each check sends the stored ETag. An
  unchanged source answers 304 with no body; with a GitHub token, a 304 also costs no rate limit.
- F-Droid and IzzyOnDroid are read through their per-package API, not the multi-megabyte index. For
  apps that have one build per ABI, a ranged request reads only the ZIP directory of each build to
  find the build for this device.
- Downloads are streamed, hashed on the fly, and copied into the install session with a kernel copy.
- The release APK is about 2.6 MB, shrunk with R8, with the Compose baseline profiles.

## Updates

A JobScheduler job checks every 3, 6, 12 or 24 hours. It runs only with a network, and only when the
battery is not low. For apps that Fonte installed, updates install silently once the app is not in
use (Android 14 install constraints). For apps that another installer owns, a notification opens the
system prompt; after that, Fonte owns them too.

## Import

Settings → Import reads an Obtainium export (any version) or a Fonte export. Installed apps are
adopted, not installed again. Fonte contacts no host while it imports. Entries from sources that
Fonte does not read (APKMirror, HTML pages, …) are skipped.

## Build

```zsh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Release signing: `scripts/make-release-key.sh` makes the key once. Its passwords go into the macOS
Keychain, and `keystore.properties` (not in git) names them.

## Licence

GPL-3.0-or-later.
