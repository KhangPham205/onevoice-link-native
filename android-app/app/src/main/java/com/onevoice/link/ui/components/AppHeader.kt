package com.onevoice.link.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.onevoice.link.R
import com.onevoice.link.ui.theme.CaptionMono
import com.onevoice.link.ui.theme.Spacing

/** App title + technical tagline. A custom header, deliberately not a TopAppBar. */
@Composable
fun AppHeader(modifier: Modifier = Modifier) {
    // TalkBack reads "•" poorly, so the tagline carries a spoken-friendly description.
    val taglineA11y = stringResource(R.string.header_tagline_a11y)
    Column(modifier) {
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
