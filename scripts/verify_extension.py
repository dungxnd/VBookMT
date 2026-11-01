#!/usr/bin/env python3
"""
Guards extension release mistakes for both VBookMT extensions:
- Edition B: Companion Host App (vbook-extension/)
- Edition A: WebGPU Standalone  (vbook-extension-webgpu/)

Checks:
1. Packaged artifacts (plugin.zip) match source files.
2. Manifests (plugin.json) and config scripts (src/config.js) agree on all settings and defaults.
3. No build/release tooling (`scripts/`, `*.py`, `*.sh`, `*.ps1`, `__pycache__`) is inside a shipped
   archive. Those live in the repo for CI to drive and must never reach a user's machine.

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

EXTENSIONS = [
    {
        "name": "VBookMT Translation Extension",
        "dir": ROOT / "vbook-extension",
        "zip": ROOT / "vbook-extension" / "plugin.zip",
        "manifest": ROOT / "vbook-extension" / "plugin.json",
        "config": ROOT / "vbook-extension" / "src" / "config.js",
    },
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def packaged_files(ext_dir: Path) -> dict[str, str]:
    files: dict[str, str] = {}
    for path in sorted(ext_dir.rglob("*")):
        if not path.is_file():
            continue
        if path.name.startswith(".") or path.suffix == ".zip" or "__pycache__" in path.parts:
            continue
        # Hash LF-normalised bytes, matching what package_extension writes. Without this the check
        # compares a CRLF worktree against an LF archive and reports every file as stale on Windows.
        files[path.relative_to(ext_dir).as_posix()] = sha256(
            path.read_bytes().replace(b"\r\n", b"\n")
        )
    return files


def zip_files(zip_path: Path) -> dict[str, str]:
    with zipfile.ZipFile(zip_path) as archive:
        return {name: sha256(archive.read(name)) for name in archive.namelist() if not name.endswith("/")}


def compare_artifact(ext: dict) -> list[str]:
    zip_path: Path = ext["zip"]
    ext_dir: Path = ext["dir"]
    ext_name: str = ext["name"]

    if not zip_path.exists():
        return [f"[{ext_name}] {zip_path.relative_to(ROOT)} is missing - run scripts/package_extension.py"]

    expected = packaged_files(ext_dir)
    actual = zip_files(zip_path)

    problems: list[str] = []
    for name in sorted(expected.keys() - actual.keys()):
        problems.append(f"  missing from plugin.zip: {name}")
    for name in sorted(actual.keys() - expected.keys()):
        problems.append(f"  only in plugin.zip (deleted from sources?): {name}")
    for name in sorted(expected.keys() & actual.keys()):
        if expected[name] != actual[name]:
            problems.append(f"  stale in plugin.zip: {name}")

    # Nothing here is shipped, so a shipped file of this shape means something is packaging the wrong
    # tree. scripts/ holds the build and release tooling and must never reach a user's machine.
    for name in sorted(actual.keys()):
        if _is_tooling_path(name):
            problems.append(f"  build tooling must not be published: {name}")

    if problems:
        problems.insert(
            0,
            f"[{ext_name}] plugin.zip does not match extension sources (run: python scripts/verify_extension.py --fix)",
        )
    return problems


#: Directory names whose contents are development tooling when they sit at the archive root.
TOOLING_ROOT_DIRS = ("scripts", ".github")
#: Source-shaped suffixes that are tooling when they turn up anywhere inside a shipped extension.
TOOLING_SUFFIXES = (".py", ".pyc", ".sh", ".ps1")


def _is_tooling_path(name: str) -> bool:
    parts = name.split("/")
    # Only a root-level `scripts/` is the build tooling; a nested directory with that name can be
    # ordinary extension source (e.g. `src/scripts/loader.js`), so it is not condemned for its name.
    if len(parts) > 1 and parts[0] in TOOLING_ROOT_DIRS:
        return True
    if "__pycache__" in parts:
        return True
    return name.lower().endswith(TOOLING_SUFFIXES)


# ---------------------------------------------------------------------------------------------
# Settings validation against the vBook extension contract
#
# Static keys live under `config` in plugin.json and are injected into each script as string
# constants; dynamic keys come from `src/config.js` and are read with `localConfig.getItem`. They are
# **two separate mechanisms**, so the old rule here — "every key in config.js must also appear in
# plugin.json" — was wrong twice over:
#
#  - it forced the same key into both tables, and per the contract a dynamic item that shares a static
#    key saves into the dynamic table while the injected constant keeps the static value. Combined
#    with a reader that checks globals first, the user's pick was masked by the static default, which
#    is exactly the "settings do not work" symptom this check was supposed to prevent;
#  - it said nothing about the things that actually break rendering.
#
# What is checked instead, from `extension-api.md`:
#  - `mode` must be one of input / select / toggle / list / database — an unknown mode **renders
#    nothing**, so `mode: "switch"` silently produced a settings screen with no control;
#  - `select` takes its choices from `values` (a plain array of strings); `options` is not a key and
#    an unknown value falls back to the first entry;
#  - `default` is a string, so `toggle` defaults are written "true" / "false";
#  - the same key must not be declared in both tables.
# ---------------------------------------------------------------------------------------------

CONFIG_KEY = re.compile(r"^\s{8}([A-Za-z_][A-Za-z0-9_]*)\s*:\s*\{", re.MULTILINE)
DEFAULT_VALUE = re.compile(r"default\s*:\s*([^,\n]+)")
MODE_VALUE = re.compile(r"mode\s*:\s*([\"'])([A-Za-z]+)\1")
VALUES_KEY = re.compile(r"\bvalues\s*:\s*\[")

VALID_MODES = ("input", "select", "toggle", "list", "database")


def _block_end(text: str, open_brace: int) -> int:
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


def _normalise(raw: str) -> str:
    value = raw.strip().rstrip(",").strip()
    if value.lower() in ("true", "false"):
        return value.lower()
    return value.strip("\"'").strip()


def script_settings(config_path: Path) -> dict[str, dict]:
    """Dynamic settings from config.js: key -> {default, mode, has_values}."""
    text = config_path.read_text(encoding="utf-8")
    found: dict[str, dict] = {}
    for match in CONFIG_KEY.finditer(text):
        name = match.group(1)
        block = text[match.start() : _block_end(text, text.index("{", match.start()))]
        found[name] = {
            "default": _normalise(m.group(1)) if (m := DEFAULT_VALUE.search(block)) else None,
            "mode": (m.group(2) if (m := MODE_VALUE.search(block)) else None),
            "has_values": bool(VALUES_KEY.search(block)),
        }
    return found


def compare_settings(ext: dict) -> list[str]:
    manifest_path: Path = ext["manifest"]
    config_path: Path = ext["config"]
    ext_name: str = ext["name"]

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    declared = manifest.get("config", {})
    static = {name: spec for name, spec in declared.items() if isinstance(spec, dict)}
    dynamic = script_settings(config_path)

    problems: list[str] = []

    def check(name: str, spec_mode, spec_default, has_values, where: str) -> None:
        if spec_mode is None:
            problems.append(f"  {where}: '{name}' has no mode (an unknown/missing mode renders nothing)")
            return
        if spec_mode not in VALID_MODES:
            problems.append(
                f"  {where}: '{name}' uses mode {spec_mode!r}, which vBook does not recognise "
                f"(valid: {', '.join(VALID_MODES)}) — it renders nothing"
            )
        if spec_mode == "select" and not has_values:
            problems.append(
                f"  {where}: '{name}' is mode=select but declares no 'values' array "
                f"('options' is not part of the contract)"
            )
        if spec_mode == "toggle" and spec_default not in (None, "true", "false"):
            problems.append(
                f"  {where}: '{name}' is mode=toggle but its default is {spec_default!r}, "
                f"expected \"true\" or \"false\""
            )

    for name, spec in sorted(static.items()):
        check(
            name,
            spec.get("mode"),
            _normalise(str(spec["default"])) if "default" in spec else None,
            isinstance(spec.get("values"), list),
            "plugin.json",
        )
    for name, spec in sorted(dynamic.items()):
        check(name, spec["mode"], spec["default"], spec["has_values"], "src/config.js")

    # The shadowing case: a dynamic item with the same key as a static one saves into the dynamic
    # table while the injected constant keeps the static value, so a reader that looks at the
    # constant first never sees the user's choice.
    for name in sorted(static.keys() & dynamic.keys()):
        problems.append(
            f"  '{name}' is declared both in plugin.json (static, injected as a constant) and in "
            f"src/config.js (dynamic) — the two tables disagree and the static value wins for any "
            f"reader that checks the constant first"
        )

    if problems:
        problems.insert(0, f"[{ext_name}] settings do not follow the vBook extension contract:")
    return problems



# ---------------------------------------------------------------------------------------------
# Codec bootstrap
#
# translate.js cannot speak to the host without `wire.js`, and it pulls that file in with
# `load('wire.js')`. Per vBook's extension-api.md that call is a **text substitution performed before
# the script runs** — "not a function call: the line is replaced with the file's content". So:
#
#  - `load` is never a callable global, and `if (typeof load === "function") { load('wire.js') }` is
#    dead code on every device. It shipped that way once: VBookWire stayed undefined, translate.js
#    took its "codec missing" branch, and every chapter answered with QuickTranslator prefixed
#    "[QT Fallback - Máy chủ VBookMT chưa bật]" while the host was running perfectly. That is the
#    single most misleading failure this extension can produce, because it blames the server.
#  - the argument must be a string literal and the file must sit at the **top level of src/**; a path
#    like `src/wire.js` is not a top-level name and is replaced by nothing, silently;
#  - the pattern is matched **anywhere in the text, including comments**, so every `load('...')` that
#    appears in the file must name a real top-level script.
# ---------------------------------------------------------------------------------------------

#: A `load('...')` / `load("...")` invocation with its literal argument.
LOAD_INVOCATION = re.compile(r"""\bload\s*\(\s*(['"`])([^'"`]*)\1\s*\)""")

#: The dead-guard pattern: testing `load` for callability.
TYPEOF_LOAD = re.compile(r"\btypeof\s+load\b")


def _strip_js_comments_and_strings(text: str) -> str:
    """Blanks comments and string literals, keeping newlines, so code can be inspected.

    Needed because the prose that explains this very bug mentions `typeof load`, and a plain text
    search would then flag the comment instead of the code.
    """
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        char = text[i]
        following = text[i + 1] if i + 1 < n else ""
        if char == "/" and following == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif char == "/" and following == "*":
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            i += 2
        elif char in ("'", '"', "`"):
            quote = char
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            out.append(" ")
        else:
            out.append(char)
            i += 1
    return "".join(out)


def compare_codec_bootstrap(ext: dict) -> list[str]:
    """translate.js must be able to reach the gRPC-Web codec at runtime, on a real device."""
    ext_name: str = ext["name"]
    src_dir: Path = ext["dir"] / "src"
    translate_path = src_dir / "translate.js"
    wire_path = src_dir / "wire.js"

    if not translate_path.exists():
        # A missing script is already reported by the artifact comparison; nothing to inspect.
        return []

    raw = translate_path.read_text(encoding="utf-8")
    problems: list[str] = []

    if not wire_path.exists():
        problems.append("  src/wire.js is missing, so translate.js has no gRPC-Web codec")

    if TYPEOF_LOAD.search(_strip_js_comments_and_strings(raw)):
        problems.append(
            "  src/translate.js tests `typeof load`: load() is a pre-run text substitution in vBook, "
            "not a callable global, so anything behind that guard never runs and the codec never loads"
        )

    targets = [match.group(2) for match in LOAD_INVOCATION.finditer(raw)]
    if "wire.js" not in targets:
        problems.append(
            "  src/translate.js never loads wire.js: without the codec every chapter falls back to "
            "QuickTranslator even while the host is running"
        )

    for target in targets:
        if "/" in target or "\\" in target:
            problems.append(
                f"  load({target!r}) names a path, but vBook only resolves files at the top level of "
                f"src/; this call is replaced by nothing"
            )
        elif not (src_dir / target).exists():
            problems.append(
                f"  load({target!r}) names a file that is not in src/ — this call is replaced by nothing"
            )

    if wire_path.exists() and "VBookWire" not in _strip_js_comments_and_strings(
        wire_path.read_text(encoding="utf-8")
    ):
        problems.append("  src/wire.js does not define VBookWire, so the codec would not exist")

    if problems:
        problems.insert(0, f"[{ext_name}] the gRPC-Web codec would not load on a device:")
    return problems


def main() -> int:
    if "--fix" in sys.argv:
        sys.path.insert(0, str(ROOT / "scripts"))
        import package_extension
        package_extension.main()
        print()

    all_problems: list[str] = []
    for ext in EXTENSIONS:
        if not ext["dir"].exists():
            continue
        all_problems.extend(compare_artifact(ext))
        all_problems.extend(compare_settings(ext))
        all_problems.extend(compare_codec_bootstrap(ext))

    if all_problems:
        print("\n".join(all_problems))
        return 1

    print("All extension artifacts match their sources, and plugin.json files agree with src/config.js.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
