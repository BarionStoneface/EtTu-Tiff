package com.barion.filmscans

import android.app.Application

/** Holds the one [AppModel], so work in progress outlives the screen. */
class EtTuTiffApp : Application() {
    val model by lazy { AppModel(this) }
}
