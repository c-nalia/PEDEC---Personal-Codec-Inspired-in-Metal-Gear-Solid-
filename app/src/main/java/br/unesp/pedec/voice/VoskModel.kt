package br.unesp.pedec.voice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Instala o modelo do Vosk. Ele vive em assets/vosk-model-pt/ e e copiado para
 * o armazenamento interno na primeira execucao, porque a biblioteca nativa
 * precisa de caminhos reais no disco e nao sabe ler assets.
 *
 * Um marcador guarda a assinatura dos arquivos instalados: se voce trocar o
 * modelo, a copia refaz sozinha na proxima abertura.
 *
 * Sobre os dois formatos de pasta, que confundem muita gente: o Vosk aceita
 * dois arranjos diferentes e escolhe pelo que encontra (ver Model::Model em
 * src/model.cc do vosk-api). Nenhum e mais correto que o outro.
 */
object VoskModel {

    private const val TAG = "PedecVoskModel"
    const val ASSET_DIR = "vosk-model-pt"

    /**
     * V2: arranjo em subpastas, usado pelos modelos de servidor mais recentes.
     *     am/final.mdl, conf/model.conf, conf/mfcc.conf, graph/HCLr.fst,
     *     graph/Gr.fst, graph/phones/word_boundary.int, ivector/
     *
     * V1: arranjo plano, que e como vem a maioria dos modelos "for android",
     *     inclusive o pequeno de portugues.
     *     final.mdl, mfcc.conf, HCLr.fst, Gr.fst, disambig_tid.int,
     *     word_boundary.int, ivector/
     *
     * O words.txt nao e obrigatorio em nenhum dos dois quando o Gr.fst carrega
     * a tabela de simbolos embutida, que e o caso dos modelos distribuidos.
     */
    enum class Layout { V1, V2, NONE }

    sealed class Status {
        data class Ready(val path: String, val layout: Layout) : Status()
        data class Missing(val message: String) : Status()
    }

    fun installedDir(ctx: Context): File = File(ctx.filesDir, "vosk/$ASSET_DIR")

    /** Descobre o arranjo do jeito que o proprio Vosk descobre. */
    fun detect(dir: File): Layout = when {
        File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile -> Layout.V2
        File(dir, "final.mdl").isFile && File(dir, "mfcc.conf").isFile -> Layout.V1
        else -> Layout.NONE
    }

    /** Mesma deteccao, olhando a lista de caminhos de assets. */
    private fun detectInAssets(files: List<String>): Layout {
        val rel = files.map { it.removePrefix("$ASSET_DIR/") }.toSet()
        return when {
            "am/final.mdl" in rel && "conf/model.conf" in rel -> Layout.V2
            "final.mdl" in rel && "mfcc.conf" in rel -> Layout.V1
            else -> Layout.NONE
        }
    }

    /**
     * Garante o modelo em disco e devolve o caminho.
     * Chame fora da thread principal: a copia leva alguns segundos.
     */
    fun ensure(ctx: Context): Status {
        val target = installedDir(ctx)
        val marker = File(target, ".pedec-install")

        val assetFiles = try {
            listAssets(ctx, ASSET_DIR)
        } catch (e: Exception) {
            Log.w(TAG, "falha ao listar assets", e)
            emptyList()
        }

        if (assetFiles.isEmpty()) {
            // Sem modelo em assets, ainda aceita um modelo colocado na mao
            // (adb push direto para o diretorio de arquivos do app).
            val layout = detect(target)
            return if (layout != Layout.NONE) {
                Status.Ready(target.absolutePath, layout)
            } else {
                Status.Missing(
                    "modelo do Vosk ausente: descompacte o vosk-model-small-pt " +
                        "em app/src/main/assets/$ASSET_DIR/ e recompile"
                )
            }
        }

        // Conferir o arranjo antes de copiar 40 MB: uma mensagem clara aqui vale
        // muito mais que um KALDI_ERR abortando o processo la na frente.
        val layout = detectInAssets(assetFiles)
        if (layout == Layout.NONE) {
            return Status.Missing(
                "assets/$ASSET_DIR nao parece um modelo do Vosk. Esperado " +
                    "final.mdl + mfcc.conf na raiz (arranjo plano) ou " +
                    "am/final.mdl + conf/model.conf (arranjo em subpastas). " +
                    "Encontrado: " + assetFiles.take(8)
                        .joinToString(", ") { it.removePrefix("$ASSET_DIR/") }
            )
        }

        val signature = assetFiles.sorted().joinToString("\n")
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == signature) {
            return Status.Ready(target.absolutePath, layout)
        }

        Log.i(TAG, "instalando modelo $layout (${assetFiles.size} arquivos)")
        return try {
            target.deleteRecursively()
            target.mkdirs()
            assetFiles.forEach { rel ->
                val dest = File(target, rel.removePrefix("$ASSET_DIR/"))
                dest.parentFile?.mkdirs()
                ctx.assets.open(rel).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
            marker.writeText(signature)
            Log.i(TAG, "modelo pronto em ${target.absolutePath}")
            Status.Ready(target.absolutePath, layout)
        } catch (e: Exception) {
            Log.e(TAG, "falha ao instalar modelo", e)
            Status.Missing("falha ao instalar o modelo: ${e.message}")
        }
    }

    /** Lista recursiva dos arquivos dentro de um diretorio de assets. */
    private fun listAssets(ctx: Context, dir: String): List<String> {
        val children = ctx.assets.list(dir) ?: return emptyList()
        if (children.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        children.forEach { child ->
            val path = "$dir/$child"
            val sub = ctx.assets.list(path)
            if (sub.isNullOrEmpty()) out += path else out += listAssets(ctx, path)
        }
        return out
    }
}
