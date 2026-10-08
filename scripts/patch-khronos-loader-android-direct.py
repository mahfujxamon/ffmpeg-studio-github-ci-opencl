#!/usr/bin/env python3
from pathlib import Path
import re
import sys


ICD_MARKER = "ffmpegkit_direct_provider_active"
DISPATCH_MARKER = "FFmpegKit Android direct-provider dispatch"


def patch_icd(path: Path) -> None:
    text = path.read_text()

    if ICD_MARKER not in text:
        include_anchor = '#include <string.h>\n'
        include_block = (
            '#include <string.h>\n'
            '#if defined(__ANDROID__)\n'
            '#include <pthread.h>\n'
            '#endif\n'
        )
        if include_anchor not in text:
            raise SystemExit("ERROR: icd.c string.h include anchor not found")
        text = text.replace(include_anchor, include_block, 1)

        state_anchor = "static int khrForceLegacyTermination = 0;\n"
        state_block = """static int khrForceLegacyTermination = 0;

#if defined(__ANDROID__)
/*
 * Android direct-provider bridge.
 *
 * Some OEM OpenCL implementations (notably certain MediaTek/ARM stacks)
 * export the normal OpenCL entry points but are not Khronos ICDs:
 *
 *   clGetPlatformIDs()                -> present
 *   clIcdGetPlatformIDsKHR()          -> absent
 *
 * The Khronos loader cannot safely treat such a library as an ICD because
 * the ICD ABI requires a vendor dispatch table and ICD-specific entry point.
 * Instead, FFmpeg calls the vendor library directly through this bridge.
 *
 * Discovery is intentionally symbol-only. No OpenCL API is called while
 * selecting the provider; the actual FFmpeg OpenCL path performs capability
 * validation afterwards.
 */
static void *ffmpegkitDirectLibrary = NULL;
static int ffmpegkitDirectMode = 0;
static pthread_once_t ffmpegkitDirectOnce = PTHREAD_ONCE_INIT;

static void ffmpegkitDirectProbeOnce(void)
{
    /*
     * New direct-provider channel. This is deliberately separate from
     * OCL_ICD_FILENAMES so the Khronos ICD enumerator never sees a non-ICD
     * Android vendor library.
     *
     * Keep OCL_ICD_FILENAMES as a compatibility fallback for older app builds.
     */
    char *filenames =
        khrIcd_secure_getenv("FFMPEGKIT_OPENCL_DIRECT_LIBRARY");
    char *cursor;

    if (!filenames)
        filenames = khrIcd_secure_getenv("OCL_ICD_FILENAMES");

    if (!filenames)
        return;

    cursor = filenames;
    while (cursor && *cursor) {
        char *next = strchr(cursor, PATH_SEPARATOR);
        void *library;
        void *platformIds;
        void *icdIds;

        if (next)
            *next = '\\0';

        if (*cursor == '\\0') {
            if (!next)
                break;
            cursor = next + 1;
            continue;
        }

        library = khrIcdOsLibraryLoad(cursor);
        if (!library) {
            if (!next)
                break;
            cursor = next + 1;
            continue;
        }

        platformIds =
            khrIcdOsLibraryGetFunctionAddress(library, "clGetPlatformIDs");
        icdIds =
            khrIcdOsLibraryGetFunctionAddress(library, "clIcdGetPlatformIDsKHR");

        if (platformIds && !icdIds) {
            /*
             * Keep this handle for the entire process lifetime. The returned
             * OpenCL platform/device objects may retain code/data references
             * into the vendor library after FFmpeg has created them.
             */
            ffmpegkitDirectLibrary = library;
            ffmpegkitDirectMode = 1;
            break;
        }

        khrIcdOsLibraryUnload(library);

        if (!next)
            break;
        cursor = next + 1;
    }

    khrIcd_free_getenv(filenames);
}

int ffmpegkit_direct_provider_active(void)
{
    pthread_once(&ffmpegkitDirectOnce, ffmpegkitDirectProbeOnce);
    return ffmpegkitDirectMode;
}

void *ffmpegkit_direct_get_proc(const char *functionName)
{
    if (!functionName || !ffmpegkit_direct_provider_active())
        return NULL;

    return khrIcdOsLibraryGetFunctionAddress(
        ffmpegkitDirectLibrary,
        functionName);
}

#else
int ffmpegkit_direct_provider_active(void)
{
    return 0;
}

void *ffmpegkit_direct_get_proc(const char *functionName)
{
    (void)functionName;
    return NULL;
}
#endif
"""
        if state_anchor not in text:
            raise SystemExit("ERROR: icd.c state anchor not found")
        text = text.replace(state_anchor, state_block, 1)

    path.write_text(text)
    print(f"PASS: patched Android direct-provider bridge into {path}")


def split_params(params: str):
    params = params.strip()
    if not params or params == "void":
        return []

    parts = []
    start = 0
    depth = 0
    for i, ch in enumerate(params):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append(params[start:i].strip())
            start = i + 1
    parts.append(params[start:].strip())
    return parts


