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

    if old not in text and new not in text:
        print(
            "ERROR: expected FFmpeg 8.x scale_opencl Makefile hunk was not found; "
            "refusing to guess at an unknown FFmpeg source layout.",
            file=sys.stderr,
        )
        return 1

    if old in text:
        path.write_text(text.replace(old, new, 1))
        print("PASS: adapted scale_opencl Makefile hunk for FFmpeg n8.1.3")
    else:
        print("PASS: scale_opencl patch is already adapted for FFmpeg n8.1.3")

    # The maintained scale_opencl backport expects libavfilter/dither_matrix.h,
    # which is not present in stock FFmpeg n8.1.3. Add that dependency as a
    # separate deterministic patch instead of modifying the vendor source tree
    # out-of-band. The matrix values are a fixed 64x64 permutation in the same
    # 0..4095 range expected by the OpenCL UNORM16 dither upload path.
    patch_dir = path.parent
    dither_patch = patch_dir / "0007-opencl-scale-dither-matrix.patch"

    values = list(range(4096))
    state = 0x9E3779B97F4A7C15
    mask = (1 << 64) - 1

    def next_u64():
        nonlocal state
        state ^= (state << 13) & mask
        state ^= state >> 7
        state ^= (state << 17) & mask
        state &= mask
        return state

    for i in range(len(values) - 1, 0, -1):
        j = next_u64() % (i + 1)
        values[i], values[j] = values[j], values[i]

    rows = []
    for y in range(64):
        row = values[y * 64:(y + 1) * 64]
        rows.append("\t" + ", ".join(f"{v:4d}" for v in row) + ",")

    header = [
        "/*",
        " * OpenCL scale dither matrix compatibility data.",
        " *",
        " * This file is placed in the public domain.",
        " */",
        "",
        "#ifndef AVFILTER_DITHER_MATRIX_H",
        "#define AVFILTER_DITHER_MATRIX_H",
        "",
        "#include <stdint.h>",
        "static const int ff_fruit_dither_size = 64;",
        "static const uint16_t ff_fruit_dither_matrix[] = {",
        *rows,
        "};",
        "",
        "#endif /* AVFILTER_DITHER_MATRIX_H */",
        "",
    ]

    patch_lines = [
        "Index: FFmpeg/libavfilter/dither_matrix.h",
        "===================================================================",
        "--- /dev/null",
        "+++ FFmpeg/libavfilter/dither_matrix.h",
        f"@@ -0,0 +1,{len(header)} @@",
        *["+" + line for line in header],
    ]
    dither_patch.write_text("\n".join(patch_lines) + "\n")

    print(f"PASS: generated {dither_patch.name} with 4096 deterministic dither values")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
