package io.livekit.android.sample.compose.components

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.flowlayout.FlowColumn
import io.livekit.android.sample.compose.components.ui.theme.LivekitandroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LivekitandroidTheme {
                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
                    Greeting("Android")
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String) {
    Text(text = "Hello $name!")
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    LivekitandroidTheme {
        // A surface container using the 'background' color from the theme
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Scaffold(
                topBar = {
                    rememberScrollState()
                    TopAppBar(
                        title = { Text(text = "title") }
                    )
                }
            ) { padding ->
                FlowColumn(Modifier.padding(padding)) {
                    SampleContent()
                }
            }
        }
//        val cameraState =
//            remember { mutableStateOf(CameraState("Front Camera", listOf("Front Camera", "Back Camera"))) }
//        CameraDropdownMenuBox(cameraState = cameraState)
    }
}

@Composable
internal fun SampleContent() {
    repeat(30) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color.Blue)
                .border(2.dp, Color.DarkGray),
            contentAlignment = Alignment.Center,
        ) {
            Text(it.toString())
        }
    }
}