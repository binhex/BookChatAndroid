package com.bookchat.ui.settings

import android.accounts.AccountManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookchat.ui.common.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val driveAuthIntent by viewModel.driveAuthIntent.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.update { it.copy(watchFolderUri = uri.toString()) }
        }
    }

    val driveAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.clearDriveAuthIntent() }

    // Launch consent screen whenever driveAuthIntent is set
    androidx.compose.runtime.LaunchedEffect(driveAuthIntent) {
        driveAuthIntent?.let { driveAuthLauncher.launch(it) }
    }

    val accountPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accountName = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (accountName != null) {
            viewModel.update { it.copy(driveAccountName = accountName) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SectionHeader("IRC") }
            item {
                EditableField(
                    label = "Nickname",
                    value = settings.ircNickname,
                    onSave = { viewModel.update { it.copy(ircNickname = this) } },
                )
            }
            item {
                EditableField(
                    label = "Server",
                    value = settings.ircServer,
                    onSave = { viewModel.update { it.copy(ircServer = this) } },
                )
            }
            item {
                EditableField(
                    label = "Port",
                    value = settings.ircPort.toString(),
                    keyboardType = KeyboardType.Number,
                    validate = { it.toIntOrNull()?.let { p -> p in 1..65535 } ?: false },
                    errorMessage = "Must be 1–65535",
                    onSave = { viewModel.update { it.copy(ircPort = this.toInt()) } },
                )
            }
            item {
                EditableField(
                    label = "Channel",
                    value = settings.ircChannel,
                    onSave = { viewModel.update { it.copy(ircChannel = this) } },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Use SSL (port 6697)") },
                    supportingContent = {
                        Text(
                            "Recommended — bypasses carrier port blocking",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        androidx.compose.material3.Switch(
                            checked = settings.ircUseSsl,
                            onCheckedChange = { on ->
                                viewModel.update { it.copy(
                                    ircUseSsl = on,
                                    ircPort = if (on) 6697 else 6667,
                                ) }
                            },
                        )
                    },
                )
            }
            item {
                EditableField(
                    label = "Password",
                    value = settings.ircPassword,
                    isPassword = true,
                    onSave = { viewModel.update { it.copy(ircPassword = this) } },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader("Downloads") }
            item {
                EditableField(
                    label = "Download timeout (seconds)",
                    value = settings.downloadTimeoutSeconds.toString(),
                    keyboardType = KeyboardType.Number,
                    validate = { it.toIntOrNull()?.let { v -> v in 1..3600 } ?: false },
                    errorMessage = "Must be 1\u20133600",
                    onSave = { viewModel.update { it.copy(downloadTimeoutSeconds = this.toInt()) } },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader("Google Drive") }
            item {
                ListItem(
                    headlineContent = { Text("Google account") },
                    supportingContent = {
                        Text(
                            text = settings.driveAccountName.ifBlank { "Not connected — tap to choose" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        accountPicker.launch(
                            AccountManager.newChooseAccountIntent(
                                null, null, arrayOf("com.google"),
                                null, null, null, null
                            )
                        )
                    },
                )
            }
            item {
                EditableField(
                    label = "CalibreWatch folder URL or ID",
                    value = settings.driveFolderId,
                    onSave = { viewModel.update { s -> s.copy(driveFolderId = viewModel.parseDriveFolderId(this)) } },
                )
            }
            item {
                Button(
                    onClick = {
                        viewModel.testDriveAuth { result ->
                            scope.launch { snackbarHostState.showSnackbar(result) }
                        }
                    },
                    enabled = settings.driveAccountName.isNotBlank() && settings.driveFolderId.isNotBlank(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text("Test / Authorize Drive") }
            }
            item { SectionHeader("Local fallback") }
            item {
                ListItem(
                    headlineContent = { Text("Watch folder") },
                    supportingContent = {
                        Text(
                            text = if (settings.watchFolderUri.isNotBlank())
                                "Folder selected ✓"
                            else
                                "Not set — books save to Downloads/BookChat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { folderPicker.launch(null) },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    trailingContent = {
                        Text("1.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
    }
}

// onSave receiver is the validated draft string
@Composable
private fun EditableField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: ((String) -> Boolean)? = null,
    errorMessage: String = "",
    onSave: String.() -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val focusRequester = remember { FocusRequester() }
    // Guard against onFocusChanged firing with isFocused=false on initial composition,
    // before LaunchedEffect has had a chance to request focus.
    var everFocused by remember { mutableStateOf(false) }
    val isError = draft.isNotBlank() && validate?.invoke(draft) == false

    fun tryCommit() {
        if (validate == null || validate(draft)) {
            draft.onSave()
        }
        editing = false
        everFocused = false
    }

    if (editing) {
            ListItem(
                headlineContent = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(label) },
                        isError = isError,
                        supportingText = if (isError) ({ Text(errorMessage) }) else null,
                        visualTransformation = if (isPassword) PasswordVisualTransformation()
                        else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = keyboardType,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { tryCommit() }),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    everFocused = true
                                } else if (everFocused) {
                                    tryCommit()
                                }
                            },
                    )
                },
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            ListItem(
                headlineContent = { Text(label) },
                trailingContent = {
                    Text(
                        text = if (isPassword && value.isNotBlank()) "••••" else value,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { editing = true },
            )
    }
}

