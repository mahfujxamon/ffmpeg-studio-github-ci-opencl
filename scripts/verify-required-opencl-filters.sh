#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/libavfilter.so" >&2
  exit 2
fi

SO="$1"
test -f "$SO"

# FFmpeg n8.1.3's OpenCL family plus the maintained scale_opencl backport.
required=(
  avgblur_opencl
  boxblur_opencl
  colorkey_opencl
  convolution_opencl
  deshake_opencl
  dilation_opencl
  erosion_opencl
  nlmeans_opencl
  overlay_opencl
  pad_opencl
  prewitt_opencl
  program_opencl
  remap_opencl
  roberts_opencl
  scale_opencl
  sobel_opencl
  tonemap_opencl
  transpose_opencl
  unsharp_opencl
  xfade_opencl
)

mapfile -t filters < <(
  strings -a "$SO" \
    | grep -E '^[A-Za-z0-9_]+_opencl$' \
    | sort -u
)

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
