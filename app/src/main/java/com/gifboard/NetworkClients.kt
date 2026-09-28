package com.gifboard

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttpClient for all network access (Fresco image fetches,
 * provider searches, and GIF downloads) so connections, sockets and thread
 * pools are reused instead of every caller spinning up its own client.
 */
object NetworkClients {
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }
}
