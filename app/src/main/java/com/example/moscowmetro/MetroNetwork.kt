package com.example.moscowmetro

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers

data class MetroCityResponse(
    val id: String,
    val name: String,
    val lines: List<MetroLine>
)

data class MetroLine(
    val id: String,
    val name: String,
    @SerializedName("hex_color") val hexColor: String,
    val stations: List<MetroStation>
)

data class MetroStation(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val order: Int,
    var lineId: String = "",
    var lineName: String = "",
    var lineColor: String = "EF161E"
)

data class LiveTrain(
    val id: String,
    val trainNumber: String,
    val lineId: String,
    val lineName: String,
    val lineColor: String,
    val currentStationIndex: Int,
    val nextStationIndex: Int,
    val forward: Boolean,
    val progress: Float // 0.0 -> 1.0 между станциями
) {
    fun getEtaSecondsToStation(stationOrder: Int): Int? {
        if (forward && stationOrder >= nextStationIndex) {
            val hops = stationOrder - nextStationIndex
            val currentLegSec = ((1f - progress) * 120).toInt()
            return currentLegSec + hops * 150
        } else if (!forward && stationOrder <= nextStationIndex) {
            val hops = nextStationIndex - stationOrder
            val currentLegSec = ((1f - progress) * 120).toInt()
            return currentLegSec + hops * 150
        }
        return null
    }
}

interface MetroApiService {
    // API hh.ru обязательно требует заголовок User-Agent, иначе возможна ошибка 403 Forbidden
    @Headers("User-Agent: MoscowMetroLiveAndroid/1.1 (contact@example.com)")
    @GET("metro/1")
    suspend fun getMoscowMetro(): MetroCityResponse

    companion object {
        fun create(): MetroApiService {
            return Retrofit.Builder()
                .baseUrl("https://api.hh.ru/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MetroApiService::class.java)
        }
    }
}

object FallbackMetroData {
    // Резервный набор центральных станций на случай отсутствия интернета
    fun getFallbackLines(): List<MetroLine> = listOf(
        MetroLine(
            id = "1",
            name = "Сокольническая",
            hexColor = "EF161E",
            stations = listOf(
                MetroStation("1_1", "Парк культуры", 55.7352, 37.5930, 0, "1", "Сокольническая", "EF161E"),
                MetroStation("1_2", "Кропоткинская", 55.7451, 37.6037, 1, "1", "Сокольническая", "EF161E"),
                MetroStation("1_3", "Библиотека им. Ленина", 55.7522, 37.6103, 2, "1", "Сокольническая", "EF161E"),
                MetroStation("1_4", "Охотный Ряд", 55.7570, 37.6161, 3, "1", "Сокольническая", "EF161E"),
                MetroStation("1_5", "Лубянка", 55.7597, 37.6253, 4, "1", "Сокольническая", "EF161E"),
                MetroStation("1_6", "Чистые пруды", 55.7649, 37.6388, 5, "1", "Сокольническая", "EF161E")
            )
        ),
        MetroLine(
            id = "5",
            name = "Кольцевая",
            hexColor = "8D5B2D",
            stations = listOf(
                MetroStation("5_1", "Парк культуры", 55.7357, 37.5915, 0, "5", "Кольцевая", "8D5B2D"),
                MetroStation("5_2", "Октябрьская", 55.7290, 37.6113, 1, "5", "Кольцевая", "8D5B2D"),
                MetroStation("5_3", "Добрынинская", 55.7289, 37.6225, 2, "5", "Кольцевая", "8D5B2D"),
                MetroStation("5_4", "Павелецкая", 55.7314, 37.6361, 3, "5", "Кольцевая", "8D5B2D"),
                MetroStation("5_5", "Таганская", 55.7423, 37.6533, 4, "5", "Кольцевая", "8D5B2D"),
                MetroStation("5_6", "Курская", 55.7585, 37.6598, 5, "5", "Кольцевая", "8D5B2D")
            )
        ),
        MetroLine(
            id = "6",
            name = "Калужско-Рижская",
            hexColor = "ED9121",
            stations = listOf(
                MetroStation("6_1", "Шаболовская", 55.7188, 37.6077, 0, "6", "Калужско-Рижская", "ED9121"),
                MetroStation("6_2", "Октябрьская", 55.7312, 37.6128, 1, "6", "Калужско-Рижская", "ED9121"),
                MetroStation("6_3", "Третьяковская", 55.7407, 37.6256, 2, "6", "Калужско-Рижская", "ED9121"),
                MetroStation("6_4", "Китай-город", 55.7543, 37.6338, 3, "6", "Калужско-Рижская", "ED9121"),
                MetroStation("6_5", "Тургеневская", 55.7645, 37.6366, 4, "6", "Калужско-Рижская", "ED9121")
            )
        )
    )
}
