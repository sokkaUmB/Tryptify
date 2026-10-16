// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtModelCompilationOptions
import ai.onnxruntime.OrtModelCompilationOptions.OrtCompileApiFlags
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.audio.sampler.SampleEdits

/**
 * The installed ONNX model, run on a Snapdragon's NPU ([QnnNpuStemBackend]) or
 * GPU ([QnnGpuStemBackend]) through ONNX Runtime's QNN execution provider.
 *
 * The same model file the CPU runs, not a separate download: the provider
 * takes the parts of the graph the hardware can run and leaves the rest to the
 * CPU inside the same session. Qualcomm's runtime libraries ship in the APK
 * (see `app/build.gradle.kts`, which keeps only the Hexagon generations a
 * current Snapdragon has).
 *
 * ## Whether it works is only known by trying
 *
 * The provider compiles the model for the hardware when the session opens,
 * and a model can fail to compile — an op the NPU lacks, a shape it will not
 * take. A failure is remembered against that model and that build of the app
 * ([QnnFailures]) and the backend reports itself unavailable after it, so the
 * next song goes straight to the next backend instead of spending another
 * minute failing. An app update tries again.
 *
 * A session never says the NPU failed to start: it runs the whole model on
 * the CPU instead. So the NPU's graph is compiled first, with the compiler
 * told to fail when the NPU took none of it ([compileForNpu]).
 *
 * ## The NPU's compile is cached
 *
 * Compiling a separator for the Hexagon NPU takes long enough to notice, so
 * the compiled graph is saved next to nothing else ([cacheDir]) the first time
 * and loaded from there after. It is tied to the model's hash and the app's
 * version, and one that will not load is deleted and rebuilt.
 */
