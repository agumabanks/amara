# Sanaa Agent Release Signing

## Keystore

- Location: `sanaa-agent.keystore` at the project root
- Alias: `sanaa`
- The keystore file is gitignored and must NOT be committed.

## Required environment variables

`app/build.gradle` reads both passwords from the environment at build time:

- `KEYSTORE_PASS`  — the keystore (store) password
- `KEY_PASS`       — the key (alias) password

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
