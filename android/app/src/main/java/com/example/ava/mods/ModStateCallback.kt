package com.example.ava.mods

import androidx.annotation.Keep

@Keep
abstract class ModStateCallback {
    @Keep
    abstract fun onStateChanged(value: Any?)
}
