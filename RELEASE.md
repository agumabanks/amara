# Sanaa Agent Release Signing

## Keystore

- Location: **outside the working tree**, at `/root/.secrets/sanaa-agent.keystore`
  (mode `0600`). Override with the `SANAA_KEYSTORE_PATH` environment variable.
- Alias: `sanaa`
- The keystore is not in the repository and must NOT be committed.

A copy used to sit at the project root as `sanaa-agent.keystore` with mode `0644`,
readable by any local user and process. It was moved to the path above after its
SHA-256 was verified unchanged, and `app/build.gradle` now resolves the path from
`SANAA_KEYSTORE_PATH` with that secure default. If you are building on a different
host, export `SANAA_KEYSTORE_PATH` to wherever you keep the key.

## Required environment variables

`app/build.gradle` reads both passwords from the environment at build time:

- `KEYSTORE_PASS`  — the keystore (store) password
- `KEY_PASS`       — the key (alias) password
- `SANAA_KEYSTORE_PATH` — optional; keystore location. Defaults to
  `/root/.secrets/sanaa-agent.keystore`.

Set them before invoking a release build:

```
export KEYSTORE_PASS='...'
export KEY_PASS='...'
./gradlew :app:assembleRelease
```

If either variable is missing, the Gradle signing block evaluates to `null`
and `assembleRelease` fails with a keystore error. Do not hard-code the
passwords in `build.gradle`, CI logs, or any committed file.

## Moving the keystore out of source control

For any commercial push, move the keystore to a secure location outside
the repository (secrets manager, encrypted volume, hardware token). The
Gradle `signingConfigs.release.storeFile` path in `app/build.gradle` is
the only reference that must be updated; everything else is unchanged.

## Play App Signing

Google Play App Signing is strongly recommended for the commercial push:

1. Generate the upload keystore locally and use it only for upload.
2. Enroll the app in Play App Signing in the Play Console.
3. Export the upload key's public certificate and upload it during
   enrollment so Play can verify signed APKs / AABs.
4. Store the upload keystore in the team's secrets manager, never in
   the repository.

For AAB delivery run `./gradlew :app:bundleRelease` instead of
`assembleRelease` once the upload key is in place.
