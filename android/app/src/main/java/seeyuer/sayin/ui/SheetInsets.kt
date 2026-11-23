package seeyuer.sayin.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Shared horizontal inset for Miuix bottom sheets.
 *
 * Miuix defaults to 24dp. SayIn keeps sheets aligned with the 20dp content
 * margin used by its pages, and callers must not add another horizontal
 * padding inside the sheet content.
 */
internal val AppSheetInsideMargin = DpSize(20.dp, 0.dp)
