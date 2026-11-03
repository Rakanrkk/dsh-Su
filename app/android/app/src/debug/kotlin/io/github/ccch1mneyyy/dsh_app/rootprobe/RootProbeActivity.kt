package io.github.ccch1mneyyy.dsh_app.rootprobe

import android.app.Activity
import android.os.Bundle

class RootProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mountRootProbe(this)
        finish()
    }
}