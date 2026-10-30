package com.collabcode.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.collabcode.app.model.*
import com.collabcode.app.agent.AgentEngine
import com.collabcode.app.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CollabCodeTheme { CollabCodeApp() } }
    }
}

@Composable
fun CollabCodeApp() {
    val app: AppState = viewModel()
    val context = LocalContext.current
    // Android 13+ asks once for permission to show the "build finished" notification.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val agent: AgentEngine = viewModel()
    AgentRoot(agent)
}

@Composable
private fun OnboardingHost(app: AppState) {
    val cc = LocalCC.current
    Column(Modifier.fillMaxSize().background(cc.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        OnboardingScreen(app)
    }
}
