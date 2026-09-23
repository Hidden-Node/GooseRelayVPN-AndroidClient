package com.gooserelay.gooserelayvpn.ui.components.mdv.controls

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor

@Composable
fun MdvFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MdvColor.PrimaryContainer.copy(alpha = 0.16f),
            selectedLabelColor = MdvColor.Primary,
            containerColor = MdvColor.SurfaceHigh,
            labelColor = MdvColor.OnSurfaceVariant
        )
    )
}

