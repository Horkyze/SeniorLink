package family.seniorlink.location

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import family.seniorlink.BuildConfig
import family.seniorlink.data.StoredEvent
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

@Composable
internal fun LocationMap(
    locations: List<StoredEvent>,
    selected: StoredEvent,
    showAll: Boolean,
    cameraRequest: Int,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val uriHandler = LocalUriHandler.current
    val onSelectLatest by rememberUpdatedState(onSelect)
    var retry by remember { mutableIntStateOf(0) }
    var loaded by remember(retry) { mutableStateOf(false) }
    var unavailable by remember(retry) { mutableStateOf(false) }
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var offline by remember { mutableStateOf(connectivity.activeNetwork == null) }
    val controller = remember(context, retry) {
        LocationMapController(context, { onSelectLatest(it) }, { fully ->
            loaded = fully
            if (fully) unavailable = false
        }, { unavailable = true })
    }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { offline = false }
            override fun onLost(network: Network) { offline = true }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, _ -> controller.lifecycle(owner.lifecycle.currentState) }
        owner.lifecycle.addObserver(observer)
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() { controller.view.onLowMemory() }
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) onLowMemory() }
        }
        context.registerComponentCallbacks(memory)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            controller.destroy()
        }
    }
    LaunchedEffect(controller, loaded) {
        if (!loaded) {
            delay(12_000)
            unavailable = true
        }
    }
    Column {
        Box(modifier) {
            key(controller) {
                AndroidView(
                    factory = { controller.view },
                    update = { controller.update(locations, selected, showAll, cameraRequest) },
                    modifier = Modifier.fillMaxSize().testTag("location-map").semantics {
                        contentDescription = "Location map. Selected ${coordinates(selected)} at ${locationTime(selected.event.occurredAt)}. ${locations.size} saved locations."
                    },
                )
            }
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = { controller.zoom(true) }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Zoom in" }) { Text("+") }
                FilledTonalButton(onClick = { controller.zoom(false) }, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Zoom out" }) { Text("−") }
            }
            if (!loaded && !unavailable && !offline) {
                Surface(Modifier.align(Alignment.TopStart).padding(8.dp), shape = MaterialTheme.shapes.small) {
                    Text("Loading map…", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Surface(Modifier.align(Alignment.BottomEnd), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                TextButton(onClick = { runCatching { uriHandler.openUri("https://www.openstreetmap.org/copyright") } },
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (unavailable || offline) {
            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (offline) "You're offline. Cached map areas may be available. Saved coordinates and history are still available."
                    else "Map tiles unavailable. Check your internet connection. Saved coordinates and history are still available.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text("Retry map") }
            }
        }
    }
}

/** Only stored sharing-phone fixes are rendered; the SDK's device-location component is never enabled. */
private class LocationMapController(
    context: Context,
    private val onSelect: (Long) -> Unit,
    private val onRendered: (Boolean) -> Unit,
    onError: () -> Unit,
) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var started = false
    private var resumed = false
    private var destroyed = false
    private var locations = emptyList<StoredEvent>()
    private var selected: StoredEvent? = null
    private var all = false
    private var request = 0
    private var lastCamera: List<Any>? = null
    private var renderedLocations = emptyList<StoredEvent>()
    private var renderedSelection: StoredEvent? = null

    init {
        MapRuntime.initialize(context)
        view = object : MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // The map owns gestures inside its bounds; the surrounding history remains scrollable.
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
                }
                return super.dispatchTouchEvent(event)
            }
        }
        view.onCreate(Bundle())
        view.addOnDidFinishRenderingMapListener { fully ->
            if (!fully && source != null && !destroyed) onRendered(false)
        }
        view.addOnDidBecomeIdleListener { if (source != null && !destroyed) onRendered(true) }
        view.addOnDidFailLoadingMapListener { if (!destroyed) onError() }
        view.getMapAsync { ready ->
            if (destroyed) return@getMapAsync
            map = ready
            ready.setPrefetchZoomDelta(0)
            ready.setMaxZoomPreference(19.0)
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isAttributionEnabled = false // Directly visible Compose attribution above.
            ready.uiSettings.isRotateGesturesEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            selected?.let { selection ->
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(selection.event.latitude!!, selection.event.longitude!!), 15.0))
            }
            ready.addOnMapClickListener { coordinate ->
                val pixel = ready.projection.toScreenLocation(coordinate)
                val radius = 20 * context.resources.displayMetrics.density
                val hits = ready.queryRenderedFeatures(RectF(pixel.x - radius, pixel.y - radius, pixel.x + radius, pixel.y + radius),
                    "current", "history")
                hits.firstOrNull()?.getStringProperty("sequence")?.toLongOrNull()?.let(onSelect)
                hits.isNotEmpty()
            }
            ready.setStyle(Style.Builder().fromJson(RASTER_STYLE)) { style ->
                if (destroyed) return@setStyle
                source = GeoJsonSource("locations", FeatureCollection.fromFeatures(emptyArray<Feature>())).also(style::addSource)
                style.addLayer(CircleLayer("history", "locations")
                    .withFilter(eq(get("current"), literal(false)))
                    .withProperties(circleColor("#B66A18"), circleRadius(7f), circleStrokeWidth(2f), circleStrokeColor("#FFFFFF")))
                style.addLayer(CircleLayer("current", "locations")
                    .withFilter(eq(get("current"), literal(true)))
                    .withProperties(circleColor("#14665B"), circleRadius(10f), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")))
                style.addLayer(CircleLayer("selected", "locations")
                    .withFilter(eq(get("selected"), literal(true)))
                    .withProperties(circleColor("#14665B"), circleOpacity(0f), circleRadius(16f),
                        circleStrokeWidth(3f), circleStrokeColor("#163E59")))
                render()
            }
        }
    }

    fun update(points: List<StoredEvent>, selection: StoredEvent, showAll: Boolean, cameraRequest: Int) {
        locations = points; selected = selection; all = showAll; request = cameraRequest
        render()
    }

    private fun render() {
        if (destroyed) return
        val ready = map ?: return
        val geoJson = source ?: return
        val selection = selected ?: return
        if (renderedLocations != locations || renderedSelection != selection) {
            onRendered(false)
            geoJson.setGeoJson(FeatureCollection.fromFeatures(locations.mapIndexed { index, stored ->
                Feature.fromGeometry(Point.fromLngLat(stored.event.longitude!!, stored.event.latitude!!)).apply {
                    addStringProperty("sequence", stored.event.sequence.toString())
                    addBooleanProperty("current", index == 0)
                    addBooleanProperty("selected", stored == selection)
                }
            }))
            renderedLocations = locations; renderedSelection = selection
        }
        // A sync refresh should not recenter a map the user has panned.
        val cameraKey = listOf(selection.source, selection.event.sequence, all, request)
        if (lastCamera == cameraKey) return
        lastCamera = cameraKey
        view.post {
            if (destroyed || lastCamera != cameraKey) return@post
            onRendered(false)
            val points = locations.map { LatLng(it.event.latitude!!, it.event.longitude!!) }.distinct()
            if (all && points.size > 1) {
                ready.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(points), 60))
            } else {
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(selection.event.latitude!!, selection.event.longitude!!),
                    if (selection.event.accuracy!! > 1000) 12.0 else 15.0))
            }
        }
    }

    fun zoom(inward: Boolean) {
        map?.animateCamera(if (inward) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
    }

    fun lifecycle(state: Lifecycle.State) {
        if (destroyed) return
        val shouldStart = state.isAtLeast(Lifecycle.State.STARTED)
        val shouldResume = state.isAtLeast(Lifecycle.State.RESUMED)
        if (!shouldResume && resumed) { view.onPause(); resumed = false }
        if (!shouldStart && started) { view.onStop(); started = false }
        if (shouldStart && !started) { view.onStart(); started = true }
        if (shouldResume && !resumed) { view.onResume(); resumed = true }
    }

    fun destroy() {
        lifecycle(Lifecycle.State.DESTROYED)
        destroyed = true
        view.onDestroy()
        source = null; map = null
    }
}

private object MapRuntime {
    private var initialized = false
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent",
                "SeniorLink/${BuildConfig.VERSION_NAME} (family.seniorlink; https://github.com/Horkyze/SeniorLink)").build())
        }.build())
        initialized = true
    }
}

private const val RASTER_STYLE = """{
  "version": 8,
  "sources": {"osm": {"type": "raster", "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
    "tileSize": 256, "maxzoom": 19, "attribution": "© OpenStreetMap contributors"}},
  "layers": [{"id": "background", "type": "background", "paint": {"background-color": "#E8EEE9"}},
    {"id": "osm", "type": "raster", "source": "osm"}]
}"""
