// SPDX-License-Identifier: MIT
attribute vec4 aFramePosition;
varying vec2 vTexCoords;
void main() {
    gl_Position = aFramePosition;
    vTexCoords = aFramePosition.xy * 0.5 + 0.5;
}
