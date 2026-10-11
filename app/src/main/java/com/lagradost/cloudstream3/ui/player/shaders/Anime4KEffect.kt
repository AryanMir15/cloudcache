package com.lagradost.cloudstream3.ui.player.shaders

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.IOException

/**
 * Anime4K GPU shaders for the built-in Media3 player.
 *
 * This is a faithful port of the AVBox media3 Anime4K implementation
 * (github.com/XiaochangXu/AVBox, `player/effect/anime4k/`, GPL-3.0 — compatible
 * with this project's license). It parses the verbatim mpv shaders shipped in
 * res/raw, plans the multi-pass chain, and runs it inside one
 * [BaseGlShaderProgram] with per-pass framebuffers and the original frame
 * available to every pass — exactly like mpv.
 *
 * Shaders: Anime4K by bloc97 (MIT, copyright 2019-2021, in each .glsl file).
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
            Anime4kGlEffect(
                listOf(
                    "anime4k_clamp_highlights",
                    "anime4k_thinlines_fast",
                ),
                context,
            )
        )
        MODE_MEDIUM -> listOf(
            Anime4kGlEffect(
                listOf(
                    "anime4k_clamp_highlights",
                    "anime4k_restore_cnn_soft_m",
                    "anime4k_upscale_cnn_x2_m",
                ),
                context,
            )
        )
        else -> emptyList()
    }

    internal fun loadSource(context: Context, asset: String): String = try {
        context.resources.openRawResource(
            context.resources.getIdentifier(asset, "raw", context.packageName)
        ).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        Log.e(TAG, "Failed to load shader resource $asset", e)
        ""
    }
}

private class Anime4kGlEffect(
    private val assets: List<String>,
    private val context: Context,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Anime4kChainProgram(context, assets, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/** ---- Ported from AVBox Anime4kShader.kt ---- */

internal data class Anime4kPass(
    val name: String,
    val binds: List<String>,
    val save: String,
    val widthExpr: List<String>,
    val heightExpr: List<String>,
    val components: Int,
    val whenExpr: List<String>,
    val fragmentShader: String,
)

internal data class Anime4kBinding(val name: String, val width: Int, val height: Int)

internal data class Anime4kPlannedPass(
    val pass: Anime4kPass,
    val width: Int,
    val height: Int,
    val writesScreen: Boolean,
    val bindings: List<Anime4kBinding>,
)

internal data class Anime4kBindingSource(
    val name: String, val target: Int, val width: Int, val height: Int,
)

internal data class Anime4kPassIo(
    val planned: Anime4kPlannedPass,
    val bindings: List<Anime4kBindingSource>,
    val outputTarget: Int,
    val createdTarget: Boolean,
)

private object Anime4kShader {

    const val MAIN = "MAIN"
    const val HOOKED = "HOOKED"
    const val INPUT_BINDING = -1
    const val SCREEN_TARGET = -1

    fun assignTargets(planned: List<Anime4kPlannedPass>): List<Anime4kPassIo> {
        val latest = HashMap<String, Int>()
        val sizes = HashMap<String, IntArray>()
        val result = ArrayList<Anime4kPassIo>()
        var nextTarget = 0
        planned.forEach { item ->
            val bindings = item.bindings.map { binding ->
                Anime4kBindingSource(
                    name = binding.name,
                    target = latest[binding.name] ?: INPUT_BINDING,
                    width = binding.width,
                    height = binding.height,
                )
            }
            var outputTarget = SCREEN_TARGET
            var created = false
            if (!item.writesScreen) {
                val save = item.pass.save
                val existing = latest[save]
                val readsSave = item.bindings.any { it.name == save || it.name == HOOKED }
                val reusable = existing != null && !readsSave &&
                    sizes[save]?.let { it[0] == item.width && it[1] == item.height } == true
                outputTarget = if (reusable) {
                    existing!!
                } else {
                    created = true
                    nextTarget++
                    nextTarget - 1
                }
                latest[save] = outputTarget
                sizes[save] = intArrayOf(item.width, item.height)
                if (save == MAIN) latest[HOOKED] = outputTarget
            }
            result.add(Anime4kPassIo(item, bindings, outputTarget, created))
        }
        return result
    }

    private const val DIRECTIVE = "//!"
    private const val PRECISION = "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
        "precision highp float;\n" +
        "#else\n" +
        "precision mediump float;\n" +
        "#endif\n"
    private const val ENTRY = "void main() {\n  gl_FragColor = hook();\n}\n"

