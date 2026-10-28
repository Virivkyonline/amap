package online.virivky.amap.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import online.virivky.amap.R
import online.virivky.amap.data.Place
import online.virivky.amap.data.Poi
import online.virivky.amap.data.PoiCategory
import online.virivky.amap.data.Recents
import online.virivky.amap.data.Services
import online.virivky.amap.data.Settings
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.map.DriveHud
import online.virivky.amap.map.NavMap
import online.virivky.amap.map.PoiIcons
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.LaneIcons
import online.virivky.amap.nav.ManeuverIcons
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.net.Geocoder
import online.virivky.amap.net.Router
import online.virivky.amap.net.TomTom
import online.virivky.amap.util.Bg
import online.virivky.amap.util.GeoUri
import org.maplibre.android.maps.MapView
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private val nav = NavigationController
    private lateinit var mapView: MapView
    private var navMap: NavMap? = null
    private lateinit var hud: DriveHud
    private lateinit var overlay: View
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var results: ListView
    private lateinit var instructionCard: View
    private lateinit var maneuverIcon: ImageView
    private lateinit var maneuverDistance: TextView
    private lateinit var maneuverRoad: TextView
    private lateinit var maneuverBadge: TextView
    private lateinit var maneuverText: TextView
    private lateinit var lanesImage: ImageView
    private lateinit var mapControls: View
    private lateinit var zoomIn: View
    private lateinit var zoomOut: View
    private lateinit var myLocation: View
    private lateinit var bottomPanel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelSubtitle: TextView
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var navBar: View
    private lateinit var navSummary: TextView
    private lateinit var navLightsIcon: View
    private lateinit var navLights: TextView
    private lateinit var navArrival: TextView
    private lateinit var navVoice: ImageButton
    private lateinit var categories: View
    private lateinit var poiPanel: View
    private lateinit var poiTitle: TextView
    private lateinit var poiStatus: TextView
    private lateinit var poiList: ListView
    private val adapter = PlaceAdapter()
    private val poiAdapter = PoiAdapter()
    private val chips = LinkedHashMap<PoiCategory, TextView>()
    private lateinit var stopChip: TextView
    private var styledCategory: PoiCategory? = null
    private var chipsStyled = false

    /** Počas navigácie: zobrazený rad so službami (tlačidlo v spodnej lište). */
    private var servicesOpen = false

    private val navigationListener = NavigationController.Listener { render() }
    private val servicesListener = Services.Listener {
        // Obsadenosť nabíjačiek sa mení v tých istých položkách.
        poiAdapter.notifyDataSetChanged()
        render()
    }
    private val locationListener: (Location) -> Unit = {
        navMap?.update()
        hud.invalidate()
    }

    private val isDark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.map)
        hud = findViewById(R.id.hud)
        overlay = findViewById(R.id.overlay)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        results = findViewById(R.id.results)
        instructionCard = findViewById(R.id.instruction_card)
        maneuverIcon = findViewById(R.id.maneuver_icon)
        maneuverDistance = findViewById(R.id.maneuver_distance)
        maneuverRoad = findViewById(R.id.maneuver_road)
        maneuverBadge = findViewById(R.id.maneuver_badge)
        maneuverText = findViewById(R.id.maneuver_text)
        lanesImage = findViewById(R.id.lanes_image)
        mapControls = findViewById(R.id.map_controls)
        zoomIn = findViewById(R.id.zoom_in)
        zoomOut = findViewById(R.id.zoom_out)
        myLocation = findViewById(R.id.my_location)
        bottomPanel = findViewById(R.id.bottom_panel)
        panelTitle = findViewById(R.id.panel_title)
        panelSubtitle = findViewById(R.id.panel_subtitle)
        primaryButton = findViewById(R.id.primary_button)
        secondaryButton = findViewById(R.id.secondary_button)
        navBar = findViewById(R.id.nav_bar)
        navSummary = findViewById(R.id.nav_summary)
        navLightsIcon = findViewById(R.id.nav_lights_icon)
        navLights = findViewById(R.id.nav_lights)
        navArrival = findViewById(R.id.nav_arrival)
        navVoice = findViewById(R.id.nav_voice)
        categories = findViewById(R.id.categories)
        poiPanel = findViewById(R.id.poi_panel)
        poiTitle = findViewById(R.id.poi_title)
        poiStatus = findViewById(R.id.poi_status)
        poiList = findViewById(R.id.poi_list)
        hud.showAttribution = true
        buildCategories()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            navMap = NavMap(this, map, isDark).also { controller ->
                controller.onFollowChanged = { render() }
                map.addOnMapLongClickListener { latLng ->
                    choose(
                        Place(
                            "Vybrané miesto",
                            String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude),
                            GeoPoint(latLng.latitude, latLng.longitude),
                        )
                    )
                    true
                }
                map.addOnMapClickListener { latLng ->
                    hideKeyboard()
                    val screen = map.projection.toScreenLocation(latLng)
                    val poi = controller.poiAt(screen.x, screen.y)
                    if (poi != null) choosePoi(poi)
                    poi != null
                }
            }
            updateViewport()
        }

        results.adapter = adapter
        poiList.adapter = poiAdapter
        poiList.setOnItemClickListener { _, _, position, _ -> choosePoi(poiAdapter.getItem(position)) }
        findViewById<View>(R.id.poi_close).setOnClickListener { closeServices() }
        findViewById<View>(R.id.nav_services).setOnClickListener {
            if (servicesOpen) closeServices() else servicesOpen = true
            render()
        }
        results.setOnItemClickListener { _, _, position, _ -> choose(adapter.getItem(position)) }
        results.setOnItemLongClickListener { _, _, position, _ ->
            val place = adapter.getItem(position)
            if (searchInput.text.isBlank()) {
                Recents.remove(place)
                adapter.places = Recents.all()
                Toast.makeText(this, "Odstránené z nedávnych", Toast.LENGTH_SHORT).show()
            }
            true
        }
        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                search()
                true
            } else {
                false
            }
        }
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchInput.text.isBlank()) showPlaces(Recents.all())
        }
        findViewById<View>(R.id.search_button).setOnClickListener { search() }
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }
        zoomIn.setOnClickListener { navMap?.zoomIn() }
        zoomOut.setOnClickListener { navMap?.zoomOut() }
        myLocation.setOnClickListener { navMap?.recenter() }
        findViewById<View>(R.id.nav_close).setOnClickListener { nav.stop() }
        navVoice.setOnClickListener {
            nav.voiceEnabled = !nav.voiceEnabled
            render()
        }

        // Mapa sa centruje do voľnej plochy medzi hornými a dolnými panelmi.
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateViewport() }

        requestPermissionsIfNeeded()
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        LocationTracker.acquire(this)
        LocationTracker.addListener(locationListener)
        nav.addListener(navigationListener)
        Services.addListener(servicesListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        nav.removeListener(navigationListener)
        Services.removeListener(servicesListener)
        LocationTracker.removeListener(locationListener)
        LocationTracker.release(this)
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        navMap?.dispose()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            results.visibility == View.VISIBLE -> {
                results.visibility = View.GONE
                searchInput.clearFocus()
                hideKeyboard()
            }
            nav.phase == Phase.ROUTING || nav.phase == Phase.PREVIEW -> nav.cancelPreview()
            Services.category != null || servicesOpen -> closeServices()
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationTracker.refresh()
        navMap?.recenter()
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf<String>()
        if (!LocationTracker.hasPermission(this)) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
            wanted += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val target = GeoUri.parse(uri) ?: return
        val point = target.point
        if (point != null) {
            choose(Place(target.label ?: "Cieľ", "", point))
        } else if (target.query != null) {
            searchInput.setText(target.query)
            search()
        }
    }

    private fun search() {
        val text = searchInput.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        showPlaces(emptyList())
        Toast.makeText(this, "Hľadám…", Toast.LENGTH_SHORT).show()
        val near = LocationTracker.last?.let { GeoPoint(it) }
        Bg.run({ Geocoder.search(text, near) }) { result ->
            result.onSuccess { found ->
                if (found.isEmpty()) Toast.makeText(this, "Nič sa nenašlo", Toast.LENGTH_SHORT).show()
                showPlaces(found)
            }.onFailure {
                Toast.makeText(this, "Vyhľadávanie zlyhalo. Skontrolujte internet.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPlaces(places: List<Place>) {
        adapter.here = LocationTracker.last?.let { GeoPoint(it) }
        adapter.places = places
        results.visibility = if (places.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun choose(place: Place) {
        results.visibility = View.GONE
        searchInput.clearFocus()
        hideKeyboard()
        nav.preview(place)
    }

    /** Rad tlačidiel so službami pod vyhľadávaním (ako v AMap). */
    private fun buildCategories() {
        val row = findViewById<LinearLayout>(R.id.category_row)
        val d = resources.displayMetrics.density
        fun chip(label: String, icon: android.graphics.drawable.Drawable?, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = (6 * d).toInt()
            setPadding((6 * d).toInt(), (5 * d).toInt(), (14 * d).toInt(), (5 * d).toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            elevation = 3 * d
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * d).toInt()
            })
        }
        stopChip = chip("Zrušiť zastávku", getDrawable(R.drawable.ic_close)?.mutate()?.apply {
            setTint(Color.WHITE)
            setBounds(0, 0, (24 * d).toInt(), (24 * d).toInt())
        }) { nav.removeStop() }
        stopChip.background = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(getColor(R.color.amap_red))
        }
        stopChip.setTextColor(Color.WHITE)
        PoiCategory.entries.forEach { category ->
            chips[category] = chip(category.label, BitmapDrawable(resources, PoiIcons.badge(category, (26 * d).toInt()))) {
                if (Services.category == category) Services.clear() else Services.show(category, searchCenter())
            }
        }
    }

    /** Stred hľadania služieb: posunutá mapa, inak poloha (null). */
    private fun searchCenter(): GeoPoint? {
        val m = navMap ?: return null
        if (m.follow || nav.phase == Phase.NAVIGATING) return null
        val target = m.map.cameraPosition.target ?: return null
        return GeoPoint(target.latitude, target.longitude)
    }

    private fun closeServices() {
        servicesOpen = false
        Services.clear()
        render()
    }

    private fun renderServices() {
        val d = resources.displayMetrics.density
        if (!chipsStyled || styledCategory != Services.category) {
            chips.forEach { (category, chip) ->
                val selected = Services.category == category
                chip.background = GradientDrawable().apply {
                    cornerRadius = 20 * d
                    setColor(if (selected) category.color else getColor(R.color.panel))
                }
                chip.setTextColor(if (selected) Color.WHITE else getColor(R.color.panel_text))
            }
            styledCategory = Services.category
            chipsStyled = true
        }
        stopChip.visibility = if (nav.via != null) View.VISIBLE else View.GONE

        val category = Services.category
        val phase = nav.phase
        poiPanel.visibility =
            if (category != null && (phase == Phase.IDLE || phase == Phase.NAVIGATING)) View.VISIBLE else View.GONE
        if (category == null) {
            if (poiAdapter.pois.isNotEmpty()) poiAdapter.pois = emptyList()
            return
        }
        poiTitle.text = category.label + if (Services.alongRoute) " pozdĺž trasy" else " v okolí"
        val found = Services.results
        poiStatus.text = when {
            Services.loading -> "Hľadám…"
            Services.error != null -> Services.error
            category == PoiCategory.EV && Settings.tomtomKey == null ->
                "${placesCount(found.size)} · obsadenosť nabíjačiek ukáže kľúč TomTom"
            else -> placesCount(found.size)
        }
        poiAdapter.alongRoute = Services.alongRoute
        if (poiAdapter.pois !== found) poiAdapter.pois = found
        // Zoznam najviac na 3,5 riadka, aby bolo vidieť aj mapu.
        val height = (minOf(found.size.toFloat(), 3.5f) * 62 * d).toInt()
        if (poiList.layoutParams.height != height) {
            poiList.layoutParams.height = height
            poiList.requestLayout()
        }
    }

    private fun placesCount(n: Int) = when (n) {
        1 -> "1 miesto"
        in 2..4 -> "$n miesta"
        else -> "$n miest"
    }

    /** Vybraná služba: počas navigácie zastávka, inak náhľad trasy k nej. */
    private fun choosePoi(poi: Poi) {
        if (nav.phase != Phase.NAVIGATING) {
            nav.preview(poi.toPlace())
            return
        }
        AlertDialog.Builder(this)
            .setTitle(poi.name)
            .setMessage(poi.detail())
            .setPositiveButton("Pridať zastávku") { _, _ ->
                nav.addStop(poi.toPlace())
                closeServices()
            }
            .setNeutralButton("Navigovať sem") { _, _ -> nav.preview(poi.toPlace()) }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun updateViewport() {
        val top = listOf(searchBar, instructionCard, categories)
            .filter { it.visibility == View.VISIBLE }.maxOfOrNull { it.bottom } ?: overlay.paddingTop
        val bottom = listOf(poiPanel, bottomPanel, navBar)
            .filter { it.visibility == View.VISIBLE }.minOfOrNull { it.top } ?: (overlay.height - overlay.paddingBottom)
        val area = Rect(0, top, mapView.width, bottom)
        if (area.height() <= 0) return
        if (area != hud.viewport) {
            hud.viewport.set(area)
            hud.invalidate()
        }
        navMap?.setViewport(area, mapView.width, mapView.height)
    }

    private fun render() {
        val phase = nav.phase
        val navigating = phase == Phase.NAVIGATING || phase == Phase.ARRIVED
        searchBar.visibility = if (navigating) View.GONE else View.VISIBLE
        instructionCard.visibility = if (navigating) View.VISIBLE else View.GONE
        navBar.visibility = if (navigating) View.VISIBLE else View.GONE
        bottomPanel.visibility = if (phase == Phase.ROUTING || phase == Phase.PREVIEW) View.VISIBLE else View.GONE
        val following = navMap?.follow ?: true
        zoomIn.visibility = if (navigating) View.GONE else View.VISIBLE
        zoomOut.visibility = if (navigating) View.GONE else View.VISIBLE
        myLocation.visibility = if (navigating && following) View.GONE else View.VISIBLE
        mapControls.visibility = if (zoomIn.visibility == View.GONE && myLocation.visibility == View.GONE) View.GONE else View.VISIBLE
        primaryButton.isEnabled = true
        if (phase != Phase.NAVIGATING) servicesOpen = false
        categories.visibility =
            if (phase == Phase.IDLE || (phase == Phase.NAVIGATING && servicesOpen)) View.VISIBLE else View.GONE
        renderServices()

        when (phase) {
            Phase.IDLE -> Unit
            Phase.ROUTING -> {
                panelTitle.text = nav.destination?.name
                panelSubtitle.text = "Hľadám trasu…"
                primaryButton.text = "Navigovať"
                primaryButton.isEnabled = false
                secondaryButton.text = "Zrušiť"
                secondaryButton.setOnClickListener { nav.cancelPreview() }
            }
            Phase.PREVIEW -> {
                val route = nav.route
                panelTitle.text = nav.destination?.name
                secondaryButton.text = "Zrušiť"
                secondaryButton.setOnClickListener { nav.cancelPreview() }
                if (route != null) {
                    val delay = if (route.live && route.trafficDelay >= 60) {
                        " · zdržanie ${Instructions.displayDuration(route.trafficDelay)}"
                    } else {
                        ""
                    }
                    panelSubtitle.text = listOfNotNull(
                        "${Instructions.displayDuration(route.duration)} · ${Instructions.displayDistance(route.distance)}$delay",
                        if (route.live) "Podľa živej premávky (TomTom)" else null,
                        nav.destination?.address?.ifBlank { null },
                    ).joinToString("\n")
                    primaryButton.text = "Navigovať"
                    primaryButton.setOnClickListener { nav.start() }
                } else {
                    panelSubtitle.text = nav.error ?: "Trasa nie je dostupná"
                    primaryButton.text = "Skúsiť znova"
                    primaryButton.setOnClickListener { nav.retry() }
                }
            }
            Phase.NAVIGATING, Phase.ARRIVED -> {
                renderInstruction()
                renderNavBar()
            }
        }
        navMap?.update()
        hud.invalidate()
        updateViewport()
    }

    /** Nastavenia: kľúč TomTom pre živú premávku. */
    private fun showSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val info = TextView(this).apply {
            text = "Živá premávka (TomTom)\n\n" +
                "1. Zaregistrujte sa zdarma na developer.tomtom.com\n" +
                "2. V časti „Keys“ skopírujte API kľúč a vložte ho sem.\n" +
                "3. Pre nabíjačky s obsadenosťou zapnite pri kľúči aj Search API " +
                "a EV Charging Stations Availability API.\n\n" +
                "Bez kľúča navigácia funguje ďalej, len bez premávky."
            textSize = 15f
        }
        val keyInput = EditText(this).apply {
            hint = "API kľúč TomTom"
            setSingleLine()
            setText(Settings.tomtomKey.orEmpty())
        }
        val showTraffic = CheckBox(this).apply {
            text = "Zobrazovať premávku na mape"
            isChecked = Settings.showTraffic
        }
        val status = TextView(this).apply {
            text = Router.lastTomTomError.orEmpty()
            setTextColor(getColor(R.color.amap_red))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(info)
            addView(keyInput)
            addView(showTraffic)
            addView(status)
        }
        AlertDialog.Builder(this)
            .setTitle("Nastavenia")
            .setView(layout)
            .setPositiveButton("Uložiť") { _, _ ->
                val key = keyInput.text.toString().trim()
                Settings.showTraffic = showTraffic.isChecked
                Settings.tomtomKey = key.ifEmpty { null }
                if (key.isNotEmpty()) {
                    Toast.makeText(this, "Overujem kľúč…", Toast.LENGTH_SHORT).show()
                    Bg.run({ TomTom.check(key) }) { result ->
                        val error = result.getOrNull()
                        Toast.makeText(
                            this,
                            error ?: "Kľúč TomTom funguje – živá premávka je zapnutá.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .setNeutralButton("Otvoriť TomTom") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.tomtom.com/")))
                } catch (e: Exception) {
                    Toast.makeText(this, "Nepodarilo sa otvoriť prehliadač", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    /** Veľké číslo vzdialenosti s menšou sivou jednotkou (ako „113 m“). */
    private fun distanceText(meters: Double): CharSequence {
        val text = Instructions.displayDistance(meters)
        val split = text.lastIndexOf(' ')
        if (split < 0) return text
        val result = SpannableString(text)
        val unitStart = split + 1
        result.setSpan(AbsoluteSizeSpan(30, true), unitStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        result.setSpan(ForegroundColorSpan(getColor(R.color.nav_text_secondary)), unitStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        result.setSpan(AbsoluteSizeSpan(14, true), split, unitStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return result
    }

    private fun renderInstruction() {
        val route = nav.route
        val p = nav.progress
        lanesImage.visibility = View.GONE
        maneuverBadge.visibility = View.GONE
        maneuverText.visibility = View.VISIBLE
        maneuverIcon.background = null
        if (nav.phase == Phase.ARRIVED) {
            maneuverIcon.setImageResource(R.drawable.ic_place)
            maneuverDistance.text = ""
            maneuverRoad.text = "Ste v cieli"
            maneuverText.text = nav.destination?.name
            return
        }
        if (route == null || p == null || nav.rerouting) {
            maneuverIcon.setImageDrawable(null)
            maneuverDistance.text = ""
            maneuverRoad.text = if (nav.rerouting) "Prepočítavam trasu…" else "Čakám na GPS…"
            maneuverText.text = ""
            return
        }
        // Tunel pred nami (bližšie ako ďalší manéver) – ako v AMap.
        val tunnel = nav.tunnelAhead
        val toTunnel = nav.tunnelDistance
        if (tunnel != null && toTunnel != null) {
            maneuverIcon.setImageResource(R.drawable.ic_tunnel)
            maneuverDistance.text = distanceText(toTunnel)
            maneuverRoad.text = when {
                tunnel.name.contains("tunel", ignoreCase = true) -> tunnel.name
                tunnel.name.isNotBlank() -> "Tunel ${tunnel.name}"
                else -> "Tunel"
            }
            maneuverText.text = if (nav.estimating) "Bez GPS – poloha sa odhaduje" else ""
            maneuverText.visibility = if (nav.estimating) View.VISIBLE else View.GONE
            return
        }
        val step = route.steps[p.nextStepIndex]
        maneuverIcon.setImageBitmap(ManeuverIcons.bitmap(step))
        maneuverDistance.text = distanceText(p.distanceToNextStep)
        if (Instructions.isHighway(step)) {
            // Diaľnica: zelená ikona a štítok ako na zelených tabuliach.
            maneuverIcon.setBackgroundResource(R.drawable.maneuver_tile_bg)
            Instructions.exitLabel(step)?.let {
                maneuverBadge.text = it
                maneuverBadge.visibility = View.VISIBLE
            }
        }
        val road = Instructions.signpost(step) ?: Instructions.road(step)
        maneuverRoad.text = road ?: Instructions.title(step)
        maneuverText.text = if (road != null) Instructions.title(step) else ""
        maneuverText.visibility = if (maneuverText.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (LaneIcons.useful(step.lanes) && p.distanceToNextStep < 1000) {
            lanesImage.setImageBitmap(LaneIcons.bitmap(step.lanes, step.modifier))
            lanesImage.visibility = View.VISIBLE
        }
    }

    private fun renderNavBar() {
        val p = nav.progress
        navVoice.setImageResource(if (nav.voiceEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        if (nav.phase == Phase.ARRIVED || p == null) {
            navSummary.text = if (nav.phase == Phase.ARRIVED) "Ste v cieli" else "Navigácia"
            navLightsIcon.visibility = View.GONE
            navLights.visibility = View.GONE
            navArrival.text = nav.destination?.name
            return
        }
        navSummary.text = "${Instructions.displayDuration(p.remainingSeconds)}  ${Instructions.displayDistance(p.remainingDistance)}"
        val lights = nav.trafficLightsAhead
        navLightsIcon.visibility = if (lights > 0) View.VISIBLE else View.GONE
        navLights.visibility = navLightsIcon.visibility
        navLights.text = lights.toString()
        val arrival = LocalTime.now().plusSeconds(p.remainingSeconds.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        navArrival.text = "$arrival Príchod"
    }
}
