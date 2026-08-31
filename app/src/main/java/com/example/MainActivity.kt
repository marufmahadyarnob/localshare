package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.ui.LocalShareScreen
import com.example.ui.LocalShareViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: LocalShareViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission for foreground service on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Handle file/text shared from other apps via Share sheet
        handleIncomingShareIntent(intent)

        setContent {
            MyApplicationTheme {
                LocalShareScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        when (action) {
            Intent.ACTION_SEND -> {
                // Single item share
                if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                    @Suppress("DEPRECATION")
                    val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    }
                    if (streamUri != null) {
                        viewModel.handleSharedUrisFromIntent(listOf(streamUri))
                    }
                } else if (type != null && type.startsWith("text/")) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    if (!sharedText.isNullOrBlank()) {
                        viewModel.handleSharedTextFromIntent(sharedText)
                    }
                } else {
                    // Fallback to text if available
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    if (!sharedText.isNullOrBlank()) {
                        viewModel.handleSharedTextFromIntent(sharedText)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple items share (e.g. multi-select in gallery)
                @Suppress("DEPRECATION")
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (!streamUris.isNullOrEmpty()) {
                    viewModel.handleSharedUrisFromIntent(streamUris.filterNotNull())
                }
            }
        }
    }
}
