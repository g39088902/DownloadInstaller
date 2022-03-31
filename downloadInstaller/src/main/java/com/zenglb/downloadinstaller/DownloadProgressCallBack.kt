package com.zenglb.downloadinstaller

import java.lang.Exception

/**
 * 下载进度回调
 *
 */
interface DownloadProgressCallBack {
    fun downloadProgress(progress: Int)
    fun downloadException(e: Exception?)
    fun onInstallStart()
}