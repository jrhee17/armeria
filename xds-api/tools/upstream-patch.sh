#!/usr/bin/env bash
# Generate (and optionally apply) the upstream delta between two Envoy versions
# using jrhee17/armeria:xds-api/tools/update-*.sh
#
# Usage:
#   tools/make-upstream-delta.sh --base v1.32.0 --target v1.33.1 \
#       [--out /tmp/upstream-delta.patch] [--apply] [--branch name] [--paths "xds-api/"]
#
# Notes:
# - Works locally and in CI.
# - If --apply is used, a new branch is created (default: update-protobuf-to-<target>).
# - Conflicts (if any) are left as normal Git conflict markers for IntelliJ/GitHub UI.

set -euo pipefail

BASE_VER=""
TARGET_VER=""
PATCH_OUT="/tmp/upstream-delta.patch"
APPLY=false
BRANCH=""
PATHS_FILTER="xds-api/"   # limit the patch to this path (space-separated allowed)

die() { echo "error: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE_VER="${2:-}"; shift 2 ;;
    --target) TARGET_VER="${2:-}"; shift 2 ;;
    --out) PATCH_OUT="${2:-}"; shift 2 ;;
    --apply) APPLY=true; shift ;;
    --branch) BRANCH="${2:-}"; shift 2 ;;
    --paths) PATHS_FILTER="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '1,40p' "$0"; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac
done

[[ -n "$BASE_VER" && -n "$TARGET_VER" ]] || die "must provide --base and --target"
if $APPLY && [[ -z "$BRANCH" ]]; then
  BRANCH="update-protobuf-to-${TARGET_VER}"
fi

# Ensure we're in a git repo root
git rev-parse --git-dir >/dev/null 2>&1 || die "run inside a git repo"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# Record current HEAD so we can come back if needed
START_REF="$(git rev-parse --abbrev-ref HEAD || true)"

# Git identity (useful in CI; harmless locally if already set)
git config user.name  >/dev/null 2>&1 || git config user.name  "automation"
git config user.email >/dev/null 2>&1 || git config user.email "automation@example.com"

# Create two temporary worktrees on throwaway branches
ts="$(date +%s)"
WT_BASE="$ROOT/.wt-base-$ts"
WT_NEW="$ROOT/.wt-new-$ts"
BR_BASE="tmp/vendor-base-$ts"
BR_NEW="tmp/vendor-new-$ts"

git worktree add -b "$BR_BASE" "$WT_BASE" HEAD >/dev/null
git worktree add -b "$BR_NEW"  "$WT_NEW"  HEAD >/dev/null

cleanup() {
  # best-effort cleanup
  git worktree remove --force "$WT_BASE" 2>/dev/null || true
  git worktree remove --force "$WT_NEW"  2>/dev/null || true
  git branch -D "$BR_BASE" 2>/dev/null || true
  git branch -D "$BR_NEW"  2>/dev/null || true
}
trap cleanup EXIT

build_snapshot() {
  local wt_dir="$1" ver="$2" label="$3"
  pushd "$wt_dir/xds-api" >/dev/null

  # Optional: ensure a clean tree in the areas update-api.sh touches.
  # If your upstream script already does a clean sync, you can skip this.
  # git clean -fdx xds-api/ || true

  pushd tools
  ( ./tools/update-sha.sh "$ver" > API_SHAS && ./tools/update-api.sh )
  popd

  git add -A
  if git diff --cached --quiet; then
    echo "[$label] no changes staged (version $ver) - committing empty marker"
    git commit --allow-empty -m "vendor: Envoy $ver ($label)"
  else
    git commit -m "vendor: Envoy $ver ($label)"
  fi
  local sha; sha="$(git rev-parse HEAD)"
  popd >/dev/null
  echo "$sha"
}

echo "== Building BASE snapshot ($BASE_VER)"
BASE_SHA="$(build_snapshot "$WT_BASE" "$BASE_VER" "BASE")"
echo "BASE @ $BASE_SHA"

echo "== Building TARGET snapshot ($TARGET_VER)"
NEW_SHA="$(build_snapshot "$WT_NEW" "$TARGET_VER" "TARGET")"
echo "TARGET @ $NEW_SHA"

# Create a binary patch limited to the path(s) you care about
echo "== Creating binary patch $PATCH_OUT"
# Build pathspec array safely (supports multiple space-separated paths)
read -r -a PATHS <<< "$PATHS_FILTER"
git diff --binary "$BASE_SHA" "$NEW_SHA" -- "${PATHS[@]}" > "$PATCH_OUT" || true

if [[ ! -s "$PATCH_OUT" ]]; then
  echo "No upstream changes under: ${PATHS[*]}"
fi
echo "Patch size: $(stat -c%s "$PATCH_OUT" 2>/dev/null || wc -c <"$PATCH_OUT") bytes"

if $APPLY; then
  echo "== Applying patch with 3-way merge on new branch: $BRANCH"
  git switch -c "$BRANCH" >/dev/null 2>&1 || git switch "$BRANCH"
  # --3way uses blob ids embedded in the patch to merge; leaves conflicts if needed
  if git apply --3way --index "$PATCH_OUT"; then
    git commit -m "vendor: Envoy $BASE_VER -> $TARGET_VER"
    echo "Applied and committed. Branch: $BRANCH"
  else
    echo "Conflicts detected. Resolve them in your Git UI, then:"
    echo "    git add -A && git commit -m 'vendor: Envoy $BASE_VER -> $TARGET_VER (resolved)'"
    exit 2
  fi
else
  echo "Patch generated only. To apply later:"
  echo "  git switch -c $BRANCH"
  echo "  git apply --3way --index $PATCH_OUT"
  echo "  git commit -m 'vendor: Envoy $BASE_VER → $TARGET_VER'"
fi
