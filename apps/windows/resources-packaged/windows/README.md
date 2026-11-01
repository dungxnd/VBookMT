Native engine DLLs land here, produced by scripts/build_windows_native.ps1.

This file only keeps the directory in git: the DLLs themselves are gitignored (*.dll)
because they are build output, not source. Compose copies everything under the matching
OS subdirectory (windows/, macos/, linux/) into the packaged app root, which is where
WindowsCt2Engine.nativeDir() looks for them.