abstract class QnnStemBackend(
    models: StemModelManager,
    protected val context: Context,
    private val unit: Accelerator,
) : OnnxStemBackend(models) {

    /** Which part of the chip, and the QNN library that drives it. */
    enum class Accelerator(val kind: BackendKind, val library: String) {
        NPU(BackendKind.QNN, "libQnnHtp.so"),
        GPU(BackendKind.GPU, "libQnnGpu.so"),
    }

    override val kind: BackendKind get() = unit.kind

    private val failures = QnnFailures(context)
    private val nativeDir: String get() = context.applicationInfo.nativeLibraryDir

    override fun unavailableReason(model: StemModel): String? = when {
        model.format != ModelFormat.ONNX -> "The installed model is not an ONNX model"
        !isSnapdragon() -> "Not a Snapdragon phone"
        // Also catches a build whose native libraries were not extracted: the
        // NPU's helper loads its half from a file, never from inside the APK.
        !File(nativeDir, unit.library).isFile -> "This build does not include the ${kind.label} libraries"
        else -> failures.reasonFor(unit, model)
    }

    override fun openSession(env: OrtEnvironment, model: StemModel, file: File): OrtSession = try {
        open(env, model, file)
    } catch (failure: Throwable) {
        failures.record(unit, model, failure)
        throw failure
    }

    override suspend fun separate(
        input: SampleEdits.Buffer,
        requested: Set<Stem>,
        options: SeparationOptions,
        onProgress: (StemProgress) -> Unit,
    ): Map<Stem, SampleEdits.Buffer> = try {
        super.separate(input, requested, options, onProgress)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // A model that compiled can still fail to run. Remember that too.
        models.installed()?.let { failures.record(unit, it, failure) }
        throw failure
    }

    private fun open(env: OrtEnvironment, model: StemModel, file: File): OrtSession {
        val provider = HashMap<String, String>()
        provider["backend_path"] = File(nativeDir, unit.library).absolutePath
        if (unit == Accelerator.GPU) {
            return env.createSession(file.absolutePath, baseOptions().apply { addQnn(provider) })
        }

        // The NPU half of QNN runs on the Hexagon DSP, which loads its library
        // from a directory it is told about: the app's own first, then the
        // phone's, as Qualcomm's own apps set it. The provider warns that a
        // path set before it "may cause the HTP backend to fail"; it would
        // set the app's directory alone, and this one starts with it.
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;$SYSTEM_DSP_PATHS", true)
        // Which Snapdragon this is, said rather than left for the NPU's driver
        // to work out — the provider's advice for any chip newer than its
        // own release. Unknown chips are still left to the driver.
        SnapdragonNpu.of(socModel())?.let { npu ->
            provider["soc_model"] = npu.socModel.toString()
            if (npu.htpArch in SnapdragonNpu.ARCHS_THE_PROVIDER_TAKES) provider["htp_arch"] = npu.htpArch.toString()
        }
        // A job measured in minutes: the clock the NPU can hold, not its burst.
        provider["htp_performance_mode"] = "sustained_high_performance"
        // The installed model is float; the NPU runs it at half precision.
        provider["enable_htp_fp16_precision"] = "1"
        // A slower compile for a faster graph, paid once thanks to the cache.
        provider["htp_graph_finalization_optimization_mode"] = "3"

        val cached = cacheFile(model)
        if (cached.isFile) {
            try {
                return env.createSession(cached.absolutePath, baseOptions().apply { addQnn(provider) })
            } catch (stale: Throwable) {
                Log.w(TAG, "Compiled NPU graph would not load; rebuilding it", stale)
                cached.delete()
            }
        }
        cacheDir(context).listFiles()?.forEach { it.delete() }
        cacheDir(context).mkdirs()
        compileForNpu(env, provider, file, cached)
        return env.createSession(cached.absolutePath, baseOptions().apply { addQnn(provider) })
    }

    /**
     * Compile [file] for the NPU into [out], or throw.
     *
     * Compiled ahead of the session rather than by it, for the one flag only
     * this way has: fail when the NPU took no part of the graph. A session
     * does not fail when the NPU cannot start. The provider logs the error,
     * claims nothing, and the whole model runs on the CPU under the NPU's
     * name — on a OnePlus 15 (QNN_DEVICE_ERROR_INVALID_CONFIG) for four
     * minutes a song, with no failure recorded and the GPU never tried.
     */
    private fun compileForNpu(env: OrtEnvironment, provider: Map<String, String>, file: File, out: File) {
        try {
            baseOptions().use { options ->
                options.addQnn(provider)
                // QNN logs at the session's level. One compile per model and
                // build, so its reasons are worth having in a debug log.
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                OrtModelCompilationOptions.createFromSessionOptions(env, options).use { compile ->
                    compile.setInputModelPath(file.absolutePath)
                    compile.setOutputModelPath(out.absolutePath)
                    compile.setEpContextEmbedMode(true)
                    compile.setCompilationFlags(EnumSet.of(OrtCompileApiFlags.ERROR_IF_NO_NODES_COMPILED))
                    compile.compileModel()
                }
            }
        } catch (failure: Throwable) {
            out.delete()
            throw failure
        }
        check(out.isFile) { "The NPU compiled nothing" }
    }

    /**
     * Named for the model, the build and how the NPU is set up
     * ([SETUP_REVISION]): a graph the old way compiled may hold no NPU part
     * at all, and must not be loaded as if it did.
     */
    private fun cacheFile(model: StemModel): File =
        File(cacheDir(context), "${model.sha256.take(16)}-${BuildConfig.VERSION_CODE}-$SETUP_REVISION.onnx")

    private fun socModel(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

    private fun isSnapdragon(): Boolean {
        if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val maker = Build.SOC_MANUFACTURER
            if (maker.equals("QTI", ignoreCase = true) || maker.contains("qualcomm", ignoreCase = true)) return true
        }
        return Build.HARDWARE.contains("qcom", ignoreCase = true)
    }

    companion object {
        private const val TAG = "QnnStemBackend"

        /** Where a Snapdragon keeps its own DSP libraries, after the app's. */
        private const val SYSTEM_DSP_PATHS = "/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp"

        /**
         * How the accelerators are set up. A change here rebuilds the NPU's
         * cache and tries again an accelerator that failed the old way, even
         * in a build with the same version code. "c2": the manifest declares
         * the DSP and OpenCL libraries, the chip is named, and the NPU graph
         * is compiled checked.
         */
        internal const val SETUP_REVISION = "c2"

        /** The NPU's compiled graphs. Hundreds of megabytes; [clear] when the model goes. */
        fun cacheDir(context: Context): File = File(context.filesDir, "stem_npu_cache")

        /** Forget the compiled graphs and the remembered failures, for a model being removed. */
        fun clear(context: Context) {
            cacheDir(context).deleteRecursively()
            QnnFailures(context).clear()
        }
    }
}

