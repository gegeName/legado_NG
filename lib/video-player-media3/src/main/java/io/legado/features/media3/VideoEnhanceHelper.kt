package io.legado.features.media3

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.LanczosResample
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
object VideoEnhanceHelper {

    enum class EnhanceMode(val id: Int, val title: String) {
        NONE(0, "关闭 (原画)"),
        AMD_CAS(1, "AMD CAS 纹理锐化 (影视加清)"),
        ANIME_4K(2, "Anime4K 线条重构 (动漫专刷)"),
        SHADOW_BOOST(3, "暗光提亮 (夜景细节修复)"),
        SMART_COMBO(4, "全能极清 (CAS 锐化 + 暗部提亮)"),
        LANCZOS_3(5, "Lanczos-3 平滑超分 (抗锯齿)");

        companion object {
            fun fromId(id: Int): EnhanceMode {
                return entries.find { it.id == id } ?: NONE
            }
        }
    }

    /**
     * 为 ExoPlayer 挂载优化的画质增强管线
     */
    fun applyEnhanceMode(player: ExoPlayer, mode: EnhanceMode) {
        val effects = ArrayList<Effect>()
        when (mode) {
            EnhanceMode.NONE -> {
                // 占位 Effect，确保 ExoPlayer 在 prepare() 前成功初始化 VideoFrameProcessor 管线
                effects.add(Contrast(0.0f))
            }

            EnhanceMode.AMD_CAS -> {
                // CAS 风格自适应锐化；视觉和性能需设备验证
                effects.add(CasGlEffect(sharpness = 0.9f))
            }

            EnhanceMode.ANIME_4K -> {
                // 动漫线条增强着色器
                effects.add(Anime4kGlEffect(strength = 1.65f))
            }

            EnhanceMode.SHADOW_BOOST -> {
                // 暗部提亮着色器
                effects.add(ShadowLiftGlEffect(gamma = 1.45f))
            }

            EnhanceMode.SMART_COMBO -> {
                // 全能单 Pass 组合 Shader：CAS 锐化 + 自适应暗部提亮
                effects.add(SmartComboGlEffect(sharpness = 0.8f, gamma = 1.38f))
            }

            EnhanceMode.LANCZOS_3 -> {
                // Lanczos-3 窗口 Sinc 采样超分重构
                    effects.add(LanczosResample.scaleToFit(3840, 2160))
            }
        }

        player.setVideoEffects(effects)
    }

