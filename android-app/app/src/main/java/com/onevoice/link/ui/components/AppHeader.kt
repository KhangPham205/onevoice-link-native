package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.onevoice.link.R
import com.onevoice.link.ui.theme.CaptionMono
import com.onevoice.link.ui.theme.Spacing
import androidx.compose.ui.unit.dp

/** App title, technical tagline, and navigation entry point. */
@Composable
fun AppHeader(
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // TalkBack reads "•" poorly, so the tagline carries a spoken-friendly description.
    val taglineA11y = stringResource(R.string.header_tagline_a11y)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu_24),
                contentDescription = stringResource(R.string.a11y_open_navigation_menu),
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.header_tagline),
                style = CaptionMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = taglineA11y },
            )
        }
    }
}
