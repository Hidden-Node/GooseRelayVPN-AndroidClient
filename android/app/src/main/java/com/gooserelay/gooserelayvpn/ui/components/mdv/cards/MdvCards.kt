package com.gooserelay.gooserelayvpn.ui.components.mdv.cards

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvRadius

@Composable
fun MdvCardLow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(MdvRadius.Lg),
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceLow),
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceLow),
            content = content
        )
    }
}

@Composable
fun MdvCardHigh(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(MdvRadius.Xl),
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh),
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh),
            content = content
        )
    }
}
