import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import upstream


class ReleaseSelectionTest(unittest.TestCase):
    def release(self, tag, **flags):
        return dict(tag_name=tag, draft=False, prerelease=False, **flags)

    def test_all_stable_releases_are_queued_in_version_order(self):
        releases = [self.release("v2.0.0"), self.release("v1.10.1"), self.release("v1.9.1")]
        self.assertEqual(upstream.pending_release(releases, "v1.9.0"), "v1.9.1")
        self.assertEqual(upstream.pending_release(releases, "v1.9.1"), "v1.10.1")

    def test_older_draft_preview_and_unrelated_tags_do_not_replace_stable_core(self):
        releases = [self.release("v1.8.1"), self.release("v1.10.0-rc.1"), self.release("canary")]
        releases += [dict(tag_name="v9.0.0", draft=True, prerelease=False),
                     dict(tag_name="v8.0.0", draft=False, prerelease=True)]
        self.assertIsNone(upstream.pending_release(releases, "v1.9.0"))

    def test_legacy_stable_tag_names_remain_supported(self):
        self.assertEqual(upstream.version("castor-v1.5.0"), (1, 5, 0))
        self.assertEqual(upstream.version("castor-1.6.1"), (1, 6, 1))

    def test_untrusted_tags_are_rejected(self):
        for tag in ("--upload-pack=anything", "v1.2.3\nother=value", "../../main", "v01.2.3"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                upstream.version(tag)

    def test_workflow_or_updater_changes_need_manual_review(self):
        self.assertTrue(upstream.automatic(["services/mediaserver/backend.go", "android/upstream.json"]))
        for path in (".github/workflows/new.yml", ".github/actions/setup/action.yml",
                     "android/scripts/upstream.py", "android/scripts/upstream_test.py",
                     "android/scripts/ci-device.sh", "android/scripts/check-device-results.py"):
            self.assertFalse(upstream.automatic([path]))


class IntegrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="castor-sync-test-")
        self.addCleanup(self.temp.cleanup)
        self.previous = Path.cwd()
        self.root = Path(self.temp.name)
        self.work = self.root / "work"
        self.work.mkdir()
        os.chdir(self.work)
        self.addCleanup(os.chdir, self.previous)
        upstream.git("init", "-b", "main")
        upstream.git("config", "user.name", "Test Author")
        upstream.git("config", "user.email", "test@example.invalid")
        self.write_commit("core.txt", "core v1\n", "core v1")
        self.base = upstream.git("rev-parse", "HEAD")
        upstream.git("switch", "-c", "upstream")
        self.write_commit("core.txt", "core v2\n", "core v2")
        self.release = upstream.git("rev-parse", "HEAD")
        upstream.git("switch", "main")
        self.write_commit("android.txt", "phone client\n", "Android client")
        self.android = upstream.git("rev-parse", "HEAD")

    def write_commit(self, name, content, message):
        Path(name).parent.mkdir(parents=True, exist_ok=True)
        Path(name).write_text(content, encoding="utf-8", newline="\n")
        upstream.git("add", "--", name)
        upstream.git("commit", "-m", message)

    def test_release_merge_keeps_android_and_upstream_history(self):
        upstream.merge_release(self.release)
        self.assertEqual(Path("core.txt").read_text(), "core v2\n")
        self.assertEqual(Path("android.txt").read_text(), "phone client\n")
        self.assertEqual(upstream.git("rev-parse", "HEAD^1"), self.android)
        self.assertEqual(upstream.git("rev-parse", "HEAD^2"), self.release)

    def test_conflicting_core_changes_stop_without_changing_main(self):
        self.write_commit("core.txt", "Android-required adaptation\n", "adapt core")
        before = upstream.git("rev-parse", "HEAD")
        with self.assertRaisesRegex(RuntimeError, "core.txt"):
            upstream.merge_release(self.release)
        self.assertEqual(upstream.git("rev-parse", "HEAD"), before)
        self.assertIn("core.txt", upstream.git("diff", "--name-only", "--diff-filter=U"))

    def candidate(self):
        remote = self.root / "remote.git"
        upstream.git("init", "--bare", str(remote))
        upstream.git("remote", "add", "origin", str(remote))
        upstream.git("push", "origin", "main")
        upstream.git("switch", "-c", "upstream-release/v2.0.0")
        upstream.merge_release(self.release)
        head = upstream.git("rev-parse", "HEAD")
        upstream.git("push", "origin", "HEAD:upstream-release/v2.0.0")
        return head, dict(state="open", head=dict(sha=head, ref="upstream-release/v2.0.0",
                         repo=dict(full_name="owner/android")), base=dict(ref="main"))

    def test_only_the_tested_commit_is_published(self):
        head, pull = self.candidate()
        with patch.object(upstream, "api", return_value=pull):
            upstream.complete("owner/android", head, self.android, 7)
        self.assertTrue(upstream.git("ls-remote", "origin", "refs/heads/main").startswith(head))

    def test_concurrent_main_change_requires_a_new_validation(self):
        head, pull = self.candidate()
        upstream.git("switch", "main")
        self.write_commit("other.txt", "unvalidated new base\n", "concurrent change")
        changed = upstream.git("rev-parse", "HEAD")
        upstream.git("push", "origin", "main")
        with patch.object(upstream, "api", return_value=pull), self.assertRaisesRegex(RuntimeError, "Main changed"):
            upstream.complete("owner/android", head, self.android, 7)
        self.assertTrue(upstream.git("ls-remote", "origin", "refs/heads/main").startswith(changed))

    def test_changed_release_branch_cannot_publish_old_test_results(self):
        head, pull = self.candidate()
        self.write_commit("late.txt", "untested\n", "late change")
        upstream.git("push", "origin", "HEAD:upstream-release/v2.0.0")
        with patch.object(upstream, "api", return_value=pull), self.assertRaisesRegex(RuntimeError, "branch changed"):
            upstream.complete("owner/android", head, self.android, 7)
        self.assertTrue(upstream.git("ls-remote", "origin", "refs/heads/main").startswith(self.android))


if __name__ == "__main__":
    unittest.main()
