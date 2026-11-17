#!/usr/bin/env bash
set -euo pipefail

test "$GITHUB_REPOSITORY" = doccstat/repose
test -n "${LSPOSED_RELEASE_TOKEN:-}" || { echo '::error::Configure LSPOSED_RELEASE_TOKEN with listing write access.'; exit 1; }
repo=Xposed-Modules-Repo/com.lixingchi.repose
work=$(mktemp -d "$RUNNER_TEMP/repose-distribute.XXXXXX")
trap 'rm -rf "$work"' EXIT

# Always resolve Latest, including delayed events and manual retries.
gh api "repos/$GITHUB_REPOSITORY/releases/latest" >"$work/source-release.json"
tag=$(jq -r .tag_name "$work/source-release.json")
[[ "$tag" =~ ^[1-9][0-9]*-[0-9]+(\.[0-9]+)*$ ]]
test "$(jq -r .draft "$work/source-release.json")" = false
test "$(jq -r .prerelease "$work/source-release.json")" = false
title=$(jq -r .name "$work/source-release.json")
jq -r .body "$work/source-release.json" >"$work/release-notes.md"
names=(Repose.apk Repose.apk.sha256 Repose-module.zip Repose-module.zip.sha256)
gh release download "$tag" --repo "$GITHUB_REPOSITORY" \
  --pattern Repose.apk --pattern Repose.apk.sha256 \
  --pattern Repose-module.zip --pattern Repose-module.zip.sha256 --dir "$work"
python3 -B tools/verify_release.py --directory "$work" --tag "$tag" \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0"

export GH_TOKEN="$LSPOSED_RELEASE_TOKEN"
# The listing's signed documentation export must initialize main first.
gh api "repos/$repo/commits/main" --jq .sha >/dev/null
releases=$(gh api --paginate --slurp "repos/$repo/releases?per_page=100")
cd "$work"
if jq -e --arg tag "$tag" '[.[][] | select(.tag_name == $tag)] | length > 0' <<<"$releases" >/dev/null; then
  gh release upload "$tag" --repo "$repo" --clobber "${names[@]}"
  gh release edit "$tag" --repo "$repo" \
    --title "$title" --notes-file "$work/release-notes.md" \
    --draft=false --prerelease=false --latest
else
  gh release create "$tag" --repo "$repo" --target main \
    --title "$title" --notes-file "$work/release-notes.md" \
    --draft=false --prerelease=false --latest "${names[@]}"
fi