    private val IVEC_DECL =
        Regex("""(?m)^(\s*)ivec2\s+(\w+)\s*=\s*ivec2\((\w+)\s*\*\s*vec2\(2\.0\)\)\s*;""")
    private val DYNAMIC_INDEX =
        Regex("""(?m)^(\s*)(?:\w+\s+)?(\w+)\s*=\s*([^;]*?)\[(\w+)\.y\s*\*\s*2\s*\+\s*\4\.x\]\s*;""")

    fun parse(text: String): List<Anime4kPass> = splitBlocks(text).map(::buildPass)

    fun planPasses(
        passes: List<Anime4kPass>,
        inputWidth: Int,
        inputHeight: Int,
    ): List<Anime4kPlannedPass> {
        val sizes = HashMap<String, IntArray>()
        sizes[MAIN] = intArrayOf(inputWidth, inputHeight)
        sizes[HOOKED] = intArrayOf(inputWidth, inputHeight)
        val planned = ArrayList<Anime4kPlannedPass>()
        passes.forEach { pass ->
            val current = sizes[HOOKED] ?: intArrayOf(inputWidth, inputHeight)
            val width = resolve(pass.widthExpr, sizes, current[0])
            val height = resolve(pass.heightExpr, sizes, current[1])
            sizes[pass.save] = intArrayOf(width, height)
            if (pass.save == MAIN) sizes[HOOKED] = intArrayOf(width, height)
            val bindings = pass.binds.map { name ->
                val bound = sizes[name] ?: intArrayOf(width, height)
                Anime4kBinding(name, bound[0], bound[1])
            }
            planned.add(Anime4kPlannedPass(pass, width, height, false, bindings))
        }
        return planned.mapIndexed { index, item ->
            item.copy(writesScreen = index == planned.lastIndex)
        }
    }

    fun resolveSize(expr: List<String>, widthOf: (String) -> Int, heightOf: (String) -> Int): Int {
        val stack = ArrayList<Int>()
        expr.forEach { token ->
            when {
                token == "*" -> {
                    if (stack.size < 2) return@forEach
                    val b = stack.removeAt(stack.lastIndex)
                    val a = stack.removeAt(stack.lastIndex)
                    stack.add(a * b)
                }
                token.endsWith(".w") -> stack.add(widthOf(token.dropLast(2)))
                token.endsWith(".h") -> stack.add(heightOf(token.dropLast(2)))
                else -> token.toIntOrNull()?.let { stack.add(it) }
            }
        }
        return stack.lastOrNull() ?: 0
    }

    fun adaptToEs2(body: String): String {
        var adapted = IVEC_DECL.replace(body) { match ->
            val indent = match.groupValues[1]
            val index = match.groupValues[2]
            val fraction = match.groupValues[3]
            "${indent}float ${index}x = step(0.5, ${fraction}.x);\n" +
                "${indent}float ${index}y = step(0.5, ${fraction}.y);"
        }
        adapted = DYNAMIC_INDEX.replace(adapted) { match ->
            val indent = match.groupValues[1]
            val target = match.groupValues[2]
            val sample = match.groupValues[3]
            val index = match.groupValues[4]
            "${indent}vec4 ${index}v = ${sample};\n" +
                "${indent}float ${target} = mix(mix(${index}v.x, ${index}v.y, ${index}x)," +
                " mix(${index}v.z, ${index}v.w, ${index}x), ${index}y);"
        }
        return adapted
    }

    private fun resolve(expr: List<String>, sizes: Map<String, IntArray>, fallback: Int): Int {
        val value = resolveSize(
            expr,
            { sizes[it]?.get(0) ?: fallback },
            { sizes[it]?.get(1) ?: fallback },
        )
        return if (value > 0) value else fallback
    }

    private class Block(val header: List<String>, val body: String)

