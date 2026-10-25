package com.wa2port.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Wa2Colors {
    val Night = Color(0xFF0B1020)
    val Panel = Color(0xE6141C30)
    val Accent = Color(0xFF9FC6FF)
    val AccentDim = Color(0xFF4A6A99)
    val Text = Color(0xFFF2F5FA)
    val TextDim = Color(0xFFA8B3C7)
    val Name = Color(0xFFFFD9A0)
}

@Composable
fun Wa2Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Wa2Colors.Accent,
            onPrimary = Wa2Colors.Night,
            background = Wa2Colors.Night,
            surface = Wa2Colors.Night,
            onSurface = Wa2Colors.Text,
            onBackground = Wa2Colors.Text,
        ),
        content = content,
    )
}

/** Large bilingual menu button (≥48 dp). */
@Composable
fun MenuButton(title: String, subtitle: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (enabled) Color(0x66203050) else Color(0x33202030))
            .border(BorderStroke(1.dp, if (enabled) Wa2Colors.AccentDim else Color(0x33FFFFFF)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = if (enabled) Wa2Colors.Text else Wa2Colors.TextDim, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, color = Wa2Colors.TextDim, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

/** Row of mutually exclusive options. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.clip(shape).border(1.dp, Wa2Colors.AccentDim, shape)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .background(if (on) Wa2Colors.Accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Wa2Colors.Night else Wa2Colors.Text, fontSize = 14.sp)
            }
        }
    }
}

/** Label + slider + value text. */
@Composable
fun LabeledSlider(label: String, value: Int, range: IntRange, valueText: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Wa2Colors.Text, fontSize = 14.sp, modifier = Modifier.width(150.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Wa2Colors.Accent, activeTrackColor = Wa2Colors.Accent),
        )
        Text(valueText, color = Wa2Colors.TextDim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
    }
}

/** Section header used in panels. */
@Composable
fun SectionTitle(text: String) {
    Text(text, color = Wa2Colors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}
