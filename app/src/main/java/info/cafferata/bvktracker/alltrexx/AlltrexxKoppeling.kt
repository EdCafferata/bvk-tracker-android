package info.cafferata.bvktracker.alltrexx

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optioneel live meevaren op alltrexx.live (info-menu → "Live delen op Alltrexx.live").
 * Port van `AlltrexxKoppeling.swift` (iOS 2.3.0), zelfde backend-protocol als de Alltrexx Mobile-app:
 *   POST /api/sleutel/gratis   {naam, type: "BOAT", bron: "bvk"}  → {id, naam, type, token}
 *   POST /api/mobiel/positie   {token, lat, lon, snelheid, koers, hoogte}
 *
 * Er wordt alleen iets verstuurd als de gebruiker het zelf aanzet. De sleutel staat versleuteld
 * (AES-GCM, sleutel in de Android Keystore); na overzetten naar een ander toestel is hij daardoor
 * onleesbaar en geldt de app als ontkoppeld (de gebruiker koppelt dan opnieuw).
 */
object AlltrexxKoppeling {

    const val BASIS = "https://alltrexx.live"
    const val LIVE_KAART_URL = "https://alltrexx.live/?bron=bvk"

    /** Minimaal interval tussen twee posities (batterij en data op zee), gelijk aan iOS. */
    private const val INTERVAL_MS = 15_000L

    data class Status(val gekoppeld: Boolean = false, val naam: String? = null, val actief: Boolean = false)

    sealed class Fout(message: String) : Exception(message) {
        object TeVaak : Fout("Te veel aanvragen achter elkaar. Probeer het over een paar minuten opnieuw.")
        object NaamGeweigerd : Fout("Deze naam is niet toegestaan. Kies een andere naam.")
        object Server : Fout("Alltrexx.live is nu niet bereikbaar. Probeer het later opnieuw.")
    }

    private const val PREFS = "alltrexx"
    private const val K_TOKEN = "token-versleuteld"
    private const val K_ID = "tracker-id"
    private const val K_NAAM = "naam"
    private const val K_ACTIEF = "actief"
    private const val KEY_ALIAS = "alltrexx-token-sleutel"

    private var prefs: SharedPreferences? = null
    private var token: String? = null
    private var laatsteVerzonden = 0L
    private val netwerk = Executors.newSingleThreadExecutor()

    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()

    /** Veilig meerdere keren aan te roepen (Activity en Service). */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        token = p.getString(K_TOKEN, null)?.let { ontsleutel(it) }
        if (token == null && p.contains(K_TOKEN)) p.edit().clear().apply() // onleesbaar (ander toestel) → ontkoppeld
        publiceer()
    }

    private fun publiceer() {
        val p = prefs ?: return
        _status.value = Status(
            gekoppeld = token != null,
            naam = p.getString(K_NAAM, null),
            actief = token != null && p.getBoolean(K_ACTIEF, false),
        )
    }

    fun zetActief(aan: Boolean) {
        prefs?.edit()?.putBoolean(K_ACTIEF, aan)?.apply()
        publiceer()
    }

    /** Vraagt een gratis sleutel aan voor deze boot en zet live delen aan. */
    suspend fun koppel(naam: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("naam", naam).put("type", "BOAT").put("bron", "bvk").toString()
            val (code, antwoord) = post("$BASIS/api/sleutel/gratis", body)
            when {
                code == 429 -> throw Fout.TeVaak
                code == 400 -> throw Fout.NaamGeweigerd
                code !in 200..299 -> throw Fout.Server
            }
            val json = runCatching { JSONObject(antwoord) }.getOrNull() ?: throw Fout.Server
            val nieuwToken = json.optString("token").takeIf { it.isNotBlank() } ?: throw Fout.Server
            val versleuteld = versleutel(nieuwToken) ?: throw Fout.Server
            token = nieuwToken
            prefs?.edit()
                ?.putString(K_TOKEN, versleuteld)
                ?.putInt(K_ID, json.optInt("id", 0))
                ?.putString(K_NAAM, json.optString("naam").ifBlank { naam })
                ?.putBoolean(K_ACTIEF, true)
                ?.apply()
            publiceer()
        }.recoverCatching { e -> throw (e as? Fout ?: Fout.Server) }
    }

    /** Wist de sleutel lokaal (de tracker verloopt vanzelf op de server). */
    fun ontkoppel() {
        token = null
        prefs?.edit()?.clear()?.apply()
        publiceer()
    }

    /** Bij elke GPS-update aangeroepen; verstuurt hooguit elke 15 s, alleen als delen aan staat. */
    fun nieuwePositie(location: Location) {
        val t = token ?: return
        if (!_status.value.actief) return
        val nu = SystemClock.elapsedRealtime()
        if (laatsteVerzonden != 0L && nu - laatsteVerzonden < INTERVAL_MS) return
        laatsteVerzonden = nu
        val body = JSONObject().put("token", t).put("lat", location.latitude).put("lon", location.longitude)
        if (location.hasSpeed()) body.put("snelheid", location.speed.toDouble())
        if (location.hasBearing()) body.put("koers", location.bearing.toDouble())
        if (location.hasAltitude()) body.put("hoogte", location.altitude)
        netwerk.execute {
            val code = runCatching { post("$BASIS/api/mobiel/positie", body.toString()).first }.getOrNull()
            // 403 = sleutel verlopen/onbekend → ontkoppelen, gebruiker kan opnieuw koppelen.
            if (code == 403) ontkoppel()
        }
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "BVKTracker-Android")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val tekst = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return code to tekst
        } finally {
            conn.disconnect()
        }
    }

    // --- Versleuteling van de sleutel met de Android Keystore (AES-256-GCM) ---

    private fun sleutel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun versleutel(tekst: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, sleutel()) }
        val data = c.iv + c.doFinal(tekst.toByteArray())
        Base64.encodeToString(data, Base64.NO_WRAP)
    }.getOrNull()

    private fun ontsleutel(opgeslagen: String): String? = runCatching {
        val data = Base64.decode(opgeslagen, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, sleutel(), GCMParameterSpec(128, data, 0, 12))
        String(c.doFinal(data, 12, data.size - 12))
    }.getOrNull()
}