    // =========================================================================
    // 1. 优化版 AMD CAS (Contrast Adaptive Sharpening) GLSL 实现
    // =========================================================================
    class CasGlEffect(private val sharpness: Float = 0.65f) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
            return CasShaderProgram(useHdr, sharpness)
        }
    }

    private class CasShaderProgram(
        useHdr: Boolean,
        private val sharpness: Float
    ) : BaseGlShaderProgram(useHdr, 1) {

        private var glProgram: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (glProgram == null) {
                glProgram = GlProgram(VERTEX_SHADER, CAS_FRAGMENT_SHADER)
            }
            glProgram?.use()
            glProgram?.setFloatsUniform("uTexSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
            glProgram?.setFloatUniform("uSharpness", sharpness)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = glProgram ?: return
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            try {
                glProgram?.delete()
            } catch (_: Exception) {
            }
        }

        companion object {
            private const val CAS_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform vec2 uTexSize;
                uniform float uSharpness;
                varying vec2 vTexCoords;

                void main() {
                    vec2 size = uTexSize;
                    if (size.x <= 0.0 || size.y <= 0.0) {
                        size = vec2(1920.0, 1080.0);
                    }
                    vec2 dx = vec2(1.0 / size.x, 0.0);
                    vec2 dy = vec2(0.0, 1.0 / size.y);

                    vec3 a = texture2D(uTexSampler, vTexCoords - dy).rgb;
                    vec3 b = texture2D(uTexSampler, vTexCoords - dx).rgb;
                    vec3 c = texture2D(uTexSampler, vTexCoords).rgb;
                    vec3 d = texture2D(uTexSampler, vTexCoords + dx).rgb;
                    vec3 e = texture2D(uTexSampler, vTexCoords + dy).rgb;

                    vec3 minColor = min(c, min(min(a, b), min(d, e)));
                    vec3 maxColor = max(c, max(max(a, b), max(d, e)));

                    // 计算对比度自适应权重
                    vec3 amp = clamp(sqrt(min(minColor, vec3(1.0) - maxColor) / (maxColor + 0.0001)), 0.0, 1.0);
                    float peak = -0.18 - uSharpness * 0.22;
                    vec3 w = amp * peak;

                    vec3 sharpColor = (a * w + b * w + c + d * w + e * w) / (vec3(1.0) + 4.0 * w);
                    // 严格约束在局部最小最大色彩范围内，彻底防止溢出和边缘白边
                    vec3 result = mix(c, clamp(sharpColor, minColor, maxColor), 0.86);
                    gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
                }
            """
        }
    }

    // =========================================================================
    // 2. 优化版 Anime4K 开源线条重构 GLSL 实现
    // =========================================================================
    class Anime4kGlEffect(private val strength: Float = 1.25f) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
            return Anime4kShaderProgram(useHdr, strength)
        }
    }

    private class Anime4kShaderProgram(
        useHdr: Boolean,
        private val strength: Float
    ) : BaseGlShaderProgram(useHdr, 1) {

        private var glProgram: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (glProgram == null) {
                glProgram = GlProgram(VERTEX_SHADER, ANIME4K_FRAGMENT_SHADER)
            }
            glProgram?.use()
            glProgram?.setFloatsUniform("uTexSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
            glProgram?.setFloatUniform("uStrength", strength)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = glProgram ?: return
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            try {
                glProgram?.delete()
            } catch (_: Exception) {
            }
        }

        companion object {
            private const val ANIME4K_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform vec2 uTexSize;
                uniform float uStrength;
                varying vec2 vTexCoords;

                float getLuma(vec3 c) {
                    return dot(c, vec3(0.2126, 0.7152, 0.0722));
                }

                void main() {
                    vec2 size = uTexSize;
                    if (size.x <= 0.0 || size.y <= 0.0) {
                        size = vec2(1920.0, 1080.0);
                    }
                    vec2 step = vec2(1.0 / size.x, 1.0 / size.y);

                    vec3 c = texture2D(uTexSampler, vTexCoords).rgb;
                    vec3 t = texture2D(uTexSampler, vTexCoords + vec2(0.0, -step.y)).rgb;
                    vec3 b = texture2D(uTexSampler, vTexCoords + vec2(0.0, step.y)).rgb;
                    vec3 l = texture2D(uTexSampler, vTexCoords + vec2(-step.x, 0.0)).rgb;
                    vec3 r = texture2D(uTexSampler, vTexCoords + vec2(step.x, 0.0)).rgb;

                    float lC = getLuma(c);
                    float lT = getLuma(t);
                    float lB = getLuma(b);
                    float lL = getLuma(l);
                    float lR = getLuma(r);

                    // Sobel 梯度计算
                    float dx = (lR - lL);
                    float dy = (lB - lT);
                    float grad = sqrt(dx * dx + dy * dy);

                    if (grad > 0.008) {
                        vec3 minColor = min(c, min(min(t, b), min(l, r)));
                        vec3 maxColor = max(c, max(max(t, b), max(l, r)));
                        vec3 avgColor = (t + b + l + r) * 0.25;
                        float edgeWeight = clamp(grad * 8.0, 0.0, 1.0);
                        vec3 edgeSharp = c + (c - avgColor) * (uStrength * 0.9);
                        c = mix(c, clamp(edgeSharp, minColor, maxColor), edgeWeight);
                    }

                    gl_FragColor = vec4(c, 1.0);
                }
            """
        }
    }

    // =========================================================================
    // 3. S-Curve 亮度自适应暗部提亮 GLSL 实现
    // =========================================================================
    class ShadowLiftGlEffect(private val gamma: Float = 1.32f) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
            return ShadowLiftShaderProgram(useHdr, gamma)
        }
    }

    private class ShadowLiftShaderProgram(
        useHdr: Boolean,
        private val gamma: Float
    ) : BaseGlShaderProgram(useHdr, 1) {

        private var glProgram: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (glProgram == null) {
                glProgram = GlProgram(VERTEX_SHADER, SHADOW_LIFT_FRAGMENT_SHADER)
            }
            glProgram?.use()
            glProgram?.setFloatUniform("uGamma", gamma)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = glProgram ?: return
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            try {
                glProgram?.delete()
            } catch (_: Exception) {
            }
        }

        companion object {
            private const val SHADOW_LIFT_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform float uGamma;
                varying vec2 vTexCoords;

                void main() {
                    vec4 color = texture2D(uTexSampler, vTexCoords);
                    vec3 rgb = clamp(color.rgb, 0.0, 1.0);

                    // 基于 Rec.709 亮度系数计算像素亮度
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    float shadowMask = 1.0 - smoothstep(0.08, 0.62, luma);
                    float liftedLuma = pow(max(luma, 0.001), 1.0 / uGamma);
                    float scale = mix(1.0, liftedLuma / max(luma, 0.001), shadowMask);
                    rgb = clamp(rgb * scale, 0.0, 1.0);

                    gl_FragColor = vec4(rgb, color.a);
                }
            """
        }
    }

    // =========================================================================
    // 4. 单 Pass 组合 Shader：AMD CAS 锐化 + 自适应暗部提亮
    // =========================================================================
    class SmartComboGlEffect(
        private val sharpness: Float = 0.55f,
        private val gamma: Float = 1.28f
    ) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
            return SmartComboShaderProgram(useHdr, sharpness, gamma)
        }
    }

    private class SmartComboShaderProgram(
        useHdr: Boolean,
        private val sharpness: Float,
        private val gamma: Float
    ) : BaseGlShaderProgram(useHdr, 1) {

        private var glProgram: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (glProgram == null) {
                glProgram = GlProgram(VERTEX_SHADER, SMART_COMBO_FRAGMENT_SHADER)
            }
            glProgram?.use()
            glProgram?.setFloatsUniform("uTexSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
            glProgram?.setFloatUniform("uSharpness", sharpness)
            glProgram?.setFloatUniform("uGamma", gamma)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = glProgram ?: return
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            try {
                glProgram?.delete()
            } catch (_: Exception) {
            }
        }

        companion object {
            private const val SMART_COMBO_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform vec2 uTexSize;
                uniform float uSharpness;
                uniform float uGamma;
                varying vec2 vTexCoords;

                void main() {
                    vec2 size = uTexSize;
                    if (size.x <= 0.0 || size.y <= 0.0) {
                        size = vec2(1920.0, 1080.0);
                    }
                    vec2 dx = vec2(1.0 / size.x, 0.0);
                    vec2 dy = vec2(0.0, 1.0 / size.y);

                    vec3 a = texture2D(uTexSampler, vTexCoords - dy).rgb;
                    vec3 b = texture2D(uTexSampler, vTexCoords - dx).rgb;
                    vec3 c = texture2D(uTexSampler, vTexCoords).rgb;
                    vec3 d = texture2D(uTexSampler, vTexCoords + dx).rgb;
                    vec3 e = texture2D(uTexSampler, vTexCoords + dy).rgb;

                    vec3 minColor = min(c, min(min(a, b), min(d, e)));
                    vec3 maxColor = max(c, max(max(a, b), max(d, e)));

                    // 1. AMD CAS 自适应锐化
                    vec3 amp = clamp(sqrt(min(minColor, vec3(1.0) - maxColor) / (maxColor + 0.0001)), 0.0, 1.0);
                    float peak = -0.18 - uSharpness * 0.2;
                    vec3 w = amp * peak;
                    vec3 sharpColor = clamp((a * w + b * w + c + d * w + e * w) / (vec3(1.0) + 4.0 * w), minColor, maxColor);

                    // 2. 自适应暗部 Gamma 提亮
                    float luma = dot(sharpColor, vec3(0.2126, 0.7152, 0.0722));
                    float shadowMask = 1.0 - smoothstep(0.08, 0.64, luma);
                    float liftedLuma = pow(max(luma, 0.001), 1.0 / uGamma);
                    float scale = mix(1.0, liftedLuma / max(luma, 0.001), shadowMask);
                    sharpColor = clamp(sharpColor * scale, 0.0, 1.0);

                    gl_FragColor = vec4(sharpColor, 1.0);
                }
            """
        }
    }

    private const val VERTEX_SHADER = """
        attribute vec4 aFramePosition;
        varying vec2 vTexCoords;
        void main() {
            gl_Position = aFramePosition;
            vTexCoords = aFramePosition.xy * 0.5 + 0.5;
        }
    """
}
