package app.truenasdock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.truenasdock.session.SessionManager
import app.truenasdock.ui.DashboardScreen
import app.truenasdock.ui.DashboardViewModel
import app.truenasdock.ui.LoginScreen
import app.truenasdock.ui.LoginViewModel
import app.truenasdock.ui.TrueNasDockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrueNasDockTheme {
                val session = remember { SessionManager(applicationContext) }
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "login") {
                    composable("login") {
                        val vm: LoginViewModel = viewModel(factory = LoginViewModel.factory(session))
                        val loggedIn = vm.state.collectAsStateWithLifecycle().value.loggedIn
                        if (loggedIn) {
                            nav.navigate("dashboard") {
                                popUpTo("login") { inclusive = true }
                            }
                        }
                        LoginScreen(vm)
                    }
                    composable("dashboard") {
                        val vm: DashboardViewModel =
                            viewModel(factory = DashboardViewModel.factory(session))
                        DashboardScreen(vm) {
                            nav.navigate("login") {
                                popUpTo("dashboard") { inclusive = true }
                            }
                        }
                    }
                }
            }
        }
    }
}
