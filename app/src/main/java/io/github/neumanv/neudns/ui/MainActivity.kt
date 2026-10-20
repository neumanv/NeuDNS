package io.github.neumanv.neudns.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.neumanv.neudns.Prefs
import io.github.neumanv.neudns.R
import io.github.neumanv.neudns.availableProviders
import io.github.neumanv.neudns.vpn.DnsVpnService
import io.github.neumanv.neudns.vpn.VpnState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var message: TextView
    private lateinit var toggle: Button
    private lateinit var counter: TextView
    private lateinit var list: ListView
    private lateinit var header: View
    private lateinit var expanded: View
    private lateinit var spacer: View
    private lateinit var compact: View
    private lateinit var compactDot: View
    private lateinit var compactStatus: TextView
    private lateinit var compactToggle: Button
    private lateinit var adapter: AppListAdapter

    private val selected = mutableSetOf<String>()
    private var loaded = false

    private val background: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val restart = Runnable { if (VpnState.status.running) DnsVpnService.start(this) }
    private val statusListener: (VpnState.Status) -> Unit = { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        selected += prefs.selectedApps
        adapter = AppListAdapter(this, background, isSelected = { it in selected }, onToggle = ::toggleApp)
        setContentView(R.layout.activity_main)
        bindViews()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        VpnState.addListener(statusListener)
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        VpnState.removeListener(statusListener)
    }

    override fun onDestroy() {
        main.removeCallbacks(restart)
        background.shutdownNow()
        super.onDestroy()
    }

    // ---------- Interfaz ----------

    private fun bindViews() {
        val root = findViewById<View>(R.id.root)
        val padding = root.paddingLeft
        list = findViewById(R.id.apps)
        val listBottom = resources.getDimensionPixelSize(R.dimen.list_bottom_padding)
        // Android 15+ dibuja detrás de las barras del sistema: se deja hueco para ellas. Por abajo
        // el hueco va dentro de la lista, para que sus filas se vean pasar por detrás.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(padding + bars.left, bars.top, padding + bars.right, 0)
            list.setPadding(0, 0, 0, listBottom + bars.bottom)
            insets
        }

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)
        message = findViewById(R.id.message)
        counter = findViewById(R.id.counter)

        toggle = findViewById(R.id.toggle)
        toggle.setOnClickListener { toggleFilter() }
        compactToggle = findViewById(R.id.compact_toggle)
        compactToggle.setOnClickListener { toggleFilter() }
        compactDot = findViewById(R.id.compact_dot)
        compactStatus = findViewById(R.id.compact_status)

        findViewById<Spinner>(R.id.provider).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                R.layout.item_provider,
                availableProviders.map { getString(it.nameRes) },
            )
            setSelection(prefs.providerIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == prefs.providerIndex) return
                    prefs.providerIndex = position
                    refreshStatus()
                    scheduleRestart()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.setFilter(s.toString().trim())
        })

        bindCollapsingHeader()
        list.adapter = adapter
    }

    /**
     * La cabecera va encima de la lista. Un hueco vacío al principio de la lista ocupa su sitio;
     * al desplazar, la cabecera sube con la lista hasta dejar solo el buscador bajo la barra compacta.
     */
    private fun bindCollapsingHeader() {
        header = findViewById(R.id.header)
        expanded = findViewById(R.id.expanded)
        compact = findViewById(R.id.compact)
        spacer = View(this).apply { layoutParams = AbsListView.LayoutParams(MATCH_PARENT, 0) }
        list.addHeaderView(spacer, null, false)

        header.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            spacer.layoutParams = spacer.layoutParams.apply { height = bottom - top }
            list.post { updateHeader() }
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) = updateHeader()
        })
    }

    private fun updateHeader() {
        val first = list.getChildAt(0)
        val scrolled = if (list.firstVisiblePosition == 0 && first != null) -first.top else Int.MAX_VALUE
        val range = (expanded.height - compact.height).coerceAtLeast(1)
        val offset = scrolled.coerceIn(0, range)
        header.translationY = -offset.toFloat()

        val progress = offset.toFloat() / range
        expanded.alpha = 1f - (progress / FADE_OUT_END).coerceAtMost(1f)
        val compactAlpha = ((progress - FADE_IN_START) / (1f - FADE_IN_START)).coerceIn(0f, 1f)
        compact.alpha = compactAlpha
        // Lo que está invisible del todo no recibe toques: así nunca se pulsa un botón que no se ve.
        compact.visibility = if (compactAlpha > 0f) View.VISIBLE else View.INVISIBLE
        expanded.visibility = if (progress >= FADE_OUT_END) View.INVISIBLE else View.VISIBLE
    }

    private fun toggleFilter() {
        if (VpnState.status.running) DnsVpnService.stop(this) else requestStart()
    }

    private fun refreshStatus() {
        val current = VpnState.status
        val count = selected.size
        val providerName = getString(prefs.provider.nameRes).substringBefore(" (")
        status.text = if (current.running) {
            resources.getQuantityString(R.plurals.status_on, count, count, providerName)
        } else {
            getString(R.string.status_off)
        }
        statusDot.background.setTint(getColor(if (current.running) R.color.accent else R.color.off))
        toggle.setText(if (current.running) R.string.action_stop else R.string.action_start)
        toggle.setBackgroundResource(if (current.running) R.drawable.bg_button_stop else R.drawable.bg_button_start)
        toggle.setTextColor(getColor(if (current.running) R.color.accent else R.color.on_accent))
        compactDot.background.setTint(getColor(if (current.running) R.color.accent else R.color.off))
        compactStatus.text = if (current.running) {
            resources.getQuantityString(R.plurals.status_on_short, count, count)
        } else {
            getString(R.string.status_off)
        }
        compactToggle.setText(if (current.running) R.string.action_stop_short else R.string.action_start_short)
        compactToggle.setBackgroundResource(if (current.running) R.drawable.bg_button_stop else R.drawable.bg_button_start)
        compactToggle.setTextColor(getColor(if (current.running) R.color.accent else R.color.on_accent))
        counter.text = if (loaded) {
            resources.getQuantityString(R.plurals.selected_count, count, count)
        } else {
            getString(R.string.loading_apps)
        }

        when {
            current.errorRes != null -> showMessage(getString(current.errorRes), null)
            usesCustomPrivateDns() -> showMessage(getString(R.string.warning_private_dns)) {
                openSettings(Settings.ACTION_WIRELESS_SETTINGS)
            }
            else -> message.visibility = View.GONE
        }
    }

    private fun showMessage(text: String, onClick: (() -> Unit)?) {
        message.text = text
        message.visibility = View.VISIBLE
        message.setOnClickListener(onClick?.let { action -> View.OnClickListener { action() } })
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Con «DNS privado» apuntando a un servidor concreto, Android cifra el DNS por su cuenta
     * y lo envía fuera de la VPN: el filtro no vería nada.
     */
    private fun usesCustomPrivateDns(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getLinkProperties(network)?.privateDnsServerName != null
    }

    // ---------- Lista de apps ----------

    private fun loadApps() {
        try {
            background.execute {
                val pm = packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName }
                    .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it) }
                    .sortedBy { it.label.lowercase() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    loaded = true
                    adapter.setApps(apps)
                    refreshStatus()
                }
            }
        } catch (_: RejectedExecutionException) {
            // La pantalla ya se está cerrando.
        }
    }

    private fun toggleApp(pkg: String) {
        if (!selected.remove(pkg)) selected += pkg
        prefs.selectedApps = selected.toSet()
        adapter.notifyDataSetChanged()
        refreshStatus()
        scheduleRestart()
    }

    /** Si el filtro está encendido, lo actualiza con los cambios cuando dejas de tocar. */
    private fun scheduleRestart() {
        main.removeCallbacks(restart)
        if (!VpnState.status.running) return
        if (selected.isEmpty()) {
            DnsVpnService.stop(this)
            return
        }
        main.postDelayed(restart, RESTART_DELAY_MS)
    }

    // ---------- VPN ----------

    private fun requestStart() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_at_least_one, Toast.LENGTH_SHORT).show()
            return
        }
        // La primera vez Android pide permiso para crear la VPN.
        val permission = VpnService.prepare(this)
        if (permission != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(permission, REQUEST_VPN)
        } else {
            DnsVpnService.start(this)
        }
    }

    @Deprecated("Activity de la plataforma, sin AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) DnsVpnService.start(this)
    }

    private companion object {
        const val REQUEST_VPN = 1
        const val RESTART_DELAY_MS = 800L

        /** La cabecera grande se desvanece en la primera parte del recorrido y la compacta aparece al final. */
        const val FADE_OUT_END = 0.8f
        const val FADE_IN_START = 0.6f
    }
}
