package online.virivky.amap.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import online.virivky.amap.data.Settings
import online.virivky.amap.data.VehicleColor
import online.virivky.amap.data.VehicleModel
import online.virivky.amap.map.MapIcons

/** Výber auta na mape v nastaveniach: tvary vozidiel (náhľad vo zvolenej farbe) a farby. */
class VehiclePicker(private val context: Context) {
    var model = Settings.vehicleModel
        private set
    var color = Settings.vehicleColor
        private set

    private val density = context.resources.displayMetrics.density
    private val models = LinearLayout(context)
    private val colors = LinearLayout(context)
    private val name = TextView(context).apply {
        textSize = 13f
        setPadding(0, dp(4), 0, 0)
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(models)
        })
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(colors)
        })
        addView(name)
    }

    init {
        VehicleModel.entries.forEach { m ->
            models.addView(
                ImageView(context).apply {
                    contentDescription = m.label
                    setOnClickListener {
                        model = m
                        refresh()
                    }
                },
                LinearLayout.LayoutParams(dp(60), dp(60)).apply { setMargins(0, dp(6), dp(6), 0) },
            )
        }
        VehicleColor.entries.forEach { c ->
            colors.addView(
                View(context).apply {
                    contentDescription = c.label
                    setOnClickListener {
                        color = c
                        refresh()
                    }
                },
                LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(dp(2), dp(10), dp(10), 0) },
            )
        }
        refresh()
    }

    private fun refresh() {
        // Mimo apply: vnútri by „color“ znamenalo farbu GradientDrawable.
        val chosenModel = model
        val chosenColor = color
        VehicleModel.entries.forEachIndexed { i, m ->
            val tile = models.getChildAt(i) as ImageView
            tile.setImageBitmap(MapIcons.vehiclePreview(context, m, chosenColor))
            // Tmavé pozadie ako cesta v noci, aby bolo vidieť aj biele auto.
            tile.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF1E2633.toInt())
                if (m == chosenModel) setStroke(dp(3), SELECTED)
            }
        }
        VehicleColor.entries.forEachIndexed { i, c ->
            colors.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c.argb)
                if (c == chosenColor) setStroke(dp(3), SELECTED) else setStroke(dp(1), 0xFF9AA0A6.toInt())
            }
        }
        name.text = "${model.label} · ${color.label}"
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val SELECTED = 0xFF3F80FF.toInt()
    }
}
