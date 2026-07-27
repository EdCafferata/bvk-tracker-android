package info.cafferata.bvktracker.location

import info.cafferata.bvktracker.gpx.GpxPoint

data class TrackingState(
    val isTracking: Boolean = false,
    val currentLocation: GpxPoint? = null,
    val trackPoints: List<GpxPoint> = emptyList(),
    val totalDistanceMeters: Double = 0.0,
    val speedMetersPerSecond: Float = 0f,
)
