// SPDX-License-Identifier: LGPL-2.1-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.tuxcompose.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.tuxcompose.model.TGSong

/** Works on imported section markers too, not only hard-coded showcase tick offsets. */
@Composable
fun StudioSections(song: TGSong?, trackIndex: Int, onDismiss: () -> Unit, onSeek: (Long) -> Unit) {
    val measures=song?.tracks?.getOrNull(trackIndex)?.measures.orEmpty()
    val sections=measures.filterIndexed { index,m -> index==0 || !m.header.marker.isNullOrBlank() }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=600.dp).semantics { testTagsAsResourceId=true }) {
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                    Text("Explore the score",style=MaterialTheme.typography.headlineSmallEmphasized,modifier=Modifier.semantics { heading() })
                    Text("Jump to a section. Your display and follow choices stay unchanged.",style=MaterialTheme.typography.bodyMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StudioIconButton(R.drawable.ic_close,"Close sections","sections-close",onClick=onDismiss)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f,false).testTag("sections-list"),
                contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                itemsIndexed(sections,key={_,m->m.number}) { index,measure ->
                    val last=sections.getOrNull(index+1)?.number?.minus(1) ?: measures.lastOrNull()?.number ?: measure.number
                    ListItem(headlineContent={Text(measure.header.marker ?: "Beginning")},
                        supportingContent={Text("Measures ${measure.number}–$last")},
                        leadingContent={StudioIcon(R.drawable.ic_music_note)},
                        colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier=Modifier.fillMaxWidth().heightIn(min=72.dp).testTag("section-${measure.number-1}")
                            .clickable { onSeek(measure.start) })
                }
            }
        }
    }
}
