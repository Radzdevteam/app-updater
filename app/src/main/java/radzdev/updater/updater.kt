package com.radzdev.radzupdater

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.Manifest
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi

class Updater(
    private val context: Context,
    private val updateUrl: String
) {
    private val client = OkHttpClient()
    private val checker =
        "aHR0cHM6Ly9yYXcuZ2l0aHViLmNvbS9SYWR6ZGV2dGVhbS9TZWN1cml0eVBhY2thZ2VDaGVja2VyL3JlZnMvaGVhZHMvbWFpbi9jaGVja2Vy"
    private lateinit var progressDialog: ProgressDialog

    fun checkForUpdates() {
        if (!checkPermissionStorage()) {
            showPermissionDialog()
        } else {
            validatePackage()
        }
    }

    private fun checkPermissionStorage(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val writePermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            readPermission == PackageManager.PERMISSION_GRANTED && writePermission == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dialog = AlertDialog.Builder(context)
                .setTitle("Permission Required")
                .setMessage("To install APKs from your Downloads folder, please grant permission to manage all files.")
                .setCancelable(false)
                .setPositiveButton("Grant Permission") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.addCategory("android.intent.category.DEFAULT")
                        intent.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Error opening permission screen: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Exit") { _, _ ->
                    (context as? ComponentActivity)?.finish()
                }
                .create()
            dialog.show()
        }
    }

    private fun validatePackage() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val decodedUrl = String(android.util.Base64.decode(checker, android.util.Base64.DEFAULT))
                val request = Request.Builder().url(decodedUrl).build()
                val response: Response = client.newCall(request).execute()
                val jsonResponse = response.body?.string()

                if (!jsonResponse.isNullOrEmpty()) {
                    val jsonObject = JSONObject(jsonResponse)
                    if (jsonObject.has("valid_packages")) {
                        val validPackages = jsonObject.optJSONArray("valid_packages")
                        val isPackageValid = (0 until validPackages!!.length()).any { index ->
                            validPackages.getString(index).trim() == context.packageName.trim()
                        }

                        if (isPackageValid) {
                            fetchUpdateDetails()
                        } else {
                            showInvalidPackageDialog()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showValidationError("Failed to validate package.")
            }
        }
    }

    private fun fetchUpdateDetails() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(updateUrl).build()
                val response: Response = client.newCall(request).execute()
                val jsonResponse = response.body?.string()

                if (!jsonResponse.isNullOrEmpty()) {
                    val jsonObject = JSONObject(jsonResponse)
                    val latestVersion = jsonObject.getString("latestVersion")
                    val releaseNotes = jsonObject.getJSONArray("releaseNotes")
                    val url = jsonObject.getString("url")

                    val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    if (latestVersion != currentVersion) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(latestVersion, releaseNotes, url)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showUpdateDialog(latestVersion: String, releaseNotes: JSONArray, url: String) {
        val releaseNotesText = (0 until releaseNotes.length()).joinToString("\n") { releaseNotes.getString(it) }

        AlertDialog.Builder(context)
            .setTitle("Update Available: $latestVersion")
            .setMessage("Release Notes:\n$releaseNotesText")
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ ->
                downloadAPK(url)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAPK(url: String) {
        progressDialog = ProgressDialog(context).apply {
            setTitle("Downloading Update")
            setMessage("Please wait...")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(url).build()
                val response: Response = client.newCall(request).execute()
                val inputStream = response.body?.byteStream()
                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")

                inputStream?.let { input ->
                    val outputStream = file.outputStream()
                    val totalSize = response.body?.contentLength() ?: 0
                    val buffer = ByteArray(1024)
                    var downloaded = 0
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        downloaded += read
                        val progress = (downloaded * 100 / totalSize).toInt()

                        withContext(Dispatchers.Main) {
                            progressDialog.progress = progress
                        }
                    }

                    outputStream.close()
                    input.close()

                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        installAPK(file)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun installAPK(apkFile: File) {
        if (apkFile.exists()) {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Error opening APK: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("APK Install", "Error opening APK", e)
            }
        }
    }

    private fun showInvalidPackageDialog() {
        AlertDialog.Builder(context)
            .setTitle("App Access Denied")
            .setMessage("Access to the app is restricted. Please contact the developer.")
            .setCancelable(false)
            .setPositiveButton("Exit", null)
            .show()
    }

    private fun showValidationError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
