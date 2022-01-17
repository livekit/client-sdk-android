package io.livekit.android.composesample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Space
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.accompanist.pager.ExperimentalPagerApi
import io.livekit.android.composesample.ui.theme.AppTheme

@ExperimentalPagerApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultUrl = preferences.getString(PREFERENCES_KEY_URL, URL) as String
        val defaultToken = preferences.getString(PREFERENCES_KEY_TOKEN, TOKEN) as String
        setContent {
            MainContent(
                defaultUrl = defaultUrl,
                defaultToken = defaultToken,
                onConnect = { url, token ->
                    val intent = Intent(this@MainActivity, CallActivity::class.java).apply {
                        putExtra(
                            CallActivity.KEY_ARGS,
                            CallActivity.BundleArgs(
                                url,
                                token
                            )
                        )
                    }
                    startActivity(intent)
                },
                onSave = { url, token ->
                    preferences.edit {
                        putString(PREFERENCES_KEY_URL, url)
                        putString(PREFERENCES_KEY_TOKEN, token)
                    }

                    Toast.makeText(
                        this@MainActivity,
                        "Values saved.",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onReset = {
                    preferences.edit {
                        clear()
                    }
                    Toast.makeText(
                        this@MainActivity,
                        "Values reset.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    @Preview(
        showBackground = true,
        showSystemUi = true,
    )
    @Composable
    fun MainContent(
        defaultUrl: String = URL,
        defaultToken: String = TOKEN,
        onConnect: (url: String, token: String) -> Unit = { _, _ -> },
        onSave: (url: String, token: String) -> Unit = { _, _ -> },
        onReset: () -> Unit = {},
    ) {
        AppTheme {
            var url by remember { mutableStateOf(defaultUrl) }
            var token by remember { mutableStateOf(defaultToken) }
            // A surface container using the 'background' color from the theme
            Surface(
                color = MaterialTheme.colors.background,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(10.dp)
                ) {
                    Spacer(modifier = Modifier.height(50.dp))
                    Image(
                        painter = painterResource(id = R.drawable.banner_dark),
                        contentDescription = "",
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Token") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { onConnect(url, token) }) {
                        Text("Connect")
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { onSave(url, token) }) {
                        Text("Save Values")
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = {
                        url = URL
                        token = TOKEN
                        onReset()
                    }) {
                        Text("Reset Values")
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
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

    companion object {
        const val PREFERENCES_KEY_URL = "url"
        const val PREFERENCES_KEY_TOKEN = "token"

        const val URL = "ws://172.22.201.83:7800"
        const val TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE2NDI5MjI5NTQsImlzcyI6IkFQSXY0UmE5eDNWQ1RvYiIsImp0aSI6InBob25lIiwibmJmIjoxNjQyMzE4MTU0LCJzdWIiOiJwaG9uZSIsInZpZGVvIjp7ImNhblB1Ymxpc2giOnRydWUsImNhblB1Ymxpc2hEYXRhIjp0cnVlLCJjYW5TdWJzY3JpYmUiOnRydWUsInJvb21Kb2luIjp0cnVlfX0.AFpUYYQy2iYqtnqozKarpli3msklxuGX42L68oKvB1U"
    }
}
