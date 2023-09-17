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
import androidx.constraintlayout.compose.Dimension
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import io.livekit.android.composesample.ui.theme.AppTheme
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {

    private lateinit var viewModel1: CallViewModel
    private lateinit var viewModel2: CallViewModel

    @OptIn(ExperimentalMaterialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModelProvider = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
                    ?: throw NullPointerException("args is null!")

                val key = extras[ViewModelProvider.NewInstanceFactory.VIEW_MODEL_KEY]
                val token = if (key == VIEWMODEL_KEY1) args.token1 else args.token2
                val showVideo = key == VIEWMODEL_KEY1
                @Suppress("UNCHECKED_CAST")
                return CallViewModel(
                    args.url,
                    token,
                    args.useDefaultVideoEncoderFactory,
                    args.codecWhiteList,
                    showVideo,
                    application
                ) as T
            }
        })
        viewModel1 = viewModelProvider.get(VIEWMODEL_KEY1, CallViewModel::class.java)
        viewModel2 = viewModelProvider.get(VIEWMODEL_KEY2, CallViewModel::class.java)

        // Setup compose view.
        setContent {
            Content(
                viewModel1,
                viewModel2
            )
        }
    }

    @ExperimentalMaterialApi
    @Composable
    fun Content(viewModel1: CallViewModel, viewModel2: CallViewModel) {
        AppTheme(darkTheme = true) {
            ConstraintLayout(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red)
            ) {
                val (topConnectionItem, bottomConnectionItem) = createRefs()

                Surface(
                    color = Color.Cyan,
                    modifier = Modifier
                        .semantics {
                            testTag = TEST_TAG1
                        }
                        .constrainAs(topConnectionItem) {
                            top.linkTo(parent.top)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            bottom.linkTo(bottomConnectionItem.top)
                            width = Dimension.fillToConstraints
                            height = Dimension.fillToConstraints
                        }
                ) {
                    ConnectionItem(viewModel = viewModel1)
                }

                Surface(
                    color = Color.Magenta,
                    modifier = Modifier
                        .semantics {
                            testTag = TEST_TAG2
                        }
                        .constrainAs(bottomConnectionItem) {
                            top.linkTo(topConnectionItem.bottom)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            bottom.linkTo(parent.bottom)
                            width = Dimension.fillToConstraints
                            height = Dimension.fillToConstraints
                        }
                ) {
                    ConnectionItem(viewModel = viewModel2)
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
