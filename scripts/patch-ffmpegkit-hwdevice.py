#!/usr/bin/env python3
"""Fix FFmpegKit's vendored fftools OpenCL/hwupload device propagation.

The Android FFmpegKit tree carries a vendored copy of fftools.  FFmpeg 8.x
requires AVFilterContext.hw_device_ctx to be set before a HWDEVICE filter is
initialized.  This patch ports the upstream ordering change and deliberately
assigns the selected device to every parsed filter, matching the older
FFmpegKit behavior, so the fix is robust even if a carried filter's public
flag differs from upstream.
"""
from pathlib import Path
import sys

MARKER = "FFMPEGKIT_HWDEVICE_BEFORE_FILTER_INIT_V9"


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
        print("FFmpegKit hw-device propagation v9 patch already installed.")
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
        " * Get the hardware device to use for this filtergraph.\n"
        " * The returned reference is owned by the hardware-device registry;\n"
        " * callers must av_buffer_ref() it when retaining it.\n"
        " */\n"
        "AVBufferRef *hw_device_for_filter(void);\n"
    )
    header_text = replace_once(
        header_text,
        header_old,
        header_new,
        "fftools_ffmpeg.h hw-device declarations",
    )

    # Replace the old post-graph setup with the upstream selection helper.
    hw_start = "int hw_device_setup_for_filter(FilterGraph *fg)\n{"
    start = hw_text.find(hw_start)
    if start < 0:
        raise SystemExit("ERROR: hw_device_setup_for_filter() not found")

    # This function is the final function in the vendored file in this build.
    old_hw = hw_text[start:].rstrip() + "\n"
    new_hw = f'''AVBufferRef *hw_device_for_filter(void)
{{
    // {MARKER}
    // Match upstream FFmpeg: explicit -filter_hw_device wins; otherwise
    // select the most recently initialized hardware device.
    if (filter_hw_device) {{
        av_log(NULL, AV_LOG_VERBOSE,
               "[FFMPEGKIT-HWDEVICE] explicit filter device: %s\\n",
               filter_hw_device->name);
        return filter_hw_device->device_ref;
    }}

    if (nb_hw_devices > 0) {{
        HWDevice *dev = hw_devices[nb_hw_devices - 1];

        if (nb_hw_devices > 1)
            av_log(NULL, AV_LOG_WARNING,
                   "There are %d hardware devices. device %s of type %s is "
                   "picked for filters by default. Set hardware device "
                   "explicitly with the filter_hw_device option if device %s "
                   "is not usable for filters.\\n",
                   nb_hw_devices, dev->name,
                   av_hwdevice_get_type_name(dev->type), dev->name);

        av_log(NULL, AV_LOG_VERBOSE,
               "[FFMPEGKIT-HWDEVICE] selected device: %s type=%s ref=%p\\n",
               dev->name, av_hwdevice_get_type_name(dev->type),
               (void *)dev->device_ref);
        return dev->device_ref;
    }}

    av_log(NULL, AV_LOG_ERROR,
           "[FFMPEGKIT-HWDEVICE] no hardware device is registered for filters.\\n");
    return NULL;
}}
'''
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
    filter_text = replace_once(
        filter_text,
        filter_sig_old,
        filter_sig_new,
        "graph_parse() signature",
    )

    create_old = (
        "    ret = avfilter_graph_segment_create_filters(seg, 0);\n"
        "    if (ret < 0)\n"
        "        goto fail;\n\n"
        "    ret = graph_opts_apply(seg);\n"
    )
    create_new = f'''    ret = avfilter_graph_segment_create_filters(seg, 0);
    if (ret < 0)
        goto fail;

    // {MARKER}
    // CRITICAL: AVFilterContext.hw_device_ctx must be assigned before
    // avfilter_graph_segment_init() reaches hwupload/unsharp_opencl/etc.
    // Use the parsed segment's filter contexts directly and assign the
    // selected device to every filter, matching legacy FFmpegKit behavior.
    if (hw_device) {{
        int assigned = 0;
        for (size_t si = 0; si < seg->nb_chains; si++) {{
            AVFilterChain *ch = seg->chains[si];
            for (size_t fi = 0; fi < ch->nb_filters; fi++) {{
                AVFilterParams *p = ch->filters[fi];
                AVFilterContext *f = p->filter;

                if (!f)
                    continue;

                f->hw_device_ctx = av_buffer_ref(hw_device);
                if (!f->hw_device_ctx) {{
                    ret = AVERROR(ENOMEM);
                    goto fail;
                }}

                assigned++;
                av_log(f, AV_LOG_VERBOSE,
                       "[FFMPEGKIT-HWDEVICE] attached device to filter %s (flags=0x%x, ref=%p)\\n",
                       f->name, f->filter->flags, (void *)f->hw_device_ctx);
            }}
        }}
        av_log(NULL, AV_LOG_VERBOSE,
               "[FFMPEGKIT-HWDEVICE] attached selected device to %d filter(s).\\n",
               assigned);
    }} else {{
        av_log(NULL, AV_LOG_VERBOSE,
               "[FFMPEGKIT-HWDEVICE] graph_parse() has no filter device.\\n");
    }}

    ret = graph_opts_apply(seg);
'''
    filter_text = replace_once(
        filter_text,
        create_old,
        create_new,
        "graph_parse() filter creation block",
    )

    # Temporary topology graph does not need a hardware device.
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
    config_new = f'''    // {MARKER}
    // Select the hardware device BEFORE parsing/initializing any filter.
    AVBufferRef *hw_device = hw_device_for_filter();

    if ((ret = graph_parse(fg->graph, graph_desc, &inputs, &outputs, hw_device)) < 0)
        goto fail;
'''
    filter_text = replace_once(
        filter_text,
        config_old,
        config_new,
        "configure_filtergraph() hw-device setup",
    )

    # Make the old symbol impossible to accidentally leave referenced.
    if "hw_device_setup_for_filter" in filter_text:
        raise SystemExit("ERROR: old hw_device_setup_for_filter reference remains in filter source")

    filter_path.write_text(filter_text)
    hw_path.write_text(hw_text)
    header_path.write_text(header_text)
    print("FFmpegKit hw-device propagation v9 patch installed.")


if __name__ == "__main__":
    main()
