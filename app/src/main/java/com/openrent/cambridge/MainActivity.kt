package com.openrent.cambridge

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openrent.cambridge.ui.CommuteSetupScreen
import com.openrent.cambridge.ui.DetailScreen
import com.openrent.cambridge.ui.ResultsScreen
import com.openrent.cambridge.ui.SearchScreen
import com.openrent.cambridge.ui.theme.OpenRent_CambridgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenRent_CambridgeTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val vm: MainViewModel = viewModel()

    val settings by vm.settings.collectAsState()
    val transit by vm.transit.collectAsState()
    val viableStops by vm.viableStops.collectAsState()
    val results by vm.results.collectAsState()
    val isSearching by vm.isSearching.collectAsState()
    val status by vm.status.collectAsState()
    val error by vm.error.collectAsState()
    val searchRun by vm.searchRun.collectAsState()
    val sort by vm.sort.collectAsState()

    NavHost(navController = navController, startDestination = "commute_setup") {
        composable("commute_setup") {
            CommuteSetupScreen(
                settings = settings,
                transit = transit,
                viableStops = viableStops,
                areaNames = vm.selectedAreaNames(),
                onSettingsChange = vm::updateSettings,
                onContinue = { navController.navigate("search") }
            )
        }
        composable("search") {
            SearchScreen(
                areaCount = vm.selectedAreaNames().size,
                maxCommuteMin = settings.maxCommuteMin,
                onSearch = { criteria ->
                    vm.search(criteria)
                    navController.navigate("results")
                }
            )
        }
        composable("results") {
            ResultsScreen(
                results = vm.sortedResults(),
                isSearching = isSearching,
                status = status,
                error = error,
                searchRun = searchRun,
                sort = sort,
                maxCommuteMin = settings.maxCommuteMin,
                onSortChange = vm::setSort,
                onListingClick = { url ->
                    navController.navigate("detail/${Uri.encode(url)}")
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            "detail/{url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { entry ->
            DetailScreen(
                url = entry.arguments?.getString("url").orEmpty(),
                onBack = { navController.popBackStack() }
            )
        }
    }
}
