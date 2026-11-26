package com.lilyroksanne.mon

import android.content.Context
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import com.lilyroksanne.mon.data.db.MemoryRepository

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val memoryRepo = MemoryRepository(application)
    internal val music = LocalMp3Player(
        application, application.getSharedPreferences("MonMp3", Context.MODE_PRIVATE)
    )

    override fun onCleared() {
        music.close()
    }
}
