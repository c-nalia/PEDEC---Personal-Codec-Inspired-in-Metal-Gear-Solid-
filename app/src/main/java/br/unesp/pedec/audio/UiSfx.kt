package br.unesp.pedec.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Sons da interface. Aqui e SoundPool, nao PcmPlayer: sao cliques curtos que
 * precisam sair na hora e nao devem forcar o modo de chamada nem roubar a rota
 * do fone. Os sons da chamada em si continuam indo pelo PcmPlayer.
 *
 * Depende de noCompress "wav" no build.gradle: o SoundPool le o asset por
 * descritor de arquivo e nao aceita entrada comprimida no APK.
 */
object UiSfx {

    private const val TAG = "PedecUiSfx"

    private var pool: SoundPool? = null
    private val ids = ConcurrentHashMap<Sfx, Int>()
    private val loaded = ConcurrentHashMap<Int, Boolean>()

    @Volatile var volume: Float = 0.8f

    @Synchronized
    fun init(ctx: Context) {
        if (pool != null) return
        val sp = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            loaded[sampleId] = status == 0
        }
        val app = ctx.applicationContext
        listOf(Sfx.UI_OPEN, Sfx.UI_SELECT, Sfx.UI_CONFIRM, Sfx.UI_EXIT, Sfx.ALERT).forEach { sfx ->
            try {
                app.assets.openFd(sfx.path).use { fd ->
                    ids[sfx] = sp.load(fd, 1)
                }
            } catch (e: Exception) {
                Log.w(TAG, "nao carregou ${sfx.path}: ${e.message}")
            }
        }
        pool = sp
    }

    fun play(sfx: Sfx) {
        val sp = pool ?: return
        val id = ids[sfx] ?: return
        if (loaded[id] != true) return
        sp.play(id, volume, volume, 1, 0, 1f)
    }

    @Synchronized
    fun release() {
        pool?.release()
        pool = null
        ids.clear()
        loaded.clear()
    }
}
