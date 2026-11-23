# Upstream core integration

Upstream: https://github.com/stupside/castor. Android: https://github.com/kdpkke/castor-android.

## Ownership and baseline

`android/`, `cmd/castor-android`, the two Android workflow files and the root `README.md`, `ARCHITECTURE.md`, `CONTRIBUTING.md` and `SECURITY.md` belong to this community project. These documents describe Android and link to upstream for core documentation. Existing Go packages, generated contracts and the Whisper pin retain their upstream layout and history. The module remains `github.com/stupside/castor` so Go's internal-import rules work without copying or exposing private service implementations.

`android/upstream.json` records the integrated stable tag and its immutable commit. Its `integrationBase` is the upstream commit actually merged: the tag's commit, unless upstream main was merged ahead of a release, as stupside/castor#74 was before 2.2.0. The stable release remains v2.5.0. The current integration advances from `0129af4` to `47fcf56` on upstream main. Android versions and release tags are independent of this baseline.

The core uses upstream's encoder families, recovery and device state machines. Android selects the superfast software preset on phones with MediaCodec and retains software continuation and retirement of failed hardware encoders. Android Restart keeps the public cast ID and addresses each media attempt separately. The embedded API accepts Android's authenticated discovery bridge so the host can use NSD and SSDP. On Android 14 and later, discovery prefers an address on the receiver's local network when NSD resolves several.

Generated HLS subtitles carry their language. For a compatible original MKV, Native playback preserves the source audio and subtitle tracks, including when another audio track is marked default. The receiver can select among the tracks it supports. Android-specific protobuf fields use numbers from 1000.

For a Native cast of a seekable phone video, Android leaves delivery to the engine. The media server can forward a compatible file from its loopback listener to a self-fetching receiver as well as a DLNA renderer. The TV receives a LAN media URL that supports byte ranges. On Chromecast a playing MP4 source must advance after a seek. If the receiver stays buffered at the new position, the driver pauses and resumes the same media session once.

AirPlay video v2 seeks within the original finite MP4 by addressing the queued item. The receiver confirms its position in a fresh playback report. A seek during pause keeps the item paused.

## Automatic updates

The `upstream-releases.yml` workflow runs at minute 23 every six hours (UTC) and supports Run workflow. It pages through GitHub's published releases, ignores drafts/prereleases/non-version tags and selects the lowest stable version above the recorded tag. Branch commits and standalone tags without a published release never trigger an update. Historical `castor-vX.Y.Z` and `castor-X.Y.Z` tags are recognized. Several waiting releases are integrated over successive runs.

1. Verify that the recorded upstream tag has not moved.
2. Fetch the selected upstream tag and resolve it to a commit; create or reuse `upstream-release/<tag>` and merge main and that release, preserving history.
3. Ordinary content conflicts confined to the four Android-owned root documents keep the Android version. This rule applies only when merging the upstream release, never when merging Android main into an existing update branch. Code conflicts, other documents, renames, deletions, additions and file-mode changes still stop integration and open one issue identifying the release and paths. Main remains unchanged. The runner's incomplete merge is disposable.
4. Record the release SHA in `android/upstream.json`, push the update branch without force and open/reuse its PR.
5. Call the reusable Android validation workflow explicitly. A PR/push created with `GITHUB_TOKEN` does not reliably start unattended downstream CI; no personal access token is needed for this design.
6. Require synchronization tests, Go tests/vet/race checks, both Android native ABI builds, 10+ JVM tests, lint and the complete 28+ device-test suite on Android 17 with 16 KB pages. Tests run with read-only repository permissions and no signing secrets.
7. Reject automatic integration of `.github/`, updater or device-validation script changes. Otherwise, recheck the PR/head and original main SHA, then fast-forward main only to the exact tested commit. A concurrent main/branch change stops integration. No reset, rebase, force-push or deletion is used.

Checks failing leave the PR open. Conflicts outside the documentation rule produce an issue. A manually closed update PR pauses that release until reopened. The workflow does not close user issues or send messages to the upstream maintainer. Source integration does not publish an Android release.

The six-hour check is not a release-event subscription to another repository. Once a release is detected, the workflow commits its integration automatically and validates it before advancing main. Incompatible changes and changes to automation still require repair/review; an arbitrary future major release cannot be promised to work without Android changes.

Android compilation depends on `:client:bufGenerate`: each changed contract regenerates the official Connect-Kotlin client from the same checkout used to build the engine. Java message generation uses those same Protobuf sources. Generated clients are build outputs rather than merged snapshots. Generation updates the communication code; new UI controls or incompatible contract changes still require Android development and tests. The current core source/history integration remains in place; adopting Buf alone does not extract the Android Go host into a separate dependency.

The WebView bridge emits Castor's canonical HLS identity, `application/x-mpegURL`, in `Stream.content_type`. HTTP response MIME headers can still use `application/vnd.apple.mpegurl`.

The two core fixes the 2.0.0 integration carried, proposed in stupside/castor#71 (HLS MIME aliases and a recognized MIME type kept authoritative; errors a Stop provokes no longer reported as `FAILED: cast stopped` or `file already closed`), are upstream since stupside/castor#74. That integration took upstream's implementation of every file the two fixes had touched and removed Android's own copies, including `output/interrupted.go`, which upstream's `teardown` package replaces. Their regression tests are upstream's.

GitHub schedules run on the default branch, can be delayed, and may be disabled after 60 days without repository activity. Check Actions occasionally or use Run workflow. Authentication uses the repository's short-lived GITHUB_TOKEN, scoped separately for preparation, read-only testing and final publication. Repository Actions settings must allow workflow-created PRs. A repository policy disallowing direct main updates will stop the final push; do not silently bypass such a policy.

## Manual repair

Resolve the reported conflicts on the update branch and preserve both Android adapters/security and upstream ownership. Run the build/test commands in [the Android guide](../README.md), including regenerated protobuf Java and rebuilt Go binaries when contracts change. Reopen a deliberately closed update PR if automatic integration should resume, then rerun Upstream releases. Workflow changes require human review and a manual merge after validation. Record the new tag/SHA only with the actual integration.

To undo an incompatible integration, prepare and review a new revert commit, rebuild and test. Keep history and previously published Android binaries; do not reset or force-push main. The original fork and PR #68 remain available as provenance.

## Android distributions

Only maintainer-published releases use the release signing identity recorded in their release notes. The updater does not read/upload the private key. CI debug APKs are temporary test artifacts with a different signer; they cannot update a release installation. Distribute GPL corresponding sources/notices alongside an APK. Android app versionCode/versionName are independent of the core tracking file and must advance for an actual Android update.

The upstream desktop release-please/container/Homebrew workflows are retained in source but disabled through repository Actions settings. Only Android validation and Upstream releases are enabled here. A future update changing workflow files is held for manual review before it can affect repository automation.
