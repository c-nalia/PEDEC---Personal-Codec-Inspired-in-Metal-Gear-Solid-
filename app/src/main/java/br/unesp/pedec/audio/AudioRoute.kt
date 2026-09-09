package br.unesp.pedec.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Empurra entrada e saida para o fone de conducao ossea (perfil SCO/hands-free)
 * e devolve o audio ao normal quando a chamada termina.
 */
class AudioRoute(ctx: Context) {

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var engaged = false

    /**
     * @return true se conseguiu entrar no perfil de chamada. Em false, o
     *         chamador deve tocar pela rota de midia.
     */
    @SuppressLint("MissingPermission")
    fun engage(): Boolean {
        if (engaged) return true
        engaged = true
        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = am.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (target != null) {
                val ok = am.setCommunicationDevice(target)
                Log.i(TAG, "rota de comunicacao -> ${target.productName} ok=$ok")
                if (ok) return true
                // O aparelho recusou o perfil de chamada: desfaz e avisa, para
                // a sessao continuar pela rota de midia em vez de ficar muda.
                am.mode = previousMode
                engaged = false
                return false
            }
        } else {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoAvailableOffCall) {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                Log.i(TAG, "SCO legado ligado")
                return true
            }
        }
        Log.i(TAG, "sem perfil de chamada disponivel, usando a rota de midia")
        am.mode = previousMode
        engaged = false
        return false
    }

    fun release() {
        if (!engaged) return
        engaged = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        } catch (e: Exception) {
            Log.w(TAG, "falha ao liberar rota", e)
        }
        am.mode = previousMode
    }

    /** true se existe qualquer fone bluetooth conectado. */
    fun hasHeadset(): Boolean {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    /**
     * true se algum fone anuncia perfil de chamada. A2DP sozinho nao conta:
     * ele toca som, mas nao tem microfone nem canal de voz.
     */
    fun hasCallProfile(): Boolean {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    private companion object { const val TAG = "PedecAudioRoute" }
}
