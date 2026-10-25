package com.gooserelay.gooserelayvpn.ui.components.mdv.controls

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor

@Composable
fun MdvFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = leadingIcon,
        modifier = modifier,
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MdvColor.OutlineVariant,
            selectedBorderColor = MdvColor.Primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MdvColor.PrimaryContainer.copy(alpha = 0.22f),
            selectedLabelColor = MdvColor.Primary,
            containerColor = MdvColor.SurfaceHigh,
            labelColor = MdvColor.OnSurfaceVariant
        )
    )
}

