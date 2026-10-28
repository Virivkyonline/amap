package online.virivky.amap.ui

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
        val distance = here?.let { Instructions.displayDistance(Geo.distance(it, place.point)) }
        view.findViewById<TextView>(R.id.place_detail).text =
            listOfNotNull(distance, place.address.ifBlank { null }).joinToString(" · ")
        return view
    }
}
