package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.di.AudioFx
import com.music.spotui.ui.theme.AppAccent

/** Settings > Audio: one equaliser for everything, presets or a custom curve, and bass boost. */
@Composable
fun AudioSettings() {
    Text("AUDIO", color = AppAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
    Toggle("Equaliser", "Presets, your own sound and bass boost", AudioFx.enabled) { AudioFx.switchOn(it) }
    if (!AudioFx.enabled) return

    // Presets: tap one to turn it on.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        for (name in AudioFx.PRESETS.map { it.name } + AudioFx.CUSTOM) {
            val selected = AudioFx.preset == name
            Text(
                name,
                color = if (selected) Color.Black else Color.White, fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (selected) AppAccent else Color(0xFF282828))
                    .clickable { AudioFx.choose(name) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // Bands: moving one makes the sound your own (Custom).
    val gains = AudioFx.gains
    AudioFx.BANDS_HZ.forEachIndexed { i, hz ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(if (hz >= 1000) "${"%.1f".format(hz / 1000f).removeSuffix(".0")} kHz" else "$hz Hz",
                color = Color.White, fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Slider(
                value = gains[i], onValueChange = { AudioFx.setBand(i, Math.round(it * 2) / 2f) },
                valueRange = -AudioFx.MAX_DB..AudioFx.MAX_DB,
                colors = SliderDefaults.colors(thumbColor = AppAccent, activeTrackColor = AppAccent),
                modifier = Modifier.weight(1f),
            )
            Text("${if (gains[i] > 0) "+" else ""}${"%.1f".format(gains[i]).removeSuffix(".0")} dB",
                color = Color(0xFFB3B3B3), fontSize = 12.sp, modifier = Modifier.width(60.dp).padding(start = 8.dp))
        }
    }

    // Bass boost.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text("Bass boost", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Slider(
            value = AudioFx.bass.toFloat(), onValueChange = { AudioFx.boostBass(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = AppAccent, activeTrackColor = AppAccent),
            modifier = Modifier.weight(1f),
        )
        Text("${AudioFx.bass}%", color = Color(0xFFB3B3B3), fontSize = 12.sp, modifier = Modifier.width(60.dp).padding(start = 8.dp))
    }

    Toggle(
        "Apply to Spotify streaming",
        when (AudioFx.streamingWorks) {
            false -> "This phone doesn't allow it: works on saved songs only. Your phone's own sound settings can cover streaming."
            else -> "Uses the phone's whole sound output, so other apps hear it too while on"
        },
        AudioFx.streaming,
    ) { AudioFx.switchStreaming(it) }
}

@Composable
private fun Toggle(label: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChecked)
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 16.sp)
            Text(detail, color = Color(0xFFB3B3B3), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
