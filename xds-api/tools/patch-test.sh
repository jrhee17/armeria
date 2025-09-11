#!/usr/bin/env bash
./upstream-patch.sh \
  --base v1.34.0 \
  --target v1.35.3 \
  --out /tmp/upstream-delta.patch
#  --apply \
#  --paths "xds-api/"   # adjust if you want to limit further
