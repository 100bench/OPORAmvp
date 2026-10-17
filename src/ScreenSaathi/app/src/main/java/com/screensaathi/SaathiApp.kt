package com.screensaathi

import android.app.Application
import com.screensaathi.session.AndroidResumeCapsuleStore
import com.screensaathi.session.ResumeCapsuleStore

class SaathiApp : Application() {
    lateinit var resumeCapsuleStore: ResumeCapsuleStore
        private set

    override fun onCreate() {
        super.onCreate()
        resumeCapsuleStore = AndroidResumeCapsuleStore(this)
    }
}
