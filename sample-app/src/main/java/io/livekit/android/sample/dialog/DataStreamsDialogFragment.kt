/*
 * Copyright 2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.sample.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.sample.CallViewModel
import io.livekit.android.sample.ReceivedStreamRecord
import io.livekit.android.sample.StreamKind
import io.livekit.android.sample.StreamSubscriptionState
import io.livekit.android.sample.databinding.DialogDataStreamsBinding
import io.livekit.android.sample.databinding.ItemDataStreamReceivedBinding
import io.livekit.android.sample.databinding.ItemDataStreamSubscriptionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private const val HELLO_CONTENT = "hello world"
private const val TWENTY_K_SIZE = 20_000
private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/**
 * 20k of random characters.
 *
 * Deliberately random rather than a repeated character: random data does not compress, so a v2
 * send of this has to fall back to a compressed multi-packet stream, where a repetitive payload of
 * the same size would shrink under the packet budget and go out as a single inline packet.
 */
private fun twentyKRandom(): String {
    val random = Random.Default
    return buildString(TWENTY_K_SIZE) {
        repeat(TWENTY_K_SIZE) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}

/**
 * A panel for exercising data streams by hand: send text or bytes on a topic to one participant or
 * everyone, and subscribe to topics to watch what arrives.
 */
class DataStreamsDialogFragment : DialogFragment() {

    private var _binding: DialogDataStreamsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CallViewModel by activityViewModels()

    /** Parallel to the destination spinner's entries; null at index 0 means broadcast. */
    private val destinations = mutableListOf<Participant.Identity?>(null)
    private lateinit var destinationAdapter: ArrayAdapter<String>
    private val subscriptionsAdapter = GroupieAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogDataStreamsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { dismiss() }

        destinationAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf(BROADCAST_LABEL),
        )
        destinationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.destinationSpinner.adapter = destinationAdapter

        binding.presetHello.setOnClickListener { binding.contentEdit.setText(HELLO_CONTENT) }
        binding.preset20kRandom.setOnClickListener { binding.contentEdit.setText(twentyKRandom()) }

        binding.sendButton.setOnClickListener { send() }
        binding.subscribeButton.setOnClickListener { subscribe() }

        binding.subscriptionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.subscriptionsList.adapter = subscriptionsAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.participants
                    .map { list -> list.filterIsInstance<RemoteParticipant>() }
                    .collect { remotes -> updateDestinations(remotes) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.streamSubscriptions.collect { subscriptions ->
                    subscriptionsAdapter.update(
                        subscriptions.map { DataStreamSubscriptionItem(it, viewModel) },
                    )
                }
            }
        }
    }

    /** Keeps the spinner in step with the room, preserving the selection where it still exists. */
    private fun updateDestinations(remotes: List<RemoteParticipant>) {
        val previous = destinations.getOrNull(binding.destinationSpinner.selectedItemPosition)

        destinations.clear()
        destinations.add(null)
        destinations.addAll(remotes.mapNotNull { it.identity })

        destinationAdapter.clear()
        destinationAdapter.add(BROADCAST_LABEL)
        destinationAdapter.addAll(destinations.drop(1).map { it?.value ?: "(unknown)" })

        // Falls back to broadcast if the participant we were targeting has left.
        val restored = destinations.indexOf(previous).takeIf { it >= 0 } ?: 0
        binding.destinationSpinner.setSelection(restored)
    }

    private fun sendKind(): StreamKind =
        if (binding.sendKindBytes.isChecked) StreamKind.BYTES else StreamKind.TEXT

    private fun send() {
        val topic = binding.sendTopicEdit.text.toString().trim()
        if (topic.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a topic", Toast.LENGTH_SHORT).show()
            return
        }
        val destination = destinations.getOrNull(binding.destinationSpinner.selectedItemPosition)
        val content = binding.contentEdit.text.toString()
        val kind = sendKind()

        binding.sendButton.isEnabled = false
        binding.sendResultText.visibility = View.GONE
        binding.sendSpinner.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.sendDataStream(kind, topic, destination, content)
            val b = _binding ?: return@launch
            b.sendSpinner.visibility = View.GONE
            b.sendButton.isEnabled = true
            b.sendResultText.visibility = View.VISIBLE
            b.sendResultText.text = result.fold(
                onSuccess = { "OK: stream ${it.take(8)} (${content.toByteArray().size}B ${kind.label})" },
                onFailure = { "Error: ${it.message ?: it::class.java.simpleName}" },
            )
        }
    }

    private fun subscribe() {
        val topic = binding.subscribeTopicEdit.text.toString().trim()
        val kind = if (binding.subscribeKindBytes.isChecked) StreamKind.BYTES else StreamKind.TEXT

        viewModel.subscribeToStream(topic, kind)
            .onSuccess { binding.subscribeTopicEdit.text.clear() }
            .onFailure {
                // Expected for a topic something else already owns, such as `lk.chat`.
                Toast.makeText(
                    requireContext(),
                    it.message ?: "Could not subscribe to $topic",
                    Toast.LENGTH_LONG,
                ).show()
            }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onDestroyView() {
        binding.subscriptionsList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val BROADCAST_LABEL = "Everyone (broadcast)"
    }
}

