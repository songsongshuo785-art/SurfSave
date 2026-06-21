package com.myAllVideoBrowser.ui.main.base

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class BaseViewModel : ViewModel() {

    abstract fun start()

    abstract fun stop()

    protected fun isMainThread(): Boolean {
        return Looper.myLooper() == Looper.getMainLooper()
    }

    protected fun runOnMain(block: () -> Unit) {
        if (isMainThread()) {
            block()
        } else {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                block()
            }
        }
    }
}
