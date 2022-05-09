package io.livekit.android.videoencodedecode

import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.accompanist.pager.ExperimentalPagerApi
import io.livekit.android.composesample.ui.theme.AppTheme
import kotlinx.parcelize.Parcelize

@OptIn(ExperimentalPagerApi::class)
class CallActivity : AppCompatActivity() {

    private lateinit var viewModel: P2PCallViewModel

    @OptIn(ExperimentalMaterialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModelProvider = ViewModelProvider(this, object : ViewModelProvider.KeyedFactory() {
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>): T {
                val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
                    ?: throw NullPointerException("args is null!")

                @Suppress("UNCHECKED_CAST")
                return P2PCallViewModel(
                    args.useDefaultVideoEncoderFactory,
                    args.codecWhiteList,
                    application
                ) as T
            }
        })
        viewModel = viewModelProvider.get(P2PCallViewModel::class.java)

        // Setup compose view.
        setContent {
            Content(
                viewModel
            )
        }
    }

    @ExperimentalMaterialApi
    @Composable
    fun Content(viewModel: P2PCallViewModel) {
        AppTheme(darkTheme = true) {
            ConstraintLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red)
            ) {
                Surface(
                    color = Color.Cyan,
                    modifier = Modifier
                        .semantics {
                            testTag = TEST_TAG1
                        }
                        .fillMaxSize()
                ) {
                    ConnectionItem(viewModel = viewModel)
                }
            }
        }
    }

    companion object {
        const val KEY_ARGS = "args"
        const val VIEWMODEL_KEY1 = "key1"
        const val VIEWMODEL_KEY2 = "key2"

        const val TEST_TAG1 = "tag1"
        const val TEST_TAG2 = "tag2"
    }

    @Parcelize
    data class BundleArgs(
        val url: String,
        val token1: String,
        val token2: String,
        val useDefaultVideoEncoderFactory: Boolean = false,
        val codecWhiteList: List<String>? = null,
    ) : Parcelable
}