    private fun splitBlocks(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        var header: MutableList<String>? = null
        var body: StringBuilder? = null
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.startsWith(DIRECTIVE + "DESC") -> {
                    val previous = header
                    if (previous != null) blocks.add(Block(previous, body?.toString().orEmpty()))
                    header = ArrayList<String>().apply { add(line) }
                    body = StringBuilder()
                }
                line.startsWith(DIRECTIVE) -> header?.add(line)
                else -> body?.append(line)?.append('\n')
            }
        }
        val last = header
        if (last != null) blocks.add(Block(last, body?.toString().orEmpty()))
        return blocks
    }

    private fun buildPass(block: Block): Anime4kPass {
        val binds = ArrayList<String>()
        var name = ""
        var save = MAIN
        var widthExpr: List<String> = emptyList()
        var heightExpr: List<String> = emptyList()
        var components = 4
        var whenExpr: List<String> = emptyList()
        block.header.forEach { line ->
            val tokens = line.removePrefix(DIRECTIVE).trim().split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val value = tokens.drop(1)
            when (tokens[0]) {
                "DESC" -> name = value.joinToString(" ")
                "BIND" -> value.firstOrNull()?.let { binds.add(it) }
                "SAVE" -> value.firstOrNull()?.let { save = it }
                "WIDTH" -> widthExpr = value
                "HEIGHT" -> heightExpr = value
                "COMPONENTS" -> components = value.firstOrNull()?.toIntOrNull() ?: components
                "WHEN" -> whenExpr = value
            }
        }
        val usedBinds = binds.filter { bind ->
            val alias = when (bind) {
                HOOKED -> MAIN
                MAIN -> HOOKED
                else -> null
            }
            block.body.contains("${bind}_") || (alias != null && block.body.contains("${alias}_"))
        }
        return Anime4kPass(
            name = name,
            binds = usedBinds,
            save = save,
            widthExpr = widthExpr,
            heightExpr = heightExpr,
            components = components,
            whenExpr = whenExpr,
            fragmentShader = fragmentShader(block.body, usedBinds),
        )
    }

    private fun fragmentShader(body: String, binds: List<String>): String = buildString {
        append(PRECISION)
        append("varying vec2 vTexCoord;\n")
        val emitted = LinkedHashSet<String>()
        binds.forEach { tex ->
            emitted.add(tex)
            append("uniform sampler2D uTex$tex;\n")
            append("uniform vec2 uPt$tex;\n")
            append("uniform vec2 uSize$tex;\n")
            appendTexMacros(tex, tex)
        }
        binds.forEach { tex ->
            val alias = when (tex) {
                HOOKED -> MAIN
                MAIN -> HOOKED
                else -> null
            }
            if (alias != null && emitted.add(alias)) appendTexMacros(alias, tex)
        }
        val adapted = adaptToEs2(body)
        append(adapted)
        if (!adapted.endsWith("\n")) append('\n')
        append(ENTRY)
    }

    private fun StringBuilder.appendTexMacros(name: String, uniform: String) {
        append("#define ${name}_tex(p) texture2D(uTex$uniform, (p))\n")
        append("#define ${name}_texOff(off) texture2D(uTex$uniform, vTexCoord + (off) * uPt$uniform)\n")
        append("#define ${name}_pos vTexCoord\n")
        append("#define ${name}_pt uPt$uniform\n")
        append("#define ${name}_size uSize$uniform\n")
    }
}

/** ---- Ported from AVBox Anime4kChainProgram.kt ---- */

