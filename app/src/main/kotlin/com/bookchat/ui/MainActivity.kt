package com.bookchat.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bookchat.ui.downloads.DownloadViewModel
import com.bookchat.ui.downloads.DownloadsScreen
import com.bookchat.ui.search.IrcStatusDot
import com.bookchat.ui.search.SearchScreen
import com.bookchat.ui.search.SearchViewModel
import com.bookchat.ui.settings.SettingsScreen
import com.bookchat.ui.common.UserEvent
import com.bookchat.ui.common.UserEventBus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var userEventBus: UserEventBus

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — download service still works, just no notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface { BookChatApp(userEventBus) }
            }
        }
    }
}

private object Routes {
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookChatApp(userEventBus: UserEventBus) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        userEventBus.events.collect { event ->
            when (event) {
                is UserEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val inSettings = currentRoute == Routes.SETTINGS
    val searchViewModel: SearchViewModel = hiltViewModel()
    val connectionState by searchViewModel.connectionState.collectAsStateWithLifecycle()
    val downloadViewModel: DownloadViewModel = hiltViewModel()
    val downloadQueue by downloadViewModel.queue.collectAsStateWithLifecycle()
    val activeDownload by downloadViewModel.activeDownload.collectAsStateWithLifecycle()
    val downloadCount = downloadQueue.size + if (activeDownload != null) 1 else 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!inSettings) {
                TopAppBar(
                    title = { Text("BookChat") },
                    navigationIcon = {
                        IrcStatusDot(
                            state = connectionState,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!inSettings) {
                NavigationBar {
                    val currentDestination = navBackStackEntry?.destination
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == Routes.SEARCH } == true,
                        onClick = {
                            navController.navigate(Routes.SEARCH) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == Routes.DOWNLOADS } == true,
                        onClick = {
                            navController.navigate(Routes.DOWNLOADS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            BadgedBox(badge = {
                                if (downloadCount > 0) Badge { Text("$downloadCount") }
                            }) {
                                Icon(Icons.Default.Download, contentDescription = null)
                            }
                        },
                        label = { Text("Downloads") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SEARCH,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SEARCH) {
                SearchScreen()
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen()
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}
