package io.github.scdouglas1999.tally.ui.settings.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import io.github.scdouglas1999.tally.ui.components.tallyUppercase
import io.github.scdouglas1999.tally.ui.phone.PhoneSheet
import io.github.scdouglas1999.tally.ui.phone.phoneScrolled
import io.github.scdouglas1999.tally.ui.theme.PhoneDimens
import io.github.scdouglas1999.tally.ui.theme.PhoneType
import io.github.scdouglas1999.tally.ui.theme.TallyColors

/**
 * The license information on a phone (`Destination.License`, upstream's `LicenseInfo`): the same list of the app's
 * libraries (upstream's `produceLibraries`), each as a row with its name, authors, version in mono and licenses in
 * mono `muted`; a tap shows the license text in a sheet, as upstream's list does.
 */
@Composable
fun PhoneLicensePage(modifier: Modifier = Modifier) {
    val libs by produceLibraries()
    var shown by remember { mutableStateOf<Library?>(null) }
    val listState = rememberLazyListState()
    Column(modifier = phoneFullPage(modifier)) {
        PhoneTopBar(title = stringResource(R.string.license_info), scrolled = listState.phoneScrolled)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(libs?.libraries.orEmpty(), key = { it.uniqueId }) { library ->
                LibraryRow(library = library, onClick = { shown = library })
            }
        }
    }
    shown?.let { library ->
        PhoneSheet(onDismiss = { shown = null }) {
            PhoneSheetTitle(library.name)
            val text =
                library.licenses
                    .mapNotNull { it.licenseContent?.takeIf { content -> content.isNotBlank() } ?: it.name }
                    .joinToString("\n\n")
            Text(
                text = text,
                style = PhoneType.bodySmall,
                color = TallyColors.textSecondary,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = phoneSheetListMaxHeight())
                        .verticalScroll(rememberScrollState())
                        .padding(PhoneDimens.margin),
            )
        }
    }
}

@Composable
private fun PhoneTopBar(
    title: String,
    scrolled: Boolean,
) {
    val dispatcher =
        androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
            ?.onBackPressedDispatcher
    io.github.scdouglas1999.tally.ui.phone.PhoneTopBar(
        title = title,
        onBack = dispatcher?.let { { it.onBackPressed() } },
        scrolled = scrolled,
    )
}

@Composable
private fun LibraryRow(
    library: Library,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PhoneSettingsRowHeight)
                .phoneTouch(onClick = onClick)
                .padding(horizontal = PhoneDimens.margin, vertical = 10.dp),
    ) {
        Row {
            Text(
                text = library.name,
                style = PhoneType.body,
                color = TallyColors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            library.artifactVersion?.let {
                Text(
                    text = it,
                    style = PhoneType.meta,
                    color = TallyColors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        val authors = library.developers.mapNotNull { it.name }.joinToString(", ")
        if (authors.isNotBlank()) {
            Text(
                text = authors,
                style = PhoneType.bodySmall,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val licenses = library.licenses.joinToString(" · ") { it.name }
        if (licenses.isNotBlank()) {
            Text(
                text = licenses.tallyUppercase(),
                style = PhoneType.label,
                color = TallyColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
