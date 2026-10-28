package online.virivky.amap.map

/**
 * Vlastný štýl vektorovej mapy (dáta OpenStreetMap cez OpenFreeMap).
 * Nočné farby sú inšpirované tmavomodrým vzhľadom navigácie AMap.
 */
object MapStyle {
    private class Palette(
        val background: String,
        /** Farba podkladu pri veľkom priblížení (počas jazdy). */
        val backgroundNear: String,
        val residential: String,
        /** Zastavané územie pri jazde (v noci tyrkysové ako v AMap). */
        val residentialNear: String,
        val park: String,
        val wood: String,
        val water: String,
        val building: String,
        val path: String,
        val minor: String,
        val major: String,
        val motorway: String,
        /** Asfalt a okraje ciest pri veľkom priblížení. */
        val asphaltNear: String?,
        val edgeNear: String?,
        val casing: String?,
        val motorwayCasing: String?,
        val centerLine: String,
        val building3d: String,
        val rail: String,
        val boundary: String,
        val roadLabel: String,
        val placeLabel: String,
        val halo: String,
        val route: String,
        val routeCasing: String,
    )

    private val NIGHT = Palette(
        background = "#1b2232",
        backgroundNear = "#1c2233",
        residential = "#1e2637",
        residentialNear = "#283f42",
        park = "#1f3a3b",
        wood = "#1c3536",
        water = "#1d4a6e",
        building = "#262e42",
        path = "#2c3446",
        minor = "#3a4357",
        major = "#4a5469",
        motorway = "#5c6782",
        asphaltNear = "#212839",
        edgeNear = "#8e98b0",
        casing = null,
        motorwayCasing = null,
        centerLine = "#d8b93c",
        building3d = "#20262f",
        rail = "#333b4e",
        boundary = "#59627a",
        roadLabel = "#aeb6c7",
        placeLabel = "#dfe3ea",
        halo = "#1b2232",
        route = "#22d39a",
        routeCasing = "#10a06c",
    )

    private val DAY = Palette(
        background = "#f2f3f5",
        backgroundNear = "#eef0f2",
        residential = "#eceef1",
        residentialNear = "#e7e9ed",
        park = "#cdeac9",
        wood = "#c1e2bd",
        water = "#a5cbf5",
        building = "#e1e3e8",
        path = "#d9dce2",
        minor = "#ffffff",
        major = "#ffffff",
        motorway = "#ffd77d",
        asphaltNear = null,
        edgeNear = null,
        casing = "#d0d4db",
        motorwayCasing = "#e3ad46",
        centerLine = "#e5b93a",
        building3d = "#dcdfe5",
        rail = "#c7cbd2",
        boundary = "#9aa0aa",
        roadLabel = "#4b5059",
        placeLabel = "#262a31",
        halo = "#ffffff",
        route = "#1fbf8a",
        routeCasing = "#0e8f62",
    )

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val NAME = """["coalesce",["get","name:sk"],["get","name:latin"],["get","name"]]"""
    private const val NOT_TUNNEL = """["!=",["get","brunnel"],"tunnel"]"""

    private fun width(vararg stops: Pair<Number, Number>) =
        """["interpolate",["exponential",1.5],["zoom"],${stops.joinToString(",") { "${it.first},${it.second}" }}]"""

    /** Šírka okraja cesty: šírka cesty + 1 až 3 px podľa priblíženia. */
    private fun casing(vararg stops: Pair<Number, Number>) =
        width(*stops.map { (z, w) -> z to w.toDouble() + (1.0 + (z.toDouble() - 14.0) * 2.0 / 3.0).coerceIn(1.0, 3.0) }.toTypedArray())

    private fun linear(vararg stops: Pair<Number, Number>) =
        """["interpolate",["linear"],["zoom"],${stops.joinToString(",") { "${it.first},${it.second}" }}]"""

    /** Farba ako text JSON: buď "#rrggbb", alebo výraz meniaci farbu podľa priblíženia. */
    private fun color(far: String, near: String?) =
        if (near == null) "\"$far\"" else """["interpolate",["linear"],["zoom"],14,"$far",16.2,"$near"]"""

