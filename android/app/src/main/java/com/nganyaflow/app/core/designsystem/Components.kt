package com.nganyaflow.app.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nganyaflow.app.R
import java.text.DecimalFormat

fun waitNumber(value: Double): String = DecimalFormat("0.#").format(value)

@Composable
fun RouteBadge(number: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier, color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(14.dp),
    ) {
        Text(number, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoChip(label: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    val status = LocalStatusColors.current
    Surface(
        modifier = modifier, shape = RoundedCornerShape(50),
        color = if (warning) status.warningContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (warning) status.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun DemoDataChip(modifier: Modifier = Modifier) = InfoChip(stringResource(R.string.demo_data), modifier, true)

@Composable
fun FreshnessChip(label: String, modifier: Modifier = Modifier) = InfoChip(label, modifier)

@Composable
fun CrowdingChip(occupancy: String, modifier: Modifier = Modifier) {
    val label = when (occupancy) {
        "empty" -> R.string.empty
        "seats_available" -> R.string.seats_available
        "standing" -> R.string.standing
        "full" -> R.string.full
        else -> R.string.unknown
    }
    InfoChip(stringResource(label), modifier, occupancy == "full" || occupancy == "standing")
}

@Composable
fun RangeBar(lower: Double, median: Double, upper: Double, modifier: Modifier = Modifier) {
    val band = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val description = formatWaitRange(lower, upper, language = LocalConfiguration.current.locales[0].language)
    Canvas(modifier.fillMaxWidth().height(28.dp).clearAndSetSemantics { contentDescription = description }) {
        val y = size.height / 2
        val start = 8.dp.toPx()
        val width = size.width - start * 2
        val maximum = maxOf(20.0, upper)
        fun position(value: Double) = start + ((value.coerceIn(0.0, maximum) / maximum).toFloat() * width)
        drawLine(track, Offset(start, y), Offset(size.width - start, y), 6.dp.toPx(), StrokeCap.Round)
        drawLine(band, Offset(position(lower), y), Offset(position(upper), y), 10.dp.toPx(), StrokeCap.Round)
        drawLine(band, Offset(position(median), 2.dp.toPx()), Offset(position(median), size.height - 2.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
fun RangeHero(lower: Double, median: Double, upper: Double, modifier: Modifier = Modifier) {
    val low by animateFloatAsState(lower.toFloat(), label = "lower wait")
    val high by animateFloatAsState(upper.toFloat(), label = "upper wait")
    Column(modifier) {
        Text(stringResource(R.string.next_vehicle), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.animateContentSize(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatWaitRange(lower, upper, language = LocalConfiguration.current.locales[0].language),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 48.sp),
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        RangeBar(low.toDouble(), median, high.toDouble())
    }
}

enum class NoticeKind { Info, Warning, Error, Success }

@Composable
fun NoticeCard(title: String, message: String, modifier: Modifier = Modifier, kind: NoticeKind = NoticeKind.Info) {
    val status = LocalStatusColors.current
    val (container, onContainer) = when (kind) {
        NoticeKind.Info -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        NoticeKind.Warning -> status.warningContainer to status.onWarningContainer
        NoticeKind.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        NoticeKind.Success -> status.successContainer to status.onSuccessContainer
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container, contentColor = onContainer) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) { Text(text) }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun ErrorState(title: String, message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NoticeCard(title, message, kind = NoticeKind.Error)
        SecondaryButton(stringResource(R.string.retry), onRetry)
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(32.dp))
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.loading_detail), style = MaterialTheme.typography.bodyMedium)
        repeat(3) { SkeletonCard() }
    }
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth(0.45f).height(16.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)))
        Box(Modifier.fillMaxWidth(0.7f).height(32.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)))
    }
}

@Composable
fun RouteCard(
    number: String, name: String, corridor: String, modifier: Modifier = Modifier,
    lower: Double? = null, median: Double? = null, upper: Double? = null,
    unavailableReason: String? = null, freshness: String? = null, occupancy: String = "unknown",
    demo: Boolean = false, onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RouteBadge(number)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(corridor, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (lower != null && median != null && upper != null) RangeHero(lower, median, upper)
            else if (unavailableReason != null) Text(unavailableReason, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (freshness != null) FreshnessChip(freshness)
                if (lower != null) CrowdingChip(occupancy)
                if (demo) DemoDataChip()
            }
        }
    }
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    if (onClick != null) Card(onClick, modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = colors) { content() }
    else Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = colors) { content() }
}

@Preview(name = "Shared components light", showBackground = true, widthDp = 412)
@Composable
private fun ComponentsLightPreview() = NganyaFlowTheme(darkTheme = false) { ComponentGallery() }

@Preview(name = "Shared components dark", showBackground = true, widthDp = 412)
@Composable
private fun ComponentsDarkPreview() = NganyaFlowTheme(darkTheme = true) { ComponentGallery() }

@Composable
private fun ComponentGallery() {
    Surface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RouteCard("237", "Thika Road", "Muthaiga · Townbound", lower = 4.0, median = 6.0, upper = 9.0,
                freshness = "2 min ago", occupancy = "standing", demo = true)
            RouteCard("17B", "Mwiki", "Thika Road", unavailableReason = "Not enough recent vehicles")
            NoticeCard("Practice data", "All arrival times shown here are simulated.", kind = NoticeKind.Warning)
            PrimaryButton("Choose a stage", {})
        }
    }
}
