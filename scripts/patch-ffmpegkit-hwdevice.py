#!/usr/bin/env python3
"""Port FFmpeg's pre-filter-init hw_device_ctx propagation into FFmpegKit's Android fftools copy."""
from pathlib import Path
import re
import sys

MARKER = "FFMPEGKIT_HWDEVICE_BEFORE_FILTER_INIT"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"ERROR: {label} pattern not found")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: patch-ffmpegkit-hwdevice.py FILTER_C HW_C HEADER")

    filter_path, hw_path, header_path = map(Path, sys.argv[1:])
    filter_text = filter_path.read_text()
    hw_text = hw_path.read_text()
    header_text = header_path.read_text()

    if all(MARKER in t for t in (filter_text, hw_text, header_text)):
        print("FFmpegKit hw-device propagation patch already installed.")
        return

    header_old = (
        "int hw_device_setup_for_decode(InputStream *ist);\n"
        "int hw_device_setup_for_encode(OutputStream *ost);\n"
        "int hw_device_setup_for_filter(FilterGraph *fg);\n"
    )
    header_new = (
        "int hw_device_setup_for_decode(InputStream *ist);\n"
        "int hw_device_setup_for_encode(OutputStream *ost);\n"
        "/**\n"
        f" * {MARKER}\n"
        " * Get a hardware device to be used with this filtergraph.\n"
        " * The returned reference is owned by the callee; callers must ref it\n"
        " * when storing it beyond the immediate use.\n"
        " */\n"
        "AVBufferRef *hw_device_for_filter(void);\n"
    )
    header_text = replace_once(header_text, header_old, header_new, "fftools_ffmpeg.h hw-device declarations")

    # Replace the FFmpegKit/FFmpeg-6-era function with the upstream API shape.
    hw_start = "int hw_device_setup_for_filter(FilterGraph *fg)\n{"
    start = hw_text.find(hw_start)
    if start < 0:
        raise SystemExit("ERROR: hw_device_setup_for_filter() not found")

    # The function is the final function in the FFmpegKit copy used by this build.
    old_hw = hw_text[start:].rstrip() + "\n"
    if not old_hw.endswith("}\n"):
        raise SystemExit("ERROR: unexpected hw_device_setup_for_filter() layout")

    new_hw = f'''AVBufferRef *hw_device_for_filter(void)\n{{\n    // {MARKER}\n    // Pick the explicitly selected filter device first. Otherwise use the\n    // last initialized hardware device, matching FFmpeg's normal behavior.\n    if (filter_hw_device)\n        return filter_hw_device->device_ref;\n    if (nb_hw_devices > 0) {{\n        HWDevice *dev = hw_devices[nb_hw_devices - 1];\n\n        if (nb_hw_devices > 1)\n            av_log(NULL, AV_LOG_WARNING, "There are %d hardware devices. device "\n                   "%s of type %s is picked for filters by default. Set hardware "\n                   "device explicitly with the filter_hw_device option if device "\n                   "%s is not usable for filters.\\n",\n                   nb_hw_devices, dev->name,\n                   av_hwdevice_get_type_name(dev->type), dev->name);\n\n        return dev->device_ref;\n    }}\n    return NULL;\n}}\n'''
    hw_text = hw_text[:start] + new_hw

    filter_sig_old = (
        "static int graph_parse(AVFilterGraph *graph, const char *desc,\n"
        "                       AVFilterInOut **inputs, AVFilterInOut **outputs)\n"
    )
    filter_sig_new = (
        "static int graph_parse(AVFilterGraph *graph, const char *desc,\n"
        "                       AVFilterInOut **inputs, AVFilterInOut **outputs,\n"
        "                       AVBufferRef *hw_device)\n"
    )
    filter_text = replace_once(filter_text, filter_sig_old, filter_sig_new, "graph_parse() signature")

    create_old = (
        "    ret = avfilter_graph_segment_create_filters(seg, 0);\n"
        "    if (ret < 0)\n"
        "        goto fail;\n\n"
        "    ret = graph_opts_apply(seg);\n"
    )
    create_new = f'''    ret = avfilter_graph_segment_create_filters(seg, 0);\n    if (ret < 0)\n        goto fail;\n\n    // {MARKER}\n    // FFmpeg 8.x hwupload initializes before the graph is applied.\n    // Attach the selected device before any HWDEVICE filter init hook runs.\n    if (hw_device) {{\n        for (int i = 0; i < graph->nb_filters; i++) {{\n            AVFilterContext *f = graph->filters[i];\n\n            if (!(f->filter->flags & AVFILTER_FLAG_HWDEVICE))\n                continue;\n\n            f->hw_device_ctx = av_buffer_ref(hw_device);\n            if (f->hw_device_ctx)\n                av_log(f, AV_LOG_INFO, "[FFMPEGKIT HWDEVICE] attached device to filter %s\\n",\n                       f->filter->name);\n            if (!f->hw_device_ctx) {{\n                ret = AVERROR(ENOMEM);\n                goto fail;\n            }}\n        }}\n    }}\n\n    ret = graph_opts_apply(seg);\n'''
    filter_text = replace_once(filter_text, create_old, create_new, "graph_parse() filter creation block")

    # Temporary topology graph: no real hw device needed here.
    filter_text = replace_once(
        filter_text,
        "    ret = graph_parse(graph, fg->graph_desc, &inputs, &outputs);\n",
        "    ret = graph_parse(graph, fg->graph_desc, &inputs, &outputs, NULL);\n",
        "init_complex_filtergraph() graph_parse call",
    )

    config_old = (
        "    if ((ret = graph_parse(fg->graph, graph_desc, &inputs, &outputs)) < 0)\n"
        "        goto fail;\n\n"
        "    ret = hw_device_setup_for_filter(fg);\n"
        "    if (ret < 0)\n"
        "        goto fail;\n"
    )
    config_new = (
        "    AVBufferRef *hw_device = hw_device_for_filter();\n\n"
        "    if ((ret = graph_parse(fg->graph, graph_desc, &inputs, &outputs, hw_device)) < 0)\n"
        "        goto fail;\n"
    )
    filter_text = replace_once(filter_text, config_old, config_new, "configure_filtergraph() hw-device setup")

    filter_path.write_text(filter_text)
    hw_path.write_text(hw_text)
    header_path.write_text(header_text)
    print("FFmpegKit hw-device propagation patch installed.")


if __name__ == "__main__":
    main()
