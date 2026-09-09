package br.unesp.pedec.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.unesp.pedec.data.Prefs

/**
 * Religa a escuta depois de reiniciar o aparelho.
 * No Android 14+ o sistema pode barrar servico de microfone iniciado no boot;
 * nesse caso basta abrir o app uma vez.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs.get(context)
        if (!prefs.autoStart || !prefs.isConfigured) return
        try {
            CodecService.start(context)
        } catch (e: Exception) {
            Log.w("PedecBoot", "nao foi possivel religar no boot", e)
        }
    }
}
