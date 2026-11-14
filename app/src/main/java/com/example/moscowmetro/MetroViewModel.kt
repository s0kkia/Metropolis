package com.example.moscowmetro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MetroViewModel : ViewModel() {
    private val api = MetroApiService.create()
    private var trainSimulationJob: Job? = null

    private val _lines = MutableStateFlow<List<MetroLine>>(emptyList())
    val lines: StateFlow<List<MetroLine>> = _lines

    private val _trains = MutableStateFlow<List<LiveTrain>>(emptyList())
    val trains: StateFlow<List<LiveTrain>> = _trains

    private val _selectedStation = MutableStateFlow<MetroStation?>(null)
    val selectedStation: StateFlow<MetroStation?> = _selectedStation

    private val _routeStart = MutableStateFlow<MetroStation?>(null)
    val routeStart: StateFlow<MetroStation?> = _routeStart

    private val _routeEnd = MutableStateFlow<MetroStation?>(null)
    val routeEnd: StateFlow<MetroStation?> = _routeEnd

    private val _currentRoute = MutableStateFlow<RouteResult?>(null)
    val currentRoute: StateFlow<RouteResult?> = _currentRoute

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _useGeoMap = MutableStateFlow(true)
    val useGeoMap: StateFlow<Boolean> = _useGeoMap

    init {
        loadMoscowMetro()
    }

    fun toggleMapMode() {
        _useGeoMap.value = !_useGeoMap.value
    }

    fun selectStation(station: MetroStation?) {
        _selectedStation.value = station
    }

    fun setStartStation(station: MetroStation) {
        _routeStart.value = station
        _selectedStation.value = null
        recalculateRoute()
    }

    fun setEndStation(station: MetroStation) {
        _routeEnd.value = station
        _selectedStation.value = null
        recalculateRoute()
    }

    fun clearRoute() {
        _routeStart.value = null
        _routeEnd.value = null
        _currentRoute.value = null
    }

    private fun recalculateRoute() {
        val start = _routeStart.value ?: return
        val end = _routeEnd.value ?: return
        _currentRoute.value = MetroRouteFinder.findShortestRoute(_lines.value, start.id, end.id)
    }

    private fun loadMoscowMetro() {
        viewModelScope.launch {
            _isLoading.value = true
            val loadedLines = try {
                val response = api.getMoscowMetro()
                response.lines.mapIndexed { lineIdx, line ->
                    val sortedStations = line.stations.sortedBy { it.order }.mapIndexed { idx, st ->
                        st.copy(
                            order = idx, // Гарантируем непрерывную индексацию 0..N-1
                            lineId = line.id,
                            lineName = line.name,
                            lineColor = line.hexColor
                        )
                    }
                    line.copy(stations = sortedStations)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                FallbackMetroData.getFallbackLines()
            }

            _lines.value = loadedLines
            _isLoading.value = false
            startTrainSimulation(loadedLines)
        }
    }

    private fun startTrainSimulation(loadedLines: List<MetroLine>) {
        trainSimulationJob?.cancel()
        val initialTrains = mutableListOf<LiveTrain>()
        loadedLines.forEach { line ->
            val count = line.stations.size
            if (count > 3) {
                initialTrains.add(
                    LiveTrain("${line.id}_F1", "№ 701 (${line.name})", line.id, line.name, line.hexColor, 0, 1, true, 0.15f)
                )
                initialTrains.add(
                    LiveTrain("${line.id}_F2", "№ 703 (${line.name})", line.id, line.name, line.hexColor, count / 2, (count / 2) + 1, true, 0.55f)
                )
                initialTrains.add(
                    LiveTrain("${line.id}_B1", "№ 702 (${line.name})", line.id, line.name, line.hexColor, count - 1, count - 2, false, 0.35f)
                )
                initialTrains.add(
                    LiveTrain("${line.id}_B2", "№ 704 (${line.name})", line.id, line.name, line.hexColor, count / 2, (count / 2) - 1, false, 0.75f)
                )
            }
        }
        _trains.value = initialTrains

        trainSimulationJob = viewModelScope.launch {
            while (true) {
                delay(100)
                _trains.value = _trains.value.map { train ->
                    val line = loadedLines.find { it.id == train.lineId } ?: return@map train
                    val maxIdx = line.stations.lastIndex
                    if (maxIdx < 1) return@map train

                    var nextProg = train.progress + 0.008f
                    var cur = train.currentStationIndex
                    var nxt = train.nextStationIndex
                    var fwd = train.forward

                    if (nextProg >= 1f) {
                        nextProg = 0f
                        cur = nxt
                        if (fwd) {
                            if (cur + 1 <= maxIdx) {
                                nxt = cur + 1
                            } else {
                                fwd = false
                                nxt = cur - 1
                            }
                        } else {
                            if (cur - 1 >= 0) {
                                nxt = cur - 1
                            } else {
                                fwd = true
                                nxt = cur + 1
                            }
                        }
                    }
                    train.copy(
                        currentStationIndex = cur,
                        nextStationIndex = nxt,
                        forward = fwd,
                        progress = nextProg
                    )
                }
            }
        }
    }
}
