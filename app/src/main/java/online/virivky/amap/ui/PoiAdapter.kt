package online.virivky.amap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import online.virivky.amap.R
import online.virivky.amap.data.Poi
import online.virivky.amap.map.PoiIcons
import online.virivky.amap.nav.Instructions

/** Zoznam služieb (nabíjačky, pumpy, …) s ikonou, vzdialenosťou a podrobnosťami. */
class PoiAdapter : BaseAdapter() {
    var pois: List<Poi> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Vzdialenosti sú merané po trase. */
    var alongRoute = false

    override fun getCount() = pois.size

    override fun getItem(position: Int) = pois[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_poi, parent, false)
        val poi = pois[position]
        val size = (36 * parent.resources.displayMetrics.density).toInt()
        view.findViewById<ImageView>(R.id.poi_icon).setImageBitmap(PoiIcons.badge(poi.category, size))
        view.findViewById<TextView>(R.id.poi_name).text = poi.name
        val distance = Instructions.displayDistance(poi.distance) + if (alongRoute) " po trase" else ""
        view.findViewById<TextView>(R.id.poi_detail).text = (listOf(distance) + poi.info()).joinToString(" · ")
        return view
    }
}
