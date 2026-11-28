package info.cafferata.bvktracker.model

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class GpxFileInfo(val file: File) {
    val name: String get() = file.nameWithoutExtension

    /**
     * Datum van de rit. Komt bij voorkeur uit de bestandsnaam (`BVK_2026-11-28_16-07`): na een
     * back-up/herstel of overzetten naar een nieuw toestel klopt de bestandstijd niet meer
     * (Android zet die dan op 1970). Valt terug op de bestandstijd voor andere namen.
     */
    val modifiedDate: Long
        get() = DATE_IN_NAME.find(name)?.groupValues?.get(1)?.let { stamp ->
            runCatching { SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).parse(stamp)?.time }.getOrNull()
        } ?: file.lastModified()

    val fileSize: Long get() = file.length()

    private companion object {
        val DATE_IN_NAME = Regex("""(\d{4}-\d{2}-\d{2}_\d{2}-\d{2})""")
    }
}
