#!/usr/bin/env python3
"""
Packages vbook-extension/ into ready-to-install zip files:
- vbook-extension/plugin.zip (Standard vBook repo structure)
- build/vbookmt-extension.zip (Direct release asset)
"""

import sys
import zipfile
from pathlib import Path

def package_extension(ext_dir: Path, output_zip: Path):
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in ext_dir.rglob("*"):
            # Exclude zip itself and hidden files
            if file.is_file() and not file.name.startswith(".") and not file.suffix == ".zip":
                arcname = file.relative_to(ext_dir)
                zf.write(file, arcname)
                print(f"  Added: {arcname}")
                
    print(f"[+] Extension packaged: {output_zip} ({output_zip.stat().st_size} bytes)")

def main():
    root = Path(__file__).resolve().parent.parent
    ext_dir = root / "vbook-extension"
    
    out_files = [
        ext_dir / "plugin.zip",
        root / "build" / "vbookmt-extension.zip"
    ]
    
    if len(sys.argv) > 1:
        out_files = [Path(sys.argv[1])]

    for out in out_files:
        print(f"[*] Packaging vBook extension to {out} ...")
        package_extension(ext_dir, out)

if __name__ == "__main__":
    main()
