#!/usr/bin/env python3
"""Compatibility trim for the maintained FFmpeg 8.x scale_opencl backport.

FFmpeg n8.1.3 does not define AV_PIX_FMT_NV15. The maintained backport includes
an optional compact-NV15 two-pass path that cannot compile against stock n8.1.3.
This helper removes only that optional path and keeps the normal YUV420P/NV12/
P010/P016 OpenCL scaler, including its dithering support.
"""
from pathlib import Path
import sys

MARKER = "/* FFMPEGKIT_N813_OPENCL_SCALE_COMPAT */"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count == 0:
        raise SystemExit(f"ERROR: compatibility anchor not found: {label}")
    if count > 1:
        raise SystemExit(f"ERROR: compatibility anchor is ambiguous ({count}): {label}")
    return text.replace(old, new, 1)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} /path/to/vf_scale_opencl.c", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"ERROR: source file not found: {path}", file=sys.stderr)
        return 2

    text = path.read_text()
    if MARKER in text:
        print("PASS: n8.1.3 scale_opencl compatibility already installed")
        return 0

    original = text

    text = replace_once(
        text,
        "    AV_PIX_FMT_NV15,\n",
        "",
        "AV_PIX_FMT_NV15 supported format",
    )

    # The helper that converts compact NV15 into a normal semi-planar format is
    # referenced only by the NV15 path, so remove the whole function.
    start = text.find("static av_cold int init_tmp_hwframes_ctx(")
    if start < 0:
        raise SystemExit("ERROR: init_tmp_hwframes_ctx function not found")
    end = text.find("static int scale_opencl_init(", start)
    if end < 0:
        raise SystemExit("ERROR: scale_opencl_init anchor not found")
    text = text[:start] + text[end:]

    # Remove the first-pass compact-NV15 program setup from scale_opencl_init().
    start = text.find("    if (ctx->in_fmt == AV_PIX_FMT_NV15) {")
    if start < 0:
        raise SystemExit("ERROR: NV15 init block not found")
    end = text.find("    if (ctx->src_w == ctx->dst_w && ctx->src_h == ctx->dst_h) {", start)
    if end < 0:
        raise SystemExit("ERROR: scale kernel selection anchor not found")
    text = text[:start] + text[end:]

    # Remove second-pass kernel setup. The standard kernel remains unchanged.
    start = text.find("    if (ctx->kernel_name_nv15) {")
    if start < 0:
        raise SystemExit("ERROR: NV15 kernel setup block not found")
    end = text.find("    ctx->initialised = 1;", start)
    if end < 0:
        raise SystemExit("ERROR: scale initialization completion anchor not found")
    text = text[:start] + text[end:]

    # This output-format guard is now unreachable because NV15 is no longer a
    # supported format, and the identifier itself does not exist in n8.1.3.
    text = replace_once(
        text,
        '''        if (out_format == AV_PIX_FMT_NV15) {\n            av_log(ctx, AV_LOG_ERROR, "Unsupported output format: %s\\n",\n                   av_get_pix_fmt_name(out_format));\n            return AVERROR(ENOSYS);\n        }\n''',
        "",
        "NV15 output guard",
    )

    # Replace the two-pass NV15 frame branch with the standard single-pass path.
    start = text.find("    if (ctx->in_fmt == AV_PIX_FMT_NV15 && (has_crop || has_scale)) {")
    if start < 0:
        raise SystemExit("ERROR: NV15 frame branch not found")
    end = text.find("    cle = clFinish(ctx->command_queue);", start)
    if end < 0:
        raise SystemExit("ERROR: command queue finish anchor not found")
    replacement = '''    if ((err = scale_opencl_run_kernel(inlink, input, output,\n                                       ctx->in_planes, ctx->out_planes, 0, 0)) < 0)\n        goto fail;\n\n'''
    text = text[:start] + replacement + text[end:]

    # Keep the standard passthrough optimization without the removed NV15 locals.
    text = replace_once(
        text,
        '''    if (ctx->passthrough && !has_scale && ctx->in_fmt == ctx->out_fmt)
        return ff_filter_frame(outlink, input);
''',
        '''    if (ctx->passthrough &&
        ctx->src_w == ctx->dst_w &&
        ctx->src_h == ctx->dst_h &&
        ctx->in_fmt == ctx->out_fmt)
        return ff_filter_frame(outlink, input);
''',
        "passthrough condition",
    )
    text = replace_once(
        text,
        '''    int has_crop = input->crop_left || input->crop_right ||
                   input->crop_top || input->crop_bottom;
    int has_scale = !(ctx->src_w == ctx->dst_w && ctx->src_h == ctx->dst_h);
''',
        "",
        "removed NV15 frame locals",
    )

    text = text.rstrip() + "\n" + MARKER + "\n"

    if text == original:
        raise SystemExit("ERROR: compatibility transform made no changes")

    path.write_text(text)
    print("PASS: n8.1.3 scale_opencl compatibility installed")
    print("PASS: removed unsupported AV_PIX_FMT_NV15 path")
    print("PASS: retained standard YUV420P/NV12/P010/P016 OpenCL scale + dithering")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
