# AMD FidelityFX shader ports

CAS and RCAS are derived from the following MIT-licensed AMD sources. The full copyright and license notices are shipped in `src/main/assets/licenses/FidelityFX.txt`.

| Algorithm | Pinned source |
| --- | --- |
| CAS | [FidelityFX-CAS, commit 9fabcc9a2c45f958aff55ddfda337e74ef894b7f](https://github.com/GPUOpen-Effects/FidelityFX-CAS/blob/9fabcc9a2c45f958aff55ddfda337e74ef894b7f/ffx-cas/ffx_cas.h) |
| RCAS | [FidelityFX-FSR, commit a21ffb8f6c13233ba336352bdff293894c706575](https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/a21ffb8f6c13233ba336352bdff293894c706575/ffx-fsr/ffx_fsr1.h) |

## Adaptations

- CAS ports the sharpen-only `CasFilter` variant with `CAS_BETTER_DIAGONALS`, `CAS_SLOW` and exact float reciprocal/square-root operations. The official sharpness formula is `-1 / mix(8, 5, sharpness)`. It replaces the previous approximate kernel and its local min/max output clipping.
- RCAS ports `FsrRcasF` with `FSR_RCAS_DENOISE` enabled. The UI-independent `[0,1]` strength maps to `[2,0]` stops through `exp2(-2 * (1 - strength))`. RCAS is used alone for sharpening; **EASU upscaling and the full FSR pipeline are not included**.
- Compute shader loads become GLES 2 fragment texture samples at texel centers with explicit edge clamping. Near-zero divisors are guarded for black, white and uniform frames. Alpha is preserved. Output dimensions equal input dimensions; the player continues to own display scaling.
- These ports operate on Media3's SDR intermediate RGB values. HDR passes through unchanged because the bounded SDR kernels have not been validated against Media3's HDR working range. These ports do not claim bit-for-bit equivalence to AMD's approximate arithmetic paths.
- The combined sharpening/shadow option reuses the same CAS port, followed by the existing shadow-lift effect. The existing anime-line option is a lightweight local filter, **not** an upstream Anime4K CNN port. Lanczos remains Media3's existing resampler.

## Other projects investigated

- [Anime4K](https://github.com/bloc97/Anime4K): a separate collection of multipass GLSL restoration/upscaling networks aimed at anime. Not imported in this change.
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) and [Real-ESRGAN ncnn Vulkan](https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan): model-based image/video restoration; would need a separate native inference path and device benchmarks. Not imported in this change.

## Validation limits

`tools/check_fidelityfx_shaders.py` compiles and executes the actual packaged GLSL through an offscreen GLES context, comparing pixels with CPU reference formulas for uniform frames, soft edges, noise, color extremes, borders and HDR bypass. It checks shader arithmetic and output shape, not Android decoder/Media3 integration or phone performance. Android playback, battery use, frame rate and perceived quality still require device testing.
