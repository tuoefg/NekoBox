#!/usr/bin/env python3
"""Builds a FlClash-style Chinese GitHub release body for this Android-only app.

Usage: release_notes.py <dist-dir> <tag> <output-file>

<dist-dir> is the directory release_assets.py populated with the per-ABI APKs. The repository the
release belongs to is read from GITHUB_REPOSITORY (owner/repo), so the generated links point at
https://github.com/<owner>/<repo>/releases/download/<tag>/<apk>.

Body layout, FlClash-style, Chinese labels:
  ### 新功能 / ### 问题修复 / ### 其他   — conventional commits since the previous v* tag
  **根据你的系统下载：**                  — one Android row, per-ABI links separated by <br>
  **完整变更列表：** [更新日志](...)      — link to the commit list
"""

import os
import re
import subprocess
import sys
from pathlib import Path

ABI_LABELS = {
    "arm64-v8a": "APK ARMv8",
    "armeabi-v7a": "APK ARMv7",
    "x86_64": "APK x64",
}
ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "x86_64"]

CONVENTIONAL = re.compile(
    r"^(feat|fix|perf|refactor|docs|style|test|build|ci|chore)"
    r"(?:\(([^)]+)\))?[:：]\s*(.+)$",
    re.IGNORECASE,
)

# Non-conventional history: map the leading verb to a section so old commits still classify.
FEATURE_VERBS = (
    "add", "new", "update", "improve", "support", "implement", "introduce",
    "rebrand", "remove", "enable", "restore",
)
FIX_VERBS = ("fix", "repair", "correct", "resolve", "address", "fixup", "修复")

# Development-process commits (task tracking, spec notes, version bumps) are noise for users.
NOISE = re.compile(
    r"^(task\s|spec\b|spec:|bump version|make release workflow manual|further improvements)",
    re.IGNORECASE,
)


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"{' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def previous_tag(tag):
    """The most recent v* tag other than the one being released."""
    for t in run("git", "tag", "--list", "v*", "--sort=-version:refname").splitlines():
        if t.strip() != tag:
            return t.strip()
    return None


def commits_since(tag):
    if tag:
        return run("git", "log", f"{tag}..HEAD", "--format=%s").splitlines()
    return run("git", "log", "--format=%s").splitlines()


def classify(subject):
    """Returns (section, scope, text); (None, None, None) means skip the commit."""
    subject = subject.strip()
    if NOISE.match(subject):
        return None, None, None
    m = CONVENTIONAL.match(subject)
    if m:
        kind, scope, text = m.group(1).lower(), m.group(2), m.group(3).strip()
        if kind == "feat":
            return "新功能", scope, text
        if kind == "fix":
            return "问题修复", scope, text
        if kind == "chore":
            return None, None, None  # release/version noise
        return "其他", scope, text
    head = subject.split(":", 1)[0].strip().lower()
    if head.startswith(FIX_VERBS):
        return "问题修复", None, subject
    if head.startswith(FEATURE_VERBS):
        return "新功能", None, subject
    return "其他", None, subject


def main():
    if len(sys.argv) != 4:
        fail("usage: release_notes.py <dist-dir> <tag> <output-file>")
    dist, tag, output = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if not repo:
        fail("GITHUB_REPOSITORY is not set")
    if not re.fullmatch(r"v[0-9]+[0-9A-Za-z.\-]*", tag):
        fail(f"unexpected tag format: {tag}")

    apks = sorted(dist.glob("*.apk"))
    if not apks:
        fail(f"no APK found in {dist}")

    def abi_of(name: str) -> str:
        found = next((a for a in ABI_LABELS if name.endswith(f"-{a}.apk")), None)
        return found or fail(f"cannot derive ABI from asset name: {name}")

    # Simple style: download table only, no changelog list (matches v2.0.1)
    parts = []

    cells = []
    for abi in ABI_ORDER:
        apk = next((p for p in apks if abi_of(p.name) == abi), None)
        if apk is None:
            fail(f"missing APK for {abi}")
        url = f"https://github.com/{repo}/releases/download/{tag}/{apk.name}"
        cells.append(f"[{ABI_LABELS[abi]}]({url})")
    parts.append(
        "**根据你的系统下载：**\n\n"
        "| 系统 | 下载 |\n"
        "| --- | --- |\n"
        f"| Android | {'<br>'.join(cells)} |"
    )

    parts.append(f"**完整变更列表：** [更新日志](https://github.com/{repo}/commits/{tag})")

    body = "\n\n".join(parts) + "\n"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(body, newline="\n")
    print(body)


if __name__ == "__main__":
    main()
