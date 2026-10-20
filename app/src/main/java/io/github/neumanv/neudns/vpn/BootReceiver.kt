package io.github.neumanv.neudns.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import io.github.neumanv.neudns.Prefs

/** Vuelve a encender el filtro tras reiniciar el dispositivo o actualizar la app, si estaba encendido. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.enabled || prefs.selectedApps.isEmpty()) return
        // Si el permiso de VPN se ha perdido (otra VPN), hace falta abrir la app.
        if (VpnService.prepare(context) != null) return
        // Si Android no deja arrancarlo ahora, se encenderá al abrir la app.
        DnsVpnService.start(context)
    }
}
