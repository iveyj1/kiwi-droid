package org.kiwidroid.app

import android.app.Application

class KiwiApplication : Application() {
    val radio: RadioController by lazy { RadioController(this) }
}
