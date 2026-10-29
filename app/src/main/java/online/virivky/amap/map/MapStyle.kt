package online.virivky.amap.map

import org.json.JSONArray
import org.json.JSONObject

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

    // Deň ako navigácia AMap: svetlomodrý podklad, pri jazde tmavomodré cesty so svetlým okrajom.
    private val DAY = Palette(
        background = "#e6edf5",
        backgroundNear = "#d8e4f1",
        residential = "#e0e8f1",
        residentialNear = "#d0deed",
        park = "#cbe9d6",
        wood = "#bfe3cc",
        water = "#a9d5f3",
        building = "#d3deea",
        path = "#c3cfdd",
        minor = "#ffffff",
        major = "#ffffff",
        motorway = "#ffd77d",
        asphaltNear = "#18233a",
        edgeNear = "#f4f7fb",
        casing = null,
        motorwayCasing = null,
        centerLine = "#f2cf3d",
        building3d = "#cad6e4",
        rail = "#b9c4d2",
        boundary = "#8f99a8",
        roadLabel = "#3d4552",
        placeLabel = "#262a31",
        halo = "#ffffff",
        route = "#16cc8c",
        routeCasing = "#0b8a63",
    )

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val NAME = """["coalesce",["get","name:sk"],["get","name:latin"],["get","name"]]"""
    private const val NOT_TUNNEL = """["!=",["get","brunnel"],"tunnel"]"""

    private fun width(vararg stops: Pair<Number, Number>) =
        """["interpolate",["exponential",1.5],["zoom"],${stops.joinToString(",") { "${it.first},${it.second}" }}]"""

    /** Hodnota výrazu [width] pri priblížení [z]. */
    private fun widthAt(stops: Array<Pair<Number, Number>>, z: Double): Double {
        val i = stops.indexOfLast { it.first.toDouble() <= z }.coerceIn(0, stops.size - 2)
        val z0 = stops[i].first.toDouble()
        val z1 = stops[i + 1].first.toDouble()
        val t = (Math.pow(1.5, z - z0) - 1) / (Math.pow(1.5, z1 - z0) - 1)
        val w0 = stops[i].second.toDouble()
        return w0 + (stops[i + 1].second.toDouble() - w0) * t.coerceIn(0.0, 1.0)
    }

    /** Šírka okraja cesty: šírka cesty + 1 až 3 px podľa priblíženia. */
    private fun casing(vararg stops: Pair<Number, Number>) =
        width(*stops.map { (z, w) -> z to w.toDouble() + (1.0 + (z.toDouble() - 14.0) * 2.0 / 3.0).coerceIn(1.0, 3.0) }.toTypedArray())

    /** Šírka či posun v metroch z vlastnosti [prop] prepočítaná na body obrazovky podľa priblíženia (~48,5° s. š.). */
    private fun meters(prop: String) =
        """["interpolate",["exponential",2],["zoom"],15,["*",["get","$prop"],0.632],22,["*",["get","$prop"],80.9]]"""

    /**
     * Ortofotomozaika SR (GKÚ Bratislava a Národné lesnícke centrum, zadarmo s uvedením zdroja) v dlaždiciach
     * Web Mercator zo servera Freemap Slovakia – rovnaký zdroj ponúka editorom OpenStreetMap. Mimo Slovenska
     * fotky nie sú a ostáva kreslená mapa.
     */
    private const val AERIAL_TILES = "https://ofmozaika.tiles.freemap.sk/{z}/{x}/{y}.jpg"
    const val AERIAL_CREDIT = "© GKÚ, NLC"

    /**
     * Vrstvy pre leteckú mapu: fotky nad kreslenými plochami (tie ostanú mimo Slovenska), 3D domy preč
     * (strechy sú na fotke), cesty pri priblížení postupne zmiznú, chodníky a trasa ostanú.
     */
    private fun aerialLayers(layers: List<String>, dark: Boolean): List<String> {
        val photo = """{"id":"aerial","type":"raster","source":"aerial","minzoom":8,
            "paint":{"raster-opacity":${linear(8 to 0, 9 to 1)},"raster-fade-duration":150""" +
            // V noci tlmene, aby fotka neoslňovala.
            (if (dark) ""","raster-brightness-max":0.55,"raster-saturation":-0.2""" else "") + "}}"
        val result = ArrayList<String>()
        for (layer in layers) {
            val o = JSONObject(layer)
            val id = o.getString("id")
            if (id == "building-3d") continue
            if (o.optString("source-layer") == "transportation" && o.optString("type") == "line" && id != "path" && id != "rail") {
                if (id.contains("center-line") || id.contains("lane-divider")) {
                    (o.optJSONObject("layout") ?: JSONObject().also { o.put("layout", it) }).put("visibility", "none")
                } else {
                    (o.optJSONObject("paint") ?: JSONObject().also { o.put("paint", it) })
                        .put("line-opacity", JSONArray(linear(12 to 0.9, 15 to 0.5, 16.5 to 0)))
                }
                result.add(o.toString())
            } else {
                result.add(layer)
            }
            if (id == "building") result.add(photo)
        }
        return result
    }

    /**
     * Čiarová vrstva trasy ešte raz pre zdroj „route-near“ (kúsok trasy od auta, prekresľovaný na každej snímke),
     * hneď za pôvodnou, aby boli všetky okraje trasy pod všetkými čiarami. Pre iné vrstvy null.
     */
    private fun nearCopy(layer: String): String? {
        if (!layer.contains(""""type":"line","source":"route"""")) return null
        return layer.replace(""""source":"route"""", """"source":"route-near"""")
            .replaceFirst(Regex(""""id":"([^"]+)""""), """"id":"$1-near"""")
    }

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
     * [aerial] = letecká mapa: fotky zhora namiesto kreslených plôch, cesty pri priblížení zmiznú (vidieť ich na fotke).
     */
    fun json(dark: Boolean, trafficKey: String? = null, aerial: Boolean = false): String {
        val c = if (dark) NIGHT else DAY
        // Od priblíženia 17 majú cesty skutočnú šírku (diaľnica 11 m na smer, hlavná 10 m, vedľajšia 8 m,
        // miestna 6,5 m, obslužná 4 m) a s každým stupňom sa zdvojnásobujú – ako v AMap pri jazde.
        val motorwayStops = arrayOf<Pair<Number, Number>>(5 to 0.6, 10 to 2.2, 14 to 8, 16 to 15.4, 17 to 27.8, 18 to 55.6, 19 to 111, 20 to 222)
        val majorStops = arrayOf<Pair<Number, Number>>(8 to 0.5, 12 to 2.2, 15 to 9, 16 to 13, 17 to 25.3, 18 to 50.6, 19 to 101, 20 to 202)
        val secondaryStops = arrayOf<Pair<Number, Number>>(10 to 0.5, 13 to 2.4, 15 to 7, 16 to 10.6, 17 to 20.2, 18 to 40.4, 19 to 81, 20 to 162)
        val minorStops = arrayOf<Pair<Number, Number>>(13 to 0.6, 15 to 4.5, 16 to 8.2, 17 to 16.4, 18 to 32.9, 19 to 65.7, 20 to 131)
        val serviceStops = arrayOf<Pair<Number, Number>>(14 to 0.5, 16 to 3.5, 17 to 10.1, 18 to 20.2, 19 to 40.4, 20 to 81)
        val serviceW = width(*serviceStops)
        // Priechod pre chodcov: 6 pruhov pozdĺž cesty na ~90 % jej šírky podľa triedy cesty, aby nepresahoval cez okraj.
        // [perBar] = násobí sa vlastnosťou „f“ (poloha pruhu od stredu cesty ako podiel šírky priechodu).
        fun byRoad(scale: Double, perBar: Boolean) = """["interpolate",["exponential",1.5],["zoom"],""" +
            listOf(16, 17, 18, 19, 20).joinToString(",") { z ->
                fun w(stops: Array<Pair<Number, Number>>) = Math.round(widthAt(stops, z.toDouble()) * scale * 1000) / 1000.0
                val match = """["match",["get","c"],"major",${w(majorStops)},"secondary",${w(secondaryStops)},""" +
                    """"service",${w(serviceStops)},${w(minorStops)}]"""
                "$z," + if (perBar) """["*",["get","f"],$match]""" else match
            } + "]"
        val zebraColor = "#f4f6f8"

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
                "paint":{"line-color":"${c.centerLine}","line-width":${linear(16 to 1, 18 to 2.5, 20 to 6)},"line-dasharray":[5,4]}}"""
            )
            // Biele prerušované čiary medzi jazdnými pruhmi na diaľniciach a rýchlostných cestách.
            add(
                """{"id":"lane-divider","type":"line","source":"omt","source-layer":"transportation","minzoom":15,
                "filter":["all",["!=",["get","ramp"],1],$NOT_TUNNEL,
                  ["any",["==",["get","class"],"motorway"],["all",["==",["get","class"],"trunk"],["==",["get","oneway"],1]]]],
                "paint":{"line-color":"#e6e9ef","line-opacity":0.8,
                  "line-width":${linear(15 to 0.8, 18 to 2.5, 20 to 6)},"line-dasharray":[6,6]}}"""
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

            // Iné varianty trasy v náhľade (sivé, ťuknutím sa vyberú).
            add(
                """{"id":"alt-casing","type":"line","source":"alternatives",
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":"${if (dark) "#4b5567" else "#8f9aab"}",
                  "line-width":${linear(5 to 4, 12 to 8, 15 to 12, 17 to 17, 18 to 23)}}}"""
            )
            add(
                """{"id":"alt-line","type":"line","source":"alternatives",
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":"${if (dark) "#8792a6" else "#c5cdd8"}",
                  "line-width":${linear(5 to 2.5, 12 to 5, 15 to 8.5, 17 to 13, 18 to 18)}}}"""
            )

            // Cesta, po ktorej vedie trasa, zvýraznená tyrkysovou (ako v AMap) – najviac na šírku cesty.
            add(
                """{"id":"route-corridor","type":"line","source":"route","minzoom":12,"maxzoom":16.5,
                "filter":["!=",["get","foot"],1],
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":"#2b5654","line-opacity":0.8,
                  "line-width":${width(12 to 2, 13 to 2.5, 15 to 4.2, 16 to 7.5, 17 to 15, 18 to 30, 19 to 60, 20 to 121)}}}"""
            )

            // Trasa so svetlým žiarom pri aute a bielymi šípkami v smere jazdy.
            add(
                """{"id":"route-casing","type":"line","source":"route",
                "filter":["!=",["get","foot"],1],
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":${trafficColor(c.routeCasing, "#b89100", "#b3261e", "#6b0d14")},
                  "line-width":${linear(5 to 4, 12 to 6.5, 15 to 7, 16 to 7.5, 17 to 11, 18 to 19.5, 19 to 38, 20 to 76)}}}"""
            )
            add(
                """{"id":"route-line","type":"line","source":"route",
                "filter":["!=",["get","foot"],1],
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":${trafficColor(c.route, "#f5c518", "#f2453d", "#9b1622")},
                  "line-width":${linear(5 to 2.5, 12 to 4.5, 15 to 5, 16 to 6, 17 to 9, 18 to 16.5, 19 to 33, 20 to 66)}}}"""
            )
            // Svetlé pruhy, ktoré plynú po trase v smere jazdy (mapa ich posúva, pozri NavMap).
            add(
                """{"id":"route-flow","type":"line","source":"route",
                "filter":["!=",["get","foot"],1],
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"#ffffff","line-opacity":0.38,"line-dasharray":[0,4,3],
                  "line-width":${linear(5 to 1.2, 12 to 2.2, 15 to 2.5, 16 to 3, 17 to 4.5, 18 to 8.2, 19 to 16.5, 20 to 33)}}}"""
            )
            // Pri veľkom priblížení tmavá medzera medzi pruhmi trasy a čiary cesty nad trasou (ako v AMap).
            add(
                """{"id":"route-gap","type":"line","source":"route","minzoom":17,
                "filter":["!=",["get","foot"],1],
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"${if (dark) "#1d5c4a" else "#178a64"}","line-opacity":0.85,
                  "line-width":["interpolate",["exponential",2],["zoom"],17,1.3,22,42]}}"""
            )
            // Pruhy pred manévrom (pohľad na pruhy): nad trasou, ale pod čiarami cesty a priechodmi pre chodcov,
            // ktoré idú cez celú cestu. Posuny do strany sú už v tvare čiar (NavMap), preto bez line-offset.
            add(
                """{"id":"lanes-surface","type":"line","source":"lanes","minzoom":15,"filter":["==",["get","k"],"surface"],
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"${c.asphaltNear ?: "#9AA1AE"}","line-width":${meters("w")}}}"""
            )
            add(
                """{"id":"lanes-good","type":"line","source":"lanes","minzoom":15,"filter":["==",["get","k"],"good"],
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"${c.route}","line-opacity":0.95,"line-width":${meters("w")}}}"""
            )
            add(
                """{"id":"lanes-sep","type":"line","source":"lanes","minzoom":15,"filter":["==",["get","k"],"sep"],
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"#FFFFFF","line-opacity":0.9,"line-dasharray":[14,20],
                  "line-width":${meters("w")}}}"""
            )
            add(
                """{"id":"lanes-edge","type":"line","source":"lanes","minzoom":15,"filter":["==",["get","k"],"edge"],
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"${c.edgeNear ?: "#6E7586"}","line-width":${meters("w")}}}"""
            )
            add(
                """{"id":"route-center-line","type":"line","source":"omt","source-layer":"transportation","minzoom":16,
                "filter":["all",["match",["get","class"],["primary","secondary","tertiary","minor"],true,false],
                  ["!=",["get","oneway"],1],$NOT_TUNNEL],
                "paint":{"line-color":"${c.centerLine}","line-width":${linear(16 to 1, 18 to 2.5, 20 to 6)},"line-dasharray":[5,4]}}"""
            )
            add(
                """{"id":"route-lane-divider","type":"line","source":"omt","source-layer":"transportation","minzoom":16,
                "filter":["all",["!=",["get","ramp"],1],$NOT_TUNNEL,
                  ["any",["==",["get","class"],"motorway"],["all",["==",["get","class"],"trunk"],["==",["get","oneway"],1]]]],
                "paint":{"line-color":"#e6e9ef","line-opacity":0.8,
                  "line-width":${linear(16 to 1, 18 to 2.5, 20 to 6)},"line-dasharray":[6,6]}}"""
            )
            // Priechody pre chodcov nad trasou (trasa ich neprekrýva, ako v AMap): každý pruh je krátka čiara
            // pozdĺž cesty posunutá od jej stredu, šírky podľa cesty – ani v 3D nepresahujú cez okraj cesty.
            add(
                """{"id":"crossings","type":"line","source":"crossings","minzoom":16,
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"$zebraColor","line-opacity":0.95,
                  "line-width":${byRoad(0.94 / 6 * 0.6, false)},"line-offset":${byRoad(0.94, true)}}}"""
            )
            // Pešia trasa: modré bodky s bielym okrajom (ako pešie trasy v iných navigáciách).
            add(
                """{"id":"route-walk-casing","type":"line","source":"route","filter":["==",["get","foot"],1],
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":"#ffffff","line-dasharray":[0,1.48],
                  "line-width":${linear(12 to 6.75, 16 to 10, 18 to 13.5, 20 to 18.9)}}}"""
            )
            add(
                """{"id":"route-walk","type":"line","source":"route","filter":["==",["get","foot"],1],
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-color":"#2f6fe0","line-dasharray":[0,2],
                  "line-width":${linear(12 to 5, 16 to 7.4, 18 to 10, 20 to 14)}}}"""
            )
            add(
                """{"id":"route-glow","type":"line","source":"route-head",
                "layout":{"line-cap":"round","line-join":"round"},
                "paint":{"line-width":${linear(12 to 4, 15 to 5, 16 to 6, 17 to 9, 18 to 16.5, 19 to 33, 20 to 66)},
                  "line-gradient":["interpolate",["linear"],["line-progress"],0,"rgba(255,255,255,0.95)",0.6,"rgba(200,255,235,0.45)",1,"rgba(34,211,154,0)"]}}"""
            )
            add(
                """{"id":"route-chevrons","type":"symbol","source":"route","minzoom":13,
                "filter":["!=",["get","foot"],1],
                "layout":{"symbol-placement":"line","symbol-spacing":${linear(13 to 60, 18 to 120)},
                  "icon-image":"chevron","icon-size":${linear(13 to 0.55, 15 to 0.75, 18 to 1.2)},
                  "icon-allow-overlap":true,"icon-ignore-placement":true,"icon-rotation-alignment":"map",
                  "icon-keep-upright":false},
                "paint":{"icon-opacity":0.85}}"""
            )

            add(
                """{"id":"lane-marks","type":"symbol","source":"lane-marks","minzoom":15,
                "layout":{"icon-image":["get","icon"],"icon-rotate":["get","bearing"],"icon-rotation-alignment":"map",
                  "icon-pitch-alignment":"map","icon-allow-overlap":true,"icon-ignore-placement":true,
                  "icon-size":["interpolate",["exponential",2],["zoom"],15,["*",0.092,["get","s"]],22,["*",11.7,["get","s"]]]}}"""
            )

            // Popisy ciest a miest.
            add(
                """{"id":"road-label","type":"symbol","source":"omt","source-layer":"transportation_name","minzoom":13,
                "filter":["match",["get","class"],["motorway","trunk","primary","secondary","tertiary","minor","service"],true,false],
                "layout":{"symbol-placement":"line","text-field":$NAME,"text-font":["Noto Sans Regular"],
                  "text-size":${linear(13 to 11, 16 to 14, 18 to 17)},"text-letter-spacing":0.04,"text-max-angle":30},
                "paint":{"text-color":${color(c.roadLabel, if (dark) null else "#eef2f8")},"text-halo-color":${color(c.halo, c.asphaltNear)},"text-halo-width":1.6}}"""
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
                "paint":{"line-color":"#1f6fff","line-width":${linear(14 to 9, 16 to 11, 17 to 13, 18 to 18, 19 to 33, 20 to 64)}}}"""
            )
            add(
                """{"id":"arrow-line","type":"line","source":"arrow",
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":"#ffffff","line-width":${linear(14 to 5.5, 16 to 7, 17 to 9, 18 to 13, 19 to 25, 20 to 48)}}}"""
            )
            add(
                """{"id":"arrow-head","type":"symbol","source":"arrowhead",
                "layout":{"icon-image":"arrowhead","icon-rotate":["get","bearing"],"icon-rotation-alignment":"map",
                  "icon-pitch-alignment":"map","icon-size":${linear(14 to 0.32, 16 to 0.42, 17 to 0.52, 18 to 0.74, 19 to 1.4, 20 to 2.7)},
                  "icon-allow-overlap":true,"icon-ignore-placement":true}}"""
            )

            // Uzávierky a práce na ceste ako prerušovaná čiara, udalosti ako dopravné značky.
            add(
                """{"id":"incident-lines","type":"line","source":"incident-lines","minzoom":10,
                "layout":{"line-cap":"butt","line-join":"round"},
                "paint":{"line-color":["case",["==",["get","closed"],1],"#e5333a","#ffb300"],
                  "line-dasharray":[1.5,1],"line-width":${linear(10 to 2, 14 to 4, 17 to 7)}}}"""
            )
            add(
                """{"id":"incidents","type":"symbol","source":"incidents","minzoom":8,
                "layout":{"icon-image":["get","icon"],"icon-allow-overlap":true,
                  "icon-size":${linear(8 to 0.6, 12 to 0.85, 15 to 1)}}}"""
            )

            // Čas jazdy pri variantoch trasy (vybraná zelenou).
            add(
                """{"id":"route-labels","type":"symbol","source":"route-labels",
                "layout":{"text-field":["get","label"],"text-font":["Noto Sans Bold"],"text-size":14,
                  "text-allow-overlap":true,"text-ignore-placement":true},
                "paint":{"text-color":["case",["==",["get","selected"],1],"#ffffff","#1f2430"],
                  "text-halo-color":["case",["==",["get","selected"],1],"${c.routeCasing}","#ffffff"],
                  "text-halo-width":4}}"""
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
            // Moje miesta: hviezdička s názvom.
            add(
                """{"id":"saved","type":"symbol","source":"saved",
                "layout":{"icon-image":"pin-saved","icon-anchor":"bottom","icon-allow-overlap":true,"icon-ignore-placement":true,
                  "text-field":["get","name"],"text-font":["Noto Sans Regular"],"text-size":12,"text-anchor":"top",
                  "text-offset":[0,0.2],"text-optional":true,"text-max-width":9},
                "paint":{"text-color":"${if (dark) "#ffd27a" else "#8a5300"}","text-halo-color":"${c.halo}","text-halo-width":1.5}}"""
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
        val sources = listOf(
            "route", "route-near", "alternatives", "route-labels", "incidents", "incident-lines", "lanes", "lane-marks", "crossings", "arrow", "arrowhead", "cameras",
            "signals", "pois", "via",
            "destination", "saved", "me",
        )
            .joinToString(",") { """"$it":{"type":"geojson","data":$EMPTY}""" } +
            ""","route-head":{"type":"geojson","lineMetrics":true,"data":$EMPTY}""" + traffic +
            if (aerial) ""","aerial":{"type":"raster","tileSize":256,"minzoom":8,"maxzoom":20,"attribution":"$AERIAL_CREDIT",
              "tiles":["$AERIAL_TILES"]}""" else ""
        val all = layers.flatMap { listOf(it) + listOfNotNull(nearCopy(it)) }

        return """{"version":8,"name":"amap-${if (dark) "night" else "day"}",
            "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
            "sources":{"omt":{"type":"vector","url":"https://tiles.openfreemap.org/planet"},$sources},
            "layers":[${(if (aerial) aerialLayers(all, dark) else all).joinToString(",")}]}"""
    }
}
