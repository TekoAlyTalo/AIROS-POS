package com.airos.pos.app

import android.app.Application

class AirosPosApplication : Application() {
    val appContainer: AppContainer by lazy {
        DefaultAppContainer(this)
    }
}
