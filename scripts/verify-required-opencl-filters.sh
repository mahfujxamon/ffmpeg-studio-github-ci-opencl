#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/libavfilter.so" >&2
  exit 2
fi

SO="$1"
test -f "$SO"

required=(
  scale_opencl
  overlay_opencl
  unsharp_opencl
  avgblur_opencl
  convolution_opencl
  nlmeans_opencl
  deshake_opencl
  tonemap_opencl
  pad_opencl
)

mapfile -t filters < <(strings -a "$SO" | grep -E '^[A-Za-z0-9_]+_opencl$' | sort -u)

echo "OpenCL filters in $(basename "$SO"):"
printf '  %s\n' "${filters[@]}"

missing=0
for filter in "${required[@]}"; do
  if printf '%s\n' "${filters[@]}" | grep -Fxq "$filter"; then
    echo "PASS  $filter"
  else
    echo "FAIL  $filter"
    missing=1
  fi
done

exit "$missing"
