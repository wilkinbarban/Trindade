package com.trindade.app.reports

import com.trindade.app.contract.models.ReportResponseReport

/**
 * Whether the server says this report may not be edited.
 *
 * One predicate for the two screens that ask: the detail decides whether to offer the way in, and the edit
 * surface decides whether to draw a save, and two copies of this expression would be two answers to one
 * question -- the shape this project keeps finding. It is the web's own `readOnly ?? !canEdit` and it fails
 * closed: only an explicit `canEdit = true` with no explicit `readOnly = true` is editable, so a report the
 * server did not positively mark editable is drawn read-only.
 */
internal fun ReportResponseReport.isReadOnly(): Boolean = readOnly ?: (canEdit != true)
