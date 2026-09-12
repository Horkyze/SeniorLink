package family.seniorlink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import family.seniorlink.R

internal object Calm {
    val Background = Color(0xFFF6F8F4)
    val Ink = Color(0xFF1D3329)
    val Muted = Color(0xFF586D61)
    val Green = Color(0xFF226953)
    val Soft = Color(0xFFE4EEE5)
    val Line = Color(0xFFD9E4DC)
    val Heart = Color(0xFFFFF0F0)
    val Chart = Color(0xFFB4495D)
    val Low = Color(0xFF9C5226)
    val LowBackground = Color(0xFFFFF0DF)
}

@Composable
internal fun CalmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Calm.Green, onPrimary = Color.White,
            primaryContainer = Calm.Soft, onPrimaryContainer = Calm.Ink,
            secondary = Calm.Green, onSecondary = Color.White,
            secondaryContainer = Calm.Soft, onSecondaryContainer = Calm.Ink,
            tertiary = Calm.Chart, onTertiary = Color.White,
            background = Calm.Background, onBackground = Calm.Ink,
            surface = Color.White, onSurface = Calm.Ink,
            surfaceVariant = Calm.Soft, onSurfaceVariant = Calm.Muted,
            surfaceContainer = Color.White, surfaceContainerLow = Color.White,
            surfaceContainerHigh = Calm.Soft, surfaceContainerHighest = Calm.Soft,
            outline = Calm.Muted, outlineVariant = Calm.Line,
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}

@Composable
internal fun CalmAppBar(initial: String? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_heart_outline), null, Modifier.size(25.dp), tint = Calm.Green)
        Text("SeniorLink", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Calm.Ink)
        if (!initial.isNullOrBlank()) Surface(shape = CircleShape, color = Calm.Soft, contentColor = Calm.Green) {
            Box(Modifier.sizeIn(minWidth = 36.dp, minHeight = 36.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                Text(initial, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private data class Destination(val id: Int, val label: String, val icon: Int)
private val destinations = listOf(
    Destination(0, "Updates", R.drawable.ic_home_outline),
    Destination(1, "Location", R.drawable.ic_location_outline),
    Destination(4, "Wearable", R.drawable.ic_watch_outline),
    Destination(2, "Phones", R.drawable.ic_people_outline),
    Destination(3, "Settings", R.drawable.ic_settings_outline),
)

@Composable
internal fun CalmNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    Surface(color = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = Calm.Line)
            BoxWithConstraints {
                val columns = if (largeText || maxWidth < 320.dp) 3 else 5
                Column {
                    destinations.chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { destination ->
                                val active = selected == destination.id
                                val color = if (active) Calm.Green else Calm.Muted
                                Column(
                                    Modifier.weight(1f).testTag("navigation-${destination.id}")
                                        .selectable(active, role = Role.Tab, onClick = { onSelect(destination.id) })
                                        .heightIn(min = 64.dp).padding(horizontal = 3.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(painterResource(destination.icon), null, Modifier.size(21.dp), tint = color)
                                    Text(destination.label, color = color, fontSize = if (largeText) 12.sp else 11.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
