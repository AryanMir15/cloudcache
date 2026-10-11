package com.lagradost.cloudstream3.ui.player.shaders

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.lagradost.cloudstream3.R
import java.io.IOException

/**
 * Anime4K GPU shaders for the built-in Media3 player.
 *
 * The upstream shaders (bloc97/Anime4K, MIT) are written for mpv: multi-pass
 * chains with named intermediate textures and multi-input passes. Media3's
 * Effect API is a linear single-input chain, so a plain list of effects cannot
 * express them. Instead this runs the WHOLE mpv chain inside ONE
 * [BaseGlShaderProgram]: passes render into our own framebuffers and may bind
 * both the original frame and any previously-saved intermediate — exactly like
 * mpv.
 *
 * The mpv GLSL is shipped verbatim in res/raw and parsed at runtime
 * (//!HOOK, //!BIND, //!SAVE, //!WIDTH/HEIGHT, //!DESC markers); the per-pass
 * <name>_tex / <name>_texOff / <name>_pos / <name>_pt / <name>_size tokens are
 * mapped onto GLES2 samplers and uniforms.
 */
@UnstableApi
object Anime4KEffect {
    private const val TAG = "Anime4KEffect"
    const val MODE_OFF = 0
    const val MODE_LIGHT = 1
    const val MODE_MEDIUM = 2

    /** Light: de-ring + line thinning. Medium: restore CNN + x2 upscale. */
    fun buildEffects(context: Context, mode: Int): List<GlEffect> = when (mode) {
        MODE_OFF -> emptyList()
        MODE_LIGHT -> listOf(
            GlEffectProgram(loadSource(context, R.raw.anime4k_clamp_highlights)),
            GlEffectProgram(loadSource(context, R.raw.anime4k_thinlines_fast)),
        )
        MODE_MEDIUM -> listOf(
            GlEffectProgram(loadSource(context, R.raw.anime4k_clamp_highlights)),
            GlEffectProgram(loadSource(context, R.raw.anime4k_restore_cnn_soft_m)),
            GlEffectProgram(loadSource(context, R.raw.anime4k_upscale_cnn_x2_m)),
        )
        else -> emptyList()
    }

    private fun loadSource(context: Context, resId: Int): String = try {
        context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        Log.e(TAG, "Failed to load shader resource $resId", e)
        ""
    }
}

/** One mpv pass extracted from a shader file. */
private class MPass(
    val bindNames: List<String>,
    val saveName: String?,
    val widthMult: Float,
    val heightMult: Float,
    val body: String,
)

/** Parses the mpv file into passes. */
private class MShader(source: String) {
    val passes: List<MPass>

    init {
        val passes = mutableListOf<MPass>()
        // Split on the whole //!DESC line (not just the marker — the description
        // text would otherwise leak into the GLSL body)
        val blocks = source.split(Regex("(?m)^//!DESC.*$"))
        for (block in blocks) {
            if (block.isBlank()) continue
            val lines = block.lines()
            val bindNames = mutableListOf<String>()
            var saveName: String? = null
            var widthMult = 1f
            var heightMult = 1f
            val bodyLines = mutableListOf<String>()
            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("//!HOOK") -> {
                        // The hook target is our single input frame. Bind it under
                        // the literal name the body references (MAIN/NATIVE/...) as
                        // well as the generic HOOKED alias.
                        val hookName = trimmed.removePrefix("//!HOOK").trim()
                        if (hookName.isNotEmpty()) {
                            bindNames.add(0, "HOOKED")
                            if (hookName != "HOOKED") bindNames.add(hookName)
                        }
                    }
                    trimmed.startsWith("//!BIND") -> {
                        val name = trimmed.removePrefix("//!BIND").trim()
                        if (name.isNotEmpty() && name !in bindNames) bindNames.add(name)
                    }
                    trimmed.startsWith("//!SAVE") -> {
                        saveName = trimmed.removePrefix("//!SAVE").trim().ifEmpty { null }
                    }
                    trimmed.startsWith("//!WIDTH") -> {
                        widthMult = parseMult(trimmed.removePrefix("//!WIDTH"))
                    }
                    trimmed.startsWith("//!HEIGHT") -> {
                        heightMult = parseMult(trimmed.removePrefix("//!HEIGHT"))
                    }
                    trimmed.startsWith("//!") -> {
                        // WHEN / COMPONENTS / etc. are ignored
                    }
                    else -> bodyLines.add(line)
                }
            }
            val body = bodyLines.joinToString("\n")
            if (body.contains("hook()")) {
                // Keep only binds the body actually references — GlProgram throws
                // on setting a uniform that doesn't exist in the program.
                val referenced = bindNames.filter { name ->
                    body.contains("${name}_tex") || body.contains("${name}_pos") ||
                        body.contains("${name}_pt") || body.contains("${name}_size")
                }.distinct()
                passes.add(MPass(referenced, saveName, widthMult, heightMult, body))
            }
        }
        this.passes = passes
    }

    private fun parseMult(expr: String): Float {
        val tokens = expr.trim().split(Regex("\\s+"))
        val mult = tokens.firstOrNull { it == "2" || it == "4" } ?: return 1f
        return if (tokens.lastOrNull() == "/") 1f / mult.toFloat() else mult.toFloat()
    }
}

