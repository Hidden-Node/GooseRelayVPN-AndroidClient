package com.gooserelay.gooserelayvpn.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.supportsEnhancedGlow

@Composable
fun MdvConnectionNodeButton(
    isConnected: Boolean,
    shouldPulse: Boolean,
    pulseScale: Float,
    statusColor: Color,
    onToggle: () -> Unit
) {
    val glowElevation = if (isConnected && supportsEnhancedGlow()) 20.dp else 8.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(184.dp)
            .scale(if (shouldPulse) pulseScale else 1f)
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .shadow(
                    elevation = glowElevation,
                    shape = CircleShape,
                    ambientColor = statusColor.copy(alpha = 0.28f),
                    spotColor = statusColor.copy(alpha = 0.38f)
                )
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(statusColor.copy(alpha = 0.18f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier.size(124.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isConnected) MdvColor.PrimaryContainer else MdvColor.SurfaceHigh
            )
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(MdvColor.Primary, MdvColor.PrimaryContainer)
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = if (isConnected) {
                        stringResource(R.string.home_disconnect)
                    } else {
                        stringResource(R.string.home_connect)
                    },
                    modifier = Modifier.size(48.dp),
                    tint = MdvColor.OnPrimaryContainer
                )
            }
        }
    }
}
