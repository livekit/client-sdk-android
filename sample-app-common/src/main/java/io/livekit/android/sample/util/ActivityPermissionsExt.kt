package io.livekit.android.sample.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

fun ComponentActivity.requestNeededPermissions() {
    val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            for (grant in grants.entries) {
                if (!grant.value) {
                    Toast.makeText(
                        this,
                        "Missing permission: ${grant.key}",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }
            }
        }
    val neededPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        .let { perms ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms + listOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                perms
            }
        }
        .filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_DENIED
        }
        .toTypedArray()

    if (neededPermissions.isNotEmpty()) {
        requestPermissionLauncher.launch(neededPermissions)
    }
}