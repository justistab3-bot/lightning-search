package com.heikeji.phonesearch

import android.app.Application
import android.content.Context
import com.heikeji.phonesearch.account.SecureSessionStore
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.data.HistoryStore
import com.heikeji.phonesearch.net.ApiClient
import com.heikeji.phonesearch.net.DeviceIdentity
import com.heikeji.phonesearch.net.HttpTransport
import com.heikeji.phonesearch.net.ProtocolContext
import com.heikeji.phonesearch.search.SearchChallengeStore
import com.heikeji.phonesearch.search.SearchRepository

class SearchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 简单的依赖容器，避免引入 DI 框架。 */
class AppContainer(context: Context) {

    val identity = DeviceIdentity(context)
    val transport = HttpTransport()
    val protocol = ProtocolContext(context, identity, transport)

    private val sessionStore = SecureSessionStore(context)
    val sessions = SessionRepository(sessionStore)

    val apiClient = ApiClient(identity, protocol, sessions, transport)
    val challenges = SearchChallengeStore()
    val searchRepository = SearchRepository(apiClient, protocol, challenges)
    val history = HistoryStore(context)

    init {
        sessions.restore()
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SearchApp).container