private val receivedTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/** One subscribed topic, with its own scrolling list of what has arrived on it. */
class DataStreamSubscriptionItem(
    private val state: StreamSubscriptionState,
    private val viewModel: CallViewModel,
) : BindableItem<ItemDataStreamSubscriptionBinding>() {

    private var scope: CoroutineScope? = null

    override fun initializeViewBinding(view: View): ItemDataStreamSubscriptionBinding =
        ItemDataStreamSubscriptionBinding.bind(view)

    override fun getLayout(): Int = io.livekit.android.sample.R.layout.item_data_stream_subscription

    override fun bind(viewBinding: ItemDataStreamSubscriptionBinding, position: Int) {
        viewBinding.topicName.text = "${state.topic}  [${state.kind.label}]"

        viewBinding.unsubscribeButton.setOnClickListener {
            viewModel.unsubscribeFromStream(state.topic, state.kind)
        }

        val receivedAdapter = GroupieAdapter()
        viewBinding.receivedList.layoutManager = LinearLayoutManager(viewBinding.root.context)
        viewBinding.receivedList.adapter = receivedAdapter

        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        newScope.launch {
            state.received.collect { records ->
                viewBinding.receivedCount.text = "Received (${state.count.value})"
                viewBinding.emptyLabel.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                viewBinding.receivedList.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
                receivedAdapter.update(records.map { DataStreamReceivedItem(it) })
            }
        }
    }

    override fun unbind(viewHolder: GroupieViewHolder<ItemDataStreamSubscriptionBinding>) {
        scope?.cancel()
        scope = null
        viewHolder.binding.receivedList.adapter = null
        super.unbind(viewHolder)
    }

    override fun isSameAs(other: com.xwray.groupie.Item<*>): Boolean =
        other is DataStreamSubscriptionItem &&
            other.state.topic == state.topic &&
            other.state.kind == state.kind

    override fun hasSameContentAs(other: com.xwray.groupie.Item<*>): Boolean =
        other is DataStreamSubscriptionItem && other.state === state
}

/** One received stream: where it came from, how big it was, and a preview of the payload. */
class DataStreamReceivedItem(private val record: ReceivedStreamRecord) :
    BindableItem<ItemDataStreamReceivedBinding>() {

    override fun initializeViewBinding(view: View): ItemDataStreamReceivedBinding =
        ItemDataStreamReceivedBinding.bind(view)

    override fun getLayout(): Int = io.livekit.android.sample.R.layout.item_data_stream_received

    override fun bind(viewBinding: ItemDataStreamReceivedBinding, position: Int) {
        val time = receivedTimeFormat.format(Date(record.receivedAtMs))
        viewBinding.meta.text =
            "#${record.n}  |  ${record.sender.value}  |  ${formatSize(record.size)}  |  $time"
        viewBinding.preview.text = record.preview
    }

    override fun isSameAs(other: com.xwray.groupie.Item<*>): Boolean =
        other is DataStreamReceivedItem &&
            other.record.n == record.n &&
            other.record.sender == record.sender

    private fun formatSize(bytes: Int): String =
        if (bytes < 1024) "${bytes}B" else "%.1fKB".format(bytes / 1024f)
}
