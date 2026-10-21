# Upstream core integration

Upstream: https://github.com/stupside/castor. Android: https://github.com/kdpkke/castor-android.

## Ownership and baseline

`android/`, `cmd/castor-android` and the two Android workflow files belong to this community project. Existing Go packages, generated contracts and the Whisper pin retain their upstream layout and history. The module remains `github.com/stupside/castor` so Go's internal-import rules work without copying or exposing private service implementations.

`android/upstream.json` records the integrated stable tag and immutable commit: v2.0.0 (`dc591b4`). The initial Android snapshot tracked v1.9.0 and also contained unreleased changes through `af8c784`. The 2.0.0 integration replaces Android's earlier discovery, FFmpeg-path, capability-URL, live-range, PNG/HLS and DLNA patches with the released upstream implementations. Android's platform-specific media adaptation selects its system font for subtitle burn-in; `cmd/castor-android` composes the native host. The temporary fixes described below are also retained pending upstream review. Android versions are independent, starting at 1.0.0 with tags such as `android-v1.0.0`.

## Automatic updates

The `upstream-releases.yml` workflow runs at minute 23 every six hours (UTC) and supports Run workflow. It pages through GitHub's published releases, ignores drafts/prereleases/non-version tags and selects the lowest stable version above the recorded tag. Branch commits and standalone tags without a published release never trigger an update. Historical `castor-vX.Y.Z` and `castor-X.Y.Z` tags are recognized. Several waiting releases are integrated over successive runs.

1. Verify that the recorded upstream tag has not moved.
2. Fetch the selected upstream tag and resolve it to a commit; create or reuse `upstream-release/<tag>` and merge main and that release, preserving history.
3. On a conflict, open one issue identifying the release and paths. Do not change main or choose ours/theirs automatically. The runner's incomplete merge is disposable.
4. Record the release SHA in `android/upstream.json`, push the update branch without force and open/reuse its PR.
5. Call the reusable Android validation workflow explicitly. A PR/push created with `GITHUB_TOKEN` does not reliably start unattended downstream CI; no personal access token is needed for this design.
6. Require synchronization tests, Go tests/vet/race checks, both Android native ABI builds, 10+ JVM tests, lint and the complete 28+ device-test suite on Android 17 with 16 KB pages. Tests run with read-only repository permissions and no signing secrets.
7. Reject automatic integration of `.github/`, updater or device-validation script changes. Otherwise, recheck the PR/head and original main SHA, then fast-forward main only to the exact tested commit. A concurrent main/branch change stops integration. No reset, rebase, force-push or deletion is used.

Checks failing leave the PR open. Conflicts produce an issue. A manually closed update PR pauses that release until reopened. The workflow does not close user issues or send messages to the upstream maintainer. Source integration does not publish an Android release.

The six-hour check is not a release-event subscription to another repository. Once a release is detected, the workflow commits its integration automatically and validates it before advancing main. Incompatible changes and changes to automation still require repair/review; an arbitrary future major release cannot be promised to work without Android changes.

Android compilation depends on `:client:bufGenerate`: each changed contract regenerates the official Connect-Kotlin client from the same checkout used to build the engine. Java message generation uses those same Protobuf sources. Generated clients are build outputs rather than merged snapshots. Generation updates the communication code; new UI controls or incompatible contract changes still require Android development and tests. The current core source/history integration remains in place; adopting Buf alone does not extract the Android Go host into a separate dependency.

The WebView bridge emits Castor's canonical HLS identity, `application/x-mpegURL`, in `Stream.content_type`. HTTP response MIME headers can still use `application/vnd.apple.mpegurl`.

Two temporary core fixes accompany the 2.0.0 integration, proposed upstream in stupside/castor#71: normalize HLS MIME aliases and parameters before inspection, keep a recognized MIME type authoritative over a misleading extension, sniff unknown types, and preserve the canonical segmented identity through ranking, including failed measurements; exclude the attempt's cancellation cause from cleanup failures and filter interrupted output I/O before recording a spool producer failure, while retaining independent errors and marking the truncated spool as interrupted. Without these, captured PNG/HLS sources can be rejected, and a successful Stop can be reported as `FAILED: cast stopped` or `file already closed`. Regression tests reproduce the failures against the original implementations. When an upstream release includes these corrections, prefer its implementation and remove any duplicate adaptation. The release tag/SHA still identifies the published baseline; it does not claim a pristine core copy.

GitHub schedules run on the default branch, can be delayed, and may be disabled after 60 days without repository activity. Check Actions occasionally or use Run workflow. Authentication uses the repository's short-lived GITHUB_TOKEN, scoped separately for preparation, read-only testing and final publication. Repository Actions settings must allow workflow-created PRs. A repository policy disallowing direct main updates will stop the final push; do not silently bypass such a policy.

## Manual repair

Resolve the reported conflicts on the update branch and preserve both Android adapters/security and upstream ownership. Run the build/test commands in [the Android guide](../README.md), including regenerated protobuf Java and rebuilt Go binaries when contracts change. Reopen a deliberately closed update PR if automatic integration should resume, then rerun Upstream releases. Workflow changes require human review and a manual merge after validation. Record the new tag/SHA only with the actual integration.

To undo an incompatible integration, prepare and review a new revert commit, rebuild and test. Keep history and previously published Android binaries; do not reset or force-push main. The original fork and PR #68 remain available as provenance.

## Android distributions

Only maintainer-published releases use the release signing identity recorded in their release notes. The updater does not read/upload the private key. CI debug APKs are temporary test artifacts with a different signer; they cannot update a release installation. Distribute GPL corresponding sources/notices alongside an APK. Android app versionCode/versionName are independent of the core tracking file and must advance for an actual Android update.

The upstream desktop release-please/container/Homebrew workflows are retained in source but disabled through repository Actions settings. Only Android validation and Upstream releases are enabled here. A future update changing workflow files is held for manual review before it can affect repository automation.
