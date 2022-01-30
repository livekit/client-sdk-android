package io.livekit.android.composesample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.google.accompanist.pager.ExperimentalPagerApi
import io.livekit.android.composesample.ui.theme.AppTheme
import io.livekit.android.sample.MainViewModel

@ExperimentalPagerApi
class MainActivity : ComponentActivity() {

    val viewModel by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()
        setContent {
            MainContent(
                defaultUrl = viewModel.getSavedUrl(),
                defaultToken = viewModel.getSavedToken(),
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
                    viewModel.setSavedUrl(url)
                    viewModel.setSavedToken(token)

                    Toast.makeText(
                        this@MainActivity,
                        "Values saved.",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onReset = {
                    viewModel.reset()
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
        defaultUrl: String = MainViewModel.URL,
        defaultToken: String = MainViewModel.TOKEN,
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
                        onReset()
                        url = MainViewModel.URL
                        token = MainViewModel.TOKEN
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

}
