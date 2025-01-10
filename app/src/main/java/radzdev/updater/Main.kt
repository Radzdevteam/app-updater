@file:Suppress("DEPRECATION")

package radzdev.updater

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*
import android.app.ProgressDialog
import org.json.JSONArray
import android.Manifest
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

class Main : ComponentActivity() {

    private val updateUrl = "https://raw.githubusercontent.com/Radzdevteam/test/refs/heads/main/updatertest"
    private val client = OkHttpClient()
    private val checker = "aHR0cHM6Ly9yYXcuZ2l0aHViLmNvbS9SYWR6ZGV2dGVhbS9TZWN1cml0eVBhY2thZ2VDaGVja2VyL3JlZnMvaGVhZHMvbWFpbi9jaGVja2Vy"
    private lateinit var progressDialog: ProgressDialog

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!checkPermissionStorage()) {
            showPermissionDialog()
        } else {
            checkForUpdates()
        }
    }

    private fun checkPermissionStorage(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()  // Check for access to manage all files
        } else {
            val readPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val writePermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            readPermission == PackageManager.PERMISSION_GRANTED && writePermission == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun showPermissionDialog() {
        // Check if the device is running Android 11 (API 30) or higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("To install APKs from your Downloads folder, please grant permission to manage all files.")
                .setCancelable(false)
                .setPositiveButton("Grant Permission") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.addCategory("android.intent.category.DEFAULT")
                        intent.data = Uri.parse("package:${packageName}")
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                        } else {
                            Toast.makeText(this, "Permission screen not available.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this, "Error opening permission screen: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Exit") { _, _ ->
                    finishAffinity()
                    exitProcess(0)
                }
                .create()
            dialog.show()
        } else {
            // On older Android versions, grant permission is not required
            checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        // Automatically check for updates on app startup
        validatePackage()
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
                            validPackages.getString(index).trim() == packageName.trim()
                        }

                        if (isPackageValid) {
                            fetchUpdateDetails()
                        } else {
                            showInvalidPackageDialog()
                        }
                    } else {
                        showValidationError("Invalid response from server: missing 'valid_packages'.")
                    }
                } else {
                    showValidationError("Empty response from server.")
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

                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
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

        AlertDialog.Builder(this)
            .setTitle("Update Available: $latestVersion")
            .setMessage("Release Notes:\n$releaseNotesText")
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ ->
                downloadAPK(url)
            }
            .setNegativeButton("Later") { _, _ ->
                finishAffinity()
                exitProcess(0)
            }
            .show()
    }

    private fun downloadAPK(url: String) {
        progressDialog = ProgressDialog(this).apply {
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

                // Using app's private storage for better compatibility
                val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")

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
                    Toast.makeText(this@Main, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun installAPK(apkFile: File) {
        if (apkFile.exists()) {
            val apkUri: Uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Error opening APK: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("APK Install", "Error opening APK", e)
            }
        } else {
            Toast.makeText(this, "APK file not found!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInvalidPackageDialog() {
        AlertDialog.Builder(this)
            .setTitle("App Access Denied")
            .setMessage("Access to the app is restricted. Please contact the developer.")
            .setCancelable(false)
            .setPositiveButton("Exit") { _, _ ->
                finishAffinity()
                exitProcess(0)
            }
            .show()
    }

    private fun showValidationError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onResume() {
        super.onResume()

        if (!checkPermissionStorage()) {
            showPermissionDialog()
        } else {
            checkForUpdates()
        }
    }

}
