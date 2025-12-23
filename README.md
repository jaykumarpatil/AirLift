# AirLift — CI release setup

This project contains a GitHub Actions workflow to build release APKs and create GitHub Releases.

## Sealed keystore & secrets (how to add)

To sign APKs in CI you can supply a JKS keystore as a base64-encoded secret and the related passwords.

Secrets used by the workflow:

- `KEYSTORE_BASE64` — base64 (single-line) contents of your `release-keystore.jks` file.
- `KEYSTORE_PASSWORD` — the password for the keystore.
- `KEY_ALIAS` — the key alias inside the keystore.
- `KEY_PASSWORD` — the password for the key alias.

If you don't provide `KEYSTORE_BASE64`, the workflow will still build the APK but will not sign it with your keystore.

### Create the base64 keystore (macOS / Linux)

1. On macOS (to create a single-line base64 payload):

```bash
base64 release-keystore.jks | tr -d '\n' > keystore.b64
```

2. On Linux (to create a single-line base64 payload):

```bash
base64 -w 0 release-keystore.jks > keystore.b64
```

3. Copy the single-line content and add it as the `KEYSTORE_BASE64` secret in your GitHub repo settings.

### Add secrets with GitHub CLI (example)

```bash
gh secret set KEYSTORE_BASE64 --body "$(cat keystore.b64)"
gh secret set KEYSTORE_PASSWORD --body "your_keystore_password"
gh secret set KEY_ALIAS --body "your_key_alias"
gh secret set KEY_PASSWORD --body "your_key_password"
```

Alternatively, open the repository Settings → Secrets → Actions → New repository secret and paste the values.

Important: never commit your `.jks` file or passwords into the repository.

## How the workflow signs the APK

- The workflow decodes `KEYSTORE_BASE64` into `release-keystore.jks` and uses the `apksigner` from the Android build-tools to sign the generated APK.
- The workflow will also attempt to pass signing properties to Gradle if a keystore file exists.

## Triggering a release

- Push a tag like `v1.0.0` and the workflow will build the APK and create a GitHub Release with the generated APK attached.

## Troubleshooting

- If the workflow fails to find `apksigner`, ensure `build-tools` version in `.github/workflows/android-release.yml` matches a valid build-tools package or install the required build-tools in the workflow.
