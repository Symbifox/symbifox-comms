package com.bluefoxconsultant.sms

import android.app.Application
import com.bluefoxconsultant.sms.data.Graph

class BfSmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
