#!/usr/bin/env bash
set -euo pipefail

test "$GITHUB_REPOSITORY" = doccstat/repose
test "$GITHUB_REF" = refs/heads/main
# An old rerun must not replace a newer main push's assets.
head_sha=$(gh api "repos/$GITHUB_REPOSITORY/commits/main" --jq .sha)
if [ "$head_sha" != "$GITHUB_SHA" ]; then
  echo 'A newer main commit exists; skipping this superseded build.'
  exit 0
fi

tag=$(python3 -B build.py --version)
version=${tag#*-}
(cd dist && sha256sum -c Repose.apk.sha256 Repose-module.zip.sha256)
cat >dist/release-notes.md <<EOF
## Repose $version

- Pause a selected work profile and fully reboot into Before First Unlock (BFU).
- Configurable time since the last unlock, always on or scheduled, with snooze/skip.
- Independent 24/7 failed-unlock thresholds and optional SystemUI panic PIN/password.
- SMS/HTTPS commands with selectable passphrase and signed one-use authentication.
- Native settings and credential-redacted diagnostic export.

Requires Android 13+, ARM64 and Magisk or KernelSU. Install the APK and module ZIP
from this release together. Vector/LSPosed is needed for hook-based features.
A full reboot clears temporary root; enforcement resumes when root and the module
service are restored. All rules start disabled.

The APK uses the permanent release key. SHA-256 checksums accompany both files.
EOF

assets=(dist/Repose.apk dist/Repose.apk.sha256 dist/Repose-module.zip dist/Repose-module.zip.sha256)
releases=$(gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases?per_page=100")
if jq -e --arg tag "$tag" '[.[][] | select(.tag_name == $tag)] | length > 0' <<<"$releases" >/dev/null; then
  gh api "repos/$GITHUB_REPOSITORY/git/refs/tags/$tag" --method PATCH \
    -f "sha=$GITHUB_SHA" -F force=true
  gh release upload "$tag" --repo "$GITHUB_REPOSITORY" --clobber "${assets[@]}"
  gh release edit "$tag" --repo "$GITHUB_REPOSITORY" \
    --title "$version" --notes-file dist/release-notes.md \
    --draft=false --prerelease=false --latest
else
  gh release create "$tag" --repo "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" \
    --title "$version" --notes-file dist/release-notes.md \
    --draft=false --prerelease=false --latest "${assets[@]}"
fi
# GITHUB_TOKEN release writes do not trigger release-event workflows.
gh workflow run distribute-release.yml --repo "$GITHUB_REPOSITORY" --ref main
