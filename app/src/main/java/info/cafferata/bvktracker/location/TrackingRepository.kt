package info.cafferata.bvktracker.location

import android.content.Context
import android.location.Location
import android.os.SystemClock
import info.cafferata.bvktracker.alltrexx.AlltrexxKoppeling
import info.cafferata.bvktracker.gpx.GpxPoint
import info.cafferata.bvktracker.gpx.GpxSession
import info.cafferata.bvktracker.store.RecoveryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the recording session, shared between [TrackingService] (which
 * feeds it GPS updates) and the UI (which observes [state]). Same single-process ownership
 * model as the iOS app's `GPXMapView.session`, just without a view to hang it off of.
 *
 * While recording, a recovery copy is written every [RECOVERY_INTERVAL_MS] (see [RecoveryStore]),
 * so a ride survives Android killing the app.
 */
object TrackingRepository {

    private const val RECOVERY_INTERVAL_MS = 15_000L

    val session = GpxSession()

    private val _state = MutableStateFlow(TrackingState())
    val state = _state.asStateFlow()

    private var recovery: RecoveryStore? = null
    private var lastRecoveryWrite = 0L

    /** Called from the Activity and the Service; safe to call more than once. */
    fun init(context: Context) {
        if (recovery == null) recovery = RecoveryStore(context.applicationContext)
        AlltrexxKoppeling.init(context)
    }

    fun start() {
        _state.value = _state.value.copy(isTracking = true)
    }

    fun stop() {
        session.startNewTrackSegment()
        _state.value = _state.value.copy(isTracking = false)
        writeRecovery()
    }

    /** Called by [TrackingService] on every GPS fix — always updates the live position, and
     * additionally records the point into the session while tracking is on. */
    fun onLocation(location: Location) {
        val point = GpxPoint(location.latitude, location.longitude, location.altitude)
        // Live meevaren (alleen als de gebruiker het aanzette); net als iOS los van het opnemen.
        AlltrexxKoppeling.nieuwePositie(location)
        if (_state.value.isTracking) {
            session.addPointToCurrentSegment(location)
            val now = SystemClock.elapsedRealtime()
            if (now - lastRecoveryWrite >= RECOVERY_INTERVAL_MS) {
                lastRecoveryWrite = now
                writeRecovery()
            }
        }
        _state.value = _state.value.copy(
            currentLocation = point,
            trackPoints = session.currentSegment.toList(),
            totalDistanceMeters = session.totalTrackedDistance,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
        )
    }

    private fun hasPoints() = session.currentSegment.isNotEmpty() || session.trackSegments.isNotEmpty()

    private fun writeRecovery() {
        if (hasPoints()) recovery?.write(session.exportToGpxString())
    }

    /** Only call after the ride was saved successfully (or deliberately discarded). */
    fun reset() {
        session.reset()
        recovery?.clear()
        _state.value = TrackingState()
    }
}
