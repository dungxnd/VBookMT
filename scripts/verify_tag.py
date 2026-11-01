#!/usr/bin/env python3
"""
Verify that the tag matches versionName in apps/android/build.gradle.kts and CHANGELOG.md.
"""
import os
import pathlib
import re
import sys

def main():
    tag_arg = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("TAG_REF", "")
    tag = tag_arg.strip().lstrip("v")
    if not tag:
        print("Error: No tag provided to verify.", file=sys.stderr)
        sys.exit(1)

    project_root = pathlib.Path(__file__).resolve().parent.parent
    gradle_path = project_root / "apps" / "android" / "build.gradle.kts"
    changelog_path = project_root / "CHANGELOG.md"

    problems = []
    if not gradle_path.exists():
        problems.append(f"Missing {gradle_path}")
    else:
        gradle = gradle_path.read_text(encoding="utf-8")
        match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
        if not match:
            problems.append("could not read versionName from apps/android/build.gradle.kts")
        elif match.group(1) != tag:
            problems.append(f"versionName is {match.group(1)!r} but the tag is {tag!r}")

    if not changelog_path.exists():
        problems.append(f"Missing {changelog_path}")
    else:
        changelog = changelog_path.read_text(encoding="utf-8")
        if f"## [v{tag}]" not in changelog:
            problems.append(f"CHANGELOG.md has no '## [v{tag}]' section")

    if problems:
        for p in problems:
            print(f"Error: {p}", file=sys.stderr)
        sys.exit(1)

    print(f"Tag v{tag} matches versionName and CHANGELOG.md")

if __name__ == "__main__":
    main()