def param_name(param: str) -> str:
    param = re.sub(r"/\*.*?\*/", " ", param, flags=re.S).strip()

    # Function-pointer parameter, e.g.
    # void (CL_CALLBACK* pfn_notify)(const char*, const void*, size_t, void*)
    m = re.search(r"\*\s*([A-Za-z_]\w*)\s*\)", param)
    if m:
        return m.group(1)

    # Ordinary parameter, including pointer and array parameters.
    m = re.search(
        r"([A-Za-z_]\w*)\s*(?:\[[^\]]*\])?\s*$",
        param,
    )
    if m:
        return m.group(1)

    raise SystemExit(f"ERROR: could not determine parameter name from: {param!r}")


def find_public_functions(text: str):
    functions = []
    pos = 0

    while True:
        m = re.search(r"\bCL_API_ENTRY\b", text[pos:])
        if not m:
            break

        start = pos + m.start()
        api_call = re.search(
            r"\bCL_API_CALL\s+([A-Za-z_]\w*)\s*\(",
            text[start:],
        )
        if not api_call:
            pos = start + len("CL_API_ENTRY")
            continue

        name = api_call.group(1)
        open_paren = start + api_call.end() - 1

        depth = 1
        i = open_paren + 1
        while i < len(text) and depth:
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1

        if depth != 0:
            raise SystemExit(f"ERROR: unterminated signature for {name}")

        close_paren = i - 1
        brace = text.find("{", close_paren)
        if brace < 0:
            raise SystemExit(f"ERROR: missing body for {name}")

        between = text[close_paren + 1:brace].strip()
        if between not in ("", "CL_API_SUFFIX__VERSION_1_0;", "CL_API_SUFFIX__VERSION_1_1;",
                           "CL_API_SUFFIX__VERSION_1_2;", "CL_API_SUFFIX__VERSION_2_0;",
                           "CL_API_SUFFIX__VERSION_2_1;", "CL_API_SUFFIX__VERSION_2_2;"):
            # Generated Khronos source uses suffix macros; permit any whitespace-only
            # or macro-only suffix but refuse arbitrary text so the patch cannot
            # silently attach to the wrong construct.
            if not re.fullmatch(r"(?:[A-Za-z_][A-Za-z0-9_]*\s*)*;?", between):
                raise SystemExit(
                    f"ERROR: unexpected tokens between signature and body for {name}: {between!r}"
                )

        params = text[open_paren + 1:close_paren]
        names = [param_name(p) for p in split_params(params)]

        return_segment = text[start:text.find("CL_API_CALL", start)].strip()
        # Return type is the portion after CL_API_ENTRY and before CL_API_CALL.
        return_segment = re.sub(r"^CL_API_ENTRY\b", "", return_segment).strip()

        functions.append({
            "name": name,
            "brace": brace,
            "args": ", ".join(names),
            "is_void": return_segment == "void",
            "is_cl_int": bool(re.fullmatch(r"cl_int", return_segment)),
        })

        pos = brace + 1

    return functions


def patch_generated_dispatch(path: Path) -> None:
    text = path.read_text()

    if DISPATCH_MARKER in text:
        print(f"PASS: generated dispatch already patched: {path}")
        return

    # The generated dispatcher includes icd.h/icd_dispatch.h but the helper
    # functions live in icd.c. Add explicit declarations before any hook calls.
    include_anchor = '#include "icd_dispatch.h"\n'
    include_block = '''#include "icd_dispatch.h"

#if defined(__ANDROID__)
/* Implemented in icd.c by the Android direct-provider bridge. */
extern int ffmpegkit_direct_provider_active(void);
extern void *ffmpegkit_direct_get_proc(const char *functionName);
#endif
'''
    if include_anchor not in text:
        raise SystemExit("ERROR: generated dispatch include anchor not found")
    text = text.replace(include_anchor, include_block, 1)

    funcs = find_public_functions(text)
    if not funcs:
        raise SystemExit("ERROR: no public OpenCL functions found in generated dispatch source")

    inserts = []
    for f in funcs:
        if f["is_void"]:
            missing = "return;"
            invoke = (
                f"        ((__typeof__(&{f['name']}))ffmpegkit_direct_cached)"
                f"({f['args']});\n"
                "        return;"
            )
        else:
            missing = (
                "return CL_INVALID_OPERATION;" if f["is_cl_int"]
                else "return 0;"
            )
            invoke = (
                f"        return ((__typeof__(&{f['name']}))ffmpegkit_direct_cached)"
                f"({f['args']});"
            )

        insertion = f"""
    /* {DISPATCH_MARKER}: {f['name']} */
    if (ffmpegkit_direct_provider_active())
    {{
        static void *ffmpegkit_direct_proc = NULL;
        void *ffmpegkit_direct_cached =
            __atomic_load_n(&ffmpegkit_direct_proc, __ATOMIC_ACQUIRE);

        if (!ffmpegkit_direct_cached)
        {{
            ffmpegkit_direct_cached =
                ffmpegkit_direct_get_proc("{f['name']}");
            __atomic_store_n(
                &ffmpegkit_direct_proc,
                ffmpegkit_direct_cached,
                __ATOMIC_RELEASE);
        }}

        if (!ffmpegkit_direct_cached)
        {{
            {missing}
        }}

{invoke}
    }}
"""
        inserts.append((f["brace"] + 1, insertion))

    for offset, insertion in reversed(inserts):
        text = text[:offset] + insertion + text[offset:]

    patched_count = text.count(DISPATCH_MARKER)
    if patched_count != len(funcs):
        raise SystemExit(
            f"ERROR: expected {len(funcs)} direct dispatch hooks, got {patched_count}"
        )

    path.write_text(text)
    print(
        f"PASS: patched {patched_count} Android direct-provider dispatch hooks into {path}"
    )


