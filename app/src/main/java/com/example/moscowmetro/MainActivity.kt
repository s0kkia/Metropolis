package com.example.moscowmetro

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContent {
            MaterialTheme {
                MoscowMetroMainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoscowMetroMainScreen(viewModel: MetroViewModel = viewModel()) {
    val lines by viewModel.lines.collectAsState()
    val trains by viewModel.trains.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedStation by viewModel.selectedStation.collectAsState()
    val routeStart by viewModel.routeStart.collectAsState()
    val routeEnd by viewModel.routeEnd.collectAsState()
    val currentRoute by viewModel.currentRoute.collectAsState()
    val useGeoMap by viewModel.useGeoMap.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Метро Москвы Live", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            if (useGeoMap) "Режим: Гео-карта Москвы (OSM)" else "Режим: Интерактивная схема",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.toggleMapMode() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text(if (useGeoMap) "Схема" else "Карта города")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E1E1E),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF121212))
        ) {
            if (isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color.Red)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Онлайн-загрузка линий и поездов Москвы...", color = Color.White)
                }
            } else {
                if (useGeoMap) {
                    OsmGeoMetroMap(
                        lines = lines,
                        trains = trains,
                        route = currentRoute,
                        onStationClick = { viewModel.selectStation(it) }
                    )
                } else {
                    SchematicMetroCanvas(
                        lines = lines,
                        trains = trains,
                        route = currentRoute,
                        onStationClick = { viewModel.selectStation(it) }
                    )
                }

                RoutePanelOverlay(
                    start = routeStart,
                    end = routeEnd,
                    route = currentRoute,
                    onClear = { viewModel.clearRoute() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                )
            }
        }

        selectedStation?.let { station ->
            ModalBottomSheet(
                onDismissRequest = { viewModel.selectStation(null) },
                containerColor = Color(0xFF222222),
                contentColor = Color.White
            ) {
                StationDetailsSheet(
                    station = station,
                    trains = trains.filter { it.lineId == station.lineId },
                    onSelectAsStart = { viewModel.setStartStation(station) },
                    onSelectAsEnd = { viewModel.setEndStation(station) }
                )
            }
        }
    }
}

@Composable
fun OsmGeoMetroMap(
    lines: List<MetroLine>,
    trains: List<LiveTrain>,
    route: RouteResult?,
    onStationClick: (MetroStation) -> Unit
) {
    val context = LocalContext.current

    // Разделяем статический слой (ветки, станции, маршрут) и динамический слой (поезда),
    // чтобы не пересоздавать 300+ маркеров станций каждые 100 мс и не блокировать клики
    val staticLayer = remember { FolderOverlay() }
    val trainsLayer = remember { FolderOverlay() }
    val trainMarkersMap = remember { mutableMapOf<String, Marker>() }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(11.5)
            controller.setCenter(GeoPoint(55.7558, 37.6173))
            overlays.add(staticLayer)
            overlays.add(trainsLayer)
        }
    }

    // Перестраиваем линии и станции ТОЛЬКО при изменении списка линий или маршрута
    LaunchedEffect(lines, route) {
        staticLayer.items.clear()
        val routeStationIds = route?.steps?.map { it.station.id }?.toSet().orEmpty()

        lines.forEach { line ->
            val lineColorInt = try {
                AndroidColor.parseColor("#${line.hexColor.removePrefix("#")}")
            } catch (e: Exception) {
                AndroidColor.RED
            }

            val polyline = Polyline().apply {
                outlinePaint.color = lineColorInt
                outlinePaint.strokeWidth = 10f
                setPoints(line.stations.map { GeoPoint(it.lat, it.lng) })
            }
            staticLayer.add(polyline)

            line.stations.forEach { st ->
                val marker = Marker(mapView).apply {
                    position = GeoPoint(st.lat, st.lng)
                    title = "${st.name} (${line.name})"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

                    val isRouteNode = routeStationIds.contains(st.id)
                    val sizePx = if (isRouteNode) 36 else 24
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setSize(sizePx, sizePx)
                        setColor(if (isRouteNode) AndroidColor.YELLOW else AndroidColor.WHITE)
                        setStroke(6, lineColorInt)
                    }
                    setOnMarkerClickListener { _, _ ->
                        onStationClick(st)
                        true
                    }
                }
                staticLayer.add(marker)
            }
        }

        if (route != null && route.steps.size > 1) {
            val routeLine = Polyline().apply {
                outlinePaint.color = AndroidColor.YELLOW
                outlinePaint.strokeWidth = 18f
                setPoints(route.steps.map { GeoPoint(it.station.lat, it.station.lng) })
            }
            staticLayer.add(routeLine)
        }

        mapView.invalidate()
    }

    // Быстрое обновление только координат поездов (10 раз в секунду без аллокации новых маркеров)
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { map ->
            trains.forEach { train ->
                val line = lines.find { it.id == train.lineId } ?: return@forEach
                val stList = line.stations
                if (train.currentStationIndex in stList.indices && train.nextStationIndex in stList.indices) {
                    val s1 = stList[train.currentStationIndex]
                    val s2 = stList[train.nextStationIndex]
                    val lat = s1.lat + (s2.lat - s1.lat) * train.progress
                    val lng = s1.lng + (s2.lng - s1.lng) * train.progress

                    val existingMarker = trainMarkersMap[train.id]
                    if (existingMarker != null) {
                        existingMarker.position = GeoPoint(lat, lng)
                    } else {
                        val newMarker = Marker(map).apply {
                            position = GeoPoint(lat, lng)
                            title = "Поезд ${train.trainNumber}"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = 8f
                                setSize(28, 28)
                                setColor(AndroidColor.YELLOW)
                                setStroke(4, AndroidColor.BLACK)
                            }
                        }
                        trainMarkersMap[train.id] = newMarker
                        trainsLayer.add(newMarker)
                    }
                }
            }
            map.invalidate()
        }
    )
}

