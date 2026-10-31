package com.gooserelay.gooserelayvpn.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.ui.components.mdv.cards.MdvCardHigh
import com.gooserelay.gooserelayvpn.ui.components.mdv.cards.MdvCardLow
import com.gooserelay.gooserelayvpn.ui.theme.ConnectedGreen
import com.gooserelay.gooserelayvpn.ui.theme.DisconnectedRed
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvSpace
import com.gooserelay.gooserelayvpn.util.VpnManager

@Composable
fun MdvConnectionTelemetryCard(
    vpnState: VpnManager.VpnState,
    downBps: Long,
    upBps: Long,
    downloadTotalBytes: Long,
    uploadTotalBytes: Long,
    connectedDurationSeconds: Long,
    proxyHost: String,
    proxyPort: Int,
    socksAuthEnabled: Boolean,
    socksUser: String,
    socksPass: String,
    scanStatus: VpnManager.ScanStatus = VpnManager.ScanStatus()
) {
    MdvCardLow(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MdvSpace.S4)
        ) {
            // Header Row: Status and Duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_connection_status_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MdvColor.PrimaryDim,
                        fontWeight = FontWeight.Bold
                    )
                    if (vpnState == VpnManager.VpnState.CONNECTING) {
                        Text(
                            text = stringResource(R.string.home_connection_preparing),
                            style = MaterialTheme.typography.titleMedium,
                            color = MdvColor.OnSurface,
                            fontWeight = FontWeight.Bold
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.home_connection_cancel_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MdvColor.OnSurfaceVariant
                        )
                    } else {
                        val statusText = when (vpnState) {
                            VpnManager.VpnState.CONNECTED -> stringResource(R.string.home_connection_running)
                            VpnManager.VpnState.DISCONNECTING -> stringResource(R.string.home_state_disconnecting)
                            VpnManager.VpnState.ERROR -> stringResource(R.string.home_connection_error_check_logs)
                            else -> stringResource(R.string.home_state_disconnected)
                        }
                        val statusColor = when (vpnState) {
                            VpnManager.VpnState.CONNECTED -> ConnectedGreen
                            VpnManager.VpnState.ERROR -> DisconnectedRed
                            else -> MdvColor.OnSurface
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (connectedDurationSeconds > 0 ||
                    vpnState == VpnManager.VpnState.CONNECTED ||
                    vpnState == VpnManager.VpnState.CONNECTING
                ) {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = stringResource(R.string.home_status_session),
                            style = MaterialTheme.typography.labelSmall,
                            color = MdvColor.OnSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatDuration(connectedDurationSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MdvColor.OnSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Network Traffic Grid
            if (downloadTotalBytes > 0 || uploadTotalBytes > 0 || downBps > 0 || upBps > 0) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S3))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                ) {
                    // Download Column
                    androidx.compose.material3.Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MdvColor.SurfaceHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(MdvSpace.S3),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.home_status_download),
                                style = MaterialTheme.typography.labelSmall,
                                color = ConnectedGreen,
                                fontWeight = FontWeight.Bold
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatSpeed(downBps),
                                style = MaterialTheme.typography.titleMedium,
                                color = MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatBytes(downloadTotalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                        }
                    }

                    // Upload Column
                    androidx.compose.material3.Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MdvColor.SurfaceHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(MdvSpace.S3),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.home_status_upload),
                                style = MaterialTheme.typography.labelSmall,
                                color = MdvColor.PrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatSpeed(upBps),
                                style = MaterialTheme.typography.titleMedium,
                                color = MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = formatBytes(uploadTotalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Goose Core Relay Stats Card
            if (scanStatus.hasStats) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S3))
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MdvColor.SurfaceHigh
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MdvSpace.S3)
                    ) {
                        Text(
                            text = stringResource(R.string.home_core_stats_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MdvColor.PrimaryDim,
                            fontWeight = FontWeight.Bold
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S2))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.home_core_stats_active_connections),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                            Text(
                                text = scanStatus.statsActive.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.home_core_stats_sessions),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                            Text(
                                text = "${scanStatus.statsSessionsOpen} / ${scanStatus.statsSessionsClose}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.home_core_stats_polls),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                            Text(
                                text = "${scanStatus.statsPollsOk} / ${scanStatus.statsPollsFail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (scanStatus.statsPollsFail > 0) DisconnectedRed else MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.home_core_stats_traffic),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                            Text(
                                text = "${scanStatus.statsBytesOut} / ${scanStatus.statsBytesIn}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (scanStatus.accountStats.isNotBlank()) {
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MdvColor.SurfaceHighest)
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.home_core_stats_accounts),
                                style = MaterialTheme.typography.labelSmall,
                                color = MdvColor.OnSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))
                            
                            val accounts = scanStatus.accountStats.split(" | ")
                            accounts.forEach { account ->
                                if (account.isNotBlank()) {
                                    Text(
                                        text = account,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MdvColor.OnSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SOCKS5 Proxy Info
            if (vpnState == VpnManager.VpnState.CONNECTED) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S3))
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.home_status_socks5_proxy), style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                        Text("$proxyHost:$proxyPort", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurface, fontWeight = FontWeight.Bold)
                    }
                    if (socksAuthEnabled) {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.home_status_socks5_auth), style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                            Text(stringResource(R.string.home_status_enabled), style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurface, fontWeight = FontWeight.Bold)
                        }
                        if (socksUser.isNotBlank()) {
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.home_status_credentials), style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                                Text("$socksUser:$socksPass", style = MaterialTheme.typography.bodySmall, color = MdvColor.OnSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MdvProfileSelectorCard(
    profileName: String,
    onNavigateToProfiles: () -> Unit
) {
    MdvCardHigh(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateToProfiles)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MdvSpace.S4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_profile_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MdvColor.OnSurfaceVariant
                )
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MdvColor.OnSurface
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MdvColor.PrimaryContainer
            )
        }
    }
}

@Composable
fun MdvErrorCard(msg: String) {
    MdvCardLow(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = msg,
            style = MaterialTheme.typography.bodyMedium,
            color = DisconnectedRed,
            modifier = Modifier.padding(MdvSpace.S3),
            textAlign = TextAlign.Center
        )
    }
}

private fun formatSpeed(bps: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        bps >= mb -> String.format("%.2f MB/s", bps / mb)
        bps >= kb -> String.format("%.1f KB/s", bps / kb)
        else -> "${bps} B/s"
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatDuration(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600L
    val minutes = (safeSeconds % 3600L) / 60L
    val secs = safeSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}
