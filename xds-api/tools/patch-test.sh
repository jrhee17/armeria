#!/usr/bin/env bash
./upstream-patch.sh \
  --dryrun \
  --target v1.35.3 \
  --out /tmp/upstream-delta.patch
#  --apply \
#  --paths "xds-api/"   # adjust if you want to limit further
