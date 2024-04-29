package com.nitsuya.quickding

import com.topjohnwu.superuser.Shell

class Application: android.app.Application() {
    init {
        try {
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
        } catch (e : Exception){
        }
    }
}