package io.github.neumanv.neudns.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.neumanv.neudns.Prefs
import io.github.neumanv.neudns.ui.MainActivity

/** Botón de ajustes rápidos para encender o apagar el filtro sin abrir la app. */
class ToggleTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (VpnState.status.running) {
            DnsVpnService.stop(this)
            return
        }
        // Sin apps elegidas o sin permiso de VPN hay que pasar por la app.
        if (Prefs(this).selectedApps.isEmpty() || VpnService.prepare(this) != null) {
            openApp()
            return
        }
        if (!DnsVpnService.start(this)) openApp()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = VpnState.status.running
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(Prefs(this).provider.nameRes).substringBefore(" (")
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
