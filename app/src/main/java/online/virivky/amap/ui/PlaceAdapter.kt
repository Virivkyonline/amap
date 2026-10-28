package online.virivky.amap.ui

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import online.virivky.amap.R
import online.virivky.amap.data.Place
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.nav.Instructions

class PlaceAdapter : BaseAdapter() {
    var places: List<Place> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var here: GeoPoint? = null

    override fun getCount() = places.size

    override fun getItem(position: Int) = places[position]

    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_place, parent, false)
        val place = places[position]
        view.findViewById<TextView>(R.id.place_name).text = place.name.ifBlank { "Bez názvu" }
        // Vzdialenosť · druh · otváracie hodiny (zelenou/červenou) · adresa.
        val detail = SpannableStringBuilder()
        fun add(text: String, color: Int? = null) {
            if (text.isBlank()) return
            if (detail.isNotEmpty()) detail.append(" · ")
            val start = detail.length
            detail.append(text)
            if (color != null) detail.setSpan(ForegroundColorSpan(color), start, detail.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        here?.let { add(Instructions.displayDistance(Geo.distance(it, place.point))) }
        add(place.kind)
        add(place.hours, place.open?.let { if (it) OPEN else CLOSED })
        add(place.address)
        view.findViewById<TextView>(R.id.place_detail).text = detail
        return view
    }

    private companion object {
        const val OPEN = 0xFF1E9E4A.toInt()
        const val CLOSED = 0xFFE5333A.toInt()
    }
}
