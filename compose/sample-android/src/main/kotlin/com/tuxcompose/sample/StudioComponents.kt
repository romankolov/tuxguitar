// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.tuxcompose.sample

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun StudioIcon(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon),description,modifier.size(24.dp))
}

@Composable
fun StudioIconButton(@DrawableRes icon: Int, description: String, tag: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick,Modifier.size(48.dp).testTag(tag),enabled=enabled) { StudioIcon(icon,description) }
}

/** Connected, shape-morphing M3 Expressive choices. Labels may wrap; targets never shrink. */
@Composable
fun <T> StudioChoices(options: List<T>, selected: T, label: (T) -> String,
    tag: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    if (options.isEmpty()) return
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val style=MaterialTheme.typography.labelLarge
    // Measure at the actual system font scale. Do not squash four large-font
    // labels into one row or rely on ellipses to hide inaccessible choices.
    val widest=options.maxOf { measurer.measure(label(it),style,softWrap=false,maxLines=1).size.width }
    val minimumCell=with(density) { widest.toDp() }+20.dp
    BoxWithConstraints(modifier.fillMaxWidth().selectableGroup().testTag("choices-${tag(options.first())}")) {
        val gap=ButtonGroupDefaults.ConnectedSpaceBetween
        val columns=((maxWidth+gap)/(minimumCell+gap)).toInt().coerceIn(1,options.size)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            options.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap),verticalAlignment=Alignment.CenterVertically) {
                    row.forEachIndexed { index,item ->
                        ToggleButton(checked=selected==item,onCheckedChange={ onSelect(item) },
                            modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag(tag(item)).semantics { role=Role.RadioButton },
                            shapes=when {
                                row.size==1 -> ToggleButtonDefaults.shapesFor(48.dp)
                                index==0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                index==row.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },contentPadding=PaddingValues(horizontal=8.dp,vertical=10.dp)) {
                            // Use the entire padded content width, not a fractional intrinsic
                            // glyph width rounded down to an Int by the Text layout.
                            Text(label(item),style=style,maxLines=2,textAlign=TextAlign.Center,
                                modifier=Modifier.fillMaxWidth().testTag("choice-label-${tag(item)}"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsHeading(title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top=24.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.titleSmallEmphasized,color=MaterialTheme.colorScheme.primary,modifier=Modifier.semantics { heading() })
        description?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SettingsSwitch(title: String, description: String, value: Boolean, tag: String,
    enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp)
        .toggleable(value,enabled=enabled,role=Role.Switch,onValueChange=onChange)
        .testTag(tag).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge,
                color=MaterialTheme.colorScheme.onSurface.copy(alpha=if (enabled) 1f else .38f))
            Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=value,onCheckedChange=null,enabled=enabled)
    }
}

@Composable
fun SettingsAction(title: String, description: String, @DrawableRes icon: Int, tag: String,
    enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(tag),
        color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.large) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            StudioIcon(icon)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleSmall)
                Text(description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
