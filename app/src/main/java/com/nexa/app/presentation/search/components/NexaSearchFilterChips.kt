package com.nexa.app.presentation.search.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.search.model.SearchFilter

/**
 * Horizontally scrollable row of compact pill-shaped glass chips for search filtering.
 * Strict exact 12-item order:
 * 1. Unread
 * 2. Audio
 * 3. Favorite
 * 4. Archived
 * 5. Groups
 * 6. Contacts
 * 7. Photos
 * 8. Documents
 * 9. Videos
 * 10. GIFs
 * 11. Links
 * 12. Non-contacts
 */
@Composable
fun NexaSearchFilterChips(
    selectedFilters: Set<SearchFilter>,
    onToggleFilter: (SearchFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchFilter.values().forEach { filter ->
            val isSelected = selectedFilters.contains(filter)

            val chipBgColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                animationSpec = tween(200),
                label = "chipBgColor"
            )

            val chipBorderColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                },
                animationSpec = tween(200),
                label = "chipBorderColor"
            )

            val chipTextColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(200),
                label = "chipTextColor"
            )

            NexaGlassSurface(
                modifier = Modifier
                    .height(34.dp)
                    .clickable { onToggleFilter(filter) },
                shape = RoundedCornerShape(17.dp),
                surfaceColor = chipBgColor,
                borderColor = chipBorderColor,
                borderWidth = if (isSelected) 1.5.dp else 1.dp,
                elevation = if (isSelected) 4.dp else 1.dp
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filter.displayName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp
                        ),
                        color = chipTextColor
                    )
                }
            }
        }
    }
}
