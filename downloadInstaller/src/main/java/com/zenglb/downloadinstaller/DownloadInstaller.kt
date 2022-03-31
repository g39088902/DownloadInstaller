package com.zenglb.downloadinstaller

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.*

enum class UpdateStatus {
    UN_DOWNLOAD,
    DOWNLOADING,
    DOWNLOAD_ERROR,
    UNINSTALL
}

class DownloadInstaller(
    private val mContext: Context,
    private var downloadApkUrl: String,
    private val downloadProgressCallBack: DownloadProgressCallBack
) {
    private var authority = ""
    private var progress = 0
    private var oldProgress = 0
    private var downloadApkUrlMd5 = ""

    //local saveFilePath
    private var storageApkPath = ""
    private var storagePrefix = ""
    private val isDownloadOnly = false

    /**
     * 获取16位的MD5 值，大写
     *
     * @param str
     * @return
     */
    private fun getUpperMD5Str16(str: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        messageDigest.reset()
        messageDigest.update(str.toByteArray(charset("UTF-8")))
        val byteArray = messageDigest.digest()
        val md5StrBuff = StringBuilder()
        for (b in byteArray) {
            if (Integer.toHexString(0xFF and b.toInt()).length == 1) md5StrBuff.append("0").append(
                Integer.toHexString(0xFF and b.toInt())
            ) else md5StrBuff.append(Integer.toHexString(0xFF and b.toInt()))
        }
        return md5StrBuff.toString().uppercase(Locale.getDefault()).substring(8, 24)
    }

    fun String.digest(algorithm: String): String {
        val byteArray = this.byteInputStream()
         val md = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(8192)
        generateSequence {
            when (val bytesRead = byteArray.read(buffer)) {
                -1 -> null
                else -> bytesRead
            }
        }.forEach { bytesRead -> md.update(buffer, 0, bytesRead) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * app下载升级管理
     */
    fun start() {
        downloadApkUrlMd5 = downloadApkUrl.digest("SHA-256")

        //https://developer.android.com/studio/build/application-id?hl=zh-cn
        authority = "${mContext.packageName}.fileProvider"

        //前缀要统一 一下 + AppUtils.getAppName(mContext)+"/Download/"
        storagePrefix = mContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.path + "/"
        storageApkPath = "$storagePrefix$downloadApkUrlMd5.apk"
        val downloadStatus = downLoadStatusMap[downloadApkUrlMd5]
        if (downloadStatus == null || downloadStatus == UpdateStatus.UN_DOWNLOAD || downloadStatus == UpdateStatus.DOWNLOAD_ERROR) {
            //如果没有正在下载&&没有下载好了还没有升级
            Thread(mDownApkRunnable).start()
        } else if (downloadStatus == UpdateStatus.DOWNLOADING) {
            Toast.makeText(mContext, "正在下载App", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 下载线程,使用最原始的HttpURLConnection，减少依赖
     * 大的APK下载还是比较慢的，后面改为多线程下载
     */
    private val mDownApkRunnable = Runnable {
        downLoadStatusMap[downloadApkUrlMd5] = UpdateStatus.DOWNLOADING
        try {
            val url = URL(downloadApkUrl)
            var conn = url.openConnection() as HttpURLConnection
            conn.connect()

            //处理下载重定向问题，302 CODE
            conn.instanceFollowRedirects = false
            if (conn.responseCode == 302) {
                //如果会重定向，保存302重定向地址，以及Cookies,然后重新发送请求(模拟请求)
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                downloadApkUrl = location
                conn = URL(downloadApkUrl).openConnection() as HttpURLConnection
            }
            val length = conn.contentLength
            val file = File(storagePrefix)
            if (!file.exists()) {
                file.mkdir()
            }
            val apkFile = File(storageApkPath)
            if (apkFile.exists() && apkFile.length() == length.toLong()) {
                //已经下载过了，直接的progress ==100,然后去安装
                progress = 100
                downloadProgressCallBack.downloadProgress(progress)
                if (!isDownloadOnly) {
                    (mContext as Activity).runOnUiThread {
                        downLoadStatusMap[downloadApkUrlMd5] = UpdateStatus.UNINSTALL
                        installProcess()
                    }
                }
                return@Runnable
            }
            val fos = FileOutputStream(apkFile)
            var count = 0
            val buf = ByteArray(2048)
            var byteCount: Int
            val `is` = conn.inputStream
            while (`is`.read(buf).also { byteCount = it } > 0) {
                count += byteCount
                progress = (count.toFloat() / length * 100).toInt()
                if (progress > oldProgress) {
                    downloadProgressCallBack.downloadProgress(progress)
                    oldProgress = progress
                }
                fos.write(buf, 0, byteCount)
            }
            if (!isDownloadOnly) {
                (mContext as Activity).runOnUiThread {
                    downLoadStatusMap[downloadApkUrlMd5] = UpdateStatus.UNINSTALL
                    installProcess()
                }
            }
            fos.flush()
            fos.close()
            `is`.close()
        } catch (e: Exception) {
            downLoadStatusMap[downloadApkUrlMd5] = UpdateStatus.DOWNLOAD_ERROR
            downloadProgressCallBack.downloadException(e)

            //后面有时间再完善异常的处理
            when {
                e is FileNotFoundException -> toastError(R.string.download_failure_file_not_found)
                e is ConnectException -> toastError(R.string.download_failure_net_deny)
                e is UnknownHostException -> toastError(R.string.download_failure_net_deny)
                e is UnknownServiceException -> toastError(R.string.download_failure_net_deny)
                e.toString().contains("Permission denied") -> toastError(R.string.download_failure_storage_permission_deny)
                else -> toastError(R.string.apk_update_download_failed)
            }
        }
    }

    /**
     * Toast error message
     *
     * @param id res id
     */
    private fun toastError(@StringRes id: Int) {
        Looper.prepare()
        Toast.makeText(mContext, mContext.resources.getString(id), Toast.LENGTH_LONG).show()
        Looper.loop()
    }

    /**
     * 安装过程处理
     */
    fun installProcess() {
        if (isDownloadOnly) return
        if (progress < 100) return
        val downloadStatus = downLoadStatusMap[downloadApkUrlMd5]!! //unboxing
        if (downloadStatus == UpdateStatus.UNINSTALL) {
            installApk()
            downLoadStatusMap[downloadApkUrlMd5] = UpdateStatus.UN_DOWNLOAD
        }
    }

    /**
     * 跳转到安装apk的页面
     */
    private fun installApk() {
        val apkFile = File(storageApkPath)
        if (!apkFile.exists()) {
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
        val contentUri = FileProvider.getUriForFile(mContext, authority, apkFile)
        intent.setDataAndType(contentUri, intentType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mContext.startActivity(intent)
        downloadProgressCallBack.onInstallStart()
    }

    companion object {
        private const val intentType = "application/vnd.android.package-archive"

        //保存下载状态信息，临时过度的方案。
        var downLoadStatusMap = mutableMapOf<String, UpdateStatus>()
    }
}
