"""scan an AAR / classes.jar for coroutines and stdlib API references the forced downgrade may miss.

The host pins kotlin-stdlib 2.0.21 and kotlinx-coroutines 1.6.4 while some Flutter plugins are
published against much newer artifacts. A bytecode call to a member those pinned versions do not
declare fails at runtime with NoSuchMethodError. This scan:

  1. reads every .class and collects Methodref / InterfaceMethodref / Fieldref entries under
     `kotlin/` and `kotlinx/coroutines/`;
  2. downloads the pinned reference jars from Maven Central (cached under build/diagnostics-api-cache);
  3. resolves each reference against the declaring class's own and inherited members and reports
     whatever is left unbacked.

Run from the repository root:
    uv run tools/diagnostics/scan_aar_apis.py <classes.jar-or.aar> [<another> ...] [--all]

Exit code 0 when nothing is missing, 1 when any reference is unbacked.
"""

from __future__ import annotations

import argparse
import io
import struct
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CACHE = REPO_ROOT / "build" / "diagnostics-api-cache"

REFERENCE_JARS = {
    "kotlin-stdlib-2.0.21.jar":
        "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar",
    "kotlin-stdlib-jdk7-2.0.21.jar":
        "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk7/2.0.21/kotlin-stdlib-jdk7-2.0.21.jar",
    "kotlin-stdlib-jdk8-2.0.21.jar":
        "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk8/2.0.21/kotlin-stdlib-jdk8-2.0.21.jar",
    "kotlinx-coroutines-core-jvm-1.6.4.jar":
        "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar",
    "kotlinx-coroutines-android-1.6.4.jar":
        "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.6.4/kotlinx-coroutines-android-1.6.4.jar",
}

WATCHED = ("kotlin/", "kotlinx/coroutines/")


class ClassFile:
    def __init__(self, data: bytes):
        self.utf8: dict[int, str] = {}
        self.class_names: dict[int, int] = {}
        self.name_types: dict[int, tuple[int, int]] = {}
        self.refs: set[tuple[str, str, str, str]] = set()
        self.declared: set[tuple[str, str, str]] = set()  # (name, descriptor, kind)
        self.name = ""
        self.super_name = ""
        self._parse(data)

    def utf_at(self, i: int) -> str:
        return self.utf8.get(i, "")

    def _parse(self, data: bytes) -> None:
        if data[:4] != b"\xca\xfe\xba\xbe":
            return
        count = struct.unpack_from(">H", data, 8)[0]
        offset = 10
        index = 1
        member_refs: list[tuple[int, int, str]] = []
        while index < count:
            tag = data[offset]
            offset += 1
            if tag == 1:
                length = struct.unpack_from(">H", data, offset)[0]
                offset += 2
                self.utf8[index] = data[offset: offset + length].decode("utf-8", "replace")
                offset += length
            elif tag == 7:
                self.class_names[index] = struct.unpack_from(">H", data, offset)[0]
                offset += 2
            elif tag == 12:
                self.name_types[index] = struct.unpack_from(">HH", data, offset)
                offset += 4
            elif tag in (9, 10, 11):
                class_index, nt_index = struct.unpack_from(">HH", data, offset)
                member_refs.append((class_index, nt_index, {9: "field", 10: "method", 11: "imethod"}[tag]))
                offset += 4
            elif tag in (3, 4):
                offset += 4
            elif tag in (5, 6):
                offset += 8
                index += 1
            elif tag == 8:
                offset += 2
            elif tag == 15:
                offset += 3
            elif tag in (16, 19, 20):  # MethodType, Module, Package
                offset += 2
            elif tag in (17, 18):  # Dynamic, InvokeDynamic: bootstrap index + NameAndType
                offset += 4
            else:
                raise ValueError(f"unknown constant-pool tag {tag} at {offset - 1}")
            index += 1

        for class_index, nt_index, kind in member_refs:
            cls = self.utf_at(self.class_names.get(class_index, 0))
            name_idx, desc_idx = self.name_types.get(nt_index, (0, 0))
            self.refs.add((cls, self.utf_at(name_idx), self.utf_at(desc_idx), kind))

        # class header and members: access, this, super, interfaces, fields, methods
        _access, this_idx, super_idx = struct.unpack_from(">HHH", data, offset)
        offset += 6
        self.name = self.utf_at(self.class_names.get(this_idx, 0))
        self.super_name = self.utf_at(self.class_names.get(super_idx, 0))
        interfaces_count = struct.unpack_from(">H", data, offset)[0]
        offset += 2 + 2 * interfaces_count

        for kind in ("field", "method"):
            members_count = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            for _ in range(members_count):
                _flags, name_idx, desc_idx = struct.unpack_from(">HHH", data, offset)
                offset += 6
                self.declared.add((self.utf_at(name_idx), self.utf_at(desc_idx), kind))
                attrs_count = struct.unpack_from(">H", data, offset)[0]
                offset += 2
                for _ in range(attrs_count):
                    _name_idx, length = struct.unpack_from(">HI", data, offset)
                    offset += 6 + length


