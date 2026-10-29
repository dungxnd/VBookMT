#!/usr/bin/env python3
"""
Generate clean release notes for VBookMT releases.
Extracts the corresponding version section from CHANGELOG.md,
or falls back to git commits since the last tag.
"""

import sys
import os
import re
import subprocess
from pathlib import Path

def get_git_log_notes(tag: str) -> str:
    try:
        # Find previous tag
        cmd_prev = ["git", "describe", "--tags", "--abbrev=0", f"{tag}^"]
        res_prev = subprocess.run(cmd_prev, capture_output=True, text=True)
        prev_tag = res_prev.stdout.strip() if res_prev.returncode == 0 else ""

        if prev_tag:
            range_arg = f"{prev_tag}..{tag}"
        else:
            range_arg = tag

        cmd_log = ["git", "log", range_arg, "--pretty=format:- %s (%h)"]
        res_log = subprocess.run(cmd_log, capture_output=True, text=True)
        if res_log.returncode == 0 and res_log.stdout.strip():
            return f"### Changes in {tag}\n\n" + res_log.stdout.strip()
    except Exception as e:
        print(f"Git log error: {e}", file=sys.stderr)

    return f"### Release {tag}\n\n- Build tự động cho phiên bản {tag}."

def extract_from_changelog(changelog_path: Path, tag: str) -> str:
    if not changelog_path.exists():
        return ""

    content = changelog_path.read_text(encoding="utf-8")
    clean_tag = tag.lstrip("v")
    
    # Pattern matching: ## [1.0.0] or ## [v1.0.0]
    pattern = rf"##\s*\[?v?{re.escape(clean_tag)}\]?[^\n]*\n([\s\S]*?)(?=\n##\s*\[|\Z)"
    match = re.search(pattern, content, re.IGNORECASE)

    if match:
        return match.group(1).strip()
    return ""

def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GITHUB_REF_NAME", "v1.0.0")
    out_file = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("build/release_notes.md")
    out_file.parent.mkdir(parents=True, exist_ok=True)

    changelog_file = Path("CHANGELOG.md")
    notes = extract_from_changelog(changelog_file, tag)

    if not notes:
        print(f"[*] Version {tag} not found in CHANGELOG.md, falling back to git log...")
        notes = get_git_log_notes(tag)

    out_file.write_text(notes.strip() + "\n", encoding="utf-8")
    print(f"[+] Clean release notes generated at: {out_file}")

if __name__ == "__main__":
    main()
