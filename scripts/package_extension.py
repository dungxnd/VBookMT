#!/usr/bin/env python3
"""
Packages the VBookMT browser extension into ready-to-install zip files.

There is exactly one edition — the companion-host build in `vbook-extension/` — and one zip format.
The script used to advertise a second "WebGPU standalone" edition built from `vbook-extension-webgpu/`,
a directory that has never existed in this repo; the duplicate named zips it produced
(`vbookmt-companion.zip`, and an older `vbookmt-webgpu.zip`) were byte-identical copies of the same
tree, which made it look like two products shipped when only one did.

Outputs `vbook-extension/plugin.zip` (the file `plugin.json` points at) plus a copy in `build/`.
"""

import sys
import zipfile
from pathlib import Path


def normalise_bytes(data: bytes) -> bytes:
    """Line endings to LF, so the archive is byte-identical on every platform.

    The zip is a committed artifact whose contents are hash-verified against the sources. Those
    sources arrive with CRLF in a Windows worktree (core.autocrlf=true) and LF on Linux CI, so packing
    the raw worktree bytes made the artifact platform-specific: built on Windows it carried CRLF, and
    CI's LF checkout then reported every file as stale. LF is what an install receives everywhere, so
    the archive is built from that regardless of who packages it.
    """
    return data.replace(b"\r\n", b"\n")


def package_extension(ext_dir: Path, output_zip: Path):
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in sorted(ext_dir.rglob("*")):
            # Exclude zip itself, hidden files, and __pycache__
            if (
                file.is_file()
                and not file.name.startswith(".")
                and file.suffix != ".zip"
                and "__pycache__" not in file.parts
            ):
                arcname = file.relative_to(ext_dir)
                # Normalise line endings to LF before writing.
                #
                # The zip is a committed artifact verified by hashing it against the sources, and
                # those sources land in the worktree with CRLF on Windows (core.autocrlf=true) but LF
                # on Linux CI. Writing whatever the worktree holds made the artifact platform-specific:
                # built here, it carried CRLF and CI's LF checkout reported every file as stale. LF is
                # the byte sequence every install receives, so the archive is built from that
                # regardless of the machine doing the packaging.
                zf.writestr(arcname.as_posix(), normalise_bytes(file.read_bytes()))
                print(f"  Added: {arcname}")
                
    print(f"[+] Extension packaged: {output_zip} ({output_zip.stat().st_size} bytes)")


def main():
    root = Path(__file__).resolve().parent.parent
    build_dir = root / "build"
    build_dir.mkdir(parents=True, exist_ok=True)

    ext_dir = root / "vbook-extension"

    if len(sys.argv) > 1 and not sys.argv[1].startswith("-"):
        custom_out = Path(sys.argv[1])
        print(f"[*] Packaging extension to {custom_out} ...")
        package_extension(ext_dir, custom_out)
        return

    print("[*] Packaging VBookMT Extension ...")
    package_extension(ext_dir, ext_dir / "plugin.zip")
    # One copy in build/ for the release workflow to attach, under the same name the docs use. The
    # old code also wrote `vbookmt-companion.zip` and (in an earlier version) `vbookmt-webgpu.zip` from
    # this same directory, which implied three products where there is one.
    package_extension(ext_dir, build_dir / "vbookmt-extension.zip")


if __name__ == "__main__":
    main()
