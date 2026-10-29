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
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.SpannableStringBuilder
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
import online.virivky.amap.data.Favorites
import online.virivky.amap.data.MapTheme
import online.virivky.amap.data.Place
import online.virivky.amap.data.Poi
import online.virivky.amap.data.PoiCategory
import online.virivky.amap.data.Recents
import online.virivky.amap.data.SavedPlaces
import online.virivky.amap.data.RouteOptions
import online.virivky.amap.data.SearchArea
import online.virivky.amap.data.SearchOptions
import online.virivky.amap.data.SearchSort
import online.virivky.amap.data.Services
import online.virivky.amap.data.Settings
import online.virivky.amap.data.TrafficEvents
import online.virivky.amap.data.TravelMode
import online.virivky.amap.geo.Geo
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.map.DriveHud
import online.virivky.amap.map.JunctionView
import online.virivky.amap.map.NavMap
import online.virivky.amap.map.PoiIcons
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.LaneIcons
import online.virivky.amap.nav.ManeuverIcons
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.net.Geocoder
import online.virivky.amap.net.MapLinks
import online.virivky.amap.net.Router
import online.virivky.amap.net.TomTom
import online.virivky.amap.util.Bg
import online.virivky.amap.util.CarLog
import online.virivky.amap.util.GeoUri
import org.maplibre.android.maps.MapView
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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
    private lateinit var junctionImage: ImageView
    private lateinit var mapControls: View
    private lateinit var zoomIn: View
    private lateinit var zoomOut: View
    private lateinit var myLocation: View
    private lateinit var viewMode: TextView
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
    private lateinit var travelModeButton: ImageButton
    private lateinit var stopChip: TextView
    private var styledCategory: PoiCategory? = null
    private var chipsStyled = false

    /** Počas navigácie: zobrazený rad so službami (tlačidlo v spodnej lište). */
    private var servicesOpen = false

    private lateinit var searchFilters: View
    private lateinit var routeChoices: LinearLayout
    private lateinit var areaChip: TextView
    private lateinit var sortChip: TextView
    private lateinit var openChip: TextView
    private var searchOptions = Settings.searchOptions
    private var searchRequest = 0

    /** Návrhy počas písania (len s kľúčom TomTom). */
    private val typeahead = Runnable {
        val text = searchInput.text.toString().trim()
        if (text.length < 3 || !searchInput.hasFocus()) return@Runnable
        val id = ++searchRequest
        Bg.run({ Geocoder.search(text, here(), searchOptions, typeahead = true) }) { result ->
            if (id != searchRequest || searchInput.text.toString().trim() != text) return@run
            result.getOrNull()?.places?.takeIf { it.isNotEmpty() }?.let { showPlaces(it) }
        }
    }

    private val navigationListener = NavigationController.Listener { render() }
    private val servicesListener = Services.Listener {
        // Obsadenosť nabíjačiek sa mení v tých istých položkách.
        poiAdapter.notifyDataSetChanged()
        render()
    }
    private val trafficListener = TrafficEvents.Listener {
        navMap?.update()
        hud.invalidate()
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
        junctionImage = findViewById(R.id.junction_image)
        junctionImage.clipToOutline = true
        mapControls = findViewById(R.id.map_controls)
        zoomIn = findViewById(R.id.zoom_in)
        zoomOut = findViewById(R.id.zoom_out)
        myLocation = findViewById(R.id.my_location)
        viewMode = findViewById(R.id.view_mode)
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
        searchFilters = findViewById(R.id.search_filters)
        routeChoices = findViewById(R.id.route_choices)
        hud.showAttribution = true
        buildCategories()
        buildFilters()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            navMap = NavMap(this, map, isDark, followSun = true, useCompass = true).also { controller ->
                controller.onFollowChanged = { render() }
                map.addOnMapLongClickListener { latLng ->
                    val point = GeoPoint(latLng.latitude, latLng.longitude)
                    choose(Place("Vybrané miesto", String.format(Locale.US, "%.5f, %.5f", point.lat, point.lon), point))
                    // Adresu doplníme, keď ju zistíme.
                    Bg.run({ Geocoder.reverse(point) }) { result ->
                        result.getOrNull()?.let { nav.relabel(point, it.name, it.address) }
                    }
                    true
                }
                map.addOnMapClickListener { latLng ->
                    hideKeyboard()
                    val screen = map.projection.toScreenLocation(latLng)
                    controller.savedAt(screen.x, screen.y)?.let {
                        placeActions(it)
                        return@addOnMapClickListener true
                    }
                    val poi = controller.poiAt(screen.x, screen.y)
                    // Ťuk na sivý variant trasy ho vyberie.
                    val alternative = if (poi == null && nav.phase == Phase.PREVIEW) controller.alternativeAt(screen.x, screen.y) else null
                    when {
                        poi != null -> choosePoi(poi)
                        alternative != null -> nav.selectRoute(alternative)
                    }
                    poi != null || alternative != null
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
            savePlaceDialog(adapter.getItem(position), fromRecents = searchInput.text.isBlank())
            true
        }
        findViewById<View>(R.id.save_place).setOnClickListener { nav.destination?.let { savePlaceDialog(it) } }
        findViewById<View>(R.id.route_prefs).setOnClickListener { showRouteOptions() }
        travelModeButton = findViewById(R.id.travel_mode)
        travelModeButton.setOnClickListener {
            nav.setTravelMode(if (nav.travelMode == TravelMode.FOOT) TravelMode.CAR else TravelMode.FOOT)
        }
        findViewById<View>(R.id.share_trip).setOnClickListener { shareTrip() }
        findViewById<View>(R.id.nav_info).setOnClickListener { shareTrip() }
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
            updateSearchUi()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) {
                Bg.main.removeCallbacks(typeahead)
                val query = text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) {
                    if (searchInput.hasFocus()) showPlaces(Recents.all())
                } else if (query.length >= 3 && searchInput.hasFocus()) {
                    // Návrhy počas písania (TomTom aj OpenStreetMap cez Photon).
                    Bg.main.postDelayed(typeahead, 450)
                }
            }

            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        findViewById<View>(R.id.search_button).setOnClickListener { search() }
        findViewById<View>(R.id.voice_button).setOnClickListener { voiceSearch() }
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }
        zoomIn.setOnClickListener { navMap?.zoomIn() }
        zoomOut.setOnClickListener { navMap?.zoomOut() }
        myLocation.setOnClickListener { navMap?.recenter() }
        viewMode.setOnClickListener {
            val next = Settings.mapPerspective.next()
            Settings.mapPerspective = next
            Toast.makeText(this, next.label, Toast.LENGTH_SHORT).show()
            render()
        }
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
        TrafficEvents.addListener(trafficListener)
        render()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        navMap?.flowEnabled = true
    }

    override fun onPause() {
        navMap?.flowEnabled = false
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        nav.removeListener(navigationListener)
        Services.removeListener(servicesListener)
        TrafficEvents.removeListener(trafficListener)
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
            results.visibility == View.VISIBLE || searchInput.hasFocus() -> {
                results.visibility = View.GONE
                searchInput.clearFocus()
                hideKeyboard()
                updateSearchUi()
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
        // Miesto zdieľané z Google Máp (alebo iných máp) cez „Zdieľať → lech-map“.
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            Toast.makeText(this, "Zisťujem miesto z odkazu…", Toast.LENGTH_SHORT).show()
            Bg.run({ MapLinks.resolve(text) }) { result ->
                val shared = result.getOrNull()
                val point = shared?.point
                when {
                    point != null -> choose(Place(shared?.name ?: "Miesto z odkazu", "Zdieľané z mapy", point))
                    !shared?.query.isNullOrBlank() -> {
                        searchInput.setText(shared?.query)
                        search()
                    }
                    else -> Toast.makeText(this, "Z odkazu sa nepodarilo zistiť polohu.", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
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

    private fun here(): GeoPoint? = LocationTracker.last?.let { GeoPoint(it) }

    /** Cieľ hlasom: rozpoznanie reči v telefóne (slovenčina). */
    private fun voiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sk-SK")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Kam idete?")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, VOICE_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "Rozpoznávanie reči nie je v telefóne dostupné", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VOICE_REQUEST || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        searchInput.setText(text)
        searchInput.setSelection(text.length)
        search()
    }

    private fun search() {
        val text = searchInput.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        Bg.main.removeCallbacks(typeahead)
        Toast.makeText(this, "Hľadám…", Toast.LENGTH_SHORT).show()
        val id = ++searchRequest
        val options = searchOptions
        Bg.run({ Geocoder.search(text, here(), options) }) { result ->
            if (id != searchRequest) return@run
            result.onSuccess { found ->
                if (found.places.isEmpty()) Toast.makeText(this, "Nič sa nenašlo", Toast.LENGTH_SHORT).show()
                found.note?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                showPlaces(found.places)
            }.onFailure {
                Toast.makeText(this, "Vyhľadávanie zlyhalo. Skontrolujte internet.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPlaces(places: List<Place>) {
        adapter.here = here()
        adapter.places = places
        results.visibility = if (places.isEmpty()) View.GONE else View.VISIBLE
        updateSearchUi()
    }

    private fun choose(place: Place, mode: TravelMode = TravelMode.CAR) {
        results.visibility = View.GONE
        searchInput.clearFocus()
        hideKeyboard()
        nav.preview(place, mode)
        updateSearchUi()
    }

    /** Počas hľadania filtre namiesto radu so službami. */
    private fun updateSearchUi() {
        val searching = searchBar.visibility == View.VISIBLE && (searchInput.hasFocus() || results.visibility == View.VISIBLE)
        searchFilters.visibility = if (searching) View.VISIBLE else View.GONE
        val phase = nav.phase
        categories.visibility =
            if ((phase == Phase.IDLE && !searching) || (phase == Phase.NAVIGATING && servicesOpen)) View.VISIBLE else View.GONE
    }

    /** Kritériá hľadania: oblasť, poradie, len otvorené. */
    private fun buildFilters() {
        val row = findViewById<LinearLayout>(R.id.filter_row)
        val d = resources.displayMetrics.density
        fun chip(onClick: () -> Unit) = TextView(this).apply {
            textSize = 13f
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            elevation = 2 * d
            setOnClickListener { onClick() }
            row.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (8 * d).toInt()
            })
        }
        areaChip = chip {
            val areas = SearchArea.entries
            AlertDialog.Builder(this)
                .setTitle("Kde hľadať")
                .setSingleChoiceItems(areas.map { it.label }.toTypedArray(), areas.indexOf(searchOptions.area)) { dialog, which ->
                    setSearchOptions(searchOptions.copy(area = areas[which]))
                    dialog.dismiss()
                }
                .show()
        }
        sortChip = chip {
            val sorts = SearchSort.entries
            AlertDialog.Builder(this)
                .setTitle("Zoradiť")
                .setSingleChoiceItems(sorts.map { it.label }.toTypedArray(), sorts.indexOf(searchOptions.sort)) { dialog, which ->
                    setSearchOptions(searchOptions.copy(sort = sorts[which]))
                    dialog.dismiss()
                }
                .show()
        }
        openChip = chip { setSearchOptions(searchOptions.copy(openNow = !searchOptions.openNow)) }
        renderFilters()
    }

    private fun setSearchOptions(options: SearchOptions) {
        searchOptions = options
        Settings.searchOptions = options
        renderFilters()
        if (searchInput.text.isNotBlank()) search()
    }

    private fun renderFilters() {
        val d = resources.displayMetrics.density
        fun style(chip: TextView, selected: Boolean) {
            chip.background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(if (selected) getColor(R.color.amap_blue) else getColor(R.color.panel))
            }
            chip.setTextColor(if (selected) Color.WHITE else getColor(R.color.panel_text))
        }
        areaChip.text = "${searchOptions.area.label} ▾"
        sortChip.text = "${searchOptions.sort.label} ▾"
        openChip.text = "Otvorené teraz"
        style(areaChip, false)
        style(sortChip, false)
        style(openChip, searchOptions.openNow)
    }

    /** Varianty trasy v náhľade: čas, dĺžka a hlavná cesta; ťuknutím sa vyberú. */
    private fun renderRouteChoices() {
        val routes = if (nav.phase == Phase.PREVIEW) nav.routes else emptyList()
        routeChoices.removeAllViews()
        if (routes.size < 2) {
            routeChoices.visibility = View.GONE
            return
        }
        routeChoices.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        val fastest = routes.minOf { it.duration }
        routes.forEachIndexed { i, r ->
            val selected = r === nav.route
            val time = Instructions.displayDuration(r.duration)
            val label = SpannableStringBuilder(time)
            label.setSpan(StyleSpan(Typeface.BOLD), 0, time.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            label.setSpan(AbsoluteSizeSpan(17, true), 0, time.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            label.append("\n").append(Instructions.displayDistance(r.distance))
            r.mainRoad.ifBlank { null }?.let { label.append(" · cez ").append(it) }
            if (r.duration == fastest) label.append("\nNajrýchlejšia")
            val card = TextView(this).apply {
                setText(label)
                textSize = 13f
                setTextColor(if (selected) Color.WHITE else getColor(R.color.panel_text))
                background = GradientDrawable().apply {
                    cornerRadius = 12 * d
                    setColor(if (selected) getColor(R.color.amap_blue) else 0x1A808080)
                }
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                setOnClickListener { nav.selectRoute(i) }
            }
            routeChoices.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = (8 * d).toInt()
            })
        }
    }

    /** Zdieľanie cieľa a času príchodu (SMS, WhatsApp, …) s odkazmi na mapu. */
    private fun shareTrip() {
        val destination = nav.destination ?: return
        val route = nav.route ?: return
        val p = nav.progress
        val navigating = nav.phase == Phase.NAVIGATING
        val seconds = if (navigating && p != null) p.remainingSeconds else route.duration
        val meters = if (navigating && p != null) p.remainingDistance else route.distance
        val arrival = LocalTime.now().plusSeconds(seconds.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        fun link(point: GeoPoint) =
            "https://www.google.com/maps/search/?api=1&query=" + String.format(Locale.US, "%.5f,%.5f", point.lat, point.lon)
        val text = buildString {
            append(if (navigating) "Som na ceste do " else "Idem do ").append(destination.name).append(". ")
            append("Príchod okolo ").append(arrival)
            append(" (").append(Instructions.displayDuration(seconds)).append(", ").append(Instructions.displayDistance(meters)).append(").")
            if (navigating) here()?.let { append("\nMoja poloha: ").append(link(it)) }
            append("\nCieľ: ").append(link(destination.point))
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Zdieľať príchod"))
    }

    /** Čomu sa má trasa vyhnúť; po zmene sa trasa prepočíta. */
    private fun showRouteOptions() {
        val current = Settings.routeOptions
        val checked = booleanArrayOf(current.avoidMotorways, current.avoidTolls, current.avoidFerries)
        AlertDialog.Builder(this)
            .setTitle("Možnosti trasy")
            .setMultiChoiceItems(
                arrayOf("Vyhnúť sa diaľniciam", "Vyhnúť sa spoplatneným úsekom (diaľničná známka)", "Vyhnúť sa trajektom"),
                checked,
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Použiť") { _, _ ->
                val options = RouteOptions(checked[0], checked[1], checked[2])
                if (options != current) {
                    Settings.routeOptions = options
                    nav.retry()
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    /**
     * Uloženie miesta: hore názov na prepísanie (označený, s klávesnicou), pod ním kam uložiť –
     * Moje miesta (pod týmto názvom), Domov, Práca; pri nedávnych aj odstránenie zo zoznamu.
     */
    private fun savePlaceDialog(place: Place, fromRecents: Boolean = false) {
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val input = EditText(this).apply {
            setSingleLine()
            setText(place.name)
            hint = "Názov miesta"
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Uložiť miesto")
            .setView(layout)
            .setNegativeButton("Zrušiť", null)
            .create()
        fun name() = input.text.toString().trim().ifEmpty { place.name }
        fun toMyPlaces() {
            val saved = SavedPlaces.add(name(), place.point)
            Toast.makeText(this, "Uložené do Mojich miest: ${saved.name}", Toast.LENGTH_SHORT).show()
        }
        val ripple = android.util.TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        fun option(label: String, action: () -> Unit) {
            layout.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(getColor(R.color.panel_text))
                setPadding(0, (14 * d).toInt(), 0, (14 * d).toInt())
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    action()
                    dialog.dismiss()
                }
            })
        }
        option("★  Uložiť do Mojich miest") { toMyPlaces() }
        for (kind in Favorites.Kind.entries) {
            option(if (kind == Favorites.Kind.HOME) "Uložiť ako Domov" else "Uložiť ako Prácu") {
                Favorites.set(kind, place)
                Toast.makeText(this, "Uložené: ${kind.label}", Toast.LENGTH_SHORT).show()
            }
        }
        if (fromRecents) {
            option("Odstrániť z nedávnych") {
                Recents.remove(place)
                adapter.places = Recents.all()
            }
        }
        // „Hotovo“ na klávesnici uloží do Mojich miest.
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            toMyPlaces()
            dialog.dismiss()
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
        input.selectAll()
    }

    /**
     * Čo vie apka o Android Auto: verzie, odkiaľ je lech-map nainštalovaná, či systém vidí jej službu
     * pre auto, a záznam pripojení a chýb z auta (na odfotenie a poslanie).
     */
    private fun showCarDiagnostics() {
        val pm = packageManager
        val info = buildList {
            add("lech-map ${pm.getPackageInfo(packageName, 0).versionName}")
            val installer = try {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(packageName).installingPackageName
                else @Suppress("DEPRECATION") pm.getInstallerPackageName(packageName)
            } catch (e: Exception) {
                null
            }
            add("Nainštalované cez: ${installer ?: "neznáme"}")
            val auto = try {
                pm.getPackageInfo("com.google.android.projection.gearhead", 0).versionName
            } catch (e: Exception) {
                null
            }
            add("Android Auto: ${auto ?: "nenájdené"}")
            val service = pm.queryIntentServices(
                Intent("androidx.car.app.CarAppService").setPackage(packageName),
                PackageManager.GET_RESOLVED_FILTER,
            ).firstOrNull()
            val categories = service?.filter?.categoriesIterator()?.asSequence()?.toList().orEmpty()
            add("Služba pre auto: ${if (service == null) "NENÁJDENÁ" else "OK " + categories.joinToString { it.substringAfterLast('.') }}")
            add("")
            val log = CarLog.lines()
            if (log.isEmpty()) add("Android Auto sa k lech-map zatiaľ nepripojilo.") else addAll(log)
        }.joinToString("\n")
        val text = TextView(this).apply {
            this.text = info
            textSize = 13f
            setTextIsSelectable(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("Android Auto – diagnostika")
            .setView(android.widget.ScrollView(this).apply { addView(text) })
            .setPositiveButton("Zdieľať") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, info)
                startActivity(Intent.createChooser(send, "Zdieľať diagnostiku"))
            }
            .setNeutralButton("Vymazať") { _, _ -> CarLog.clear() }
            .setNegativeButton("Zavrieť", null)
            .show()
    }

    /** Moje miesta: uloženie miesta, kde som (napr. v lese), a zoznam uložených od najbližšieho. */
    private fun showSavedPlaces() {
        val here = here()
        val places = SavedPlaces.all.sortedBy { if (here == null) 0.0 else Geo.distance(here, it.point) }
        val dates = DateTimeFormatter.ofPattern("d. M. yyyy")
        val labels = listOf("＋  Uložiť miesto, kde som") + places.map { place ->
            val distance = here?.let { " · " + Instructions.displayDistance(Geo.distance(it, place.point)) }.orEmpty()
            "★  ${place.name}$distance · ${Instant.ofEpochMilli(place.savedAt).atZone(ZoneId.systemDefault()).format(dates)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Moje miesta")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) saveMyLocation() else placeActions(places[which - 1])
            }
            .setNegativeButton("Zavrieť", null)
            .show()
    }

    /** Uloží aktuálnu polohu z GPS pod zvoleným názvom. */
    private fun saveMyLocation() {
        val location = LocationTracker.last
        if (location == null) {
            Toast.makeText(this, "Čakám na polohu GPS…", Toast.LENGTH_SHORT).show()
            return
        }
        val age = (System.currentTimeMillis() - location.time) / 1000
        val info = buildList {
            if (location.hasAccuracy()) add("Presnosť polohy ±${location.accuracy.toInt()} m")
            if (age > 120) add("Poloha je ${age / 60} min stará – počkaj na GPS pod holým nebom.")
        }.joinToString("\n")
        val initial = "Miesto " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d. M. HH:mm"))
        askName("Uložiť miesto, kde som", initial, info.ifEmpty { null }) { name ->
            val saved = SavedPlaces.add(name, GeoPoint(location))
            Toast.makeText(this, "Uložené: ${saved.name}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Pole na názov: navrhnutý názov je označený a klávesnica hneď otvorená, takže stačí písať
     * (prepíše ho), alebo len potvrdiť. Aj „Hotovo“ na klávesnici uloží.
     */
    private fun askName(title: String, initial: String, message: String? = null, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            setText(initial)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(padded(input))
            .setPositiveButton("Uložiť") { _, _ -> onSave(input.text.toString().trim().ifEmpty { initial }) }
            .setNegativeButton("Zrušiť", null)
            .create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            onSave(input.text.toString().trim().ifEmpty { initial })
            dialog.dismiss()
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
        input.selectAll()
    }

    /** Čo s uloženým miestom: navigovať pešo či autom, ukázať, premenovať, zdieľať, odstrániť. */
    private fun placeActions(saved: SavedPlaces.Saved) {
        val actions = arrayOf("Navigovať pešo", "Navigovať autom", "Ukázať na mape", "Premenovať", "Zdieľať", "Odstrániť")
        AlertDialog.Builder(this)
            .setTitle(saved.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> choose(saved.toPlace(), TravelMode.FOOT)
                    1 -> choose(saved.toPlace(), TravelMode.CAR)
                    2 -> navMap?.showPoint(saved.point)
                    3 -> askName("Premenovať", saved.name) { name -> SavedPlaces.rename(saved.id, name) }
                    4 -> {
                        val link = "https://www.google.com/maps/search/?api=1&query=" +
                            String.format(Locale.US, "%.6f,%.6f", saved.point.lat, saved.point.lon)
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${saved.name}\n$link")
                        startActivity(Intent.createChooser(send, "Zdieľať miesto"))
                    }
                    5 -> AlertDialog.Builder(this)
                        .setMessage("Odstrániť ${saved.name}?")
                        .setPositiveButton("Odstrániť") { _, _ -> SavedPlaces.remove(saved.id) }
                        .setNegativeButton("Zrušiť", null)
                        .show()
                }
            }
            .show()
    }

    /** Pole v dialógu s okrajmi. */
    private fun padded(view: View): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun openFavorite(kind: Favorites.Kind) {
        val place = Favorites.get(kind)
        if (place != null) {
            choose(place)
        } else {
            Toast.makeText(
                this,
                "${kind.label}: zatiaľ nie je uložené. Vyhľadaj miesto a v náhľade trasy ťukni na hviezdičku.",
                Toast.LENGTH_LONG,
            ).show()
        }
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
        stopChip = chip("Zastávky", getDrawable(R.drawable.ic_close)?.mutate()?.apply {
            setTint(Color.WHITE)
            setBounds(0, 0, (24 * d).toInt(), (24 * d).toInt())
        }) { manageStops() }
        stopChip.background = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(getColor(R.color.amap_red))
        }
        stopChip.setTextColor(Color.WHITE)
        for (kind in Favorites.Kind.entries) {
            val icon = getDrawable(if (kind == Favorites.Kind.HOME) R.drawable.ic_home else R.drawable.ic_work)?.mutate()
            icon?.setTint(getColor(R.color.amap_blue))
            chip(kind.label, icon) { openFavorite(kind) }.apply {
                background = GradientDrawable().apply {
                    cornerRadius = 20 * d
                    setColor(getColor(R.color.panel))
                }
                setTextColor(getColor(R.color.panel_text))
                setOnLongClickListener {
                    if (Favorites.get(kind) != null) {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Odstrániť uložené miesto ${kind.label}?")
                            .setPositiveButton("Odstrániť") { _, _ -> Favorites.clear(kind) }
                            .setNegativeButton("Zrušiť", null)
                            .show()
                    }
                    true
                }
            }
        }
        val star = getDrawable(R.drawable.ic_star)?.mutate()
        star?.setTint(0xFFF2A100.toInt())
        chip("Moje miesta", star) { showSavedPlaces() }.apply {
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(getColor(R.color.panel))
            }
            setTextColor(getColor(R.color.panel_text))
        }
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
        stopChip.visibility = if (nav.stops.isNotEmpty()) View.VISIBLE else View.GONE
        stopChip.text = if (nav.stops.size == 1) "Zrušiť zastávku" else "Zastávky (${nav.stops.size})"

        val category = Services.category
        val phase = nav.phase
        poiPanel.visibility =
            if (category != null && (phase == Phase.IDLE || phase == Phase.NAVIGATING)) View.VISIBLE else View.GONE
        if (category == null) {
            if (poiAdapter.pois.isNotEmpty()) poiAdapter.pois = emptyList()
            return
        }
        poiTitle.text = "${category.label} ${Services.areaLabel}"
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

    /** Zastávky na trase: jednu zrušíme hneď, pri viacerých výber. */
    private fun manageStops() {
        val stops = nav.stops
        if (stops.size <= 1) {
            nav.removeStop()
            return
        }
        val items = stops.map { "Zrušiť: ${it.name}" } + "Zrušiť všetky zastávky"
        AlertDialog.Builder(this)
            .setTitle("Zastávky")
            .setItems(items.toTypedArray()) { _, which -> nav.removeStop(stops.getOrNull(which)) }
            .setNegativeButton("Zavrieť", null)
            .show()
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
        // Parkovisko pri cieli sa stane novým cieľom.
        if (Services.atDestination) {
            nav.navigateTo(poi.toPlace())
            closeServices()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(poi.name)
            .setMessage(poi.detail())
            .setPositiveButton("Pridať zastávku") { _, _ ->
                if (nav.addStop(poi.toPlace())) {
                    closeServices()
                } else {
                    Toast.makeText(this, "Najviac ${NavigationController.MAX_STOPS} zastávky", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Navigovať sem") { _, _ ->
                nav.navigateTo(poi.toPlace())
                closeServices()
            }
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
        viewMode.visibility = if (navigating) View.VISIBLE else View.GONE
        viewMode.text = Settings.mapPerspective.short
        mapControls.visibility =
            if (zoomIn.visibility == View.GONE && myLocation.visibility == View.GONE && viewMode.visibility == View.GONE) View.GONE
            else View.VISIBLE
        primaryButton.isEnabled = true
        if (phase != Phase.NAVIGATING) servicesOpen = false
        updateSearchUi()
        renderServices()
        renderRouteChoices()

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
                    val foot = nav.travelMode == TravelMode.FOOT
                    panelSubtitle.text = listOfNotNull(
                        (if (foot) "Pešo · " else "") +
                            "${Instructions.displayDuration(route.duration)} · ${Instructions.displayDistance(route.distance)}$delay",
                        if (route.direct) "Vzdušnou čiarou – chodník sa nenašiel alebo nie je internet" else null,
                        if (route.live) "Podľa živej premávky (TomTom)" else null,
                        Settings.routeOptions.label.ifBlank { null }?.replaceFirstChar { it.uppercase() },
                        nav.destinationWeather?.let { "Počasie v cieli ${it.label}" },
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
        // Kým sa trasa počíta, tlačidlo je viditeľne neaktívne.
        primaryButton.alpha = if (primaryButton.isEnabled) 1f else 0.45f
        travelModeButton.setImageResource(if (nav.travelMode == TravelMode.FOOT) R.drawable.ic_walk else R.drawable.ic_car)
        navMap?.update()
        hud.invalidate()
        updateViewport()
    }

    /** Nastavenia: kľúč TomTom, farby mapy, auto na mape, hlasové upozornenia. */
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
        val themeLabel = TextView(this).apply {
            text = "Farby mapy"
            textSize = 15f
            setPadding(0, padding / 2, 0, 0)
        }
        val themes = android.widget.RadioGroup(this)
        MapTheme.entries.forEachIndexed { i, theme ->
            themes.addView(android.widget.RadioButton(this).apply {
                id = 1000 + i
                text = theme.label
                isChecked = Settings.mapTheme == theme
            })
        }
        val vehicleLabel = TextView(this).apply {
            text = "Auto na mape"
            textSize = 15f
            setPadding(0, padding / 2, 0, 0)
        }
        val vehicle = VehiclePicker(this)
        val warningsLabel = TextView(this).apply {
            text = "Hlasové upozornenia"
            textSize = 15f
            setPadding(0, padding / 2, 0, 0)
        }
        val warnCameras = CheckBox(this).apply {
            text = "Radary a úsekové merania"
            isChecked = Settings.warnCameras
        }
        val warnIncidents = CheckBox(this).apply {
            text = "Nehody, uzávierky a zápchy"
            isChecked = Settings.warnIncidents
        }
        val warnSpeed = CheckBox(this).apply {
            text = "Prekročenie rýchlosti"
            isChecked = Settings.warnSpeed
        }
        val toleranceLabel = TextView(this).apply {
            text = "Tolerancia rýchlosti"
            textSize = 15f
            setPadding(0, padding / 2, 0, 0)
        }
        val toleranceValues = intArrayOf(-1, 0, 5, 10)
        val tolerance = android.widget.RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        toleranceValues.forEachIndexed { i, value ->
            tolerance.addView(android.widget.RadioButton(this).apply {
                id = 2000 + i
                text = if (value < 0) "auto" else "+$value"
                isChecked = Settings.speedTolerance == value
            })
        }
        val carDiagnostics = TextView(this).apply {
            text = "Android Auto – diagnostika"
            textSize = 15f
            setTextColor(getColor(R.color.amap_blue))
            setPadding(0, padding / 2, 0, padding / 4)
            setOnClickListener { showCarDiagnostics() }
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
            addView(themeLabel)
            addView(themes)
            addView(vehicleLabel)
            addView(vehicle.view)
            addView(warningsLabel)
            addView(warnCameras)
            addView(warnIncidents)
            addView(warnSpeed)
            addView(toleranceLabel)
            addView(tolerance)
            addView(carDiagnostics)
            addView(status)
        }
        AlertDialog.Builder(this)
            .setTitle("Nastavenia")
            .setView(android.widget.ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Uložiť") { _, _ ->
                val key = keyInput.text.toString().trim()
                MapTheme.entries.getOrNull(themes.checkedRadioButtonId - 1000)?.let { Settings.mapTheme = it }
                Settings.vehicleModel = vehicle.model
                Settings.vehicleColor = vehicle.color
                Settings.warnCameras = warnCameras.isChecked
                Settings.warnIncidents = warnIncidents.isChecked
                Settings.warnSpeed = warnSpeed.isChecked
                toleranceValues.getOrNull(tolerance.checkedRadioButtonId - 2000)?.let { Settings.speedTolerance = it }
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
        junctionImage.visibility = View.GONE
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
        if (route == null || p == null) {
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
        // Pri prepočte pokyn starej trasy neplatí (napr. „otočte sa“ za autom) – len stav.
        if (nav.rerouting) {
            maneuverIcon.setImageResource(R.drawable.ic_navigation)
            maneuverDistance.text = ""
            maneuverRoad.text = "Prepočítavam trasu…"
            maneuverText.text = ""
            maneuverText.visibility = View.GONE
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
        // Pokyn ostáva, stav len v doplnkovom riadku.
        val note = when {
            p.traveled < 50 && p.offRouteDistance > 40 -> "Prejdite na vyznačenú trasu"
            else -> null
        }
        if (note != null) {
            maneuverText.text = note
            maneuverText.visibility = View.VISIBLE
        }
        // Zväčšený obraz križovatky pred zjazdom, rozdvojením a kruhovým objazdom.
        if (JunctionView.wanted(step, p.distanceToNextStep)) {
            val w = (instructionCard.width - instructionCard.paddingLeft - instructionCard.paddingRight).takeIf { it > 0 } ?: 1000
            junctionImage.setImageBitmap(JunctionView.bitmap(step, w, (w * 0.56f).toInt(), navMap?.isDark ?: isDark))
            junctionImage.visibility = View.VISIBLE
        }
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
        // Krátko ako AMap („1h24min 147 km“) a pri dlhom texte menšie písmo, aby sa jednotka neodsekla.
        val minutes = maxOf(1, (p.remainingSeconds / 60).roundToInt())
        val time = if (minutes < 60) "$minutes min" else "${minutes / 60}h${minutes % 60}min"
        val summary = "$time  ${Instructions.displayDistance(p.remainingDistance)}"
        navSummary.textSize = when {
            summary.length > 15 -> 19f
            summary.length > 13 -> 21f
            else -> 23f
        }
        navSummary.text = summary
        val lights = nav.trafficLightsAhead
        navLightsIcon.visibility = if (lights > 0) View.VISIBLE else View.GONE
        navLights.visibility = navLightsIcon.visibility
        navLights.text = lights.toString()
        val arrival = LocalTime.now().plusSeconds(p.remainingSeconds.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        navArrival.text = "$arrival Príchod"
    }

    private companion object {
        const val VOICE_REQUEST = 7
    }
}
