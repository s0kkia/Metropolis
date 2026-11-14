package com.example.moscowmetro

import java.util.PriorityQueue
import kotlin.math.*

data class RouteStep(
    val station: MetroStation,
    val isTransfer: Boolean,
    val cumulativeMinutes: Int
)

data class RouteResult(
    val steps: List<RouteStep>,
    val totalMinutes: Int,
    val transfersCount: Int
)

object MetroRouteFinder {

    private data class Edge(val toStationId: String, val weightMinutes: Int, val isTransfer: Boolean)

    fun findShortestRoute(
        lines: List<MetroLine>,
        startStationId: String,
        endStationId: String
    ): RouteResult? {
        if (startStationId == endStationId) return null

        val stationMap = mutableMapOf<String, MetroStation>()
        val adj = mutableMapOf<String, MutableList<Edge>>()

        // 1. Заполняем граф перегонами внутри каждой ветки (3 минуты на перегон)
        for (line in lines) {
            val sorted = line.stations.sortedBy { it.order }
            for (st in sorted) {
                val enriched = st.copy(
                    lineId = line.id,
                    lineName = line.name,
                    lineColor = line.hexColor
                )
                stationMap[enriched.id] = enriched
                if (!adj.containsKey(enriched.id)) {
                    adj[enriched.id] = mutableListOf()
                }
            }
            for (i in 0 until sorted.size - 1) {
                val u = sorted[i].id
                val v = sorted[i + 1].id
                adj[u]?.add(Edge(v, 3, false))
                adj[v]?.add(Edge(u, 3, false))
            }
        }

        // 2. Автоматически находим пересадочные узлы (одинаковое имя станции или расстояние < 350 м)
        val allStations = stationMap.values.toList()
        for (i in allStations.indices) {
            for (j in i + 1 until allStations.size) {
                val s1 = allStations[i]
                val s2 = allStations[j]
                if (s1.lineId != s2.lineId) {
                    val sameName = s1.name.trim().equals(s2.name.trim(), ignoreCase = true)
                    val distMeters = haversineMeters(s1.lat, s1.lng, s2.lat, s2.lng)
                    if (sameName || distMeters < 350.0) {
                        adj[s1.id]?.add(Edge(s2.id, 5, true))
                        adj[s2.id]?.add(Edge(s1.id, 5, true))
                    }
                }
            }
        }

        // 3. Алгоритм Дейкстры
        val dist = mutableMapOf<String, Int>()
        val prev = mutableMapOf<String, Pair<String, Boolean>>()
        val pq = PriorityQueue<Pair<String, Int>>(16, compareBy { it.second })

        dist[startStationId] = 0
        pq.add(startStationId to 0)

        while (pq.isNotEmpty()) {
            val current = pq.poll() ?: break
            val u = current.first
            val d = current.second

            val bestKnown = dist[u] ?: Int.MAX_VALUE
            if (d > bestKnown) continue
            if (u == endStationId) break

            for (edge in adj[u].orEmpty()) {
                val newDist = d + edge.weightMinutes
                val targetBest = dist[edge.toStationId] ?: Int.MAX_VALUE
                if (newDist < targetBest) {
                    dist[edge.toStationId] = newDist
                    prev[edge.toStationId] = u to edge.isTransfer
                    pq.add(edge.toStationId to newDist)
                }
            }
        }

        val totalMin = dist[endStationId] ?: return null

        // Восстановление пути
        val steps = mutableListOf<RouteStep>()
        var curr: String? = endStationId
        var transfers = 0

        while (curr != null) {
            val st = stationMap[curr] ?: break
            val prevInfo = prev[curr]
            val isTransfer = prevInfo?.second ?: false
            if (isTransfer) transfers++
            steps.add(RouteStep(st, isTransfer, dist[curr] ?: 0))
            curr = prevInfo?.first
        }
        steps.reverse()

        return RouteResult(
            steps = steps,
            totalMinutes = totalMin,
            transfersCount = transfers
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