@Composable
fun SchematicMetroCanvas(
    lines: List<MetroLine>,
    trains: List<LiveTrain>,
    route: RouteResult?,
    onStationClick: (MetroStation) -> Unit
) {
    var scale by remember { mutableStateOf(1.5f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val minLat = 55.50
    val maxLat = 55.95
    val minLng = 37.30
    val maxLng = 37.90

    val textPaint = remember {
        Paint().apply {
            color = AndroidColor.WHITE
            textSize = 26f
            isAntiAlias = true
        }
    }

    // Прямая трансформация координат прямо на холсте гарантирует 100% точность попадания по станции при любом зуме
    fun transformPoint(baseX: Float, baseY: Float, centerX: Float, centerY: Float): Offset {
        val tx = (baseX - centerX) * scale + centerX + offset.x
        val ty = (baseY - centerY) * scale + centerY + offset.y
        return Offset(tx, ty)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(lines) {
                detectTapGestures { tapOffset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f

                    var closestStation: MetroStation? = null
                    var minDist = 48f // Радиус захвата клика в пикселях экрана

                    lines.forEach { line ->
                        line.stations.forEach { st ->
                            val baseX = ((st.lng - minLng) / (maxLng - minLng) * size.width).toFloat()
                            val baseY = ((maxLat - st.lat) / (maxLat - minLat) * size.height).toFloat()
                            val screenPos = transformPoint(baseX, baseY, centerX, centerY)
                            val dist = hypot(tapOffset.x - screenPos.x, tapOffset.y - screenPos.y)
                            if (dist < minDist) {
                                minDist = dist
                                closestStation = st
                            }
                        }
                    }
                    closestStation?.let { onStationClick(it) }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.7f, 10f)
                    offset += pan
                }
            }
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        fun latLngToScreen(lat: Double, lng: Double): Offset {
            val baseX = ((lng - minLng) / (maxLng - minLng) * size.width).toFloat()
            val baseY = ((maxLat - lat) / (maxLat - minLat) * size.height).toFloat()
            return transformPoint(baseX, baseY, centerX, centerY)
        }

        val routeStationIds = route?.steps?.map { it.station.id }?.toSet().orEmpty()

        lines.forEach { line ->
            val lineColor = try {
                Color(AndroidColor.parseColor("#${line.hexColor.removePrefix("#")}"))
            } catch (e: Exception) {
                Color.Gray
            }
            val stations = line.stations

            for (i in 0 until stations.size - 1) {
                val start = latLngToScreen(stations[i].lat, stations[i].lng)
                val end = latLngToScreen(stations[i + 1].lat, stations[i + 1].lng)
                drawLine(
                    color = lineColor,
                    start = start,
                    end = end,
                    strokeWidth = 6f * scale.coerceAtMost(2.5f),
                    cap = StrokeCap.Round
                )
            }

            stations.forEach { st ->
                val pos = latLngToScreen(st.lat, st.lng)
                val isSelectedInRoute = routeStationIds.contains(st.id)
                val radiusScale = scale.coerceIn(1f, 2.2f)
                drawCircle(
                    color = if (isSelectedInRoute) Color.Yellow else Color.White,
                    radius = (if (isSelectedInRoute) 9f else 5.5f) * radiusScale,
                    center = pos
                )
                drawCircle(color = lineColor, radius = 3.5f * radiusScale, center = pos)

                if (scale > 1.8f || isSelectedInRoute) {
                    drawContext.canvas.nativeCanvas.drawText(
                        st.name,
                        pos.x + 12f,
                        pos.y + 6f,
                        textPaint
                    )
                }
            }
        }

        if (route != null && route.steps.size > 1) {
            for (i in 0 until route.steps.size - 1) {
                val p1 = latLngToScreen(route.steps[i].station.lat, route.steps[i].station.lng)
                val p2 = latLngToScreen(route.steps[i + 1].station.lat, route.steps[i + 1].station.lng)
                drawLine(
                    color = Color.Yellow,
                    start = p1,
                    end = p2,
                    strokeWidth = 11f * scale.coerceAtMost(2.5f),
                    cap = StrokeCap.Round
                )
            }
        }

        trains.forEach { train ->
            val line = lines.find { it.id == train.lineId } ?: return@forEach
            val stations = line.stations
            if (train.currentStationIndex !in stations.indices || train.nextStationIndex !in stations.indices) return@forEach

            val s1 = stations[train.currentStationIndex]
            val s2 = stations[train.nextStationIndex]
            val p1 = latLngToScreen(s1.lat, s1.lng)
            val p2 = latLngToScreen(s2.lat, s2.lng)

            val trainPos = Offset(
                x = p1.x + (p2.x - p1.x) * train.progress,
                y = p1.y + (p2.y - p1.y) * train.progress
            )
            val rScale = scale.coerceIn(1f, 2f)
            drawCircle(color = Color.Yellow.copy(alpha = 0.45f), radius = 11f * rScale, center = trainPos)
            drawCircle(color = Color.Yellow, radius = 5.5f * rScale, center = trainPos)
        }
    }
}

@Composable
fun RoutePanelOverlay(
    start: MetroStation?,
    end: MetroStation?,
    route: RouteResult?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (start == null && end == null) {
        Surface(
            modifier = modifier,
            color = Color(0xCC1E1E1E),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Нажмите на любую станцию, чтобы узнать прибытие поезда или построить маршрут",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE1E1E1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Откуда: ${start?.name ?: "Выберите на карте"}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Куда: ${end?.name ?: "Выберите на карте"}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
                TextButton(onClick = onClear) {
                    Text("Сбросить", color = Color(0xFFFF5252))
                }
            }

            if (route != null) {
                HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Время в пути: ~${route.totalMinutes} мин • Пересадок: ${route.transfersCount} • Станций: ${route.steps.size}",
                    color = Color(0xFFFFD54F),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(route.steps) { step ->
                        Surface(
                            color = if (step.isTransfer) Color(0xFF424242) else Color(0xFF2C2C2C),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (step.isTransfer) "⇄ ${step.station.name}" else step.station.name,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StationDetailsSheet(
    station: MetroStation,
    trains: List<LiveTrain>,
    onSelectAsStart: () -> Unit,
    onSelectAsEnd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val badgeColor = try {
                Color(AndroidColor.parseColor("#${station.lineColor.removePrefix("#")}"))
            } catch (e: Exception) {
                Color.Red
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(badgeColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(station.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Линия: ${station.lineName}", fontSize = 14.sp, color = Color.LightGray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Ближайшие поезда (Live):", fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD54F))
        Spacer(modifier = Modifier.height(8.dp))

        val arrivingTrains = trains.mapNotNull { train ->
            val eta = train.getEtaSecondsToStation(station.order)
            if (eta != null) train to eta else null
        }.sortedBy { it.second }.take(3)

        if (arrivingTrains.isEmpty()) {
            Text("Ожидание данных о составе на перегоне...", color = Color.Gray, fontSize = 14.sp)
        } else {
            arrivingTrains.forEach { (train, sec) ->
                val min = sec / 60
                val remSec = sec % 60
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Поезд ${train.trainNumber} (${if (train.forward) "в сторону конечной" else "в центр/обратно"})",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (min == 0) "через $remSec сек" else "$min мин $remSec сек",
                        color = Color(0xFF81C784),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSelectAsStart,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Text("Отсюда (Старт)")
            }
            Button(
                onClick = onSelectAsEnd,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Text("Сюда (Финиш)")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
