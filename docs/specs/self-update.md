# Self-update, spec 1

Today the launcher reaches a TV only through ADB: `build-and-install.sh` builds it on the owner's
computer, signs it with that computer's debug key and pushes it over the debug bridge. Updating it
means doing all of that again. This spec lets an installed launcher update itself from a release
feed, with no computer involved, in one of two modes the owner picks: **manual** (a "Check for
updates" row) or **weekly** (the launcher checks once a week and asks).

The launcher never installs anything without the owner pressing Update on the TV. The update is
verified three times: its SHA-256 against the feed, its signing key against the installed
launcher's, and Android's own same-key rule on install.

## Goals and non-goals

Goals:

- Update the launcher on a TV with no computer, no ADB and no cable after the first install.
- Two modes, `manual` and `weekly`, plus `off`, set in `config.xml` or the `config.json` overlay.
- Nothing runs in the background: no service, no boot receiver, no scheduled job. Checks happen
  only while the home screen is in front.
- A failed or interrupted update leaves the installed launcher as it was.

Non-goals:

- Updating the debloat state, firmware or any other app. The updater installs exactly one package:
  `com.gathin.tvhome`.
- Silent updates. The owner always presses Update; Android may add its own confirmation on top.
- Updating launchers built on the owner's own computer from the official feed. A self-built APK
  carries the owner's code changes and `config.xml`, and is signed with a key only that computer
  holds; the official APK can't install over it (see [Channels](#channels)).

## Channels

The feed URL and the expected signer are fixed at build time, as `BuildConfig` fields:

| Field | Official build (CI) | `build-and-install.sh` (default) |
| :- | :- | :- |
| `UPDATE_FEED` | `https://github.com/Gathin23/cleanyourtv/releases/latest/download/update.json` | empty |
| `UPDATE_SIGNER` | SHA-256 of the release certificate | empty |

- **Official channel.** CI builds the APK from a tag, signs it with the project's release key and
  publishes it with a feed file (see [Release pipeline](#release-pipeline)). These TVs self-update.
- **Self-built.** `UPDATE_FEED` is empty, the updater is compiled in but inert, and the "Check for
  updates" row says the launcher was built on a computer and is updated by running
  `build-and-install.sh` again. Nothing touches the network.
- **Forks.** A fork can set both fields through Gradle properties (`-PupdateFeed=… -PupdateSigner=…`)
  and run its own feed with its own key. The launcher doesn't know or care whose feed it is.

## Configuration

One new key, in `config.xml` and in the `config.json` overlay (`themes/README.md` gets a row):

| Key | Values | Default | Meaning |
| :- | :- | :- | :- |
| `self_update` | `off`, `manual`, `weekly` | `weekly` | `off`: never contact the feed; the row says updates are off. `manual`: only when the owner picks "Check for updates". `weekly`: as manual, plus an automatic check at most once every 7 days. |

An unknown value is treated as `manual`. With an empty `UPDATE_FEED` the key has no effect.

`PROMPT.md`'s checklist gets one question, next to the one about firmware updates: "Launcher
updates: weekly check, manual only, or off?".

## Feed

`update.json`, a release asset next to the APK, at most 4 KB, UTF-8 JSON:

```json
{
  "schema": 1,
  "package": "com.gathin.tvhome",
  "version_code": 30000,
  "version_name": "3.0",
  "min_sdk": 26,
  "apk_url": "https://github.com/Gathin23/cleanyourtv/releases/download/v3.0.0/cleanyourtv-3.0.apk",
  "apk_size": 251904,
  "apk_sha256": "9f2c…64 hex…",
  "notes": "Self-update. Faster grid on 1 GB TVs."
}
```

Validation is strict, in the style of [`card.md`](card.md): any violation means "no update
offered", with one log line under the tag `TvHomeUpdate`.

| Field | Rule |
| :- | :- |
| `schema` | Integer `1`. Anything else: ignored, so a future format can't confuse an old launcher. |
| `package` | Exactly `com.gathin.tvhome`. |
| `version_code` | Integer greater than the installed `versionCode`. Equal or lower: up to date. |
| `version_name` | 1 to 16 characters from `0-9 A-Z a-z . -`. Shown to the owner. |
| `min_sdk` | Integer. Above the TV's `Build.VERSION.SDK_INT`: no update offered, and the manual check says the TV is too old for the new version. |
| `apk_url` | `https`, on a host from the allowlist below. |
| `apk_size` | Integer, 1 byte to 20 MB. The download stops at this size. |
| `apk_sha256` | 64 lower-case hex characters. |
| `notes` | Optional, up to 200 characters, shown as plain text with control characters removed. |

**Host allowlist.** The feed and the APK are fetched over HTTPS only, following at most 5
redirects, each to a host on the list: `github.com` and the hosts GitHub serves release assets
from (`objects.githubusercontent.com`, `release-assets.githubusercontent.com`; to be confirmed
against a real redirect when this is implemented). A fork's build adds its feed host. The
launcher uses the system trust store only; Android 7+ doesn't trust user-added CAs in apps by
default, and the launcher doesn't opt in.

**Request.** `GET` with `User-Agent: CleanYourTV/<versionName>`, no cookies, no device or account
identifiers, no query string. Connect timeout 10 s, read timeout 15 s. GitHub sees the TV's IP
address, as with any download.

## Launcher behaviour

### When it checks

- **Weekly.** Every time the home screen comes to the front (`onResume`), when `self_update` is
  `weekly` and at least 7 days have passed since the last successful check, and the TV has a
  network connection, the launcher checks on a background thread. A failed check is retried
  after 24 hours instead of 7 days. A last-check time more than a day in the future (the TV's
  clock was wrong) counts as "never checked".
- **Manual.** Hold OK on the Settings pill. Today this opens the Module cards list; it becomes a
  small menu with two rows, "Module cards" and "Check for updates", the second showing the
  installed version (for example "Check for updates · 2.1"). OK on it checks right away, whatever
  the time since the last check, and shows the result in the row: "Up to date", "Version 3.0
  available", "Couldn't reach the update server", "Updates are off", or "Built on a computer:
  update with build-and-install.sh".

A check never blocks the UI, never runs while another app is in front, and only one check or
download runs at a time.

### Offering an update

When a check finds a newer version:

1. The Settings pill shows a small dot until the update is installed or skipped.
2. Once the grid is laid out, the home screen has focus, no menu is open and no module card prompt
   is pending, the launcher shows one prompt, styled like the card prompt: "CleanYourTV 3.0 is
   available", the notes, and three buttons: **Later** (focused first), **Update**, **Skip this
   version**. Back means Later.
3. Later: the dot stays; the prompt comes back at the next weekly check, not sooner. Skip this
   version: no prompt or dot for this `version_code`; a later version is offered as usual, and the
   manual check still offers the skipped one.
4. A manual check that finds an update shows the same prompt straight away.

### Downloading and checking the APK

After Update:

1. **Install permission.** If `canRequestPackageInstalls()` is false, the launcher explains why
   it needs "Install unknown apps" and opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for its
   own package. Many TVs don't have that screen; if it can't be opened, the prompt says the
   permission has to be granted once from a computer (`appops set com.gathin.tvhome
   REQUEST_INSTALL_PACKAGES allow`). The install script grants it at install time so this rarely
   shows (see [Install script](#install-script-and-migration)).
2. **Download** to `cacheDir/update/cleanyourtv.apk` with a progress line in the prompt. Back
   cancels and deletes the file. A download larger than `apk_size` is stopped and deleted.
3. **Verify**, before anything is handed to Android:
   - SHA-256 of the file equals `apk_sha256`;
   - `PackageManager.getPackageArchiveInfo` reads it as `com.gathin.tvhome` with the feed's
     `version_code`;
   - it has exactly one signer, and that signer's digest equals both `UPDATE_SIGNER` and the
     installed launcher's signer digest (the same digest code `Cards` uses for module cards).

   Any mismatch deletes the file, logs the reason and shows "The update didn't pass its checks and
   was not installed".

### Installing

1. Before the commit, the launcher stores the `version_code` it's installing and shows
   "Updating… Home restarts in a moment".
2. A `PackageInstaller` session (`MODE_FULL_INSTALL`, app package name set). On Android 12+ the
   session asks for `USER_ACTION_NOT_REQUIRED`: once the launcher installed the previous version
   itself, Android skips its own dialog; the first time (the previous version came from ADB),
   Android shows it.
3. The commit's result goes to a receiver that isn't exported. `STATUS_PENDING_USER_ACTION`
   starts the confirmation intent Android hands back. Any failure status shows its message (for
   example `INSTALL_FAILED_VERIFICATION_FAILURE` on China-market TCL sets that refuse every
   sideload) and the launcher stays as it was.
4. On success Android stops the launcher's process. The system restarts Home; the new version
   finds the stored `version_code` equal to its own, deletes the cache file and shows the toast
   "Updated to 3.0". A stored value that doesn't match means the update didn't land; the file is
   deleted and nothing is shown.

### What survives an update

An update keeps the package's data and permissions, so these carry over unchanged: the
`config.json` overlay and wallpaper (external files directory), `cards.json` (module card
decisions), the `ACCESS_FINE_LOCATION` grant, the screensaver choice, and the default Home
setting.

To be confirmed on test TVs before release (see [Testing](#testing)):

- **Accessibility services.** `build-and-install.sh` notes that Android switches them off on every
  reinstall. If an in-place update does the same, the launcher restores them on its first start
  after an update: it remembers which of its services were enabled before the commit and puts them
  back in `enabled_accessibility_services`. That needs `WRITE_SECURE_SETTINGS`, which the install
  script grants over ADB; without it the launcher shows how to switch them on in Settings.
- **Home preference on Google TV 14.** The install script exists partly because Android drops the
  Home preference when the set of Home apps changes. An in-place update shouldn't change that set,
  but this has to be checked on a Google TV with the stock launcher disabled, because the failure
  there is the setup wizard's crash loop.

### Stored state

`files/update.json` in private storage, written atomically, validated strictly on read (a bad file
is moved to `update.json.bad` and treated as empty): last successful check, last failed check,
skipped `version_code`, the `version_code` being installed. It never leaves the TV.

## Permissions

New in the manifest:

| Permission | Why |
| :- | :- |
| `INTERNET` | Fetch the feed and the APK, only from the allowlisted hosts and only from the updater code. |
| `REQUEST_INSTALL_PACKAGES` | Hand the verified APK to `PackageInstaller`. Also needs the owner's "Install unknown apps" approval, or the ADB grant. |

Only if the accessibility check above shows it's needed: `WRITE_SECURE_SETTINGS`. It's declared in
the manifest (so `pm grant` accepts it), but no app can ask the owner for it; only the install
script grants it, over ADB. It's used only for the accessibility restore.

This changes a promise in [`card.md`](card.md), Security: "has no network permission". That
sentence becomes: "The card code never uses the network. The launcher's only network use is the
updater, which talks to the update feed and nothing else, and only when `self_update` isn't `off`
and the build has a feed." A build with an empty `UPDATE_FEED` drops `REQUEST_INSTALL_PACKAGES` from
the merged manifest (a `tools:node="remove"` in a build-type manifest). `INTERNET` stays in every
build since the opt-in Bitcoin line (`btc_ticker`) needs it; with that line off, a self-built
launcher makes no network requests.

## Release pipeline

A GitHub Actions workflow on tags `v*`:

1. Builds `assembleRelease` with `-PupdateFeed` and `-PupdateSigner` set, `versionCode` taken from
   the tag and checked against the previous release (it has to go up).
2. Signs with the release keystore from repository secrets (`RELEASE_KEYSTORE` base64,
   `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`), APK Signature
   Scheme v2 and v3. v3 allows a future key rotation without every TV reinstalling over ADB.
3. Checks the result with `apksigner verify --print-certs` against `UPDATE_SIGNER`.
4. Writes `update.json` with the size and SHA-256 of the signed APK and the first line of the
   release notes, and attaches `cleanyourtv-<version>.apk` and `update.json` to the release.
   The release notes also list the SHA-256 for people who download the APK by hand.

The secrets live in a GitHub environment that needs a maintainer's approval to run. The keystore
has an offline backup: losing it means every TV on the official channel has to reinstall over ADB
to get further updates.

Pre-releases aren't served: `releases/latest/download/` always points at the latest full release.

## Install script and migration

`build-and-install.sh` (and `.ps1`) gain:

- `CHANNEL=official`: instead of building, download the latest official APK and `update.json`,
  check the SHA-256, then install as today. Without it the script builds locally as now.
- On every install: `appops set com.gathin.tvhome REQUEST_INSTALL_PACKAGES allow` and
  `pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS`, both best effort.

Moving a TV from a self-built launcher to the official channel means one last ADB session,
because the signing key changes and Android only accepts that through an uninstall:

1. Turn the local `config.xml` into a `config.json` overlay (every `config.xml` key already has an
   overlay key) and keep any pushed theme.
2. Enable the stock launcher, so the TV is never without a Home app.
3. Uninstall `com.gathin.tvhome`. This removes its data: the overlay and the module card decisions
   go with it; the owner answers the card prompts again.
4. Install the official APK, push the overlay back, then run the existing order: start the new
   launcher, disable the stock one, set the Home activity, check where Home resolves.

The script does this as `CHANNEL=official MIGRATE=1`, prints what it will remove and asks before
step 3.

## Failure handling

| Situation | Result |
| :- | :- |
| No network, DNS failure, timeout, HTTP error | Check fails quietly (weekly) or says so (manual); retried after 24 h. |
| Feed doesn't parse or breaks a rule | No update offered; one log line. |
| Download interrupted or cancelled | Partial file deleted; launcher unchanged. |
| Hash, package, version or signer mismatch | File deleted; "didn't pass its checks"; launcher unchanged. |
| "Install unknown apps" not allowed and no screen to allow it | Prompt shows the ADB command. |
| Android refuses the install (verification, storage, downgrade) | Android's message shown; launcher unchanged. |
| Owner declines Android's confirmation | Same as Later. |
| The updater code throws | Updates switch off for the session with one log line; Home behaves as with `self_update` `off`, as card code does today. |

## Security

- **What an attacker needs to install code: the release key.** A changed feed, a hijacked
  redirect or a swapped release asset fails the SHA-256 or signer check, and failing those,
  Android's own rule that an update must carry the installed app's key. Control of the GitHub
  account alone lets someone withhold updates or offer an old version, not install one: Android
  refuses a lower `versionCode`.
- **The release key is the trust anchor.** Kept as a CI secret behind a protected environment,
  with an offline backup, never on a developer laptop.
- **Bounded input.** Feed 4 KB, APK 20 MB, 5 redirects, timeouts on every request, HTTPS only, a
  fixed host list. Notes are plain text with a length limit and never rendered as HTML or links.
- **Owner in control.** Nothing installs without Update; `off` means no network traffic at all;
  self-built launchers can't install packages at all.
- **Privacy.** A weekly request to GitHub with a version in the User-Agent and nothing else. No
  identifiers, no telemetry, no reporting of results.

## Testing

Unit tests (JVM, no device) for the feed parser and its limits, the next-check calculation (clock
in the future, failure backoff, skipped version) and the `update.json` state file.

On TVs, before the first release with the updater:

| TV | What to check |
| :- | :- |
| Android 8 (API 26) | Install permission flow, signer read through the old API. |
| Android 9 to 11 | Full update; Android's confirmation shown on the first update. |
| Android 12+ | Second update with no Android dialog (`USER_ACTION_NOT_REQUIRED`). |
| Google TV 14, stock launcher disabled | Home still resolves to the launcher after the update; no setup-wizard crash loop. |
| TV with `INPUT_KEY_SERVICE` / `HOME_KEY_SERVICE` on | Services still on after the update, or restored. |
| TV without the "Install unknown apps" screen | ADB-granted appop is enough; message when it's missing. |
| China-market TCL | `INSTALL_FAILED_VERIFICATION_FAILURE` reported, launcher unchanged. |

Plus the negative cases with a local feed: wrong hash, wrong signer, lower version, oversized APK,
`schema: 2`, a redirect to a host off the list.

## Rollout

1. Release key, CI workflow, `update.json`; a release with the APK but no updater yet, so the
   official channel exists and `CHANNEL=official` can install it.
2. The updater in the launcher, behind `self_update`, and the Settings pill menu.
3. Install script: `CHANNEL=official`, the grants, `MIGRATE=1`.
4. Docs: README (Quick start and the FAQ entry about ADB), `themes/README.md`, `PROMPT.md`,
   `card.md`'s Security section, `TROUBLESHOOTING.md` for the failure messages.

## Open questions

- Default for `self_update` on the official channel: `weekly` (this spec) or `manual`, for owners
  who'd rather their TV make no requests at all unless asked.
- Who holds the release key, and where its offline backup lives.
- Whether to mirror `update.json` on `cleanyour.tv`, so the feed URL doesn't depend on the GitHub
  repository name (it was renamed once already, on 19 October 2026).
