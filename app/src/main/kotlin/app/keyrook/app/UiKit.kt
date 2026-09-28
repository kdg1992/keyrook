// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Opacity of secondary text; unlike the Material medium emphasis (60 %) it keeps AA contrast on the brand colours. */
internal const val HINT_TEXT_ALPHA = 0.72f

/** Padding of the small buttons inside rows and cards. */
internal val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/**
 * Line icons drawn for this project (no icon library is bundled). They are decorative: the button that shows one always
 * has a visible or spoken text, so the icons carry no content description.
 */
internal object KeyrookIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
        ).build()

    private fun PathBuilder.circle(x: Float, y: Float, radius: Float) {
        moveTo(x - radius, y)
        arcToRelative(radius, radius, 0f, true, true, 2 * radius, 0f)
        arcToRelative(radius, radius, 0f, true, true, -2 * radius, 0f)
        close()
    }

    val Search = icon("search") { circle(10.5f, 10.5f, 6.5f); moveTo(15.5f, 15.5f); lineTo(20f, 20f) }
    val Add = icon("add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Lock = icon("lock") {
        moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(8.5f, 11f); lineTo(8.5f, 8f)
        arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f)
        lineTo(15.5f, 11f)
    }
    val More = icon("more") { circle(12f, 5.5f, 0.6f); circle(12f, 12f, 0.6f); circle(12f, 18.5f, 0.6f) }
    val ChevronDown = icon("chevron") { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) }
    val Close = icon("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Settings = icon("settings") {
        circle(12f, 12f, 3f)
        moveTo(12f, 3f); lineTo(12f, 6f); moveTo(12f, 18f); lineTo(12f, 21f)
        moveTo(3f, 12f); lineTo(6f, 12f); moveTo(18f, 12f); lineTo(21f, 12f)
        moveTo(5.6f, 5.6f); lineTo(7.8f, 7.8f); moveTo(16.2f, 16.2f); lineTo(18.4f, 18.4f)
        moveTo(5.6f, 18.4f); lineTo(7.8f, 16.2f); moveTo(16.2f, 7.8f); lineTo(18.4f, 5.6f)
    }
    val Info = icon("info") { circle(12f, 12f, 9f); moveTo(12f, 11f); lineTo(12f, 16.5f); moveTo(12f, 7.6f); lineTo(12f, 7.7f) }
    val Theme = icon("theme") {
        moveTo(20f, 14.5f)
        arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f)
        arcTo(6.5f, 6.5f, 0f, false, false, 20f, 14.5f)
        close()
    }
}

/** An icon sized for buttons, tinted with the button's content colour. */
@Composable
internal fun ButtonIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}

/** A titled section on the surface colour with a subtle outline; the title is a heading for screen readers. */
@Composable
internal fun SectionCard(title: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {},
                         content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, LocalChrome.current.outline),
        color = MaterialTheme.colors.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(title, Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(UiText.locale), modifier.semantics { heading() }, style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.primary)
}

/** Secondary text: captions, hints and metadata. */
@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onSurface.copy(alpha = HINT_TEXT_ALPHA))
}

internal enum class BannerKind { ERROR, WARNING, INFO }

/** A status message across the content width; announced politely by screen readers. */
@Composable
internal fun Banner(text: String, kind: BannerKind, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null,
                    actions: @Composable RowScope.() -> Unit = {}) {
    val colors = MaterialTheme.colors
    val accent = when (kind) { BannerKind.ERROR -> colors.error; BannerKind.WARNING -> colors.secondary; BannerKind.INFO -> colors.primary }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, accent),
        color = accent.copy(alpha = 0.08f).compositeOver(MaterialTheme.colors.surface)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Text(text, Modifier.weight(1f).padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (kind == BannerKind.ERROR) colors.error else colors.onSurface)
            actions()
            if (onClose != null) TextButton(onClick = onClose, contentPadding = CompactButtonPadding) { Text(UiText.text("shell.close")) }
        }
    }
}

/** Colour of an entry type's avatar; high contrast uses the accent for all types. White text reaches 4.5:1 on each. */
private val typeColors = mapOf(
    EntryType.WEB to 0xFF1D4E6B, EntryType.TRANSFER to 0xFF2F6A4F, EntryType.EMAIL to 0xFF7A3B2E,
    EntryType.PANEL to 0xFF5B3F7A, EntryType.SERVER to 0xFF36505F, EntryType.SSH to 0xFF6B4A12,
    EntryType.DOMAIN to 0xFF1F5E6E, EntryType.CUSTOM to 0xFF4A5563,
)

internal fun typeAvatarColor(type: EntryType): Long = typeColors.getValue(type)

private fun typeInitials(type: EntryType): String = when (type) {
    EntryType.WEB -> "WWW"; EntryType.TRANSFER -> "FTP"; EntryType.EMAIL -> "@"; EntryType.PANEL -> "CP"
    EntryType.SERVER -> "SRV"; EntryType.SSH -> "SSH"; EntryType.DOMAIN -> "DNS"; EntryType.CUSTOM -> "···"
}

/** A small coloured square naming the entry type; decorative because the type is also written as text beside it. */
@Composable
internal fun TypeAvatar(type: EntryType, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val highContrast = LocalChrome.current.highContrast
    val fill = if (highContrast) MaterialTheme.colors.primary else Color(typeAvatarColor(type))
    val content = if (highContrast) MaterialTheme.colors.onPrimary else Color.White
    Surface(Modifier.size(size).clearAndSetSemantics {}, shape = MaterialTheme.shapes.small, color = fill, contentColor = content) {
        Box(contentAlignment = Alignment.Center) {
            Text(typeInitials(type), fontSize = if (size < 36.dp) 9.sp else 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** A toggle shaped as a chip; announced as a checkbox with its label. */
@Composable
internal fun FilterChip(selected: Boolean, label: String, enabled: Boolean = true, description: String? = null, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colors
    Surface(
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .then(if (description != null) Modifier.describedAs(description) else Modifier),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (selected) colors.primary else LocalChrome.current.outline.copy(alpha = 0.4f)),
        color = if (selected) colors.primary.copy(alpha = 0.12f).compositeOver(MaterialTheme.colors.surface) else colors.surface,
        contentColor = if (selected) colors.primary else colors.onSurface,
    ) {
        Text((if (selected) "✓ " else "") + label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.body2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Mutually exclusive options as one segmented control; each segment is announced as a radio button. */
@Composable
internal fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, modifier: Modifier = Modifier,
                                  onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colors
    Surface(modifier, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, colors.primary), color = colors.surface) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            options.forEachIndexed { index, (value, label) ->
                if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.primary))
                val active = value == selected
                Surface(
                    Modifier.weight(1f, fill = false).selectable(active, enabled = enabled, role = Role.RadioButton) { onSelect(value) },
                    color = if (active) colors.primary else colors.surface,
                    contentColor = if (active) colors.onPrimary else colors.primary,
                ) {
                    Text(label, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.button,
                        maxLines = 1)
                }
            }
        }
    }
}

/** An entry type to pick for a new entry; announced as a radio button in a `selectableGroup()`. */
@Composable
internal fun TypeChip(type: EntryType, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colors
    Surface(
        Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else LocalChrome.current.outline),
        color = if (selected) colors.primary.copy(alpha = 0.10f).compositeOver(colors.surface) else colors.surface,
    ) {
        Row(Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypeAvatar(type, size = 28.dp)
            Text(type.label, style = MaterialTheme.typography.body2, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.primary else colors.onSurface)
        }
    }
}
