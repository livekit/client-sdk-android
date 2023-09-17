package io.livekit.android.sample

import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

typealias CreateViewModel<VM> = () -> VM

inline fun <reified VM : ViewModel> FragmentActivity.viewModelByFactory(
    noinline create: CreateViewModel<VM>
): Lazy<VM> {
    return viewModels {
        createViewModelFactoryFactory(create)
    }
}

fun <VM> createViewModelFactoryFactory(
    create: CreateViewModel<VM>
): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return create() as? T
                ?: throw IllegalArgumentException("Unknown viewmodel class!")
        }
    }
}