def parse_jar_classes(data: bytes) -> list[ClassFile]:
    classes: list[ClassFile] = []
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for entry in jar.namelist():
            if entry.endswith(".class"):
                try:
                    classes.append(ClassFile(jar.read(entry)))
                except Exception as failure:  # noqa: BLE001 - the entry name is the diagnosis
                    raise ValueError(f"{entry}: {failure}") from failure
    return classes


def read_artifact_classes(path: Path) -> list[ClassFile]:
    data = path.read_bytes()
    if path.suffix == ".aar":
        with zipfile.ZipFile(io.BytesIO(data)) as aar:
            data = aar.read("classes.jar")
    return parse_jar_classes(data)


def reference_world() -> tuple[set[tuple[str, str, str, str]], dict[str, str]]:
    """(all declared members as (class, name, desc, kind), class -> super)."""
    CACHE.mkdir(parents=True, exist_ok=True)
    members: set[tuple[str, str, str, str]] = set()
    supers: dict[str, str] = {}
    for name, url in REFERENCE_JARS.items():
        path = CACHE / name
        if not path.is_file():
            print(f"fetching {name} …", file=sys.stderr)
            with urllib.request.urlopen(url, timeout=60) as response:
                path.write_bytes(response.read())
        for cls in parse_jar_classes(path.read_bytes()):
            supers[cls.name] = cls.super_name
            for member in cls.declared:
                members.add((cls.name, *member))
    return members, supers


def backed(ref: tuple[str, str, str, str], members: set, supers: dict[str, str]) -> bool:
    cls, name, desc, kind = ref
    seen = set()
    while cls and cls not in seen:
        seen.add(cls)
        if (cls, name, desc, kind) in members:
            return True
        cls = supers.get(cls, "")
        # The JDK and Android framework types are not in the reference jars; their members are
        # provided by the platform at runtime, so only library members can be missing.
        if cls.startswith(("java/", "javax/", "android/", "dalvik/")):
            return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", nargs="+", help="classes.jar or .aar files to scan")
    parser.add_argument("--all", action="store_true", help="list every reference, not just missing ones")
    args = parser.parse_args()

    refs_total: set[tuple[str, str, str, str]] = set()
    for raw in args.artifacts:
        refs = {ref for cls in read_artifact_classes(Path(raw)) for ref in cls.refs if ref[0].startswith(WATCHED)}
        refs_total |= refs
        print(f"{Path(raw).name}: {len(refs)} coroutines/stdlib references")

    members, supers = reference_world()
    missing = {ref for ref in refs_total if not backed(ref, members, supers)}

    if args.all:
        for cls, name, desc, kind in sorted(refs_total):
            mark = "MISSING " if (cls, name, desc, kind) in missing else "         "
            print(f"{mark}{kind:7s} {cls}.{name}{desc}")

    print(f"\n{len(refs_total)} references, {len(missing)} unbacked by the pinned versions")
    for cls, name, desc, kind in sorted(missing):
        print(f"  MISSING {kind} {cls}.{name}{desc}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
