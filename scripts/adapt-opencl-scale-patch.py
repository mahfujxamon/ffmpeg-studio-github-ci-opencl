#!/usr/bin/env python3
"""Adapt the maintained FFmpeg 8.x scale_opencl backport to FFmpeg n8.1.3.

The published backport was generated against an earlier FFmpeg 8.x tree. In
n8.1.3, the CUDA scaler Makefile object list gained `scale_filters.o`, changing
only the patch context for that hunk. The implementation and registration
changes are intentionally left untouched.
"""
from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} PATCH_FILE", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"ERROR: patch file not found: {path}", file=sys.stderr)
        return 2

    text = path.read_text()

    old = """@@ -476,6 +476,7 @@ OBJS-$(CONFIG_SCALE_D3D12_FILTER)
 OBJS-$(CONFIG_SCALE_CUDA_FILTER)             += vf_scale_cuda.o scale_eval.o \\
                                                 vf_scale_cuda.ptx.o cuda/load_helper.o
 OBJS-$(CONFIG_SCALE_NPP_FILTER)              += vf_scale_npp.o scale_eval.o
+OBJS-$(CONFIG_SCALE_OPENCL_FILTER)           += vf_scale_opencl.o opencl.o opencl/scale.o scale_eval.o
 OBJS-$(CONFIG_SCALE_QSV_FILTER)              += vf_vpp_qsv.o
 OBJS-$(CONFIG_SCALE_VAAPI_FILTER)            += vf_scale_vaapi.o scale_eval.o vaapi_vpp.o
 OBJS-$(CONFIG_SCALE_VT_FILTER)               += vf_scale_vt.o scale_eval.o
"""

    new = """@@ -476,6 +476,7 @@ OBJS-$(CONFIG_SCALE_D3D12_FILTER)
 OBJS-$(CONFIG_SCALE_CUDA_FILTER)             += vf_scale_cuda.o scale_eval.o scale_filters.o \\
                                                 vf_scale_cuda.ptx.o cuda/load_helper.o
 OBJS-$(CONFIG_SCALE_NPP_FILTER)              += vf_scale_npp.o scale_eval.o
+OBJS-$(CONFIG_SCALE_OPENCL_FILTER)           += vf_scale_opencl.o opencl.o opencl/scale.o scale_eval.o
 OBJS-$(CONFIG_SCALE_QSV_FILTER)              += vf_vpp_qsv.o
 OBJS-$(CONFIG_SCALE_VAAPI_FILTER)            += vf_scale_vaapi.o scale_eval.o vaapi_vpp.o
 OBJS-$(CONFIG_SCALE_VT_FILTER)               += vf_scale_vt.o scale_eval.o
"""

    if old not in text:
        if new in text:
            print("PASS: scale_opencl patch is already adapted for FFmpeg n8.1.3")
            return 0
        print(
            "ERROR: expected FFmpeg 8.x scale_opencl Makefile hunk was not found; "
            "refusing to guess at an unknown FFmpeg source layout.",
            file=sys.stderr,
        )
        return 1

    path.write_text(text.replace(old, new, 1))
    print("PASS: adapted scale_opencl Makefile hunk for FFmpeg n8.1.3")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
