// SPDX-License-Identifier: MIT
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// FsrRcasF, full precision, FSR_RCAS_DENOISE enabled; sharpening only (no EASU upscaling).
// Sources, port changes and full MIT notice: THIRD_PARTY_NOTICES.md / licenses/FidelityFX.txt.
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexSize;
uniform float uSharpness;
uniform float uUseHdr;
varying vec2 vTexCoords;

vec3 loadPixel(vec2 offset) {
    vec2 halfTexel = 0.5 / uTexSize;
    vec2 uv = clamp(vTexCoords + offset / uTexSize, halfTexel, 1.0 - halfTexel);
    return clamp(texture2D(uTexSampler, uv).rgb, 0.0, 1.0);
}
float luma2(vec3 rgb) { return rgb.b * 0.5 + (rgb.r * 0.5 + rgb.g); }

void main() {
    vec4 center = texture2D(uTexSampler, vTexCoords);
    if (uUseHdr > 0.5) { gl_FragColor = center; return; }
    vec3 b = loadPixel(vec2( 0.0, -1.0));
    vec3 d = loadPixel(vec2(-1.0,  0.0));
    vec3 e = clamp(center.rgb, 0.0, 1.0);
    vec3 f = loadPixel(vec2( 1.0,  0.0));
    vec3 h = loadPixel(vec2( 0.0,  1.0));
    float bL = luma2(b), dL = luma2(d), eL = luma2(e), fL = luma2(f), hL = luma2(h);
    float lo = min(min(min(bL, dL), eL), min(fL, hL));
    float hi = max(max(max(bL, dL), eL), max(fL, hL));
    float noise = abs(0.25 * (bL + dL + fL + hL) - eL) / max(hi - lo, 0.00001);
    float noiseWeight = 1.0 - 0.5 * clamp(noise, 0.0, 1.0);
    vec3 mn = min(min(b, d), min(f, h));
    vec3 mx = max(max(b, d), max(f, h));
    // Guard zero denominators for perfectly black/white frames; otherwise use exact reciprocals.
    vec3 hitMin = min(mn, e) / max(4.0 * mx, vec3(0.00001));
    vec3 hitMax = (1.0 - max(mx, e)) / min(4.0 * mn - 4.0, vec3(-0.00001));
    vec3 channelLobe = max(-hitMin, hitMax);
    float lobe = max(-0.1875, min(max(max(channelLobe.r, channelLobe.g), channelLobe.b), 0.0));
    // Map [0,1] control to the upstream FsrRcasCon [2,0] sharpness stops.
    lobe *= exp2(-2.0 * (1.0 - uSharpness)) * noiseWeight;
    vec3 rgb = (e + (b + d + f + h) * lobe) / (1.0 + 4.0 * lobe);
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), center.a);
}
