#!/usr/bin/env python3
"""Integrate stable Castor releases without overwriting Android or rewriting history."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


UPSTREAM = "stupside/castor"
LOCK = Path("android/upstream.json")


def run(*args, check=True):
    return subprocess.run(args, check=check, text=True, encoding="utf-8", capture_output=True)


def git(*args):
    return run("git", *args).stdout.strip()


def api(path, method="GET", payload=None):
    args = ["gh", "api", path, "--method", method]
    if payload is not None:
        args += ["--input", "-"]
    result = subprocess.run(args, input=json.dumps(payload) if payload is not None else None,
                            check=True, text=True, encoding="utf-8", capture_output=True)
    return json.loads(result.stdout) if result.stdout.strip() else None


def version(tag):
    match = re.fullmatch(r"(?:v|castor-v|castor-)?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", tag)
    if not match:
        raise ValueError(f"Unsupported stable release tag: {tag!r}")
    return tuple(map(int, match.groups()))


def pending_release(releases, current):
    pending = []
    for release in releases:
        if release["draft"] or release["prerelease"]:
            continue
        tag = release["tag_name"]
        try:
            candidate = version(tag)
        except ValueError:
            continue
        if candidate > version(current):
            pending.append((candidate, tag))
    return min(pending)[1] if pending else None


def automatic(paths):
    # Workflow changes need human review before they can gain write permissions.
    return not any(p.startswith((".github/", "android/scripts/upstream")) for p in paths)


def merge_release(commit):
    result = run("git", "merge", "--no-edit", "--no-ff", commit, check=False)
    if result.returncode:
        conflicts = git("diff", "--name-only", "--diff-filter=U")
        raise RuntimeError("Upstream integration stopped. Conflicting files:\n" + conflicts
                           if conflicts else result.stderr + result.stdout)


def output(**values):
    print(json.dumps(values, indent=2))
    if path := os.environ.get("GITHUB_OUTPUT"):
        with open(path, "a", encoding="utf-8", newline="\n") as target:
            for key, value in values.items():
                target.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")


def issue(repo, title, body):
    existing = api(f"repos/{repo}/issues?state=open&per_page=100")
    if not any(i["title"] == title for i in existing):
        api(f"repos/{repo}/issues", "POST", {"title": title, "body": body})


def prepare(repo):
    if git("status", "--porcelain"):
        raise RuntimeError("Start upstream integration from a clean checkout")
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if lock["repository"] != UPSTREAM:
        raise RuntimeError("Unexpected upstream repository")
    # The recorded tag must remain immutable, even on runs with no new release.
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", f"refs/tags/{lock['tag']}")
    if git("rev-parse", "FETCH_HEAD^{commit}") != lock["commit"]:
        raise RuntimeError("The recorded upstream tag moved; manual review required")
    releases = json.loads(run("gh", "api", "--paginate", "--slurp",
                             f"repos/{UPSTREAM}/releases?per_page=100").stdout)
    tag = pending_release([r for page in releases for r in page], lock["tag"])
    if tag is None:
        output(ready=False, message="No newer stable Castor release")
        return
    base = git("rev-parse", "HEAD")
    git("fetch", "--no-tags", f"https://github.com/{UPSTREAM}.git", f"refs/tags/{tag}")
    upstream = git("rev-parse", "FETCH_HEAD^{commit}")
    branch = f"upstream-release/{tag}"
    remote = git("ls-remote", "--heads", "origin", f"refs/heads/{branch}")
    if remote:
        git("fetch", "origin", f"refs/heads/{branch}")
        git("switch", "-c", branch, "FETCH_HEAD")
    else:
        git("switch", "-c", branch)
    try:
        merge_release(base)
        merge_release(upstream)
    except RuntimeError as error:
        issue(repo, f"Castor {tag}: integration needs conflict resolution",
              f"Release: https://github.com/{UPSTREAM}/releases/tag/{tag}\n\n"
              f"Android base: `{base}`\nUpstream commit: `{upstream}`\n\n"
              f"```text\n{error}\n```\n\nMain was not changed. Resolve the conflicts manually; "
              "the updater never uses ours/theirs or force-pushes.")
        raise
    lock.update(tag=tag, commit=upstream, integrationBase=upstream)
    LOCK.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8", newline="\n")
    git("add", "--", str(LOCK))
    if run("git", "diff", "--cached", "--quiet", check=False).returncode:
        git("commit", "-m", f"chore(core): track Castor {tag}")
    head = git("rev-parse", "HEAD")
    paths = git("diff", "--name-only", base, head).splitlines()
    can_merge = automatic(paths)
    git("push", "origin", f"HEAD:refs/heads/{branch}")
    pulls = api(f"repos/{repo}/pulls?state=all&head={repo.split('/')[0]}:{branch}&base=main&per_page=100")
    opened = next((p for p in pulls if p["state"] == "open"), None)
    if opened is None:
        if any(p.get("merged_at") is None for p in pulls):
            raise RuntimeError("This release PR was closed manually; reopen it to resume integration")
        opened = api(f"repos/{repo}/pulls", "POST", {
            "title": f"chore(core): integrate Castor {tag}", "head": branch, "base": "main",
            "body": f"Integrates https://github.com/{UPSTREAM}/releases/tag/{tag} "
                    f"(`{upstream}`), preserving Android changes and upstream history.\n\n"
                    "The updater runs native ARM64/x86-64 builds, Go checks, JVM tests, "
                    "lint and Android device tests before advancing main. "
                    + ("Workflow/automation changes require manual review."
                       if not can_merge else "Main advances only to the exact tested commit.")})
    output(ready=True, head=head, base=base, pr=opened["number"], automatic=can_merge)


def complete(repo, head, base, number):
    if not re.fullmatch(r"[0-9a-f]{40}", head) or not re.fullmatch(r"[0-9a-f]{40}", base):
        raise ValueError("Expected full commit hashes")
    pull = api(f"repos/{repo}/pulls/{int(number)}")
    if (pull["state"] != "open" or pull["head"]["repo"]["full_name"] != repo
            or not pull["head"]["ref"].startswith("upstream-release/")
            or pull["head"]["sha"] != head or pull["base"]["ref"] != "main"):
        raise RuntimeError("The tested update no longer matches its pull request")
    git("fetch", "origin", f"refs/heads/{pull['head']['ref']}")
    if git("rev-parse", "FETCH_HEAD") != head:
        raise RuntimeError("The release branch changed after validation")
    if not automatic(git("diff", "--name-only", base, head).splitlines()):
        raise RuntimeError("Workflow/automation changes require manual review")
    current = git("ls-remote", "--heads", "origin", "refs/heads/main").split()[0]
    if current != base:
        raise RuntimeError("Main changed during validation; the next run must retest integration")
    # Fast-forward only: a concurrent change to main makes this push fail safely.
    git("push", "origin", f"{head}:refs/heads/main")
    print(f"Integrated the validated release: https://github.com/{repo}/pull/{number}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "complete"))
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--head")
    parser.add_argument("--base")
    parser.add_argument("--pr", type=int)
    args = parser.parse_args()
    if not args.repo or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("A GitHub owner/repository is required")
    if args.operation == "prepare":
        prepare(args.repo)
    else:
        if not all((args.head, args.base, args.pr)):
            parser.error("complete requires --head, --base and --pr")
        complete(args.repo, args.head, args.base, args.pr)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr, file=sys.stderr)
        sys.exit(1)
