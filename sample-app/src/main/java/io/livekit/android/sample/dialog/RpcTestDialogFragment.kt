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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.sample.CallViewModel
import io.livekit.android.sample.RpcHandlerState
import io.livekit.android.sample.RpcInvocationRecord
import io.livekit.android.sample.RpcRequestResult
import io.livekit.android.sample.databinding.DialogRpcTestBinding
import io.livekit.android.sample.databinding.ItemRpcHandlerBinding
import io.livekit.android.sample.databinding.ItemRpcInvocationBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HELLO_PAYLOAD = "hello world"
private const val TWENTY_K_SIZE = 20 * 1024

private fun twentyKPayload(): String = "X".repeat(TWENTY_K_SIZE)

class RpcTestDialogFragment : DialogFragment() {

    private var _binding: DialogRpcTestBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CallViewModel by activityViewModels()

    private val participantsList = mutableListOf<RemoteParticipant>()
    private lateinit var participantSpinnerAdapter: ArrayAdapter<String>
    private val handlersAdapter = GroupieAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogRpcTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { dismiss() }

        participantSpinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf(),
        )
        participantSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.participantSpinner.adapter = participantSpinnerAdapter

        binding.presetHello.setOnClickListener { binding.payloadEdit.setText(HELLO_PAYLOAD) }
        binding.preset20k.setOnClickListener { binding.payloadEdit.setText(twentyKPayload()) }

        binding.sendButton.setOnClickListener { sendRpc() }

        binding.registerButton.setOnClickListener {
            val topic = binding.topicEdit.text.toString().trim()
            if (topic.isEmpty()) {
                Toast.makeText(requireContext(), "Enter a topic", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.registerRpcHandler(topic, initialResponse = "")
            binding.topicEdit.text.clear()
        }

        binding.handlersList.layoutManager = LinearLayoutManager(requireContext())
        binding.handlersList.adapter = handlersAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.participants
                    .map { list -> list.filterIsInstance<RemoteParticipant>() }
                    .collect { remotes ->
                        participantsList.clear()
                        participantsList.addAll(remotes)
                        participantSpinnerAdapter.clear()
                        participantSpinnerAdapter.addAll(
                            remotes.map { it.identity?.value ?: "(unknown)" },
                        )
                    }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.handlers.collect { handlers ->
                    handlersAdapter.update(
                        handlers.map { RpcHandlerItem(it, viewModel) },
                    )
                }
            }
        }
    }

    private fun sendRpc() {
        val pos = binding.participantSpinner.selectedItemPosition
        if (pos == AdapterView.INVALID_POSITION || pos >= participantsList.size) {
            Toast.makeText(requireContext(), "No participant selected", Toast.LENGTH_SHORT).show()
            return
        }
        val identity = participantsList[pos].identity
        if (identity == null) {
            Toast.makeText(requireContext(), "Participant has no identity", Toast.LENGTH_SHORT).show()
            return
        }
        val method = binding.methodEdit.text.toString().trim()
        if (method.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a method name", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = binding.payloadEdit.text.toString()

        binding.sendButton.isEnabled = false
        binding.responseText.visibility = View.GONE
        binding.sendSpinner.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.performRpc(identity, method, payload)
            val b = _binding ?: return@launch
            b.sendSpinner.visibility = View.GONE
            b.sendButton.isEnabled = true
            b.responseText.visibility = View.VISIBLE
            b.responseText.text = when (result) {
                is RpcRequestResult.Success -> "Success:\n${result.response}"
                is RpcRequestResult.Error -> "Error: [${result.code ?: "?"}] ${result.message}"
            }
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
        binding.handlersList.adapter = null
        super.onDestroyView()
        _binding = null
    }
}

private val invocationTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

class RpcHandlerItem(
    private val state: RpcHandlerState,
    private val viewModel: CallViewModel,
) : BindableItem<ItemRpcHandlerBinding>() {

    private var scope: CoroutineScope? = null
    private var watcher: TextWatcher? = null

    override fun initializeViewBinding(view: View): ItemRpcHandlerBinding =
        ItemRpcHandlerBinding.bind(view)

    override fun getLayout(): Int = io.livekit.android.sample.R.layout.item_rpc_handler

    override fun bind(viewBinding: ItemRpcHandlerBinding, position: Int) {
        viewBinding.methodName.text = state.method

        viewBinding.unregisterButton.setOnClickListener {
            viewModel.unregisterRpcHandler(state.method)
        }

        // Bind the static-response edit. Remove any prior watcher before mutating text so
        // we don't fire it during programmatic updates.
        viewBinding.staticResponseEdit.removeTextChangedListener(watcher)
        val current = state.staticResponse.value
        if (viewBinding.staticResponseEdit.text?.toString() != current) {
            viewBinding.staticResponseEdit.setText(current)
        }
        val newWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                viewModel.updateStaticResponse(state.method, s?.toString() ?: "")
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }
        viewBinding.staticResponseEdit.addTextChangedListener(newWatcher)
        watcher = newWatcher

        viewBinding.handlerPresetHello.setOnClickListener {
            viewBinding.staticResponseEdit.setText(HELLO_PAYLOAD)
        }
        viewBinding.handlerPreset20k.setOnClickListener {
            viewBinding.staticResponseEdit.setText(twentyKPayload())
        }

        val invocationsAdapter = GroupieAdapter()
        viewBinding.invocationList.layoutManager = LinearLayoutManager(viewBinding.root.context)
        viewBinding.invocationList.adapter = invocationsAdapter

        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        newScope.launch {
            state.invocations.collect { records ->
                viewBinding.emptyLabel.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                invocationsAdapter.update(records.map { RpcInvocationItem(it) })
            }
        }
    }

    override fun unbind(viewHolder: GroupieViewHolder<ItemRpcHandlerBinding>) {
        scope?.cancel()
        scope = null
        viewHolder.binding.staticResponseEdit.removeTextChangedListener(watcher)
        watcher = null
        viewHolder.binding.invocationList.adapter = null
        super.unbind(viewHolder)
    }

    override fun isSameAs(other: com.xwray.groupie.Item<*>): Boolean =
        other is RpcHandlerItem && other.state.method == state.method

    override fun hasSameContentAs(other: com.xwray.groupie.Item<*>): Boolean =
        other is RpcHandlerItem && other.state === state
}

class RpcInvocationItem(private val record: RpcInvocationRecord) :
    BindableItem<ItemRpcInvocationBinding>() {

    override fun initializeViewBinding(view: View): ItemRpcInvocationBinding =
        ItemRpcInvocationBinding.bind(view)

    override fun getLayout(): Int = io.livekit.android.sample.R.layout.item_rpc_invocation

    override fun bind(viewBinding: ItemRpcInvocationBinding, position: Int) {
        val time = invocationTimeFormat.format(Date(record.timestamp))
        viewBinding.timestamp.text = "$time  ${record.caller.value}"
        val bytes = record.payload.toByteArray(Charsets.UTF_8).size
        viewBinding.byteLength.text = "${bytes}B"
        viewBinding.payloadText.text = record.payload
    }

    override fun isSameAs(other: com.xwray.groupie.Item<*>): Boolean =
        other is RpcInvocationItem &&
            other.record.timestamp == record.timestamp &&
            other.record.caller == record.caller
}
