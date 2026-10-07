# v10.6 `scale_opencl` build fix

Root cause of v10.5 CI failure:

```text
error: patch failed: libavfilter/Makefile:476
ERROR: patch 0006-add-opencl-scaler-and-pixfmt-converter-impl.patch does not apply
```

FFmpegKit `v8.1.9-lts-android` downloads FFmpeg `n8.1.3`. The maintained
8.x `scale_opencl` backport was authored against an earlier FFmpeg 8.x tree,
where the CUDA scaler object list did not yet contain `scale_filters.o`.

Only that patch context differs. The implementation (`vf_scale_opencl.c` and
`opencl/scale.cl`), FFmpeg configure dependency, filter registration, and
OpenCL source declaration are unchanged.

v10.6 downloads and SHA-1 verifies the published patch, then runs
`scripts/adapt-opencl-scale-patch.py` to replace that one known context with the
exact n8.1.3 context. The adapter refuses to modify an unknown patch layout.
