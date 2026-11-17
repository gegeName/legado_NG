/*
 * SPDX-License-Identifier: MIT
 * GLSL adaptations of AMD FidelityFX CAS / FSR RCAS.
 * Copyright (c) 2020-2021 Advanced Micro Devices, Inc. All rights reserved.
 * See assets/licenses/FidelityFX.txt and THIRD_PARTY_NOTICES.md for sources and changes.
 */
package io.legado.features.media3

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Sharpening at the input resolution; output sizing remains owned by the player. */
@OptIn(UnstableApi::class)
internal class FidelityFxSharpenEffect(
    private val algorithm: Algorithm,
    private val sharpness: Float,
) : GlEffect {
    enum class Algorithm(val asset: String) {
        CAS("shaders/fidelityfx_cas_fragment.glsl"),
        RCAS("shaders/fidelityfx_rcas_fragment.glsl"),
    }

    init { require(sharpness.isFinite() && sharpness in 0f..1f) }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SharpenProgram(context, algorithm, sharpness, useHdr)

    private class SharpenProgram(
        private val context: Context,
        private val algorithm: Algorithm,
        private val sharpness: Float,
        private val useHdr: Boolean,
    ) : BaseGlShaderProgram(useHdr, 1) {
        private var program: GlProgram? = null

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            require(inputWidth > 0 && inputHeight > 0)
            val shader = program ?: GlProgram(
                context, "shaders/fidelityfx_sharpen_vertex.glsl", algorithm.asset,
            ).also { program = it }
            shader.use()
            shader.setFloatsUniform("uTexSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()))
            shader.setFloatUniform("uSharpness", sharpness)
            // These SDR kernels use [0, 1] bounds. Keep HDR values intact until separately validated.
            shader.setFloatUniform("uUseHdr", if (useHdr) 1f else 0f)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val shader = checkNotNull(program)
            shader.use()
            shader.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            shader.setBufferAttribute(
                "aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            shader.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        }

        override fun release() {
            try { super.release() } finally {
                try { program?.delete() } finally { program = null }
            }
        }
    }
}
