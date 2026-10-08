package dev.tubebeam.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.tubebeam.TubeBeamApp
import dev.tubebeam.ui.screens.AddTvScreen
import dev.tubebeam.ui.screens.AddTvViewModel
import dev.tubebeam.ui.screens.DebugScreen
import dev.tubebeam.ui.screens.DebugViewModel
import dev.tubebeam.ui.screens.HelpScreen
import dev.tubebeam.ui.screens.TvDetailScreen
import dev.tubebeam.ui.screens.TvDetailViewModel
import dev.tubebeam.ui.screens.TvListScreen
import dev.tubebeam.ui.screens.TvListViewModel
import dev.tubebeam.ui.theme.TubeBeamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = TubeBeamApp.container(this)
        val openTvId = intent?.getLongExtra(EXTRA_TV_ID, -1L)?.takeIf { it > 0 }

        setContent {
            TubeBeamTheme {
                val nav = rememberNavController()
                LaunchedEffect(openTvId) { if (openTvId != null) nav.navigate("detail/$openTvId") }

                NavHost(navController = nav, startDestination = "list") {
                    composable("list") {
                        val vm: TvListViewModel = viewModel { TvListViewModel(container) }
                        TvListScreen(
                            vm = vm,
                            onAdd = { nav.navigate("add") },
                            onOpen = { nav.navigate("detail/${it.id}") },
                            onHelp = { nav.navigate("help") },
                            onDebug = { nav.navigate("debug") },
                        )
                    }
                    composable("add") {
                        val vm: AddTvViewModel = viewModel { AddTvViewModel(container) }
                        AddTvScreen(vm, onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() }, onHelp = { nav.navigate("help") })
                    }
                    composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        val id = entry.arguments?.getLong("id") ?: return@composable
                        val vm: TvDetailViewModel = viewModel(key = "detail-$id") { TvDetailViewModel(container, id) }
                        TvDetailScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable("help") { HelpScreen(onBack = { nav.popBackStack() }) }
                    composable("debug") {
                        val vm: DebugViewModel = viewModel { DebugViewModel(container) }
                        DebugScreen(vm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_TV_ID = "tv_id"
    }
}
