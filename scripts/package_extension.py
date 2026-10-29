#!/usr/bin/env python3
"""
Packages vbook-extension/ into a ready-to-install vbookmt-extension.zip file.
"""

import sys
import zipfile
from pathlib import Path

def package_extension(ext_dir: Path, output_zip: Path):
    output_zip.parent.mkdir(parents=True, exist_ok=True)
    
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in ext_dir.rglob("*"):
            if file.is_file():
                arcname = file.relative_to(ext_dir)
                zf.write(file, arcname)
                print(f"  Added: {arcname}")
                
    print(f"[+] Extension packaged successfully: {output_zip} ({output_zip.stat().st_size} bytes)")

def main():
    root = Path(__file__).resolve().parent.parent
    ext_dir = root / "vbook-extension"
    out_file = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "build" / "vbookmt-extension.zip"
    
    print(f"[*] Packaging vBook extension from {ext_dir} to {out_file} ...")
    package_extension(ext_dir, out_file)

if __name__ == "__main__":
    main()
