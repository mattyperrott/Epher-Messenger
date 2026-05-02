package com.epher.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epher.app.R
import com.epher.app.ui.theme.AlertRed
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.ChromePurpleSoft
import com.epher.app.ui.theme.DuneSand
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.InkPanel
import com.epher.app.ui.theme.EmberCoral
import com.epher.app.ui.theme.NightInk
import com.epher.app.ui.theme.TideTeal

@Composable
fun EpherBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NightInk),
    ) {
        content()
    }
}

@Composable
fun HeroCard(
    eyebrow: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = EmberCoral,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NoticeCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accentColor: Color = ChromePurple,
    containerColor: Color = InkCard,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = accentColor,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ErrorNoticeCard(
    body: String,
    modifier: Modifier = Modifier,
) {
    NoticeCard(
        title = "ACTION NEEDED",
        body = body,
        modifier = modifier,
        accentColor = AlertRed,
        containerColor = InkCard,
    )
}

@Composable
fun StatusChip(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    Surface(
        modifier = modifier,
        color = if (active) ChromePurple.copy(alpha = 0.22f) else ChromePurpleDark,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 38.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (active) ChromePurpleSoft else InkCard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (active) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = if (active) DuneSand else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (active) 1f else 0.55f),
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        trailing?.invoke()
    }
}

@Composable
fun EpherTopChrome(
    modifier: Modifier = Modifier,
    left: @Composable RowScope.() -> Unit,
    right: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ChromePurple)
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides DuneSand) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = left,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = right,
            )
        }
    }
}

@Composable
fun AppBodyPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = NightInk,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        content()
    }
}

@Composable
fun GlitchLogo(
    modifier: Modifier = Modifier,
    size: Int = 128,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.epher_logo_foreground),
            contentDescription = "Epher logo",
            modifier = Modifier.size(size.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
fun BottomStatusStrip(
    modifier: Modifier = Modifier,
    indicators: SessionIndicators,
    expanded: Boolean = false,
    onToggleExpanded: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(InkPanel.copy(alpha = 0.96f))
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionStatusChip(indicator = indicators.connected)
            SessionStatusChip(indicator = indicators.verified)
            SessionStatusChip(indicator = indicators.encrypted)
            if (onToggleExpanded != null) {
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "Toggle panel",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionStatusChip(
    indicator: SessionIndicator,
    modifier: Modifier = Modifier,
) {
    val loadingTransition = rememberInfiniteTransition(label = "sessionChip")
    val loadingAlpha by loadingTransition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sessionChipAlpha",
    )
    val state = indicator.state
    val containerColor = when (state) {
        SessionIndicatorState.Active -> Color.Black.copy(alpha = 0.40f)
        SessionIndicatorState.Loading -> ChromePurple.copy(alpha = loadingAlpha)
        SessionIndicatorState.Inactive -> Color.Black.copy(alpha = 0.26f)
    }
    val iconColor = when (state) {
        SessionIndicatorState.Active -> when (indicator.label.lowercase()) {
            "connected" -> Color(0xFF4ADE80)
            "verified" -> TideTeal
            "encrypted" -> ChromePurpleSoft
            else -> ChromePurpleSoft
        }

        SessionIndicatorState.Loading -> ChromePurpleSoft.copy(alpha = 0.88f)
        SessionIndicatorState.Inactive -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    }
    val textAlpha = when (state) {
        SessionIndicatorState.Active -> 1f
        SessionIndicatorState.Loading -> 0.96f
        SessionIndicatorState.Inactive -> 0.55f
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 24.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    SessionIndicatorState.Active -> {
                        if (indicator.label.equals("Connected", ignoreCase = true)) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(iconColor),
                            )
                        } else {
                            Icon(
                                imageVector = if (indicator.label.equals("Encrypted", ignoreCase = true)) {
                                    Icons.Rounded.Lock
                                } else {
                                    Icons.Rounded.Shield
                                },
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(10.dp),
                            )
                        }
                    }

                    SessionIndicatorState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.3.dp,
                            color = DuneSand,
                            trackColor = Color.Transparent,
                        )
                    }

                    SessionIndicatorState.Inactive -> {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
            }
            Text(
                text = indicator.label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 10.sp,
                    letterSpacing = 0.7.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
            )
        }
    }
}

enum class DrawerTab(val title: String) {
    Peers("PEERS"),
    Info("INFO"),
    Log("LOG"),
}

@Composable
fun DrawerTabs(
    selected: DrawerTab,
    onSelect: (DrawerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DrawerTab.entries.forEach { tab ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tab.title,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Transparent)
                            .padding(vertical = 6.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (selected == tab) 1f else 0.86f,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            DrawerTab.entries.forEach { tab ->
                HorizontalDivider(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .background(if (selected == tab) ChromePurple else ChromePurple.copy(alpha = 0.18f))
                        .clickableNoRipple { onSelect(tab) },
                    color = Color.Transparent,
                )
            }
        }
    }
}

@Composable
fun DetailLine(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueMaxLines: Int = Int.MAX_VALUE,
    valueOverflow: TextOverflow = TextOverflow.Clip,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = valueMaxLines,
            overflow = valueOverflow,
        )
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(1.dp)
                .background(ChromePurple.copy(alpha = 0.16f)),
        )
    }
}

@Composable
fun DarkInsetPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ChromePurple.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = InkPanel,
    ) {
        content()
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    composedClickable(onClick)
