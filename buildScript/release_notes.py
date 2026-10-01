#!/usr/bin/env python3
"""Builds the "Download based on your OS:" header of a GitHub release body for this Android-only app.

Usage: release_notes.py <dist-dir> <tag> <output-file>

<dist-dir> is the directory release_assets.py populated with the per-ABI APKs. The repository the
release belongs to is read from GITHUB_REPOSITORY (owner/repo), so the generated links point at
https://github.com/<owner>/<repo>/releases/download/<tag>/<apk>.
"""

import os
import re
import sys
from pathlib import Path

ABI_LABELS = {
    "arm64-v8a": "Android &middot; arm64-v8a",
    "armeabi-v7a": "Android &middot; armeabi-v7a",
    "x86_64": "Android &middot; x86_64",
}
ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "x86_64"]


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


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

    rows = []
    for abi in ABI_ORDER:
        apk = next((p for p in apks if abi_of(p.name) == abi), None)
        if apk is None:
            fail(f"missing APK for {abi}")
        url = f"https://github.com/{repo}/releases/download/{tag}/{apk.name}"
        rows.append(f"| {ABI_LABELS[abi]} | [{apk.name}]({url}) |")

    body = (
        "**Download based on your OS:**\n\n"
        "| OS / ABI | Download |\n"
        "| --- | --- |\n"
        + "\n".join(rows)
        + "\n"
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(body, newline="\n")
    print(body)


if __name__ == "__main__":
    main()
