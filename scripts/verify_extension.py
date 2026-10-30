#!/usr/bin/env python3
"""
Guards the two extension mistakes that ship silently and cost a release:

1. **A stale packaged artifact.** `vbook-extension/plugin.zip` is committed because it is what
   users install, but it is *generated* from the sources. Nothing rebuilt it in CI, so a change
   to `src/*.js` or `plugin.json` could ship with the previous defaults baked into the zip - which
   already happened once (v1.0.0 shipped the old 15 s timeout after the source moved to 120 s).

2. **Manifest and script disagreeing about settings.** vBook renders the `config` block from
   `plugin.json`; the script reads the defaults from `src/config.js`. Two sources of truth for the
   same five settings, and a mismatch means the UI promises one thing while the runtime does another.

Byte-comparing the zip with a fresh one does not work (zipfile stores timestamps), so this compares
entry names and content hashes instead.

Usage:
    python scripts/verify_extension.py            # verify, exit non-zero on failure
    python scripts/verify_extension.py --fix      # repackage first, then verify
"""

import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXT_DIR = ROOT / "vbook-extension"
PLUGIN_ZIP = EXT_DIR / "plugin.zip"
MANIFEST = EXT_DIR / "plugin.json"
SCRIPT_CONFIG = EXT_DIR / "src" / "config.js"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def packaged_files(ext_dir: Path) -> dict[str, str]:
    """Entry name -> content hash, mirroring what scripts/package_extension.py writes."""
    files: dict[str, str] = {}
    for path in sorted(ext_dir.rglob("*")):
        if not path.is_file():
            continue
        if path.name.startswith(".") or path.suffix == ".zip":
            continue
        files[path.relative_to(ext_dir).as_posix()] = sha256(path.read_bytes())
    return files


def zip_files(zip_path: Path) -> dict[str, str]:
    with zipfile.ZipFile(zip_path) as archive:
        return {name: sha256(archive.read(name)) for name in archive.namelist() if not name.endswith("/")}


def compare_artifact() -> list[str]:
    if not PLUGIN_ZIP.exists():
        return [f"{PLUGIN_ZIP.relative_to(ROOT)} is missing - run scripts/package_extension.py"]

    expected = packaged_files(EXT_DIR)
    actual = zip_files(PLUGIN_ZIP)

    problems: list[str] = []
    for name in sorted(expected.keys() - actual.keys()):
        problems.append(f"  missing from plugin.zip: {name}")
    for name in sorted(actual.keys() - expected.keys()):
        problems.append(f"  only in plugin.zip (deleted from sources?): {name}")
    for name in sorted(expected.keys() & actual.keys()):
        if expected[name] != actual[name]:
            problems.append(f"  stale in plugin.zip: {name}")

    if problems:
        problems.insert(
            0,
            "plugin.zip does not match the extension sources "
            "(run: python scripts/package_extension.py)",
        )
    return problems


# ---------------------------------------------------------------------------------------------
# Settings agreement: plugin.json's config block vs src/config.js defaults
# ---------------------------------------------------------------------------------------------

# Keys are declared at a fixed depth in config.js, inside `return Response.success({ ... })`.
CONFIG_KEY = re.compile(r"^\s{8}([A-Za-z_][A-Za-z0-9_]*)\s*:\s*\{", re.MULTILINE)
DEFAULT_VALUE = re.compile(r"default\s*:\s*([^,\n]+)")


def _block_end(text: str, open_brace: int) -> int:
    """Index just past the brace that closes the one at `open_brace`."""
    depth = 0
    for index in range(open_brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index + 1
    return len(text)


def script_defaults() -> dict[str, str]:
    """Setting name -> default value, normalised to a bare string."""
    text = SCRIPT_CONFIG.read_text(encoding="utf-8")
    found: dict[str, str] = {}
    for match in CONFIG_KEY.finditer(text):
        name = match.group(1)
        block = text[match.start() : _block_end(text, text.index("{", match.start()))]
        value = DEFAULT_VALUE.search(block)
        if value:
            found[name] = _normalise(value.group(1))
    return found


def _normalise(raw: str) -> str:
    value = raw.strip().rstrip(",").strip()
    # `str(True)` is "True" while JavaScript writes "true": compare booleans case-insensitively.
    if value.lower() in ("true", "false"):
        return value.lower()
    return value.strip("\"'").strip()


def compare_settings() -> list[str]:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    declared = manifest.get("config", {})
    # vBook's own platform keys (max_line, max_length, support_auto_detect, ...) are scalars with no
    # `default`; only object entries describe a setting this plugin both declares and reads.
    manifest_defaults = {
        name: _normalise(str(spec["default"]))
        for name, spec in declared.items()
        if isinstance(spec, dict) and "default" in spec
    }
    return describe_mismatches(manifest_defaults, script_defaults())


def describe_mismatches(manifest: dict[str, str], runtime: dict[str, str]) -> list[str]:
    problems: list[str] = []
    for name in sorted(manifest.keys() - runtime.keys()):
        problems.append(f"  declared in plugin.json but not in src/config.js: {name}")
    for name in sorted(runtime.keys() - manifest.keys()):
        problems.append(f"  read by src/config.js but not offered in plugin.json: {name}")
    for name in sorted(manifest.keys() & runtime.keys()):
        if manifest[name] != runtime[name]:
            problems.append(
                f"  default differs for {name}: plugin.json={manifest[name]!r} config.js={runtime[name]!r}"
            )
    if problems:
        problems.insert(0, "plugin.json and src/config.js disagree about settings:")
    return problems


def main() -> int:
    if "--fix" in sys.argv:
        sys.path.insert(0, str(ROOT / "scripts"))
        import package_extension  # noqa: PLC0415 - local import keeps the check dependency-free

        package_extension.package_extension(EXT_DIR, PLUGIN_ZIP)
        print()

    problems = compare_artifact() + compare_settings()
    if problems:
        print("\n".join(problems))
        return 1

    print("Extension artifact matches its sources, and plugin.json agrees with src/config.js.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
