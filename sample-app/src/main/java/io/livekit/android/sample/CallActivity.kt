package io.livekit.android.sample

import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import com.snakydesign.livedataextensions.combineLatest
import com.xwray.groupie.GroupieAdapter
import io.livekit.android.sample.databinding.CallActivityBinding
import kotlinx.parcelize.Parcelize

class CallActivity : AppCompatActivity() {

    val viewModel: CallViewModel by viewModelByFactory {

        val args = intent.getParcelableExtra<BundleArgs>(KEY_ARGS)
            ?: throw NullPointerException("args is null!")
        CallViewModel(args.url, args.token, application)
    }
    lateinit var binding: CallActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = CallActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)

        val adapter = GroupieAdapter()
        binding.viewPager.apply {
            this.adapter = adapter
        }

        combineLatest(
            viewModel.room,
            viewModel.remoteParticipants
        ) { room, participants -> room to participants }
            .observe(this) {
                val (room, participants) = it
                val items = participants.map { participant -> ParticipantItem(room, participant) }
                adapter.update(items)
            }
    }


    companion object {
        const val KEY_ARGS = "args"
    }

    @Parcelize
    data class BundleArgs(val url: String, val token: String) : Parcelable
}