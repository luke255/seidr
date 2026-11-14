#!/usr/bin/env bash
# Publishes the version in gradle.properties as a GitHub release:
#   1. checks the tree is clean, on main, and the version isn't released yet;
#   2. builds the signed APK and version.json (./gradlew :app:dist);
#   3. pushes main, then creates the release as a draft, uploads both files,
#      and publishes it (which creates the tag). Installed copies see it on
#      their next update check.
#
# Needs: the release keystore in ~/.config/seidr/, ANDROID_HOME, and a GitHub
# token with Contents read/write on the repo, in GITHUB_TOKEN or
# ~/.config/seidr/github-token. Release notes are the commit subjects since the
# previous release; edit them on GitHub afterwards if you like.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "release: $*" >&2; exit 1; }

version=$(sed -n 's/^seidr\.version=//p' gradle.properties)
repo=$(sed -n 's/^seidr\.repo=//p' gradle.properties)
tag="v$version"
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "seidr.version in gradle.properties isn't major.minor.patch: '$version'"

token=${GITHUB_TOKEN:-$(cat "$HOME/.config/seidr/github-token" 2>/dev/null || true)}
[[ -n $token ]] || die "no token: set GITHUB_TOKEN or save one to ~/.config/seidr/github-token"

[[ -z $(git status --porcelain) ]] || die "uncommitted changes; commit or stash them first"
[[ $(git rev-parse --abbrev-ref HEAD) == main ]] || die "release from main"

api() {
    curl -sS --fail-with-body -H "Authorization: Bearer $token" -H "Accept: application/vnd.github+json" \
        -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}
json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

# A 401 here usually means the token has expired.
status=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" \
    "https://api.github.com/repos/$repo/releases/tags/$tag")
case $status in
    200) die "$tag is already released: bump seidr.version in gradle.properties" ;;
    404) ;;
    401) die "GitHub refused the token (expired?). Make a new one and save it to ~/.config/seidr/github-token" ;;
    *) die "GitHub answered $status when checking for $tag" ;;
esac

./gradlew --console=plain --quiet :app:dist
apk="build/dist/seidr-$version.apk"
[[ -f $apk && -f build/dist/version.json ]] || die "dist didn't produce $apk"

git fetch --quiet --tags origin
prev=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)
notes=$(git log --no-merges --format='- %s' ${prev:+$prev..}HEAD)
git push --quiet origin main
sha=$(git rev-parse HEAD)

payload=$(TAG="$tag" SHA="$sha" NAME="Seiðr $version" NOTES="$notes" python3 -c '
import json, os
print(json.dumps({"tag_name": os.environ["TAG"], "target_commitish": os.environ["SHA"],
                  "name": os.environ["NAME"], "body": os.environ["NOTES"], "draft": True}))')
id=$(api -X POST "https://api.github.com/repos/$repo/releases" -d "$payload" | json '["id"]')

for f in "$apk" build/dist/version.json; do
    api -X POST -H "Content-Type: application/octet-stream" --data-binary "@$f" \
        "https://uploads.github.com/repos/$repo/releases/$id/assets?name=$(basename "$f")" > /dev/null
done

url=$(api -X PATCH "https://api.github.com/repos/$repo/releases/$id" -d '{"draft": false, "make_latest": "true"}' | json '["html_url"]')
git fetch --quiet --tags origin
echo "released $tag: $url"
echo "apk: $PWD/$apk"
