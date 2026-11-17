package com.nganyaflow.app.feature.rider

import com.nganyaflow.app.core.model.Route
import com.nganyaflow.app.core.model.Stage
import org.junit.Assert.*
import org.junit.Test

class RiderLogicTest {
    private val stages = listOf(Stage("town", "Koja", -1.283, 36.821), Stage("mid", "Muthaiga", -1.254, 36.846), Stage("far", "Thika"))
    private val route = Route("237-thika", "Thika to Koja", "237", "Thika Road", stages.map { it.id }, listOf("townbound", "outbound"), "digital-matatus-2019")

    @Test fun searchMatchesStageNumberCorridorAndNameAndKeepsNoMatchEmpty() {
        listOf("muthaiga", "237", "thika road", "KOJA", " 237 ", "").forEach { assertTrue(routeMatches(route, it, stages)) }
        assertFalse(routeMatches(route, "does not exist", stages))
    }

    @Test fun terminiOnlyOfferBoardableDirections() {
        assertEquals(listOf("outbound"), route.servedDirections("town"))
        assertEquals(listOf("townbound"), route.servedDirections("far"))
        assertEquals(listOf("townbound", "outbound"), route.servedDirections("mid"))
    }

    @Test fun nearestStageUsesOnlyLocalCoordinatesAndMissingCoordinatesStayUnknown() {
        assertEquals(0.0, distanceKm(-1.254, 36.846, stages[1])!!, .0001)
        assertTrue(distanceKm(-1.254, 36.846, stages[0])!! > 1)
        assertNull(distanceKm(-1.254, 36.846, stages[2]))
    }

    @Test fun evidenceTimeDisplaysNairobi() {
        assertTrue(nairobiTime("2026-11-17T07:00:00Z").contains("10:00:00"))
    }
}