def patch_loader_entrypoints(path: Path) -> None:
    text = path.read_text()
    marker = "FFMPEGKIT_DIRECT_LOADER_ENTRYPOINTS"
    if marker in text:
        print(f"PASS: loader entry points already patched: {path}")
        return

    include_anchor = '#include <string.h>\n'
    include_block = '''#include <string.h>
#if defined(__ANDROID__)
/* Implemented in icd.c by the Android direct-provider bridge. */
extern int ffmpegkit_direct_provider_active(void);
extern void *ffmpegkit_direct_get_proc(const char *functionName);
#endif
'''
    if include_anchor not in text:
        raise SystemExit("ERROR: icd_dispatch.c string.h include anchor not found")
    text = text.replace(include_anchor, include_block, 1)

    platform_anchor = '''clGetPlatformIDs(cl_uint num_entries,
    cl_platform_id * platforms,
    cl_uint * num_platforms) CL_API_SUFFIX__VERSION_1_0
{
'''
    platform_insert = '''#if defined(__ANDROID__)
    /* FFMPEGKIT_DIRECT_LOADER_ENTRYPOINTS: clGetPlatformIDs is special in the
     * Khronos loader and is not generated in icd_dispatch_generated.c. */
    if (ffmpegkit_direct_provider_active()) {
        typedef cl_int (CL_API_CALL *FFmpegKitClGetPlatformIDs)(
            cl_uint, cl_platform_id *, cl_uint *);
        FFmpegKitClGetPlatformIDs fn =
            (FFmpegKitClGetPlatformIDs)ffmpegkit_direct_get_proc(
                "clGetPlatformIDs");
        if (fn)
            return fn(num_entries, platforms, num_platforms);
    }
#endif
'''
    if platform_anchor not in text:
        raise SystemExit("ERROR: clGetPlatformIDs anchor not found in icd_dispatch.c")
    text = text.replace(platform_anchor, platform_anchor + platform_insert, 1)

    ext_anchor = '''clGetExtensionFunctionAddressForPlatform(cl_platform_id platform,
    const char * function_name) CL_API_SUFFIX__VERSION_1_2
{
'''
    ext_insert = '''#if defined(__ANDROID__)
    /*
     * Direct Android providers may export this normally even without the
     * Khronos ICD ABI. Bypass loader handle validation in direct mode.
     */
    if (ffmpegkit_direct_provider_active()) {
        typedef void *(CL_API_CALL *FFmpegKitClGetExtensionFunctionAddressForPlatform)(
            cl_platform_id, const char *);
        FFmpegKitClGetExtensionFunctionAddressForPlatform fn =
            (FFmpegKitClGetExtensionFunctionAddressForPlatform)
                ffmpegkit_direct_get_proc(
                    "clGetExtensionFunctionAddressForPlatform");
        if (fn)
            return fn(platform, function_name);
    }
#endif
'''
    if ext_anchor not in text:
        raise SystemExit(
            "ERROR: clGetExtensionFunctionAddressForPlatform anchor not found in icd_dispatch.c"
        )
    text = text.replace(ext_anchor, ext_anchor + ext_insert, 1)

    path.write_text(text)
    print(f"PASS: patched direct Android loader entry points into {path}")

def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: patch-khronos-loader-android-direct.py <icd.c> <icd_dispatch_generated.c> <icd_dispatch.c>"
        )

    icd = Path(sys.argv[1])
    generated = Path(sys.argv[2])
    loader_dispatch = Path(sys.argv[3])

    if not icd.is_file():
        raise SystemExit(f"ERROR: missing loader source: {icd}")
    if not generated.is_file():
        raise SystemExit(f"ERROR: missing generated dispatch source: {generated}")
    if not loader_dispatch.is_file():
        raise SystemExit(f"ERROR: missing loader dispatch source: {loader_dispatch}")

    patch_icd(icd)
    patch_loader_entrypoints(loader_dispatch)
    patch_generated_dispatch(generated)


if __name__ == "__main__":
    main()
