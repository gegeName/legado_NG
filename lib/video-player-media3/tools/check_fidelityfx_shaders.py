"""Execute packaged GLSL against AMD-formula references using an offscreen GLES 2 context.

Python standard library only. Example on Windows with the Android SDK software renderer:
  python check_fidelityfx_shaders.py --gles-dir D:/SDK/emulator/lib64/gles_swiftshader
This is a desktop shader check, not Android playback or performance validation.
"""
import argparse
import ctypes as C
import json
import math
import os
from pathlib import Path
import random


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, list(args)
    return fn


class Gles:
    def __init__(self, directory):
        self.dll_directory = os.add_dll_directory(str(directory)) if os.name == "nt" else None
        loader = C.WinDLL if os.name == "nt" else C.CDLL
        self.egl = loader(str(directory / ("libEGL.dll" if os.name == "nt" else "libEGL.so")))
        self.gl = loader(str(directory / ("libGLESv2.dll" if os.name == "nt" else "libGLESv2.so")))
        ptr, integer, uint, flt = C.c_void_p, C.c_int, C.c_uint, C.c_float
        ip = C.POINTER(integer)
        self.get_display = bind(self.egl, "eglGetDisplay", ptr, ptr)
        self.initialize = bind(self.egl, "eglInitialize", uint, ptr, ip, ip)
        self.choose = bind(self.egl, "eglChooseConfig", uint, ptr, ip, C.POINTER(ptr), integer, ip)
        self.bind_api = bind(self.egl, "eglBindAPI", uint, uint)
        self.create_surface = bind(self.egl, "eglCreatePbufferSurface", ptr, ptr, ptr, ip)
        self.create_context = bind(self.egl, "eglCreateContext", ptr, ptr, ptr, ptr, ip)
        self.make_current = bind(self.egl, "eglMakeCurrent", uint, ptr, ptr, ptr, ptr)
        self.destroy_surface = bind(self.egl, "eglDestroySurface", uint, ptr, ptr)
        self.destroy_context = bind(self.egl, "eglDestroyContext", uint, ptr, ptr)
        self.terminate = bind(self.egl, "eglTerminate", uint, ptr)
        self.display = self.get_display(None)
        assert self.initialize(self.display, None, None), "eglInitialize failed"
        attributes = (integer * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        config, count = ptr(), integer()
        assert self.choose(self.display, attributes, C.byref(config), 1, C.byref(count)) and count.value
        assert self.bind_api(0x30A0)
        self.surface = self.create_surface(self.display, config, (integer * 5)(0x3057, 64, 0x3056, 64, 0x3038))
        self.context = self.create_context(self.display, config, None, (integer * 3)(0x3098, 2, 0x3038))
        assert self.surface and self.context and self.make_current(self.display, self.surface, self.surface, self.context)
        specifications = {
            "glGetString": (C.c_char_p, uint), "glGetError": (uint,),
            "glCreateShader": (uint, uint), "glShaderSource": (None, uint, integer, C.POINTER(C.c_char_p), ip),
            "glCompileShader": (None, uint), "glGetShaderiv": (None, uint, uint, ip),
            "glGetShaderInfoLog": (None, uint, integer, ip, ptr), "glDeleteShader": (None, uint),
            "glCreateProgram": (uint,), "glAttachShader": (None, uint, uint),
            "glLinkProgram": (None, uint), "glGetProgramiv": (None, uint, uint, ip),
            "glGetProgramInfoLog": (None, uint, integer, ip, ptr), "glDeleteProgram": (None, uint),
            "glUseProgram": (None, uint), "glGetUniformLocation": (integer, uint, C.c_char_p),
            "glUniform1i": (None, integer, integer), "glUniform1f": (None, integer, flt),
            "glUniform2f": (None, integer, flt, flt), "glGetAttribLocation": (integer, uint, C.c_char_p),
            "glEnableVertexAttribArray": (None, uint),
            "glVertexAttribPointer": (None, uint, integer, uint, C.c_ubyte, integer, ptr),
            "glGenTextures": (None, integer, C.POINTER(uint)), "glBindTexture": (None, uint, uint),
            "glTexParameteri": (None, uint, uint, integer), "glActiveTexture": (None, uint),
            "glTexImage2D": (None, uint, integer, integer, integer, integer, integer, uint, uint, ptr),
            "glDeleteTextures": (None, integer, C.POINTER(uint)),
            "glViewport": (None, integer, integer, integer, integer),
            "glDrawArrays": (None, uint, integer, integer),
            "glReadPixels": (None, integer, integer, integer, integer, uint, uint, ptr),
            "glDisable": (None, uint),
        }
        for name, signature in specifications.items():
            setattr(self, name, bind(self.gl, name, *signature))
        self.glDisable(0x0BD0)  # GL_DITHER, for reproducible 8-bit comparisons.

    def program(self, vertex, fragment):
        shaders = []
        try:
            for kind, source in ((0x8B31, vertex), (0x8B30, fragment)):
                shader = self.glCreateShader(kind)
                shaders.append(shader)
                encoded = C.c_char_p(source.encode())
                self.glShaderSource(shader, 1, C.byref(encoded), None)
                self.glCompileShader(shader)
                status = C.c_int()
                self.glGetShaderiv(shader, 0x8B81, C.byref(status))
                log = C.create_string_buffer(8192)
                self.glGetShaderInfoLog(shader, len(log), None, log)
                assert status.value, log.value.decode()
            program = self.glCreateProgram()
            for shader in shaders:
                self.glAttachShader(program, shader)
            self.glLinkProgram(program)
            status = C.c_int()
            self.glGetProgramiv(program, 0x8B82, C.byref(status))
            log = C.create_string_buffer(8192)
            self.glGetProgramInfoLog(program, len(log), None, log)
            assert status.value, log.value.decode()
            return program
        finally:
            for shader in shaders:
                self.glDeleteShader(shader)

    def render(self, program, width, height, pixels, sharpness, hdr=False):
        self.glUseProgram(program)
        self.glUniform1i(self.glGetUniformLocation(program, b"uTexSampler"), 0)
        self.glUniform2f(self.glGetUniformLocation(program, b"uTexSize"), width, height)
        self.glUniform1f(self.glGetUniformLocation(program, b"uSharpness"), sharpness)
        self.glUniform1f(self.glGetUniformLocation(program, b"uUseHdr"), float(hdr))
        texture = C.c_uint()
        self.glGenTextures(1, C.byref(texture))
        try:
            self.glActiveTexture(0x84C0)
            self.glBindTexture(0x0DE1, texture)
            for key, value in ((0x2801, 0x2600), (0x2800, 0x2600), (0x2802, 0x812F), (0x2803, 0x812F)):
                self.glTexParameteri(0x0DE1, key, value)
            input_bytes = (C.c_ubyte * len(pixels))(*pixels)
            self.glTexImage2D(0x0DE1, 0, 0x1908, width, height, 0, 0x1908, 0x1401, input_bytes)
            vertices = (C.c_float * 16)(-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1)
            attribute = self.glGetAttribLocation(program, b"aFramePosition")
            self.glEnableVertexAttribArray(attribute)
            self.glVertexAttribPointer(attribute, 4, 0x1406, 0, 0, vertices)
            self.glViewport(0, 0, width, height)
            self.glDrawArrays(0x0005, 0, 4)
            output = (C.c_ubyte * len(pixels))()
            self.glReadPixels(0, 0, width, height, 0x1908, 0x1401, output)
            assert self.glGetError() == 0, "GLES error"
            return list(output)
        finally:
            self.glDeleteTextures(1, C.byref(texture))

    def close(self):
        self.make_current(self.display, None, None, None)
        self.destroy_context(self.display, self.context)
        self.destroy_surface(self.display, self.surface)
        self.terminate(self.display)


def reference(mode, width, height, pixels, strength, denoise=True):
    """Exact-arithmetic reference from the pinned AMD headers, with the documented zero guards."""
    def load(x, y, c):
        return pixels[(min(height - 1, max(0, y)) * width + min(width - 1, max(0, x))) * 4 + c] / 255

    result = []
    for y in range(height):
        for x in range(width):
            samples = [[load(x + dx, y + dy, c) for c in range(3)]
                       for dx, dy in ((-1, -1), (0, -1), (1, -1), (-1, 0), (0, 0), (1, 0), (-1, 1), (0, 1), (1, 1))]
            cross, center = [samples[i] for i in (1, 3, 5, 7)], samples[4]
            if mode == "cas":
                weights = []
                for c in range(3):
                    mn, mx = min(p[c] for p in cross + [center]), max(p[c] for p in cross + [center])
                    mn += min(p[c] for p in samples)
                    mx += max(p[c] for p in samples)
                    amp = math.sqrt(max(0, min(1, min(mn, 2 - mx) / max(mx, 1e-5))))
                    weights.append(-amp / (8 - 3 * strength))
            else:
                lumas = [p[2] * .5 + p[0] * .5 + p[1] for p in cross + [center]]
                noise = abs(sum(lumas[:4]) * .25 - lumas[4]) / max(max(lumas) - min(lumas), 1e-5)
                attenuation = 1 - .5 * min(1, noise) if denoise else 1
                channel_lobes = []
                for c in range(3):
                    mn, mx = min(p[c] for p in cross), max(p[c] for p in cross)
                    hit_min = min(mn, center[c]) / max(4 * mx, 1e-5)
                    hit_max = (1 - max(mx, center[c])) / min(4 * mn - 4, -1e-5)
                    channel_lobes.append(max(-hit_min, hit_max))
                weight = max(-.1875, min(max(channel_lobes), 0)) * 2 ** (-2 * (1 - strength)) * attenuation
                weights = [weight] * 3
            for c, weight in enumerate(weights):
                value = (center[c] + sum(p[c] for p in cross) * weight) / (1 + 4 * weight)
                assert math.isfinite(value)
                result.append(round(min(1, max(0, value)) * 255))
            result.append(pixels[(y * width + x) * 4 + 3])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gles-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    shader_dir = Path(__file__).resolve().parents[1] / "src/main/assets/shaders"
    gl = Gles(args.gles_dir.resolve())
    report = {"renderer": gl.glGetString(0x1F01).decode(), "checks": []}
    rng = random.Random(20261117)
    cases = [("black", 7, 5, [0, 0, 0, 255] * 35), ("white", 7, 5, [255, 255, 255, 255] * 35),
             ("flat_gray", 7, 5, [128, 128, 128, 123] * 35),
             ("single_pixel", 1, 1, [37, 128, 221, 77])]
    for width, height in ((17, 9), (9, 17), (1, 11), (13, 1)):
        cases.append((f"noise_{width}x{height}", width, height,
                      [rng.randrange(256) for _ in range(width * height * 4)]))
    edge = [round(64 + 128 / (1 + math.exp(-(x - 8) * .8))) for x in range(17)]
    cases.append(("soft_edge", 17, 9, [c for _ in range(9) for v in edge for c in (v, v, v, 255)]))
    impulse = [128, 128, 128, 255] * 81
    impulse[40 * 4:40 * 4 + 3] = [153] * 3
    cases.append(("noise_impulse", 9, 9, impulse))
    try:
        for mode in ("cas", "rcas"):
            program = gl.program((shader_dir / "fidelityfx_sharpen_vertex.glsl").read_text(),
                                 (shader_dir / f"fidelityfx_{mode}_fragment.glsl").read_text())
            try:
                for name, width, height, pixels in cases:
                    for strength in (0.0, .65, 1.0):
                        actual = gl.render(program, width, height, pixels, strength)
                        expected = reference(mode, width, height, pixels, strength)
                        error = max(abs(a - b) for a, b in zip(actual, expected))
                        assert len(actual) == width * height * 4 and error <= 2, (mode, name, strength, error)
                        assert actual[3::4] == pixels[3::4], (mode, name, "alpha changed")
                        if name in ("black", "white", "flat_gray", "single_pixel"):
                            assert max(abs(a - b) for a, b in zip(actual, pixels)) <= 1, (mode, name)
                        if name == "soft_edge":
                            index = (4 * width + 8) * 4
                            before = pixels[index + 4] - pixels[index - 4]
                            after = actual[index + 4] - actual[index - 4]
                            # Weak RCAS can round to the same 8-bit pixel; the default must sharpen.
                            assert after >= before and (strength < .65 or after > before), mode
                        if name == "noise_impulse" and mode == "rcas":
                            no_denoise = reference(mode, width, height, pixels, strength, denoise=False)
                            assert actual[40 * 4] <= no_denoise[40 * 4], "Noise attenuation regressed"
                        report["checks"].append({"mode": mode, "case": name, "strength": strength, "max_byte_error": error})
                    bypass = gl.render(program, width, height, pixels, .65, hdr=True)
                    assert bypass == pixels, (mode, name, "HDR bypass changed SDR-range test pixels")
                report["checks"].append({"mode": mode, "case": "hdr_bypass_sdr_range"})
            finally:
                gl.glDeleteProgram(program)
    finally:
        gl.close()
    report["passed"] = len(report["checks"])
    if args.output:
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"renderer": report["renderer"], "passed": report["passed"]}))


if __name__ == "__main__":
    main()
