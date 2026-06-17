package com.bookchat.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookchat.service.DownloadItemState
import com.bookchat.ui.downloads.DownloadViewModel
import com.bookchat.ui.downloads.DownloadsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreenWithStrip(content: @Composable () -> Unit) {
    val downloadViewModel: DownloadViewModel = hiltViewModel()
    val active by downloadViewModel.activeDownload.collectAsStateWithLifecycle()
    val queue by downloadViewModel.queue.collectAsStateWithLifecycle()

    val hasActivity = active != null || queue.isNotEmpty()
    val sheetState = rememberStandardBottomSheetState(
        initialValue = if (hasActivity) SheetValue.Expanded else SheetValue.PartiallyExpanded,
        skipHiddenState = true,
        confirmValueChange = { true },
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    if (hasActivity) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetContent = { DownloadsScreen(viewModel = downloadViewModel) },
            sheetPeekHeight = 120.dp,
        ) {
            content()
        }
    } else {
        content()
    }
}
