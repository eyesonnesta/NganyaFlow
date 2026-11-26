package com.nganyaflow.app.feature.rider

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.nganyaflow.app.core.designsystem.NganyaFlowTheme
import com.nganyaflow.app.core.model.AppSettings
import com.nganyaflow.app.core.model.Route
import com.nganyaflow.app.core.model.Stage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RiderUiTest {
    @get:Rule val compose = createComposeRule()
    private val state = RiderState(settings = AppSettings("http://10.0.2.2:8000/"),
        stages = listOf(Stage("muthaiga", "Muthaiga"), Stage("koja", "Koja")),
        routes = listOf(Route("237", "Thika to Koja", "237", "Thika Road", listOf("koja", "muthaiga"), listOf("townbound", "outbound"))))

    @Test fun directionSwitchChangesSelection() {
        var selected = "townbound"
        compose.setContent {
            var direction by remember { mutableStateOf("townbound") }
            NganyaFlowTheme { DirectionControl(direction, listOf("townbound", "outbound")) { direction = it; selected = it } }
        }
        compose.onNodeWithText("Away from town").performClick()
        compose.runOnIdle { assertEquals("outbound", selected) }
        compose.onNodeWithText("Away from town").assertIsSelected()
    }

    @Test fun routeSearchEmptyKeepsTheQuery() {
        compose.setContent { NganyaFlowTheme { RiderRoutes(state, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("no-such-route")
        compose.onNodeWithText("Nothing matches this search").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("no-such-route")
    }

    @Test fun stageSelectionReturnsTheChosenStage() {
        var selected = ""
        compose.setContent { NganyaFlowTheme { StagePicker(state, {}) { selected = it.id } } }
        compose.onNodeWithText("Koja").performClick()
        compose.runOnIdle { assertEquals("koja", selected) }
    }
}
