package info.cafferata.bvktracker.store

import android.content.Context
import java.io.File

/**
 * Herstelkopie van de rit die nu wordt opgenomen (zelfde idee als de iOS-app 2.3.0).
 *
 * De opname staat verder alleen in het geheugen; sluit Android de app tijdens het varen af
 * (geheugen, accu, crash), dan zou de hele rit weg zijn. Tijdens het opnemen schrijven we daarom
 * regelmatig een GPX-kopie weg. Bij de volgende start zet de app die terug als gewoon bestand.
 *
 * De kopie staat in een submap, zodat hij niet in de lijst met opgeslagen tracks verschijnt.
 */
class RecoveryStore(context: Context) {

    private val dir = File(context.filesDir, "herstel")
    private val file = File(dir, "lopende-rit.gpx")

    val exists: Boolean get() = file.isFile && file.length() > 0

    /** Atomisch schrijven: eerst naar een tijdelijk bestand, dan hernoemen. Fouten zijn niet fataal. */
    fun write(gpx: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "lopende-rit.tmp")
            tmp.writeText(gpx)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun read(): String? = runCatching { file.readText() }.getOrNull()

    fun clear() {
        file.delete()
        File(dir, "lopende-rit.tmp").delete()
    }
}