    private fun road(id: String, classes: String, color: String, w: String, minzoom: Int) =
        """{"id":"$id","type":"line","source":"omt","source-layer":"transportation","minzoom":$minzoom,
           "filter":["all",["match",["get","class"],[$classes],true,false],$NOT_TUNNEL],
           "layout":{"line-cap":"round","line-join":"round"},
           "paint":{"line-color":$color,"line-width":$w}}"""

    /** Farba trasy podľa premávky na úseku (vlastnosť „level“ z TomTom). */
    private fun trafficColor(free: String, slow: String, jam: String, stopped: String) =
        """["match",["coalesce",["get","level"],0],1,"$slow",2,"$jam",3,"$stopped","$free"]"""

    /**
     * [trafficKey] = kľúč TomTom; ak je zadaný, na cesty sa prekreslí živá premávka.
     */
    fun json(dark: Boolean, trafficKey: String? = null): String {
        val c = if (dark) NIGHT else DAY
        val motorwayStops = arrayOf<Pair<Number, Number>>(5 to 0.6, 10 to 2.2, 14 to 8, 18 to 32)
        val majorStops = arrayOf<Pair<Number, Number>>(8 to 0.5, 12 to 2.2, 15 to 9, 18 to 28)
        val secondaryStops = arrayOf<Pair<Number, Number>>(10 to 0.5, 13 to 2.4, 15 to 7, 18 to 24)
        val minorStops = arrayOf<Pair<Number, Number>>(13 to 0.6, 15 to 4.5, 18 to 18)
        val serviceW = width(14 to 0.5, 16 to 2.5, 18 to 9)

        // V noci: pri jazde tmavý asfalt so svetlými okrajmi, pri oddialení modrosivé cesty.
        val near = c.asphaltNear
        val edge = c.edgeNear
        val minorColor = color(c.minor, near)
        val majorColor = color(c.major, near)
        val motorwayColor = color(c.motorway, near)

        val roads = buildList {
            add(
                """{"id":"path","type":"line","source":"omt","source-layer":"transportation","minzoom":15,
                "filter":["match",["get","class"],["path","pedestrian","track"],true,false],
                "paint":{"line-color":"${c.path}","line-width":${width(15 to 1, 18 to 3)},"line-dasharray":[2,1.5]}}"""
            )
            val casingColor = c.casing?.let { "\"$it\"" } ?: edge?.let { color(c.minor, it) }
            val motorwayCasing = c.motorwayCasing?.let { "\"$it\"" } ?: edge?.let { color(c.motorway, it) }
            if (casingColor != null) {
                add(road("minor-casing", "\"minor\",\"service\"", casingColor, casing(*minorStops), 13))
                add(road("secondary-casing", "\"secondary\",\"tertiary\"", casingColor, casing(*secondaryStops), 10))
                add(road("major-casing", "\"primary\",\"trunk\"", casingColor, casing(*majorStops), 8))
            }
            if (motorwayCasing != null) {
                add(road("motorway-casing", "\"motorway\"", motorwayCasing, casing(*motorwayStops), 5))
            }
            add(road("service", "\"service\"", minorColor, serviceW, 14))
            add(road("minor", "\"minor\"", minorColor, width(*minorStops), 13))
            add(road("secondary", "\"secondary\",\"tertiary\"", majorColor, width(*secondaryStops), 10))
            add(road("major", "\"primary\",\"trunk\"", majorColor, width(*majorStops), 8))
            add(road("motorway", "\"motorway\"", motorwayColor, width(*motorwayStops), 5))
            // Žltá prerušovaná stredová čiara na obojsmerných cestách.
            add(
                """{"id":"center-line","type":"line","source":"omt","source-layer":"transportation","minzoom":16,
                "filter":["all",["match",["get","class"],["primary","secondary","tertiary","minor"],true,false],
                  ["!=",["get","oneway"],1],$NOT_TUNNEL],
                "paint":{"line-color":"${c.centerLine}","line-width":${linear(16 to 0.8, 18 to 2)},"line-dasharray":[5,4]}}"""
            )
            // Biele prerušované čiary medzi jazdnými pruhmi na diaľniciach a rýchlostných cestách.
            add(
                """{"id":"lane-divider","type":"line","source":"omt","source-layer":"transportation","minzoom":15,
                "filter":["all",["!=",["get","ramp"],1],$NOT_TUNNEL,
                  ["any",["==",["get","class"],"motorway"],["all",["==",["get","class"],"trunk"],["==",["get","oneway"],1]]]],
                "paint":{"line-color":"${if (dark) "#e6e9ef" else "#c2c7cf"}","line-opacity":0.8,
                  "line-width":${linear(15 to 0.8, 18 to 2)},"line-dasharray":[6,6]}}"""
            )
            add(
                """{"id":"rail","type":"line","source":"omt","source-layer":"transportation","minzoom":12,
                "filter":["match",["get","class"],["rail","transit"],true,false],
                "paint":{"line-color":"${c.rail}","line-width":${width(12 to 1, 18 to 3)},"line-dasharray":[3,2]}}"""
            )
        }

        val layers = buildList {
            add("""{"id":"background","type":"background","paint":{"background-color":${color(c.background, c.backgroundNear)}}}""")
            add(
                """{"id":"residential","type":"fill","source":"omt","source-layer":"landuse","minzoom":9,
                "filter":["match",["get","class"],["residential","suburb","neighbourhood","commercial","retail","industrial"],true,false],
                "paint":{"fill-color":${color(c.residential, c.residentialNear)}}}"""
            )
            add(
                """{"id":"wood","type":"fill","source":"omt","source-layer":"landcover",
                "filter":["match",["get","class"],["wood","forest"],true,false],
                "paint":{"fill-color":"${c.wood}"}}"""
            )
            add(
                """{"id":"grass","type":"fill","source":"omt","source-layer":"landcover",
                "filter":["match",["get","class"],["grass","farmland","wetland"],true,false],
                "paint":{"fill-color":"${c.park}","fill-opacity":0.7}}"""
            )
            add("""{"id":"park","type":"fill","source":"omt","source-layer":"park","paint":{"fill-color":"${c.park}"}}""")
            add("""{"id":"water","type":"fill","source":"omt","source-layer":"water","paint":{"fill-color":"${c.water}"}}""")
            add(
                """{"id":"waterway","type":"line","source":"omt","source-layer":"waterway","minzoom":10,
                "paint":{"line-color":"${c.water}","line-width":${width(10 to 1, 18 to 6)}}}"""
            )
            add(
                """{"id":"building","type":"fill","source":"omt","source-layer":"building","minzoom":14,"maxzoom":15.5,
                "paint":{"fill-color":"${c.building}","fill-opacity":${linear(14 to 0, 15 to 1)}}}"""
            )
            addAll(roads)
            if (trafficKey != null) {
                add(
                    """{"id":"traffic","type":"raster","source":"traffic","minzoom":8,
                    "paint":{"raster-opacity":${linear(8 to 0.75, 14 to 0.9)}}}"""
                )
            }
            add(
                """{"id":"boundary","type":"line","source":"omt","source-layer":"boundary",
                "filter":["all",["<=",["get","admin_level"],2],["!=",["get","maritime"],1]],
                "paint":{"line-color":"${c.boundary}","line-width":${width(3 to 0.6, 10 to 1.5)},"line-dasharray":[4,2]}}"""
            )
            // 3D budovy pri jazde.
            add(
                """{"id":"building-3d","type":"fill-extrusion","source":"omt","source-layer":"building","minzoom":15.5,
                "paint":{"fill-extrusion-color":"${c.building3d}","fill-extrusion-opacity":0.9,
                  "fill-extrusion-height":["coalesce",["get","render_height"],8],
                  "fill-extrusion-base":["coalesce",["get","render_min_height"],0]}}"""
            )

            // Čiara k cieľu (tenká červená, ako v AMap).
            add(
                """{"id":"destination-line","type":"line","source":"destination-line",
                "paint":{"line-color":"#e5333a","line-width":1.3,"line-opacity":0.85}}"""
            )

            // Cesta, po ktorej vedie trasa, zvýraznená tyrkysovou (ako v AMap).
            add(
                """{"id":"route-corridor","type":"line","source":"route","minzoom":12,
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":"${if (dark) "#2b5654" else "#cde8e1"}","line-opacity":0.8,
                  "line-width":${linear(12 to 5, 14 to 11, 16 to 18, 17 to 25, 18 to 36)}}}"""
            )

            // Trasa so svetlým žiarom pri aute a bielymi šípkami v smere jazdy.
            add(
                """{"id":"route-casing","type":"line","source":"route",
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":${trafficColor(c.routeCasing, "#b89100", "#b3261e", "#6b0d14")},
                  "line-width":${linear(5 to 4, 12 to 8, 15 to 13, 17 to 19, 18 to 26)}}}"""
            )
            add(
                """{"id":"route-line","type":"line","source":"route",
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":${trafficColor(c.route, "#f5c518", "#f2453d", "#9b1622")},
                  "line-width":${linear(5 to 2.5, 12 to 5.5, 15 to 10, 17 to 15, 18 to 21)}}}"""
            )
            add(
                """{"id":"route-glow","type":"line","source":"route-head",
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-width":${linear(12 to 5, 15 to 9, 17 to 14, 18 to 20)},
                  "line-gradient":["interpolate",["linear"],["line-progress"],0,"rgba(255,255,255,0.95)",0.6,"rgba(200,255,235,0.45)",1,"rgba(34,211,154,0)"]}}"""
            )
            add(
                """{"id":"route-chevrons","type":"symbol","source":"route","minzoom":13,
                "layout":{"symbol-placement":"line","symbol-spacing":${linear(13 to 60, 18 to 120)},
                  "icon-image":"chevron","icon-size":${linear(13 to 0.55, 15 to 0.75, 18 to 1.2)},
                  "icon-allow-overlap":true,"icon-ignore-placement":true,"icon-rotation-alignment":"map",
                  "icon-keep-upright":false},
                "paint":{"icon-opacity":0.85}}"""
            )

            // Popisy ciest a miest.
            add(
                """{"id":"road-label","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":13,
                "filter":["match",["get","class"],["motorway","trunk","primary","secondary","tertiary","minor","service"],true,false],
                "layout":{"symbol-placement":"line","text-field":$NAME,"text-font":["Noto Sans Regular"],
                  "text-size":${linear(13 to 11, 16 to 14, 18 to 17)},"text-letter-spacing":0.04,"text-max-angle":30},
                "paint":{"text-color":"${c.roadLabel}","text-halo-color":${color(c.halo, if (dark) c.asphaltNear else null)},"text-halo-width":1.6}}"""
            )
            add(
                """{"id":"water-label","type":"symbol","source":"omt","source-layer":"water_name","minzoom":11,
                "layout":{"text-field":$NAME,"text-font":["Noto Sans Italic"],"text-size":12},
                "paint":{"text-color":"${c.roadLabel}","text-halo-color":"${c.halo}","text-halo-width":1.2}}"""
            )
            add(
                """{"id":"place-small","type":"symbol","source":"omt","source-layer":"place","minzoom":11,
                "filter":["match",["get","class"],["village","suburb","hamlet","neighbourhood","quarter"],true,false],
                "layout":{"text-field":$NAME,"text-font":["Noto Sans Regular"],"text-size":${linear(11 to 11, 15 to 15)}},
                "paint":{"text-color":"${c.placeLabel}","text-halo-color":"${c.halo}","text-halo-width":1.5}}"""
            )
            add(
                """{"id":"place-big","type":"symbol","source":"omt","source-layer":"place","minzoom":4,
                "filter":["match",["get","class"],["city","town"],true,false],
                "layout":{"text-field":$NAME,"text-font":["Noto Sans Bold"],"text-size":${linear(5 to 11, 10 to 15, 14 to 19)}},
                "paint":{"text-color":"${c.placeLabel}","text-halo-color":"${c.halo}","text-halo-width":1.6}}"""
            )

            // Šípka manévru (biela s modrým okrajom) nad popismi.
            add(
                """{"id":"arrow-casing","type":"line","source":"arrow",
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"#1f6fff","line-width":${linear(14 to 13, 16 to 19, 17 to 24, 18 to 29)}}}"""
            )
            add(
                """{"id":"arrow-line","type":"line","source":"arrow",
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"#ffffff","line-width":${linear(14 to 8, 16 to 13, 17 to 18, 18 to 23)}}}"""
            )
            add(
                """{"id":"arrow-head","type":"symbol","source":"arrowhead",
                "layout":{"icon-image":"arrowhead","icon-rotate":["get","bearing"],"icon-rotation-alignment":"map",
                  "icon-pitch-alignment":"map","icon-size":${linear(14 to 0.45, 16 to 0.75, 17 to 1, 18 to 1.25)},
                  "icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )

            // Služby (nabíjačky, pumpy, …); názvy až pri väčšom priblížení.
            add(
                """{"id":"pois","type":"symbol","source":"pois",
                "layout":{"icon-image":["get","icon"],"icon-anchor":"bottom","icon-allow-overlap":true,
                  "icon-size":${linear(9 to 0.7, 14 to 0.9, 17 to 1)},"symbol-sort-key":["get","rank"],
                  "text-field":["step",["zoom"],"",14,["get","name"]],"text-font":["Noto Sans Regular"],
                  "text-size":12,"text-anchor":"top","text-offset":[0,0.2],"text-max-width":9,"text-optional":true},
                "paint":{"text-color":"${c.roadLabel}","text-halo-color":"${c.halo}","text-halo-width":1.4}}"""
            )

            // Značky: semafory, radary, zastávka, cieľ, moja poloha (kompas + auto).
            add(
                """{"id":"signals","type":"symbol","source":"signals","minzoom":14,
                "layout":{"icon-image":"signal","icon-anchor":"bottom","icon-size":${linear(14 to 0.7, 17 to 1)},
                  "icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )
            add(
                """{"id":"cameras","type":"symbol","source":"cameras","minzoom":11,
                "layout":{"icon-image":"camera","icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )
            add(
                """{"id":"via","type":"symbol","source":"via",
                "layout":{"icon-image":"pin-via","icon-anchor":"bottom","icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )
            add(
                """{"id":"destination","type":"symbol","source":"destination",
                "layout":{"icon-image":"pin","icon-anchor":"bottom","icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )
            add(
                """{"id":"me-compass","type":"symbol","source":"me","filter":["==",["get","compass"],1],
                "layout":{"icon-image":"compass","icon-rotation-alignment":"map","icon-pitch-alignment":"map",
                  "icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )
            add(
                """{"id":"me-car","type":"symbol","source":"me",
                "layout":{"icon-image":["get","icon"],"icon-rotate":["get","bearing"],"icon-rotation-alignment":"map",
                  "icon-pitch-alignment":"map","icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )
        }

        val traffic = if (trafficKey == null) "" else {
            val style = if (dark) "relative0-dark" else "relative0"
            val key = java.net.URLEncoder.encode(trafficKey, "UTF-8")
            ""","traffic":{"type":"raster","tileSize":256,"minzoom":5,"maxzoom":22,"attribution":"© TomTom",
              "tiles":["https://api.tomtom.com/traffic/map/4/tile/flow/$style/{z}/{x}/{y}.png?key=$key&tileSize=512&thickness=8"]}"""
        }
        val sources = listOf("route", "arrow", "arrowhead", "cameras", "signals", "pois", "via", "destination", "destination-line", "me")
            .joinToString(",") { """"$it":{"type":"geojson","data":$EMPTY}""" } +
            ""","route-head":{"type":"geojson","lineMetrics":true,"data":$EMPTY}""" + traffic

        return """{"version":8,"name":"amap-${if (dark) "night" else "day"}",
            "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
            "sources":{"omt":{"type":"vector","url":"https://tiles.openfreemap.org/planet"},$sources},
            "layers":[${layers.joinToString(",")}]}"""
    }
}
