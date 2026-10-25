package com.wa2port.android.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ui.Wa2Colors

/** One quick-bar action. [active] highlights toggles (AUTO/SKIP). */
data class QuickAction(val label: String, val active: Boolean = false, val onClick: () -> Unit)

@Composable
private fun QuickButton(a: QuickAction, width: Dp) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .sizeIn(minWidth = width, minHeight = 48.dp)
            .clip(shape)
            .background(if (a.active) Wa2Colors.Accent.copy(alpha = 0.85f) else Color(0x99101A30))
            .border(1.dp, Wa2Colors.AccentDim, shape)
            .clickable(onClick = a.onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            a.label,
            color = if (a.active) Wa2Colors.Night else Wa2Colors.Text,
            fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

/** Vertical column of quick buttons for a side letterbox. */
@Composable
fun QuickColumn(actions: List<QuickAction>, width: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        actions.forEach { QuickButton(it, width) }
    }
}

/** Horizontal row of quick buttons (top letterbox or overlay on the stage). */
@Composable
fun QuickRow(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { QuickButton(it, 52.dp) }
    }
}
