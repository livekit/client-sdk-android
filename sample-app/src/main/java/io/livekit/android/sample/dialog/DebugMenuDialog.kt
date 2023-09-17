package io.livekit.android.sample.dialog

import android.R
import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter
import io.livekit.android.sample.CallViewModel

fun Activity.showDebugMenuDialog(callViewModel: CallViewModel) {
    val builder = with(AlertDialog.Builder(this)) {
        setTitle("Debug Menu")

        val arrayAdapter = ArrayAdapter<String>(this@showDebugMenuDialog, R.layout.select_dialog_item)
        arrayAdapter.add("Simulate Migration")
        arrayAdapter.add("Reconnect to Room")
        setAdapter(arrayAdapter) { dialog, index ->
            when (index) {
                0 -> callViewModel.simulateMigration()
                1 -> callViewModel.reconnect()
            }
            dialog.dismiss()
        }
    }
    builder.show()
}
