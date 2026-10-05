package com.jm.reader

import android.app.Application
import android.content.Context
import com.jm.reader.data.download.DownloadManager
import com.jm.reader.data.history.HistoryManager
import com.jm.reader.data.net.ApiClient
import com.jm.reader.data.net.HostManager
import com.jm.reader.data.repo.AppRepository
import com.jm.reader.data.session.SessionManager
import com.jm.reader.ui.strings.LanguageManager
import com.jm.reader.util.CrashHandler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class JMApplication : Application() {

    lateinit var session: SessionManager
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var repository: AppRepository
        private set
    lateinit var languageManager: LanguageManager
        private set
    lateinit var downloadManager: DownloadManager
        private set
    lateinit var historyManager: HistoryManager
        private set

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        session = SessionManager(this)
        languageManager = LanguageManager(session)

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        val hostManager = HostManager(session, http)
        apiClient = ApiClient(session, http)
        repository = AppRepository(session, apiClient, hostManager)
        downloadManager = DownloadManager(this, repository)
        historyManager = HistoryManager(this)
    }

    companion object {
        fun from(context: Context): JMApplication = context.applicationContext as JMApplication
    }
}
