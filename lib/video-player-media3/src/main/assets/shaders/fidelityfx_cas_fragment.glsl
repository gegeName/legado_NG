// SPDX-License-Identifier: MIT
// Copyright (c) 2020 Advanced Micro Devices, Inc. All rights reserved.
// CasFilter(noScaling=true), CAS_BETTER_DIAGONALS + CAS_SLOW, full precision.
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

void main() {
    vec4 center = texture2D(uTexSampler, vTexCoords);
    if (uUseHdr > 0.5) { gl_FragColor = center; return; }
    vec3 a = loadPixel(vec2(-1.0, -1.0));
    vec3 b = loadPixel(vec2( 0.0, -1.0));
    vec3 c = loadPixel(vec2( 1.0, -1.0));
    vec3 d = loadPixel(vec2(-1.0,  0.0));
    vec3 e = clamp(center.rgb, 0.0, 1.0);
    vec3 f = loadPixel(vec2( 1.0,  0.0));
    vec3 g = loadPixel(vec2(-1.0,  1.0));
    vec3 h = loadPixel(vec2( 0.0,  1.0));
    vec3 i = loadPixel(vec2( 1.0,  1.0));
    vec3 mn = min(min(min(d, e), f), min(b, h));
    vec3 mx = max(max(max(d, e), f), max(b, h));
    // Soft min/max are twice their normalized values in the upstream diagonal variant.
    mn += min(min(min(mn, a), c), min(g, i));
    mx += max(max(max(mx, a), c), max(g, i));
    vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, vec3(0.00001)), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, uSharpness);
    vec3 weight = amp * peak;
    vec3 rgb = (e + (b + d + f + h) * weight) / (1.0 + 4.0 * weight);
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), center.a);
}
