package io.livekit.android.sample.dialog

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter
import io.livekit.android.sample.CallViewModel

fun Activity.showAudioProcessorSwitchDialog(callViewModel: CallViewModel) {
    val builder = with(AlertDialog.Builder(this)) {
        setTitle("AudioProcessor: ["+ callViewModel.audioProcessor?.getName() + "] is " + if (callViewModel.enableAudioProcessor.value == true) "On" else "Off")

        val arrayAdapter = ArrayAdapter<String>(this@showAudioProcessorSwitchDialog, R.layout.select_dialog_item)
        arrayAdapter.add("On")
        arrayAdapter.add("Off")
        setAdapter(arrayAdapter) { dialog, index ->
            when (index) {
                0 -> callViewModel.toggleEnhancedNs(true)
                1 -> callViewModel.toggleEnhancedNs(false)
            }
            dialog.dismiss()
        }
    }
    builder.show()
}
