package br.unesp.pedec.music

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent

/**
 * Controle do Spotify por intents do Android.
 *
 * Existe um SDK oficial (App Remote) e existe a Web API, e os dois exigem
 * registrar um app, obter client ID, configurar redirect URI e manter token
 * renovado. Para tocar uma playlist na voz, isso e infraestrutura demais.
 *
 * O Spotify responde ao intent padrao de midia do Android,
 * ACTION_MEDIA_PLAY_FROM_SEARCH, que aceita uma busca em texto livre e comeca
 * a tocar. Sem login, sem chave, sem token para expirar. O preco e nao ter
 * retorno: o intent e uma via de mao unica, entao nao da para saber o que
 * comecou a tocar nem se deu certo.
 *
 * Pausar e pular usam eventos de tecla de midia, que valem para qualquer
 * tocador em primeiro plano — funciona no Spotify, no YouTube Music e no que
 * mais estiver ativo.
 */
class MusicBridge(private val ctx: Context) {

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isSpotifyInstalled(): Boolean = try {
        ctx.packageManager.getPackageInfo(SPOTIFY, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Toca o que a busca encontrar. E o caminho para "toca Pink Floyd".
     *
     * @param focus "artist", "album", "playlist" ou vazio. Ajuda o Spotify a
     *        escolher entre a musica e a playlist de mesmo nome.
     */
    fun playSearch(query: String, focus: String = ""): Boolean {
        if (query.isBlank()) return false
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(SPOTIFY)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, mediaFocus(focus))
            putExtra(android.app.SearchManager.QUERY, query)
            when (focus) {
                "artist" -> putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
                "album" -> putExtra(MediaStore.EXTRA_MEDIA_ALBUM, query)
                "playlist" -> putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, query)
                else -> putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return send(intent, "busca \"$query\"")
    }

    /**
     * Abre um endereco spotify: direto. Usado pelos atalhos que voce salva,
     * onde um apelido curto aponta para uma playlist especifica.
     */
    fun playUri(uri: String): Boolean {
        val clean = uri.trim()
        if (clean.isBlank()) return false
        // Aceita tanto spotify:playlist:ID quanto o link https do compartilhar.
        val target = if (clean.startsWith("http")) toSpotifyUri(clean) else clean
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            setPackage(SPOTIFY)
            // Sem o referrer o Spotify as vezes abre a tela do item sem tocar.
            putExtra(
                Intent.EXTRA_REFERRER,
                Uri.parse("android-app://${ctx.packageName}")
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return send(intent, target)
    }

    /** https://open.spotify.com/playlist/ID?si=... -> spotify:playlist:ID */
    private fun toSpotifyUri(url: String): String {
        val path = url.substringAfter("open.spotify.com/", "")
            .substringBefore('?')
            .trim('/')
        val parts = path.split('/')
        return if (parts.size >= 2) "spotify:${parts[0]}:${parts[1]}" else url
    }

    fun pause() = mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun resume() = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    fun next() = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun mediaKey(code: Int): Boolean = try {
        // Precisa do par para baixo e para cima; so um dos dois e ignorado.
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        true
    } catch (e: Exception) {
        Log.w(TAG, "tecla de midia falhou", e)
        false
    }

    private fun send(intent: Intent, what: String): Boolean = try {
        ctx.startActivity(intent)
        Log.i(TAG, "spotify <- $what")
        true
    } catch (e: Exception) {
        Log.w(TAG, "nao consegui acionar o spotify: $what", e)
        false
    }

    private fun mediaFocus(focus: String) = when (focus) {
        "artist" -> "vnd.android.cursor.item/artist"
        "album" -> "vnd.android.cursor.item/album"
        "playlist" -> "vnd.android.cursor.item/playlist"
        else -> "vnd.android.cursor.item/*"
    }

    private companion object {
        const val TAG = "PedecMusic"
        const val SPOTIFY = "com.spotify.music"
    }
}
