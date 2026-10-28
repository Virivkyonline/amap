package online.virivky.amap.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import online.virivky.amap.R
import online.virivky.amap.data.Place
import online.virivky.amap.data.Recents
import online.virivky.amap.geo.GeoPoint
import online.virivky.amap.location.LocationTracker
import online.virivky.amap.map.TileCache
import online.virivky.amap.nav.Instructions
import online.virivky.amap.nav.LaneIcons
import online.virivky.amap.nav.ManeuverIcons
import online.virivky.amap.nav.NavigationController
import online.virivky.amap.nav.NavigationController.Phase
import online.virivky.amap.net.Geocoder
import online.virivky.amap.util.Bg
import online.virivky.amap.util.GeoUri
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private val nav = NavigationController
    private lateinit var map: MapView
    private lateinit var searchBar: View
    private lateinit var searchInput: EditText
    private lateinit var results: ListView
    private lateinit var instructionCard: View
    private lateinit var maneuverIcon: ImageView
    private lateinit var maneuverDistance: TextView
    private lateinit var maneuverText: TextView
    private lateinit var maneuverRoad: TextView
    private lateinit var lanesImage: ImageView
    private lateinit var bottomPanel: View
    private lateinit var panelTitle: TextView
    private lateinit var panelSubtitle: TextView
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private val adapter = PlaceAdapter()

    private val navigationListener = NavigationController.Listener { render() }
    private val tileListener: () -> Unit = { map.invalidate() }
    private val locationListener: (Location) -> Unit = { map.invalidate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        results = findViewById(R.id.results)
        instructionCard = findViewById(R.id.instruction_card)
        maneuverIcon = findViewById(R.id.maneuver_icon)
        maneuverDistance = findViewById(R.id.maneuver_distance)
        maneuverText = findViewById(R.id.maneuver_text)
        maneuverRoad = findViewById(R.id.maneuver_road)
        lanesImage = findViewById(R.id.lanes_image)
        bottomPanel = findViewById(R.id.bottom_panel)
        panelTitle = findViewById(R.id.panel_title)
        panelSubtitle = findViewById(R.id.panel_subtitle)
        primaryButton = findViewById(R.id.primary_button)
        secondaryButton = findViewById(R.id.secondary_button)

        results.adapter = adapter
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
        findViewById<View>(R.id.zoom_in).setOnClickListener {
            map.renderer.zoomBy(1.0)
            map.invalidate()
        }
        findViewById<View>(R.id.zoom_out).setOnClickListener {
            map.renderer.zoomBy(-1.0)
            map.invalidate()
        }
        findViewById<View>(R.id.my_location).setOnClickListener {
            map.follower.recenter()
            map.invalidate()
        }
        map.onLongPress = { point ->
            choose(Place("Vybrané miesto", String.format(Locale.US, "%.5f, %.5f", point.lat, point.lon), point))
        }
        map.onUserMoved = { hideKeyboard() }

        // Mapa sa centruje do voľnej plochy medzi hornými a dolnými panelmi.
        findViewById<View>(R.id.overlay).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateViewport() }

        requestPermissionsIfNeeded()
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        LocationTracker.acquire(this)
        LocationTracker.addListener(locationListener)
        TileCache.addListener(tileListener)
        nav.addListener(navigationListener)
        render()
    }

    override fun onStop() {
        nav.removeListener(navigationListener)
        TileCache.removeListener(tileListener)
        LocationTracker.removeListener(locationListener)
        LocationTracker.release(this)
        super.onStop()
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
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationTracker.refresh()
        map.follower.recenter()
        map.invalidate()
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

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun updateViewport() {
        val top = if (instructionCard.visibility == View.VISIBLE) instructionCard.bottom else searchBar.bottom
        val bottom = if (bottomPanel.visibility == View.VISIBLE) bottomPanel.top else map.height
        val viewport = Rect(0, top, map.width, bottom)
        if (viewport != map.renderer.viewport && viewport.height() > 0) {
            map.renderer.viewport.set(viewport)
            map.invalidate()
        }
    }

    private fun render() {
        val phase = nav.phase
        val navigating = phase == Phase.NAVIGATING || phase == Phase.ARRIVED
        searchBar.visibility = if (navigating) View.GONE else View.VISIBLE
        instructionCard.visibility = if (navigating) View.VISIBLE else View.GONE
        bottomPanel.visibility = if (phase == Phase.IDLE) View.GONE else View.VISIBLE
        secondaryButton.visibility = View.VISIBLE
        primaryButton.isEnabled = true

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
                    panelSubtitle.text = listOfNotNull(
                        "${Instructions.displayDuration(route.duration)} · ${Instructions.displayDistance(route.distance)}",
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
                val p = nav.progress
                panelTitle.text = if (phase == Phase.ARRIVED) {
                    "Ste v cieli"
                } else if (p != null) {
                    "Príchod " + LocalTime.now().plusSeconds(p.remainingSeconds.toLong())
                        .format(DateTimeFormatter.ofPattern("H:mm"))
                } else {
                    "Navigácia"
                }
                panelSubtitle.text = if (p != null && phase == Phase.NAVIGATING) {
                    "${Instructions.displayDuration(p.remainingSeconds)} · ${Instructions.displayDistance(p.remainingDistance)}"
                } else {
                    nav.destination?.name
                }
                primaryButton.text = "Ukončiť"
                primaryButton.setOnClickListener { nav.stop() }
                secondaryButton.text = if (nav.voiceEnabled) "Zvuk: zap." else "Zvuk: vyp."
                secondaryButton.setOnClickListener {
                    nav.voiceEnabled = !nav.voiceEnabled
                    render()
                }
            }
        }
        map.invalidate()
        updateViewport()
    }

    private fun renderInstruction() {
        val route = nav.route
        val p = nav.progress
        lanesImage.visibility = View.GONE
        if (nav.phase == Phase.ARRIVED) {
            maneuverIcon.setImageResource(R.drawable.ic_place)
            maneuverDistance.text = "Cieľ"
            maneuverText.text = "Ste v cieli"
            maneuverRoad.text = nav.destination?.name
            return
        }
        if (route == null || p == null || nav.rerouting) {
            maneuverIcon.setImageDrawable(null)
            maneuverDistance.text = ""
            maneuverText.text = if (nav.rerouting) "Prepočítavam trasu…" else "Čakám na GPS…"
            maneuverRoad.text = ""
            return
        }
        val step = route.steps[p.nextStepIndex]
        maneuverIcon.setImageBitmap(ManeuverIcons.bitmap(step))
        maneuverDistance.text = Instructions.displayDistance(p.distanceToNextStep)
        maneuverText.text = Instructions.title(step)
        maneuverRoad.text = Instructions.road(step).orEmpty()
        if (LaneIcons.useful(step.lanes) && p.distanceToNextStep < 1000) {
            lanesImage.setImageBitmap(LaneIcons.bitmap(step.lanes, step.modifier))
            lanesImage.visibility = View.VISIBLE
        }
    }
}