private class GlEffectProgram(private val source: String) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Anime4KShaderProgram(MShader(source))
}

/** Runs the full mpv chain inside one Media3 [BaseGlShaderProgram]. */
@UnstableApi
private class Anime4KShaderProgram(
    private val shader: MShader,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {

    private inner class GLPass(
        val program: GlProgram,
        val bindNames: List<String>,
        val outWidth: Int,
        val outHeight: Int,
    )

    private var inputWidth = 0
    private var inputHeight = 0
    private var outputWidth = 0
    private var outputHeight = 0
    private var glPasses = listOf<GLPass>()
    private val intermediateTextures = mutableMapOf<String, Int>()
    private val intermediateFbos = mutableMapOf<String, Int>()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        releaseInternal()
        buildPasses()
        return Size(outputWidth, outputHeight)
    }

    private fun buildPasses() {
        glPasses = emptyList()
        val built = mutableListOf<GLPass>()
        val passCount = shader.passes.size
        var compiled = 0

        shader.passes.forEachIndexed { index, pass ->
            val outW = (inputWidth * pass.widthMult).toInt().coerceAtLeast(1)
            val outH = (inputHeight * pass.heightMult).toInt().coerceAtLeast(1)

            val fragment = buildFragmentShader(pass.body, pass.bindNames)
            val program = try {
                GlProgram(VERTEX_SHADER, fragment).apply {
                    setBufferAttribute("aPosition", QUAD_POSITIONS, 2)
                    setBufferAttribute("aTexCoor", QUAD_TEXCOORDS, 2)
                }.also { compiled++ }
            } catch (e: Exception) {
                // Never black out playback for a bad pass: fall back to a plain
                // copy so the chain keeps flowing (image may look unprocessed).
                Log.e(TAG, "Pass $index shader compile failed, using passthrough", e)
                Log.e(TAG, "--- fragment ---\n$fragment")
                try {
                    GlProgram(VERTEX_SHADER, PASSTHROUGH_FRAGMENT).apply {
                        setBufferAttribute("aPosition", QUAD_POSITIONS, 2)
                        setBufferAttribute("aTexCoor", QUAD_TEXCOORDS, 2)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Passthrough fallback also failed", e2)
                    throw e2
                }
            }

            val isLast = index == passCount - 1
            if (isLast) {
                outputWidth = outW
                outputHeight = outH
            } else if (pass.saveName != null) {
                val (tex, fbo) = createFbo(outW, outH)
                intermediateTextures[pass.saveName!!] = tex
                intermediateFbos[pass.saveName!!] = fbo
            }

            built.add(GLPass(program, pass.bindNames, outW, outH))
        }
        glPasses = built
        Log.i(TAG, "Anime4K chain: $compiled/$passCount passes compiled")
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (glPasses.isEmpty()) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        glPasses.forEachIndexed { index, glPass ->
            val isLast = index == glPasses.lastIndex
            if (!isLast) {
                val saveName = shader.passes[index].saveName
                val fbo = intermediateFbos[saveName] ?: 0
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            }
            // The last pass renders into the caller's focused output buffer
            GLES20.glViewport(0, 0, glPass.outWidth, glPass.outHeight)

            val program = glPass.program
            program.use()

            glPass.bindNames.forEachIndexed { unit, name ->
                val texId = if (name == "HOOKED" || name == "MAIN" || name == "NATIVE") {
                    inputTexId
                } else {
                    intermediateTextures[name] ?: inputTexId
                }
                program.setSamplerTexIdUniform("${name}_SAMPLER", texId, unit)
                val (texW, texH) = textureSizeOf(name)
                program.setFloatsUniformIfPresent("${name}_PT", floatArrayOf(1f / texW, 1f / texH))
            }
            program.setFloatsUniformIfPresent(
                "uPassOutSize", floatArrayOf(glPass.outWidth.toFloat(), glPass.outHeight.toFloat())
            )

            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun textureSizeOf(name: String): Pair<Int, Int> =
        if (name == "HOOKED" || name == "MAIN" || name == "NATIVE") {
            inputWidth to inputHeight
        } else {
            val savedAt = shader.passes.indexOfFirst { it.saveName == name }
            if (savedAt >= 0) {
                val p = shader.passes[savedAt]
                (inputWidth * p.widthMult).toInt() to (inputHeight * p.heightMult).toInt()
            } else inputWidth to inputHeight
        }

    private fun createFbo(width: Int, height: Int): Pair<Int, Int> {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texIds[0], 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return texIds[0] to fboIds[0]
    }

    private fun buildFragmentShader(body: String, bindNames: List<String>): String {
        val sb = StringBuilder()
        sb.append("#version 100\n")
        sb.append("precision highp float;\n")
        sb.append("varying vec2 vTexCoor;\n")
        sb.append("uniform vec2 uPassOutSize;\n")
        sb.append(SELECT4_HELPER)
        for (name in bindNames) {
            sb.append("uniform sampler2D ${name}_SAMPLER;\n")
            sb.append("uniform vec2 ${name}_PT;\n")
            sb.append("#define ${name}_pos (vTexCoor * uPassOutSize * ${name}_PT)\n")
            sb.append("#define ${name}_pt ${name}_PT\n")
            sb.append("#define ${name}_size (1.0 / ${name}_PT)\n")
            sb.append("#define ${name}_tex(p) texture2D(${name}_SAMPLER, p)\n")
            sb.append(
                "#define ${name}_texOff(o) " +
                    "texture2D(${name}_SAMPLER, ${name}_pos + ${name}_PT * (o))\n"
            )
        }
        // mpv GLSL uses texture(); GLES2 needs texture2D()
        var portedBody = body.replace(Regex("\\btexture\\("), "texture2D(")
        // GLES2 bans dynamic vector indexing in fragment shaders (the Anime4K
        // x2 pass does tex(...)[i.y*2+i.x]) — rewrite to a select4() helper.
        portedBody = rewriteDynamicVectorIndex(portedBody)
        sb.append(portedBody)
        sb.append("\nvoid main() { gl_FragColor = hook(); }\n")
        return sb.toString()
    }

    /** Rewrites `NAME_tex(<balanced>)[<expr>]` into `select4(NAME_tex(<balanced>), <expr>)`. */
    private fun rewriteDynamicVectorIndex(shader: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < shader.length) {
            val texStart = shader.indexOf("_tex(", i)
            if (texStart < 0) {
                out.append(shader.substring(i))
                break
            }
            out.append(shader.substring(i, texStart + 4)) // up to and including "_tex"
            val openParen = texStart + 4
            var depth = 0
            var close = -1
            for (j in openParen until shader.length) {
                when (shader[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            close = j
                            break
                        }
                    }
                }
            }
            if (close < 0) {
                out.append(shader.substring(openParen))
                break
            }
            val callText = shader.substring(openParen, close + 1)
            i = close + 1
            if (i < shader.length && shader[i] == '[') {
                val closeBracket = shader.indexOf(']', i)
                if (closeBracket > 0) {
                    val expr = shader.substring(i + 1, closeBracket)
                    out.append("select4").append(callText).append(", ").append(expr).append(")")
                    i = closeBracket + 1
                    continue
                }
            }
            out.append(callText)
        }
        return out.toString()
    }

    override fun release() {
        releaseInternal()
        super.release()
    }

    private fun releaseInternal() {
        glPasses.forEach { runCatching { it.program.delete() } }
        glPasses = emptyList()
        intermediateTextures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        intermediateFbos.values.forEach { GLES20.glDeleteFramebuffers(1, intArrayOf(it), 0) }
        intermediateTextures.clear()
        intermediateFbos.clear()
    }

    companion object {
        private const val TAG = "Anime4KShaderProgram"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoor;
            varying vec2 vTexCoor;
            void main() {
                gl_Position = aPosition;
                vTexCoor = aTexCoor;
            }
        """

        private const val PASSTHROUGH_FRAGMENT = """
            #version 100
            precision highp float;
            varying vec2 vTexCoor;
            uniform sampler2D HOOKED_SAMPLER;
            void main() { gl_FragColor = texture2D(HOOKED_SAMPLER, vTexCoor); }
        """

        // GLES2 forbids dynamic vector indexing in fragment shaders — Anime4K's
        // x2 pass does tex(...)[i.y*2+i.x], rewritten to select4().
        private const val SELECT4_HELPER = """
            float select4(vec4 v, int i) {
                if (i == 0) return v.x;
                if (i == 1) return v.y;
                if (i == 2) return v.z;
                return v.w;
            }
        """

        // Media3 renders video textures with the first row at the TOP (y flipped
        // vs GLES's bottom-left origin), so texcoords use 1-y.
        private val QUAD_POSITIONS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_TEXCOORDS = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    }
}