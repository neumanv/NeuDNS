package io.github.neumanv.neudns.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import io.github.neumanv.neudns.R
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Una app instalada que se puede elegir. */
class AppEntry(val pkg: String, val label: String, val info: ApplicationInfo)

/**
 * Lista de apps con casilla. Las marcadas aparecen primero. Los iconos se cargan en segundo
 * plano al aparecer en pantalla y solo se guardan unos pocos en memoria.
 */
class AppListAdapter(
    private val activity: Activity,
    private val background: Executor,
    private val isSelected: (String) -> Boolean,
    private val onToggle: (String) -> Unit,
) : BaseAdapter() {

    private var all = emptyList<AppEntry>()
    private var visible = emptyList<AppEntry>()
    private var filter = ""

    private val icons = LruCache<String, Drawable>(ICON_CACHE_SIZE)
    private val loading = HashSet<String>()

    fun setApps(apps: List<AppEntry>) {
        all = apps
        refresh()
    }

    fun setFilter(text: String) {
        filter = text
        refresh()
    }

    /** Vuelve a ordenar: las marcadas primero y, dentro de cada grupo, por orden alfabético. */
    fun refresh() {
        visible = all
            .filter { filter.isEmpty() || it.label.contains(filter, true) || it.pkg.contains(filter, true) }
            .sortedByDescending { isSelected(it.pkg) }
        notifyDataSetChanged()
    }

    override fun getCount() = visible.size
    override fun getItem(position: Int) = visible[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: activity.layoutInflater.inflate(R.layout.item_app, parent, false)
        val holder = row.tag as? RowHolder ?: RowHolder(row).also { holder ->
            row.tag = holder
            // El toque lo recoge la propia fila: no depende de cómo gestione ListView los clics.
            holder.row.setOnClickListener { holder.pkg?.let(onToggle) }
        }
        val app = visible[position]
        val checked = isSelected(app.pkg)
        holder.pkg = app.pkg
        holder.label.text = app.label
        holder.pkgText.text = app.pkg
        holder.check.isChecked = checked
        holder.row.isActivated = checked
        holder.row.contentDescription = app.label
        val icon = icons[app.pkg]
        holder.icon.setImageDrawable(icon)
        if (icon == null) loadIcon(app, holder)
        return row
    }

    private fun loadIcon(app: AppEntry, holder: RowHolder) {
        if (!loading.add(app.pkg)) return
        try {
            background.execute {
                val icon = app.info.loadIcon(activity.packageManager)
                activity.runOnUiThread {
                    loading.remove(app.pkg)
                    icons.put(app.pkg, icon)
                    if (holder.pkg == app.pkg) holder.icon.setImageDrawable(icon)
                }
            }
        } catch (_: RejectedExecutionException) {
            loading.remove(app.pkg) // la pantalla se está cerrando
        }
    }

    private class RowHolder(row: View) {
        val row: View = row.findViewById(R.id.row)
        val icon: ImageView = row.findViewById(R.id.icon)
        val label: TextView = row.findViewById(R.id.label)
        val pkgText: TextView = row.findViewById(R.id.pkg)
        val check: CheckBox = row.findViewById(R.id.check)
        var pkg: String? = null
    }

    private companion object {
        const val ICON_CACHE_SIZE = 64
    }
}
