package info.cafferata.bvktracker.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Weer en waterstand voor het Markermeer. Port van `WeatherService.swift` uit de iOS-app (2.3.0):
 * één Open-Meteo-verzoek voor wind/temperatuur en de laatste échte waterstandmeting van
 * Rijkswaterstaat (Almere Hollandse Brug).
 *
 * Privacy: anders dan op iOS vragen we het weer op voor een VAST punt op het Markermeer en niet
 * voor de GPS-positie. Zo verlaat de locatie van de gebruiker het toestel nooit.
 * Waarden die een bron niet levert blijven `null` (op het scherm "—", nooit 0).
 */
object WeatherService {

    /** Markermeer, vaargebied van de Blocq van Kuffeler. */
    const val LAT = 52.4170
    const val LON = 5.2175

    data class Weather(
        val windKn: Double? = null,
        val windDirection: Double? = null,
        val gustKn: Double? = null,
        val temperature: Double? = null,
        val waterLevelCm: Double? = null,
    )

    suspend fun fetch(): Weather = withContext(Dispatchers.IO) {
        val current = runCatching { parseCurrent(JSONObject(get(openMeteoUrl()))) }.getOrNull()
        val water = runCatching { parseWaterLevel(JSONObject(postWaterLevel())) }.getOrNull()
        (current ?: Weather()).copy(waterLevelCm = water)
    }

    private fun openMeteoUrl() = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
        "&current=wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m" +
        "&wind_speed_unit=kn&timezone=Europe%2FAmsterdam"

    fun parseCurrent(json: JSONObject): Weather? {
        val cur = json.optJSONObject("current") ?: return null
        fun d(key: String): Double? = if (cur.has(key) && !cur.isNull(key)) cur.optDouble(key).takeUnless { it.isNaN() } else null
        return Weather(
            windKn = d("wind_speed_10m"),
            windDirection = d("wind_direction_10m"),
            gustKn = d("wind_gusts_10m"),
            temperature = d("temperature_2m"),
        )
    }

    private fun postWaterLevel(): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = Date()
        val body = JSONObject()
            .put("Locatie", JSONObject().put("Code", "almere.hollandsebrug")) // dichtstbijzijnd werkend meetpunt Markermeer
            .put("AquoPlusWaarnemingMetadata", JSONObject().put("AquoMetadata", JSONObject()
                .put("Compartiment", JSONObject().put("Code", "OW"))
                .put("Grootheid", JSONObject().put("Code", "WATHTE"))))
            .put("Periode", JSONObject()
                .put("Begindatumtijd", iso.format(Date(now.time - 30 * 60 * 1000)))
                .put("Einddatumtijd", iso.format(now)))
        return post(
            "https://ddapi20-waterwebservices.rijkswaterstaat.nl/ONLINEWAARNEMINGENSERVICES/OphalenWaarnemingen",
            body.toString(),
        )
    }

    /**
     * De DDAPI levert meerdere reeksen (meting, verwachting, astronomisch getij). Neem de laatste
     * échte meting; is die er niet, dan de verwachting. Nooit blind de eerste reeks.
     */
    fun parseWaterLevel(json: JSONObject): Double? {
        val series = json.optJSONArray("WaarnemingenLijst") ?: JSONArray()
        fun last(procesType: String): Double? {
            for (i in 0 until series.length()) {
                val s = series.optJSONObject(i) ?: continue
                if (s.optJSONObject("AquoMetadata")?.optString("ProcesType") != procesType) continue
                val list = s.optJSONArray("MetingenLijst") ?: return null
                if (list.length() == 0) return null
                val m = list.optJSONObject(list.length() - 1)?.optJSONObject("Meetwaarde") ?: return null
                return if (m.has("Waarde_Numeriek") && !m.isNull("Waarde_Numeriek")) m.optDouble("Waarde_Numeriek") else null
            }
            return null
        }
        return last("meting") ?: last("verwachting")
    }

    /** Knopen naar Beaufort (WMO-grenzen). */
    fun beaufort(knots: Double): Int {
        val limits = doubleArrayOf(1.0, 4.0, 7.0, 11.0, 17.0, 22.0, 28.0, 34.0, 41.0, 48.0, 56.0, 64.0)
        return limits.indexOfFirst { knots < it }.let { if (it < 0) 12 else it }
    }

    /** Pijl in de richting waarheen de wind waait (windrichting = vanwaar hij komt). */
    fun windArrow(degrees: Double): String {
        val arrows = arrayOf("↓", "↙", "←", "↖", "↑", "↗", "→", "↘")
        val norm = ((degrees % 360) + 360) % 360
        return arrows[((norm + 22.5) / 45.0).toInt() % 8]
    }

    private fun get(url: String): String = request(url, "GET", null)
    private fun post(url: String, body: String): String = request(url, "POST", body)

    private fun request(url: String, method: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "BVKTracker-Android")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-API-KEY", "BVKTracker")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
