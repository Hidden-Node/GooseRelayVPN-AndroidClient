package com.gooserelay.gooserelayvpn.ui.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gooserelay.gooserelayvpn.BuildConfig
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.ui.components.mdv.cards.MdvCardHigh
import com.gooserelay.gooserelayvpn.ui.components.mdv.cards.MdvCardLow
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvRadius
import com.gooserelay.gooserelayvpn.ui.theme.MdvSpace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val mainGithubLink = stringResource(R.string.project_main_github)
    val androidClientGithubLink = stringResource(R.string.project_android_client_github)
    val engineVersion = stringResource(R.string.engine_version)

    Scaffold(
        containerColor = MdvColor.Background,
        topBar = {
            MdvBackTopAppBar(
                title = stringResource(R.string.title_info),
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(MdvSpace.S4),
            verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
        ) {
            item {
                MdvCardLow(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(MdvRadius.Xl)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        MdvColor.PrimaryContainer,
                                        MdvColor.Primary
                                    )
                                )
                            )
                            .padding(18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_launcher_foreground_raw),
                                contentDescription = stringResource(R.string.info_app_logo),
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.size(MdvSpace.S3))
                            Column {
                                Text(
                                    text = stringResource(R.string.info_app_name_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.info_overview_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MdvColor.OnSurface
                                )
                                Spacer(modifier = Modifier.height(MdvSpace.S2))
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text(stringResource(R.string.info_build_information)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Info,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        disabledContainerColor = MdvColor.SurfaceHigh,
                                        disabledLabelColor = MdvColor.OnSurface,
                                        disabledLeadingIconContentColor = MdvColor.PrimaryContainer
                                    )
                                )
                            }
                        }
                    }
                }
            }
            item {
                MdvCardHigh(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(MdvSpace.S4),
                        verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                    ) {
                        Text(stringResource(R.string.info_project_links), style = MaterialTheme.typography.titleMedium)
                        InfoLinkRow(
                            title = stringResource(R.string.info_main_github),
                            link = mainGithubLink,
                            onOpen = { uriHandler.openUri(mainGithubLink.ensureUrlScheme()) }
                        )
                        InfoLinkRow(
                            title = stringResource(R.string.info_android_client),
                            link = androidClientGithubLink,
                            onOpen = { uriHandler.openUri(androidClientGithubLink.ensureUrlScheme()) }
                        )
                    }
                }
            }
            item {
                MdvCardHigh(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(MdvSpace.S4),
                        verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                    ) {
                        Text(stringResource(R.string.info_version_info), style = MaterialTheme.typography.titleMedium)
                        InfoValueRow(label = stringResource(R.string.info_app_version), value = BuildConfig.VERSION_NAME)
                        InfoValueRow(label = stringResource(R.string.info_upstream_engine), value = engineVersion)
                    }
                }
            }
        }
    }
}

private fun String.ensureUrlScheme(): String {
    return if (startsWith("http://") || startsWith("https://")) this else "https://$this"
}

@Composable
private fun InfoLinkRow(title: String, link: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = link,
                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                color = MdvColor.PrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Icon(
            imageVector = Icons.Filled.OpenInNew,
            contentDescription = stringResource(R.string.info_open_link),
            tint = MdvColor.PrimaryContainer
        )
    }
}

@Composable
private fun InfoValueRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MdvColor.Surface.copy(alpha = 0.75f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MdvColor.OnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