@UnstableApi
private class Anime4kChainProgram(
    private val context: Context,
    private val assets: List<String>,
    hdr: Boolean,
) : BaseGlShaderProgram(false, 3) {

    private val passes = ArrayList<CompiledPass>()
    private val targets = ArrayList<Target>()
    private var copyProgram: GlProgram? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var failed = hdr

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseGl()
        outputWidth = inputWidth
        outputHeight = inputHeight
        if (!failed) {
            try {
                build(inputWidth, inputHeight)
            } catch (e: Exception) {
                failed = true
                passes.clear()
                releaseTargets()
                Log.e("Anime4kChain", "chain build failed, fallback to passthrough", e)
            }
        }
        return Size(outputWidth, outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (failed) {
                drawCopy(inputTexId)
                return
            }
            val previousFramebuffer = IntArray(1)
            val previousViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFramebuffer, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, previousViewport, 0)
            passes.forEach { compiled ->
                if (compiled.writesScreen) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previousFramebuffer[0])
                    GLES20.glViewport(
                        previousViewport[0], previousViewport[1],
                        previousViewport[2], previousViewport[3],
                    )
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, compiled.targetFramebuffer)
                    GLES20.glViewport(0, 0, compiled.width, compiled.height)
                }
                val program = compiled.program
                program.use()
                compiled.bindings.forEachIndexed { unit, binding ->
                    val textureId = if (binding.isInput) inputTexId else binding.textureId
                    try {
                        program.setSamplerTexIdUniform("uTex${binding.name}", textureId, unit)
                    } catch (e: RuntimeException) {
                        // sampler not present in this program — skip
                    }
                    program.setFloatsUniformIfPresent(
                        "uPt${binding.name}",
                        floatArrayOf(1f / binding.width, 1f / binding.height),
                    )
                    program.setFloatsUniformIfPresent(
                        "uSize${binding.name}",
                        floatArrayOf(binding.width.toFloat(), binding.height.toFloat()),
                    )
                }
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        releaseGl()
    }

    private fun build(inputWidth: Int, inputHeight: Int) {
        // Split the clamp file around the middle passes: its stats passes run
        // first (on the original), its clamp pass runs last (on the result).
        val parsed = assets.map { Anime4kShader.parse(Anime4KEffect.loadSource(context, it)) }
        val clamp = parsed.firstOrNull().orEmpty()
        val middle = parsed.drop(1).flatten()
        val composed = clamp.dropLast(1) + middle + clamp.takeLast(1)
        val planned = Anime4kShader.planPasses(composed, inputWidth, inputHeight)
        if (planned.isEmpty()) throw IllegalStateException("no pass parsed")

        val byTarget = HashMap<Int, Target>()
        Anime4kShader.assignTargets(planned).forEach { item ->
            val plannedPass = item.planned
            val program = GlProgram(VERTEX_SHADER, plannedPass.pass.fragmentShader)
            program.setBufferAttribute(
                "aFramePosition",
                quadArray(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            val bindings = item.bindings.map { binding ->
                val target = byTarget[binding.target]
                BoundBinding(
                    binding.name,
                    target?.texture ?: 0,
                    target == null,
                    binding.width,
                    binding.height,
                )
            }
            val targetFramebuffer: Int
            if (item.outputTarget == Anime4kShader.SCREEN_TARGET) {
                targetFramebuffer = 0
            } else {
                val target = byTarget[item.outputTarget]
                    ?: newTarget(plannedPass.width, plannedPass.height)
                        .also { byTarget[item.outputTarget] = it }
                targetFramebuffer = target.framebuffer
            }
            passes.add(
                CompiledPass(
                    program = program,
                    bindings = bindings,
                    targetFramebuffer = targetFramebuffer,
                    width = plannedPass.width,
                    height = plannedPass.height,
                    writesScreen = plannedPass.writesScreen,
                ),
            )
        }
        val last = planned.last()
        outputWidth = last.width
        outputHeight = last.height
        Log.i(
            "Anime4kChain",
            "chain: ${assets.joinToString("+")} passes=${passes.size} " +
                "in=${inputWidth}x$inputHeight out=${outputWidth}x$outputHeight"
        )
    }

    private fun newTarget(width: Int, height: Int): Target {
        val texture = createTexture(width, height)
        val target = Target(texture, createFramebuffer(texture), width, height)
        targets.add(target)
        return target
    }

    private fun drawCopy(inputTexId: Int) {
        val program = copyProgram ?: GlProgram(VERTEX_SHADER, COPY_SHADER).also {
            it.setBufferAttribute(
                "aFramePosition",
                quadArray(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            copyProgram = it
        }
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createFramebuffer(textureId: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, textureId, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    private fun releaseGl() {
        passes.forEach { runCatching { it.program.delete() } }
        runCatching { copyProgram?.delete() }
        passes.clear()
        releaseTargets()
        copyProgram = null
    }

    private fun releaseTargets() {
        targets.forEach { target ->
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(target.texture), 0) }
            GLES20.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        }
        targets.clear()
    }

    private class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)

    private class BoundBinding(
        val name: String,
        val textureId: Int,
        val isInput: Boolean,
        val width: Int,
        val height: Int,
    )

    private class CompiledPass(
        val program: GlProgram,
        val bindings: List<BoundBinding>,
        val targetFramebuffer: Int,
        val width: Int,
        val height: Int,
        val writesScreen: Boolean,
    )

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """

        const val COPY_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vTexCoord);
            }
            """

        /** media3's standard quad (float[] of x,y,1 per vertex). */
        fun quadArray(): FloatArray = GlUtil.getNormalizedCoordinateBounds()
    }
}