# Dependency and code-scanning policy

Applies to `schism-android`, `schism-backend`, and the GitHub Actions used by both.
Enforced by `.github/dependabot.yml`, `.github/workflows/security.yml`, and `.github/CODEOWNERS`.

## Severity and response windows

| Severity of the advisory | Action | Window |
| --- | --- | --- |
| Critical | Patch, or disable the affected code path, then ship a hotfix release | 48 hours from triage |
| High | Patch on `main`; ships in the next release | 7 days |
| Moderate | Patch on `main` | 30 days |
| Low / informational | Batched into the weekly Dependabot group | Next release |

The window starts when the advisory becomes visible to us (Dependabot alert, CodeQL alert,
`govulncheck` failure in the backend workflow, or a report to the support address). A high or
critical finding with no available patch is tracked as a release blocker with a written mitigation,
not silently deferred.

`actions/dependency-review-action` fails a pull request on `high` or above, so a new dependency
carrying a known high-severity advisory cannot be merged at all.

## Review requirements

The following changes require an explicit owner review — they are listed in `.github/CODEOWNERS`
so GitHub requests that review automatically:

- Release signing: `keystore.properties` handling, `signingConfigs`, anything reading
  `RELEASE_KEYSTORE_*` secrets, and `.github/workflows/release.yml`.
- Authentication and session handling: `schism-backend/internal/api`, `internal/config`
  (token keys, public URLs, feature toggles).
- Cryptography: `golang.org/x/crypto`, token signing/verification, and any hashing used for
  OCR model integrity.
- Android manifest and permissions: `app/src/main/AndroidManifest.xml` (SMS, network, and
  foreground-service declarations in particular).
- OCR model sources: `schism-backend/internal/modelcatalog`, the model download/verification path
  in the app, and any change that would place model bytes back inside a build artifact.
- Database schema: `schism-backend/internal/store/migrations`.

## Allowed dependency sources

- Android: `google()` and `mavenCentral()` only, declared centrally in
  `schism-android/settings.gradle.kts` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`. Versions are
  pinned in `gradle/libs.versions.toml`. No snapshot, no dynamic (`+`, `latest.release`), no
  custom Maven URL, no committed binary AAR/JAR.
- Backend: the public Go module proxy with `go.sum` verification. No `replace` directives pointing
  outside the repository.
- Actions: pinned by major version, first-party (`actions/*`, `github/codeql-action`) or
  `gradle/actions`. A new third-party action needs an owner review before use.
- OCR models are not dependencies of any build. They are downloaded at runtime from the backend
  catalog and verified by hash; `tools/release/verify_android_artifacts.sh` fails the release if
  model bytes appear in an artifact.

## Automated updates never auto-merge

Auto-merge is not enabled on this repository. Dependabot groups are arranged so the risky bumps
arrive in their own pull request, and these categories always require a human read of the changelog
plus a passing Android/backend workflow before merge:

- release signing and anything touching the upload key,
- Play services / Play Core / Play Billing,
- ONNX Runtime and OpenCV (native code loaded into the app process),
- Room, DataStore, and backend database drivers or migration tooling,
- authentication, token, and crypto libraries.

Everything else may be merged on a green build once the diff has been read.

## Code scanning and secret scanning

- CodeQL runs for `go` and `java-kotlin` on pull requests, pushes to `main`, and weekly.
  `security-events: write` is granted to the CodeQL job only.
- `contents: write` is granted only to the Gradle dependency-submission job, and only on pushes to
  `main`; pull requests from forks never receive it.
- The backend workflow runs `govulncheck` against the live Go vulnerability database on every run.
- GitHub secret scanning with push protection must stay enabled for this repository (a repository
  setting, not a workflow). If push protection blocks a commit, rotate the credential — do not
  bypass the block. The release signing key and its passwords exist only as repository secrets and
  in the owner's offline backup; they are never written to the repository, and CI writes them to
  `$RUNNER_TEMP` only.
- Pull-request builds sign with an ephemeral keystore generated on the runner, so a compromised
  pull request cannot produce an artifact that installs over a production install.
