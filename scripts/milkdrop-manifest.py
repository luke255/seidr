#!/usr/bin/env python3
"""Writes app/src/main/assets/milkdrop/manifest.txt: the visualiser's presets
and textures, which the app downloads from projectM's repositories (PresetPack).

Each line is "<git blob sha1> <path>"; the app checks every file against it.
Only listed files are used, so leaving a preset out (an author asked, or it's
broken) means leaving it out here. Needs `gh` (GitHub's API, read-only).

    scripts/milkdrop-manifest.py [PRESETS_COMMIT [TEXTURES_COMMIT]]
"""
import json, os, subprocess, sys

PRESETS = "projectM-visualizer/presets-cream-of-the-crop"
TEXTURES = "projectM-visualizer/presets-milkdrop-texture-pack"
# Left out: transitions to black, which read as the music stopping.
EXCLUDE_PREFIXES = ("! Transition/",)


def gh(path):
    return json.loads(subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True).stdout)


def commit(repo, ref):
    return gh(f"repos/{repo}/commits/{ref}")["sha"]


def blobs(repo, sha):
    tree = gh(f"repos/{repo}/git/trees/{sha}?recursive=1")
    if tree["truncated"]:
        sys.exit(f"{repo}: tree listing truncated")
    return {e["path"]: e["sha"] for e in tree["tree"] if e["type"] == "blob"}


presets_sha = commit(PRESETS, sys.argv[1] if len(sys.argv) > 1 else "master")
textures_sha = commit(TEXTURES, sys.argv[2] if len(sys.argv) > 2 else "master")
lines = [f"# {PRESETS} {presets_sha}", f"# {TEXTURES} {textures_sha}"]
for path, sha in sorted(blobs(PRESETS, presets_sha).items()):
    if path.endswith(".milk") and not path.startswith(EXCLUDE_PREFIXES):
        lines.append(f"{sha} presets/{path}")
for path, sha in sorted(blobs(TEXTURES, textures_sha).items()):
    if path.startswith("textures/"):
        lines.append(f"{sha} {path}")

out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "milkdrop", "manifest.txt")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print(f"{len(lines) - 2} files -> {os.path.normpath(out)}")
