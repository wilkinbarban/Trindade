package com.trindade.app.reports

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import java.io.File

/**
 * One report: its contents, its photos and its WhatsApp text.
 *
 * Stateless like the other screens, with one exception: the capture flow owns a `File` between
 * launching the camera and receiving its answer, and that is screen state by nature -- the ViewModel
 * never sees the camera, only the path it produced.
 *
 * The export text is rendered by the server and displayed verbatim. It is fetched rather than composed
 * because the text is the product's output, and a second implementation here would be a second thing
 * to keep in agreement with the first.
 */
@Composable
fun ReportDetailScreen(
    state: ReportDetailViewModel.UiState,
    onAddPhoto: (String) -> Unit,
    onDeletePhoto: (Int) -> Unit,
    onLoadExport: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var pendingCapture by remember { mutableStateOf<File?>(null) }

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        // Only reported when the camera actually wrote the file: a cancelled capture leaves an empty
        // file, and compressing it would fail with a message about an unreadable photo rather than
        // about the cancellation that caused it.
        if (saved) pendingCapture?.let { onAddPhoto(it.path) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A way out that is visible rather than left to the system back gesture, which is not
        // discoverable on a device the operator may be holding in one hand with gloves on. The edit
        // action sits beside it because the two are the same kind of thing: a way to leave this screen
        // for the one that does something with the report rather than shows it.
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
                // Drawn only when the server's own flag says so, and the flag is read rather than
                // recomputed: which day, which creator and therefore whether an edit would be accepted
                // is the backend's rule, and a client that derived the window would be a second
                // implementation of it -- with the phone's clock and timezone as its inputs. Both of the
                // other shapes fail closed, which is why the comparison is against `true` rather than
                // against false: a report the server marked non-editable must not offer an edit, and
                // neither must one whose flag never arrived, because an absent answer is not an
                // affirmative one. The screen the action opens reads the same flag again on arrival and
                // draws no save of its own for such a report (D6), so this is the first of two places
                // the one flag is rendered and neither of them computes it.
                if (state.report?.canEdit == true) {
                    TextButton(onClick = onEdit) { Text(stringResource(R.string.report_edit)) }
                }
            }
        }

        state.message?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.report?.let { report ->
            item {
                Text(
                    text = "${report.turno.value} • ${report.reportDate}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(text = report.user.displayName, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
            }

            items(report.items) { item ->
                Text(
                    text = if (item.checked) "✓ ${item.taskName}" else "— ${item.taskName}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            items(report.temperatures) { reading ->
                Text(
                    text = "${reading.location}: ${reading.value}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            HorizontalDivider()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.report_photos, state.photos.size, MAX_PHOTOS),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        items(state.photos) { photo ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = photo.filePath.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onDeletePhoto(photo.id) }) {
                    Text(stringResource(R.string.report_photo_remove))
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    val file = newCaptureFile(context)
                    pendingCapture = file
                    capture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                },
                enabled = state.canAddPhoto,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.uploadingPhoto) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.report_photo_add))
                }
            }
        }

        item {
            HorizontalDivider()
            Button(
                onClick = onLoadExport,
                enabled = !state.loadingExport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.loadingExport) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.export_load))
                }
            }
        }

        state.exportText?.let { text ->
            item {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.export_copy))
                }
            }
        }
    }
}

/**
 * A file the camera writes into, in the app cache so a failed upload leaves nothing behind.
 *
 * The name carries a timestamp because the camera app may be handed several in a session and a fixed
 * name would have the second capture overwrite the first.
 */
private fun newCaptureFile(context: Context): File {
    val directory = File(context.cacheDir, "captures").apply { mkdirs() }
    return File(directory, "photo_${System.currentTimeMillis()}.jpg")
}

/** The five the server accepts, mirrored so the add action can be withheld instead of refused. */
private const val MAX_PHOTOS = 5

@Composable
fun ReportDetailRoute(
    reportId: Int,
    onEdit: () -> Unit,
    onBack: () -> Unit,
    viewModel: ReportDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Forced, and keyed on the id: this route is drawn again every time the operator arrives at a
    // report, including on the way back from the edit screen, and the instance it talks to is scoped to
    // the activity's store rather than to this composition. A load that trusted the retained value
    // would show the report as it was before the save, which is the one thing that must not happen after
    // a write this app itself sent. See [ReportDetailViewModel.load] for why the read is forced here and
    // why the early return it overrides exists at all.
    androidx.compose.runtime.LaunchedEffect(reportId) { viewModel.load(reportId, force = true) }

    ReportDetailScreen(
        state = state,
        onAddPhoto = viewModel::attachPhoto,
        onDeletePhoto = viewModel::deletePhoto,
        onLoadExport = viewModel::loadExport,
        onEdit = onEdit,
        onBack = onBack,
    )
}