@Singleton
class QnnNpuStemBackend @Inject constructor(
    models: StemModelManager,
    @ApplicationContext context: Context,
) : QnnStemBackend(models, context, Accelerator.NPU)

@Singleton
class QnnGpuStemBackend @Inject constructor(
    models: StemModelManager,
    @ApplicationContext context: Context,
) : QnnStemBackend(models, context, Accelerator.GPU)

/**
 * Which accelerator could not run which model, kept across launches so a
 * failure costs one attempt and not one per song.
 */
internal class QnnFailures(context: Context) {

    private val prefs = context.getSharedPreferences("stem_accelerators", Context.MODE_PRIVATE)

    fun reasonFor(unit: QnnStemBackend.Accelerator, model: StemModel): String? = prefs.getString(key(unit, model), null)

    fun record(unit: QnnStemBackend.Accelerator, model: StemModel, failure: Throwable) {
        val reason = "Could not run on the ${unit.kind.label}: " +
            (failure.message?.lineSequence()?.firstOrNull()?.take(160) ?: failure.javaClass.simpleName)
        Log.w("QnnStemBackend", reason, failure)
        prefs.edit().putString(key(unit, model), reason).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(unit: QnnStemBackend.Accelerator, model: StemModel) =
        "${unit.name}:${model.sha256}:${BuildConfig.VERSION_CODE}:${QnnStemBackend.SETUP_REVISION}"
}

/**
 * A Snapdragon as QNN numbers it: its SoC model and its NPU's Hexagon
 * generation. The numbers are QNN's own, from its device table as ExecuTorch's
 * Qualcomm backend carries it (`QcomChipset`); they are not the chip's name.
 */
internal data class SnapdragonNpu(val socModel: Int, val htpArch: Int) {
    companion object {
        /** Phones' Snapdragons, by the name Android gives them (`Build.SOC_MODEL`). */
        private val KNOWN = mapOf(
            "SM8350" to SnapdragonNpu(30, 68), // 888
            "SM8450" to SnapdragonNpu(36, 69), // 8 Gen 1
            "SM8475" to SnapdragonNpu(42, 69), // 8+ Gen 1
            "SM8550" to SnapdragonNpu(43, 73), // 8 Gen 2
            "SM8635" to SnapdragonNpu(68, 73), // 8s Gen 3
            "SM7675" to SnapdragonNpu(70, 73), // 7+ Gen 3
            "SM8650" to SnapdragonNpu(57, 75), // 8 Gen 3
            "SM8750" to SnapdragonNpu(69, 79), // 8 Elite
            "SM8850" to SnapdragonNpu(87, 81), // 8 Elite Gen 5
            "SM8845" to SnapdragonNpu(97, 81), // 8 Gen 5
        )

        /**
         * The generations ONNX Runtime 1.29's `htp_arch` option reads; V79 is
         * not among them, so an 8 Elite gives its SoC model alone.
         */
        val ARCHS_THE_PROVIDER_TAKES = setOf(68, 69, 73, 75, 81)

        /** "SM8850", or a variant such as "SM8850-AC"; null for a chip not listed. */
        fun of(socModel: String?): SnapdragonNpu? =
            socModel?.trim()?.uppercase()?.substringBefore('-')?.let { KNOWN[it] }
    }
}
