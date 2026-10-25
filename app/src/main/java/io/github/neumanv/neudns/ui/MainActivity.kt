package io.github.neumanv.neudns.ui

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.neumanv.neudns.Prefs
import io.github.neumanv.neudns.R
import io.github.neumanv.neudns.availableProviders
import io.github.neumanv.neudns.dns.BlockStats
import io.github.neumanv.neudns.dns.Domains
import io.github.neumanv.neudns.vpn.DnsVpnService
import io.github.neumanv.neudns.vpn.VpnState
import java.text.NumberFormat
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
    private lateinit var unblockableNote: TextView
    private lateinit var exceptionsRow: TextView
    private lateinit var blockedRow: TextView
    private lateinit var diagnosticsRow: TextView
    private lateinit var customServerRow: TextView
    private lateinit var pauseRow: TextView
    private lateinit var stats: TextView
    private lateinit var providerSpinner: Spinner
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

    // Mientras se ve la pantalla, lo que cambia con el tiempo (pausa, recuento, diagnóstico) se refresca cada segundo.
    private val tick = object : Runnable {
        override fun run() {
            refreshLive()
            main.postDelayed(this, TICK_MS)
        }
    }

    private var diagnosticsDialog: AlertDialog? = null
    private var diagnosticsAdapter: ArrayAdapter<String>? = null
    private var diagnosticsItems: List<BlockStats.Recent> = emptyList()

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
        main.postDelayed(tick, TICK_MS)
        // Al volver de Ajustes el servicio comprueba de nuevo si se ha activado «Bloquear conexiones sin VPN».
        if (VpnState.status.running) DnsVpnService.start(this)
    }

    override fun onPause() {
        super.onPause()
        VpnState.removeListener(statusListener)
        main.removeCallbacks(tick)
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
        unblockableNote = findViewById(R.id.unblockable_note)
        stats = findViewById(R.id.stats)
        exceptionsRow = findViewById(R.id.exceptions)
        exceptionsRow.setOnClickListener { showDomains(DomainList.ALLOWED) }
        blockedRow = findViewById(R.id.blocked)
        blockedRow.setOnClickListener { showDomains(DomainList.BLOCKED) }
        diagnosticsRow = findViewById(R.id.diagnostics)
        diagnosticsRow.setOnClickListener { openDiagnostics() }
        customServerRow = findViewById(R.id.custom_server)
        customServerRow.setOnClickListener { showCustomServer() }
        pauseRow = findViewById(R.id.pause)
        pauseRow.setOnClickListener { if (VpnState.status.paused) DnsVpnService.resume(this) else showPauseOptions() }

        toggle = findViewById(R.id.toggle)
        toggle.setOnClickListener { toggleFilter() }
        compactToggle = findViewById(R.id.compact_toggle)
        compactToggle.setOnClickListener { toggleFilter() }
        compactDot = findViewById(R.id.compact_dot)
        compactStatus = findViewById(R.id.compact_status)

        providerSpinner = findViewById<Spinner>(R.id.provider).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                R.layout.item_provider,
                availableProviders.map { getString(it.nameRes) } + getString(R.string.provider_custom),
            )
            setSelection(prefs.providerIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == prefs.providerIndex) return
                    // La primera vez hay que escribir la dirección del servidor personalizado.
                    if (position == Prefs.CUSTOM_INDEX && prefs.customUrl == null) {
                        showCustomServer()
                        return
                    }
                    prefs.providerIndex = position
                    onProviderChanged()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.setFilter(s.toString().trim())
        })

        refreshDomainRows()
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
        toggle.setText(if (current.running) R.string.action_stop else R.string.action_start)
        toggle.setBackgroundResource(if (current.running) R.drawable.bg_button_stop else R.drawable.bg_button_start)
        toggle.setTextColor(getColor(if (current.running) R.color.accent else R.color.on_accent))
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
            current.running && current.lockdown -> showMessage(getString(R.string.warning_lockdown)) {
                openSettings(Settings.ACTION_VPN_SETTINGS)
            }
            usesCustomPrivateDns() -> showMessage(getString(R.string.warning_private_dns)) {
                openSettings(Settings.ACTION_WIRELESS_SETTINGS)
            }
            else -> message.visibility = View.GONE
        }
        refreshLive()
    }

    /** Lo que cambia con el paso del tiempo: estado de la pausa, recuento y diagnóstico. */
    private fun refreshLive() {
        val current = VpnState.status
        val count = selected.size
        val paused = current.paused
        val pauseMinutes = minutesLeft(current.pausedUntil - SystemClock.elapsedRealtime())
        val providerName = getString(prefs.provider.nameRes).substringBefore(" (")
        status.text = when {
            paused -> resources.getQuantityString(R.plurals.status_paused, pauseMinutes, pauseMinutes)
            current.running -> resources.getQuantityString(R.plurals.status_on, count, count, providerName)
            else -> getString(R.string.status_off)
        }
        compactStatus.text = when {
            paused -> getString(R.string.status_paused_short)
            current.running -> resources.getQuantityString(R.plurals.status_on_short, count, count)
            else -> getString(R.string.status_off)
        }
        val dot = getColor(
            when {
                paused -> R.color.warn
                current.running -> R.color.accent
                else -> R.color.off
            },
        )
        statusDot.background.setTint(dot)
        compactDot.background.setTint(dot)

        pauseRow.visibility = if (current.running && prefs.provider.unfilteredUrl != null) View.VISIBLE else View.GONE
        pauseRow.setText(if (paused) R.string.resume_action else R.string.pause_action)

        val queries = VpnState.stats.queryCount
        val blocked = VpnState.stats.blockedCount
        stats.visibility = if (current.running) View.VISIBLE else View.GONE
        stats.text = if (queries == 0L) {
            getString(R.string.stats_none)
        } else {
            val number = NumberFormat.getIntegerInstance()
            getString(R.string.stats, number.format(queries), number.format(blocked), (blocked * 100 / queries).toInt())
        }

        val recording = VpnState.stats.isRecording
        val recordingMinutes = minutesLeft(VpnState.stats.recordingRemainingMs)
        diagnosticsRow.text = when {
            recording -> resources.getQuantityString(R.plurals.diagnostics_on, recordingMinutes, recordingMinutes)
            VpnState.stats.recent().isNotEmpty() -> getString(R.string.diagnostics_done)
            else -> getString(R.string.diagnostics_off)
        }
        if (diagnosticsDialog?.isShowing == true) fillDiagnostics()
    }

    /** Minutos que faltan, redondeando hacia arriba: «queda 1 minuto» hasta el último segundo. */
    private fun minutesLeft(ms: Long): Int = ((ms.coerceAtLeast(0) + 59_999) / 60_000).toInt()

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
                val (installed, system) = pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName }
                    // Las apps del sistema (Ajustes y otras integradas) comparten el UID de Android: filtrarlas
                    // afectaría a servicios del sistema, así que no se ofrecen.
                    .partition { Process.isApplicationUid(it.uid) }
                val (unblockable, offered) = installed.partition { it.packageName in UNBLOCKABLE_APPS }
                val apps = offered
                    .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it, sharesUid(it.packageName)) }
                    .sortedBy { it.label.lowercase() }
                val unblockableNames = unblockable.map { it.loadLabel(pm).toString() }.sortedBy { it.lowercase() }
                val hidden = (system + unblockable).map { it.packageName }.toSet()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    loaded = true
                    // Las que se eligieron antes de ocultarlas dejan de filtrarse.
                    if (selected.removeAll(hidden)) {
                        prefs.selectedApps = selected.toSet()
                        scheduleRestart()
                    }
                    showUnblockableNote(unblockableNames)
                    adapter.setApps(apps)
                    refreshStatus()
                }
            }
        } catch (_: RejectedExecutionException) {
            // La pantalla ya se está cerrando.
        }
    }

    /** Explica por qué no aparecen las apps instaladas cuyos anuncios no se pueden bloquear. */
    private fun showUnblockableNote(names: List<String>) {
        if (names.isEmpty()) {
            unblockableNote.visibility = View.GONE
            return
        }
        val list = if (names.size == 1) {
            names[0]
        } else {
            names.dropLast(1).joinToString(", ") + getString(R.string.list_last_separator) + names.last()
        }
        unblockableNote.text = getString(R.string.unblockable_note, list)
        unblockableNote.visibility = View.VISIBLE
    }

    /** Si comparte UID con otras apps, Android les aplicará también el filtro. */
    private fun sharesUid(pkg: String): Boolean = try {
        @Suppress("DEPRECATION") // sigue siendo la forma de saberlo sin permisos adicionales
        packageManager.getPackageInfo(pkg, 0).sharedUserId != null
    } catch (_: PackageManager.NameNotFoundException) {
        false
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

    // ---------- Servidor ----------

    private fun onProviderChanged() {
        refreshStatus()
        refreshDomainRows()
        scheduleRestart()
    }

    private fun showPauseOptions() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pause_title)
            .setItems(R.array.pause_options) { _, which ->
                DnsVpnService.pause(this, PAUSE_MINUTES[which] * 60_000L)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Pide la dirección del servidor personalizado. Si se cancela, el selector vuelve a lo que había. */
    private fun showCustomServer() {
        val input = singleLineInput(R.string.custom_hint, prefs.customUrl.orEmpty())
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.custom_title)
            .setMessage(R.string.custom_message)
            .setView(padded(input))
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { providerSpinner.setSelection(prefs.providerIndex) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = Domains.dohUrl(input.text.toString())
                if (url == null) {
                    input.error = getString(R.string.custom_invalid)
                    return@setOnClickListener
                }
                // Primero la dirección y después la selección, que se guarda por dirección.
                prefs.customUrl = url
                prefs.providerIndex = Prefs.CUSTOM_INDEX
                dialog.dismiss()
                onProviderChanged()
            }
        }
        showWithKeyboard(dialog, input)
    }

    // ---------- Dominios permitidos y bloqueados ----------

    /** Las dos listas de dominios del usuario: comparten los diálogos y cambian los textos. */
    private enum class DomainList(
        val title: Int,
        val empty: Int,
        val addTitle: Int,
        val addMessage: Int,
        val removeConfirm: Int,
        val none: Int,
        val count: Int,
    ) {
        ALLOWED(
            R.string.exceptions_title, R.string.exceptions_empty, R.string.exceptions_add_title,
            R.string.exceptions_add_message, R.string.exceptions_remove_confirm,
            R.string.exceptions_none, R.plurals.exceptions_count,
        ),
        BLOCKED(
            R.string.blocked_title, R.string.blocked_empty, R.string.blocked_add_title,
            R.string.blocked_add_message, R.string.blocked_remove_confirm,
            R.string.blocked_none, R.plurals.blocked_count,
        ),
    }

    private fun domains(list: DomainList) = if (list == DomainList.ALLOWED) prefs.exceptions else prefs.blocked

    /** Permitir dominios necesita el servidor sin bloqueo del mismo proveedor. */
    private fun canAllowDomains() = prefs.provider.unfilteredUrl != null

    private fun refreshDomainRows() {
        for ((row, list) in listOf(exceptionsRow to DomainList.ALLOWED, blockedRow to DomainList.BLOCKED)) {
            val count = domains(list).size
            row.text = if (count == 0) getString(list.none) else resources.getQuantityString(list.count, count, count)
        }
        val canAllow = canAllowDomains()
        exceptionsRow.isEnabled = canAllow
        exceptionsRow.setTextColor(getColor(if (canAllow) R.color.accent else R.color.muted))
        if (!canAllow) exceptionsRow.setText(R.string.exceptions_unavailable)

        val customUrl = prefs.customUrl
        customServerRow.visibility = if (prefs.isCustomProvider && customUrl != null) View.VISIBLE else View.GONE
        if (customUrl != null) customServerRow.text = getString(R.string.custom_server_row, Uri.parse(customUrl).host)
    }

    /** Lista de dominios: al tocar uno se ofrece quitarlo. */
    private fun showDomains(list: DomainList) {
        val domains = domains(list).sorted()
        val builder = AlertDialog.Builder(this)
            .setTitle(list.title)
            .setPositiveButton(R.string.exceptions_add) { _, _ -> showAddDomain(list) }
            .setNegativeButton(R.string.close, null)
        if (domains.isEmpty()) {
            builder.setMessage(list.empty)
        } else {
            builder.setItems(domains.toTypedArray()) { _, which -> confirmRemoveDomain(list, domains[which]) }
        }
        builder.show()
    }

    private fun confirmRemoveDomain(list: DomainList, domain: String, onDone: () -> Unit = { showDomains(list) }) {
        AlertDialog.Builder(this)
            .setMessage(getString(list.removeConfirm, domain))
            .setPositiveButton(R.string.exceptions_remove) { _, _ ->
                updateDomains(list, domains(list) - domain)
                onDone()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone() }
            .show()
    }

    private fun showAddDomain(list: DomainList, initial: String = "", onDone: () -> Unit = { showDomains(list) }) {
        val input = singleLineInput(R.string.exceptions_hint, initial)
        val dialog = AlertDialog.Builder(this)
            .setTitle(list.addTitle)
            .setMessage(list.addMessage)
            .setView(padded(input))
            .setPositiveButton(R.string.exceptions_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // El botón se gestiona aquí para que el diálogo no se cierre si el dominio no es válido.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val domain = Domains.normalize(input.text.toString())
                if (domain == null) {
                    input.error = getString(R.string.exceptions_invalid)
                    return@setOnClickListener
                }
                updateDomains(list, domains(list) + domain)
                dialog.dismiss()
                onDone()
            }
        }
        showWithKeyboard(dialog, input)
    }

    /** Guarda la lista. Un dominio no puede estar permitido y bloqueado a la vez: se quita de la otra. */
    private fun updateDomains(list: DomainList, domains: Set<String>) {
        if (list == DomainList.ALLOWED) {
            prefs.exceptions = domains
            prefs.blocked = prefs.blocked - domains
        } else {
            prefs.blocked = domains
            prefs.exceptions = prefs.exceptions - domains
        }
        refreshDomainRows()
        // El servicio aplica el cambio al momento, sin rehacer la VPN.
        if (VpnState.status.running) DnsVpnService.start(this)
    }

    // ---------- Diagnóstico ----------

    private fun openDiagnostics() {
        val stats = VpnState.stats
        when {
            stats.isRecording || stats.recent().isNotEmpty() -> showDiagnostics()
            !VpnState.status.running -> Toast.makeText(this, R.string.diagnostics_need_running, Toast.LENGTH_SHORT).show()
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_intro_title)
                .setMessage(R.string.diagnostics_intro)
                .setPositiveButton(R.string.diagnostics_start) { _, _ ->
                    stats.startRecording(DIAGNOSTICS_MS)
                    refreshLive()
                    showDiagnostics()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Últimos dominios bloqueados; la lista se actualiza sola mientras está abierta. */
    private fun showDiagnostics() {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            // Sin dominios, la única fila es una explicación que no se puede tocar.
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = diagnosticsItems.isNotEmpty()
        }
        diagnosticsAdapter = adapter
        fillDiagnostics(force = true)
        diagnosticsDialog = AlertDialog.Builder(this)
            .setTitle(diagnosticsTitle())
            .setAdapter(adapter) { _, which -> diagnosticsItems.getOrNull(which)?.let(::onDiagnosticClicked) }
            .setPositiveButton(R.string.close, null)
            .setNegativeButton(R.string.diagnostics_stop) { _, _ ->
                VpnState.stats.stopRecording()
                refreshLive()
            }
            // Al tocar un dominio puede abrirse ya otro diálogo de diagnóstico antes de que este se cierre.
            .setOnDismissListener { dismissed -> if (diagnosticsDialog === dismissed) diagnosticsDialog = null }
            .show()
    }

    private fun fillDiagnostics(force: Boolean = false) {
        val adapter = diagnosticsAdapter ?: return
        val items = VpnState.stats.recent()
        diagnosticsDialog?.setTitle(diagnosticsTitle())
        if (!force && items == diagnosticsItems) return
        diagnosticsItems = items
        adapter.setNotifyOnChange(false)
        adapter.clear()
        if (items.isEmpty()) {
            adapter.add(getString(if (VpnState.stats.isRecording) R.string.diagnostics_empty else R.string.diagnostics_empty_done))
        } else {
            adapter.addAll(
                items.map { item ->
                    val name = if (item.count > 1) getString(R.string.diagnostics_item_repeated, item.domain, item.count) else item.domain
                    if (item.byUser) getString(R.string.diagnostics_item_user, name) else name
                },
            )
        }
        adapter.notifyDataSetChanged()
    }

    private fun diagnosticsTitle(): String {
        if (!VpnState.stats.isRecording) return getString(R.string.diagnostics_title_done)
        val minutes = minutesLeft(VpnState.stats.recordingRemainingMs)
        return resources.getQuantityString(R.plurals.diagnostics_title, minutes, minutes)
    }

    /** Lo que ha bloqueado el usuario se ofrece quitarlo; lo que ha bloqueado el servidor, permitirlo. */
    private fun onDiagnosticClicked(item: BlockStats.Recent) {
        if (item.byUser) {
            val entry = Domains.match(item.domain, prefs.blocked) ?: return showDiagnostics()
            confirmRemoveDomain(DomainList.BLOCKED, entry, onDone = ::showDiagnostics)
        } else if (canAllowDomains()) {
            showAddDomain(DomainList.ALLOWED, item.domain, onDone = ::showDiagnostics)
        } else {
            Toast.makeText(this, R.string.exceptions_unavailable_toast, Toast.LENGTH_SHORT).show()
            showDiagnostics()
        }
    }

    // ---------- Diálogos ----------

    private fun singleLineInput(hintRes: Int, initial: String) = EditText(this).apply {
        hint = getString(hintRes)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
        setText(initial)
        setSelection(initial.length)
    }

    private fun padded(view: View): View {
        val padding = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(view)
        }
    }

    private fun showWithKeyboard(dialog: AlertDialog, input: EditText) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
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
        const val TICK_MS = 1000L
        const val DIAGNOSTICS_MS = 10 * 60 * 1000L

        /** Duraciones de R.array.pause_options, en minutos. */
        val PAUSE_MINUTES = longArrayOf(5, 15, 60)

        /** La cabecera grande se desvanece en la primera parte del recorrido y la compacta aparece al final. */
        const val FADE_OUT_END = 0.8f
        const val FADE_IN_START = 0.6f
    }
}
