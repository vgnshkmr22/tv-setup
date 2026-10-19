# Releasing the launcher

Pushing a tag `vMAJOR.MINOR.PATCH` runs [`.github/workflows/release.yml`](../.github/workflows/release.yml).
It builds the launcher, signs it with the release key, checks the signature, and attaches
`cleanyourtv-<version>.apk` and `update.json` to the GitHub release. Every TV on the official
channel picks it up within a week ([`docs/specs/self-update.md`](specs/self-update.md)).

```bash
git tag -a v3.0.0 -m "Self-update. Faster grid on 1 GB TVs."
git push origin v3.0.0
```

The first line of the release notes (or of the tag message, when no release exists yet) becomes
the note TVs show in the update prompt, so keep it to one plain sentence under 200 characters.
The version code is `major × 10000 + minor × 100 + patch` and has to go up, so minor and patch stay
below 100. Tags with a suffix (`v3.0.0-rc1`) are refused: TVs only ever get full releases.

If you'd rather write the notes in the web UI, create the release there with a new tag; the
workflow fills in the files and appends the APK's SHA-256 to the notes.

## The release key, once

Every official APK has to be signed with the same key, forever: Android refuses an update signed
with a different one, and a TV then needs a computer and `MIGRATE=1` to move on. Make the key on a
trusted machine:

```bash
keytool -genkeypair -v -keystore cleanyourtv-release.jks -alias cleanyourtv \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=CleanYourTV"
base64 -i cleanyourtv-release.jks | tr -d '\n' > cleanyourtv-release.jks.b64   # for the secret
keytool -list -v -keystore cleanyourtv-release.jks -alias cleanyourtv | grep SHA256   # the signer digest
```

Use the same password for the keystore and the key unless you have a reason not to.

## GitHub settings

1. **Settings → Environments → New environment `release`.** Add yourself (and any co-maintainer) as
   required reviewers, and limit deployment to tags matching `v*`. The workflow then waits for an
   approval before it can see the key.
2. **Environment secrets** in `release`, not repository secrets:

   | Secret | Value |
   | :- | :- |
   | `RELEASE_KEYSTORE` | the contents of `cleanyourtv-release.jks.b64` |
   | `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
   | `RELEASE_KEY_ALIAS` | `cleanyourtv` |
   | `RELEASE_KEY_PASSWORD` | the key password |

3. Then delete the `.b64` file, and the `.jks` too once it's backed up.

## Offline backup

Lose the key and every TV on the official channel is stuck on its version until someone migrates
it by hand. Keep two copies of `cleanyourtv-release.jks` and its passwords outside GitHub, for
example an encrypted USB drive and a password manager entry, and write down where they are for
whoever maintains the project next. Never commit the keystore, and never put it on a CI runner
other than through the `release` environment.

## Forks

A fork builds its own official channel the same way, with its own key: the workflow points the
feed at `https://github.com/<owner>/<repo>/releases/latest/download/update.json` for whichever
repository runs it. Its TVs never accept an APK from this repository, or the other way round.
