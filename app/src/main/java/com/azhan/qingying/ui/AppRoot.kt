package com.azhan.qingying.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.azhan.qingying.AppContainer
import com.azhan.qingying.ui.home.HomeScreen
import com.azhan.qingying.ui.player.PlayerScreen
import com.azhan.qingying.ui.settings.SettingsScreen
import com.azhan.qingying.ui.theme.DarkShell
import com.azhan.qingying.ui.theme.LightShell
import com.azhan.qingying.ui.theme.PlayerPalette
import com.azhan.qingying.ui.theme.QingYingTheme

object Routes {
    const val HOME = "home"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
}

@Composable
fun AppRoot(container: AppContainer) {
    val prefs by container.library.prefs.collectAsState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    // 播放页用固定的近黑配色，其余页面跟随「深色 / 浅色」设置。
    // 主题挂在这一层，切换页面时整棵树换色，页面内部不用各自处理。
    val palette = when (route) {
        Routes.PLAYER -> PlayerPalette
        else -> if (prefs.darkShell) DarkShell else LightShell
    }

    QingYingTheme(palette = palette) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenPlayer = {
                        navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.PLAYER) {
                PlayerScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
