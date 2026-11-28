package info.cafferata.bvktracker.store

import android.content.Context
import info.cafferata.bvktracker.model.GpxFileInfo
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/** Saves, lists, and deletes recorded `.gpx` files in the app's private storage. */
class GpxFileRepository(context: Context) {

    private val tracksDir: File = context.filesDir

    fun listFiles(): List<GpxFileInfo> =
        tracksDir.listFiles { f -> f.isFile && f.extension.equals("gpx", ignoreCase = true) }
            ?.map { GpxFileInfo(it) }
            ?.sortedByDescending { it.modifiedDate }
            ?: emptyList()

    /**
     * Slaat een rit op. Overschrijft nooit een bestaand bestand (twee ritten in dezelfde minuut
     * krijgen `_2`, `_3`, ...) en schrijft eerst naar een tijdelijk bestand, zodat een halve
     * schrijfactie geen kapot GPX-bestand achterlaat. Gooit [IOException] als het mislukt: de
     * aanroeper mag de rit dan NIET wissen.
     */
    @Throws(IOException::class)
    fun save(gpxContents: String, suggestedName: String? = null): File {
        val base = suggestedName ?: defaultFilename()
        var file = File(tracksDir, "$base.gpx")
        var n = 2
        while (file.exists()) file = File(tracksDir, "${base}_${n++}.gpx")
        val tmp = File(tracksDir, "${file.name}.tmp")
        tmp.writeText(gpxContents)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Kon ${file.name} niet opslaan")
        }
        return file
    }

    fun delete(info: GpxFileInfo) {
        info.file.delete()
    }

    fun defaultFilename(): String {
        // Locale.ROOT: altijd westerse cijfers, ook als het toestel bv. op Arabisch staat.
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT)
        return "BVK_${fmt.format(java.util.Date())}"
    }
}
