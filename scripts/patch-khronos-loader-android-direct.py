#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-khronos-loader-android-direct.py <icd.c>")

path = Path(sys.argv[1])
text = path.read_text()

if "ffmpegkit_legacy_clGetPlatformIDs_fn" not in text:
    anchor = "#include <string.h>\n"
    if anchor not in text:
        raise SystemExit("ERROR: icd.c include anchor not found")
    typedef = """
typedef cl_int (CL_API_CALL *ffmpegkit_legacy_clGetPlatformIDs_fn)(
    cl_uint num_entries,
    cl_platform_id *platforms,
    cl_uint *num_platforms);
"""
    text = text.replace(anchor, anchor + typedef, 1)

old = """    pfn_clGetExtensionFunctionAddress p_clGetExtensionFunctionAddress = NULL;
    pfn_clIcdGetPlatformIDs p_clIcdGetPlatformIDs = NULL;
"""
new = """    pfn_clGetExtensionFunctionAddress p_clGetExtensionFunctionAddress = NULL;
    pfn_clIcdGetPlatformIDs p_clIcdGetPlatformIDs = NULL;
    ffmpegkit_legacy_clGetPlatformIDs_fn p_clGetPlatformIDs = NULL;
    int ffmpegkit_legacy_direct = 0;
"""
if old not in text:
    raise SystemExit("ERROR: loader function-pointer block not found")
text = text.replace(old, new, 1)

old = """    // get the library's clGetExtensionFunctionAddress pointer
    p_clGetExtensionFunctionAddress = (pfn_clGetExtensionFunctionAddress)(size_t)khrIcdOsLibraryGetFunctionAddress(library, "clGetExtensionFunctionAddress");
    if (!p_clGetExtensionFunctionAddress)
    {
        KHR_ICD_TRACE("failed to get function address clGetExtensionFunctionAddress\\n");
        goto Done;
    }

    // use that function to get the clIcdGetPlatformIDsKHR function pointer
    p_clIcdGetPlatformIDs = (pfn_clIcdGetPlatformIDs)(size_t)p_clGetExtensionFunctionAddress("clIcdGetPlatformIDsKHR");
    if (!p_clIcdGetPlatformIDs)
    {
        KHR_ICD_TRACE("failed to get extension function address clIcdGetPlatformIDsKHR\\n");
        goto Done;
    }
"""
new = """    // Standard ICDs expose clGetExtensionFunctionAddress() and
    // clIcdGetPlatformIDsKHR(). Some Android vendor implementations do not,
    // but expose a fully usable ordinary clGetPlatformIDs().
    p_clGetExtensionFunctionAddress = (pfn_clGetExtensionFunctionAddress)(size_t)khrIcdOsLibraryGetFunctionAddress(library, "clGetExtensionFunctionAddress");
    p_clGetPlatformIDs = (ffmpegkit_legacy_clGetPlatformIDs_fn)(size_t)khrIcdOsLibraryGetFunctionAddress(library, "clGetPlatformIDs");

    if (p_clGetExtensionFunctionAddress)
    {
        p_clIcdGetPlatformIDs = (pfn_clIcdGetPlatformIDs)(size_t)p_clGetExtensionFunctionAddress("clIcdGetPlatformIDsKHR");
    }

    if (!p_clIcdGetPlatformIDs)
    {
        if (!p_clGetPlatformIDs)
        {
            KHR_ICD_TRACE("failed to get clGetPlatformIDs for direct Android provider\\n");
            goto Done;
        }
        p_clIcdGetPlatformIDs = (pfn_clIcdGetPlatformIDs)(size_t)p_clGetPlatformIDs;
        ffmpegkit_legacy_direct = 1;
        KHR_ICD_TRACE("accepting Android direct OpenCL provider via clGetPlatformIDs\\n");
    }
"""
if old not in text:
    raise SystemExit("ERROR: standard ICD lookup block not found")
text = text.replace(old, new, 1)

old = """        // call clGetPlatformInfo on the returned platform to get the suffix
        result = KHR_ICD2_DISPATCH(platforms[i])->clGetPlatformInfo(
            platforms[i],
            CL_PLATFORM_ICD_SUFFIX_KHR,
            0,
            NULL,
            &suffixSize);
        if (CL_SUCCESS != result)
        {
            KHR_ICD_TRACE("failed query platform ICD suffix\\n");
            free(vendor);
            continue;
        }
        suffix = (char *)malloc(suffixSize);
        if (!suffix)
        {
            KHR_ICD_TRACE("failed to allocate memory\\n");
            free(vendor);
            continue;
        }
        result = KHR_ICD2_DISPATCH(platforms[i])->clGetPlatformInfo(
            platforms[i],
            CL_PLATFORM_ICD_SUFFIX_KHR,
            suffixSize,
            suffix,
            NULL);
        if (CL_SUCCESS != result)
        {
            KHR_ICD_TRACE("failed query platform ICD suffix\\n");
            free(suffix);
            free(vendor);
            continue;
        }
"""
new = """        // A legacy Android direct provider is not required to implement
        // CL_PLATFORM_ICD_SUFFIX_KHR. Give it a stable private suffix instead
        // of rejecting an otherwise usable GPU.
        if (ffmpegkit_legacy_direct)
        {
            const char *legacySuffix = "LEGACY";
            suffixSize = strlen(legacySuffix) + 1;
            suffix = (char *)malloc(suffixSize);
            if (!suffix)
            {
                free(vendor);
                continue;
            }
            memcpy(suffix, legacySuffix, suffixSize);
        }
        else
        {
            result = KHR_ICD2_DISPATCH(platforms[i])->clGetPlatformInfo(
                platforms[i],
                CL_PLATFORM_ICD_SUFFIX_KHR,
                0,
                NULL,
                &suffixSize);
            if (CL_SUCCESS != result)
            {
                KHR_ICD_TRACE("failed query platform ICD suffix\\n");
                free(vendor);
                continue;
            }
            suffix = (char *)malloc(suffixSize);
            if (!suffix)
            {
                KHR_ICD_TRACE("failed to allocate memory\\n");
                free(vendor);
                continue;
            }
            result = KHR_ICD2_DISPATCH(platforms[i])->clGetPlatformInfo(
                platforms[i],
                CL_PLATFORM_ICD_SUFFIX_KHR,
                suffixSize,
                suffix,
                NULL);
            if (CL_SUCCESS != result)
            {
                KHR_ICD_TRACE("failed query platform ICD suffix\\n");
                free(suffix);
                free(vendor);
                continue;
            }
        }
"""
if old not in text:
    raise SystemExit("ERROR: ICD suffix block not found")
text = text.replace(old, new, 1)

path.write_text(text)
print(f"PASS: patched {path}")
