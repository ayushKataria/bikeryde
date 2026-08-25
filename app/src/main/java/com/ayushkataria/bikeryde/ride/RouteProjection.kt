package com.ayushkataria.bikeryde.ride

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Flattens a ride's raw lat/lng points onto a 2D drawing surface. Shared by [RouteMapView] (the
 * live in-app route) and the static-image/video renderers, so a ride's route looks the same shape
 * everywhere it's drawn.
 */
object RouteProjection {

    /** The lat/lng extent a set of points spans, plus the longitude scale factor to draw it undistorted. */
    data class GeoBounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
        val lngScale: Double = max(cos(Math.toRadians((minLat + maxLat) / 2.0)), 0.15)

        /** Width/height of the route's bounding box, in latitude-degree-equivalent units. */
        val geoWidth: Double = max((maxLng - minLng) * lngScale, 1e-6)
        val geoHeight: Double = max(maxLat - minLat, 1e-6)
    }

    fun geoBoundsOf(points: List<RidePoint>): GeoBounds {
        var minLat = points[0].lat
        var maxLat = points[0].lat
        var minLng = points[0].lng
        var maxLng = points[0].lng
        for (p in points) {
            minLat = minOf(minLat, p.lat)
            maxLat = maxOf(maxLat, p.lat)
            minLng = minOf(minLng, p.lng)
            maxLng = maxOf(maxLng, p.lng)
        }
        return GeoBounds(minLat, maxLat, minLng, maxLng)
    }

    /**
     * A [GeoBounds] fit into a screen rect: scaled uniformly (so the route isn't distorted) by
     * whichever axis is tighter, then centered on the other axis — a route much wider than it is
     * tall (or vice versa) lands in the middle of the rect instead of being squashed against one
     * edge with a large empty gap on the other side. Compute once per draw and reuse for every
     * point drawn together (route path + markers) so they stay in the same projected space.
     */
    class Viewport(private val geoBounds: GeoBounds, private val screenBounds: RectF) {
        private val scale = min(
            screenBounds.width() / geoBounds.geoWidth,
            screenBounds.height() / geoBounds.geoHeight
        )
        private val marginX = (screenBounds.width() - geoBounds.geoWidth * scale) / 2.0
        private val marginY = (screenBounds.height() - geoBounds.geoHeight * scale) / 2.0

        fun project(point: RidePoint): PointF {
            val x = screenBounds.left + marginX + (point.lng - geoBounds.minLng) * geoBounds.lngScale * scale
            // Screen y grows downward; latitude grows northward, so flip.
            val y = screenBounds.bottom - marginY - (point.lat - geoBounds.minLat) * scale
            return PointF(x.toFloat(), y.toFloat())
        }
    }

    fun viewportFor(points: List<RidePoint>, screenBounds: RectF): Viewport =
        Viewport(geoBoundsOf(points), screenBounds)

    /** Projects [points] into [screenBounds] — see [Viewport]. */
    fun project(points: List<RidePoint>, screenBounds: RectF): List<PointF> {
        val viewport = viewportFor(points, screenBounds)
        return points.map { viewport.project(it) }
    }

    /**
     * Turns the raw, sometimes-sparse GPS fixes into a smooth curve (Catmull-Rom spline,
     * expressed as cubic Beziers) instead of a jagged point-to-point line — the shape a hand
     * would draw tracing the same points, closer to the road actually ridden.
     */
    fun smoothedPath(pts: List<PointF>): Path {
        val path = Path()
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 2) {
            path.lineTo(pts[1].x, pts[1].y)
            return path
        }
        for (i in 0 until pts.size - 1) {
            val p0 = pts[if (i == 0) i else i - 1]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[if (i + 2 < pts.size) i + 2 else i + 1]

            val c1x = p1.x + (p2.x - p0.x) / 6f
            val c1y = p1.y + (p2.y - p0.y) / 6f
            val c2x = p2.x - (p3.x - p1.x) / 6f
            val c2y = p2.y - (p3.y - p1.y) / 6f

            path.cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
        }
        return path
    }
}
