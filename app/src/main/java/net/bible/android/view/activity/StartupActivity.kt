/*
 * Copyright (c) 2020-2022 Martin Denham, Tuomas Airaksinen and the AndBible contributors.
 *
 * This file is part of AndBible: Bible Study (http://github.com/AndBible/and-bible).
 *
 * AndBible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * AndBible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with AndBible.
 * If not, see http://www.gnu.org/licenses/.
 */

package net.bible.android.view.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

import net.bible.android.BibleApplication
import net.bible.android.SharedConstants
import net.bible.android.activity.R
import net.bible.android.activity.databinding.SpinnerBinding
import net.bible.android.activity.databinding.StartupViewBinding
import net.bible.android.control.backup.BackupControl
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.report.ErrorReportControl
import net.bible.android.database.SwordDocumentInfo
import net.bible.android.view.activity.base.CurrentActivityHolder
import net.bible.android.view.activity.base.CustomTitlebarActivityBase
import net.bible.android.view.activity.base.Dialogs
import net.bible.android.view.activity.base.firstTime
import net.bible.android.view.activity.discrete.CalculatorActivity
import net.bible.android.view.activity.download.DownloadActivity
import net.bible.android.view.activity.download.FirstDownload
import net.bible.android.view.activity.installzip.InstallZip
import net.bible.android.view.activity.installzip.InstallZipEvent
import net.bible.android.view.activity.page.MainBibleActivity
import net.bible.service.common.BuildVariant
import net.bible.service.common.CommonUtils
import net.bible.service.common.CommonUtils.checkPoorTranslations
import net.bible.service.common.CommonUtils.json
import net.bible.service.common.htmlToSpan
import net.bible.service.db.DatabaseContainer
import net.bible.service.sword.SwordDocumentFacade

import org.apache.commons.lang3.StringUtils
import java.util.*


var comingFromStartupActivity = false

/** Called first to show download screen if no documents exist
 *
 * @author Martin Denham [mjdenham at gmail dot com]
 */
open class StartupActivity : CustomTitlebarActivityBase() {
    private lateinit var spinnerBinding: SpinnerBinding
    private lateinit var startupViewBinding: StartupViewBinding

    private val docsDao get() = DatabaseContainer.instance.repoDb.swordDocumentInfoDao()
    private val previousInstallDetected: Boolean get() = docsDao.getKnownInstalled().isNotEmpty();
    override val doNotInitializeApp = true

    private suspend fun getListOfBooksUserWantsToRedownload(context: Context) : List<SwordDocumentInfo>? {
        var result: List<SwordDocumentInfo>?;
        withContext(Dispatchers.Main) {
            result = suspendCoroutine {
                val books = docsDao.getKnownInstalled().sortedBy { it.language }
                val bookNames = books.map {
                    context.getString(R.string.something_with_parenthesis, it.name, it.language)
                }.toTypedArray()

                val checkedItems = bookNames.map { true }.toBooleanArray()
                val dialog = AlertDialog.Builder(context)
                    .setPositiveButton(R.string.okay) { d, _ ->
                        val selectedBooks = books.filterIndexed { index, book -> checkedItems[index] }
                        if(selectedBooks.isEmpty()) {
                            it.resume(null)
                        } else {
                            it.resume(selectedBooks)
                        }
                    }
                    .setMultiChoiceItems(bookNames, checkedItems) { _, pos, value ->
                        checkedItems[pos] = value
                    }
                    .setNeutralButton(R.string.select_none) { _, _ -> it.resume(null) }
                    .setNegativeButton(R.string.cancel) { _, _ -> it.resume(null) }
                    .setOnCancelListener {_ -> it.resume(null)}
                    .setTitle(getString(R.string.redownload))
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val allSelected = checkedItems.find { !it } == null
                        val newValue = !allSelected
                        val v = dialog.listView
                        for (i in 0 until v.count) {
                            v.setItemChecked(i, newValue)
                            checkedItems[i] = newValue
                        }
                        (it as Button).text = getString(if (allSelected) R.string.select_all else R.string.select_none)
                    }
                }
                dialog.show()
                CommonUtils.fixAlertDialogButtons(dialog)
            }
        }
        return result;
    }

    private suspend fun checkForExternalStorage(): Boolean {
        var time = 0L
        val delayMillis = 50L
        val timeout = 15000L
        var success = true
        while(Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            delay(delayMillis)
            time += delayMillis;
            if(time > timeout) {
                Log.e(TAG, "waitForBibleView timed out")
                success = false
                break
            }
        }
        if(!success) {
            Dialogs.showMsg2(this@StartupActivity, R.string.no_sdcard_error)
            finish()
        }
        return success
    }

    private suspend fun checkWebView(): Boolean {
        val info = WebViewCompat.getCurrentWebViewPackage(applicationContext)
        Log.i(TAG, "checkWebView: WebView version ${info?.packageName} ${info?.versionName}")

        if(info?.packageName == "com.huawei.webview") return true // We won't check huawei version number as it does not follow Chromium version numbering.

        val versionNum = info?.versionName?.split(".")?.first()?.split(" ")?.first()?.toIntOrNull() ?: return true // null -> can't check
        val minimumVersion = 83 // tested with Android Emulator API 30 and looks to function OK
        if(versionNum < minimumVersion) {
            val playUrl = "https://play.google.com/store/apps/details?id=${info.packageName}"
            val playLink = "<a href=\"$playUrl\">${getString(R.string.play)}</a>"

            val msg = getString(R.string.old_webview, info.versionName, minimumVersion.toString(), getString(R.string.app_name_medium), playLink)

            val spanned = htmlToSpan(msg)

            return suspendCoroutine {
                val dlgBuilder = AlertDialog.Builder(this)
                    .setMessage(spanned)
                    .setCancelable(false)
                    .setPositiveButton(R.string.proceed_anyway) { _, _ -> it.resume(true) }
                    .setNeutralButton(R.string.close) { _, _ ->
                        it.resume(false)
                        finish()
                    }

                val d = dlgBuilder.show()
                d.findViewById<TextView>(android.R.id.message)!!.movementMethod = LinkMovementMethod.getInstance()
            }
        }
        return true
    }

    /** Called when the activity is first created.  */
    @SuppressLint("ApplySharedPref")
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "StartupActivity.onCreate")
        super.onCreate(savedInstanceState)
        ABEventBus.register(this)
        spinnerBinding = SpinnerBinding.inflate(layoutInflater)
        if(CommonUtils.isDiscrete) {
            spinnerBinding.imageView.setImageResource(
                R.drawable.ic_calculator_color
            )
            spinnerBinding.splashTitleText.text = getString(R.string.app_name_calculator)
        }
        startupViewBinding = StartupViewBinding.inflate(layoutInflater)
        setContentView(spinnerBinding.root)
        buildActivityComponent().inject(this)
        supportActionBar!!.hide()

        lifecycleScope.launch {
            if (!checkForExternalStorage()) return@launch
            if(!BuildVariant.Appearance.isDiscrete) {
                ErrorReportControl.checkCrash(this@StartupActivity)
            }
            // fixNightMode() for StartupActivity needs to be here
            if(firstTime) {
                firstTime = false
                delay(250)
                recreate()
                return@launch
            }

            // switch back to ui thread to continue
            postBasicInitialisationControl()
        }
    }

    override fun onDestroy() {
        ABEventBus.unregister(this)
        super.onDestroy()
    }

    private suspend fun initializeDatabase() {
        withContext(Dispatchers.IO) {
            DatabaseContainer.ready = true
            DatabaseContainer.instance
        }
    }

    private suspend fun postBasicInitialisationControl() = withContext(Dispatchers.Main) {
        if(!checkWebView()) return@withContext

        // When I mess up database, I can re-create database like this.
        //BackupControl.deleteAllDatabases()

        initializeDatabase()

        // When enabled, go to the calculator first,
        // even when there are no Bible documents already installed.
        if(!checkCalculator()) return@withContext
        if(BuildVariant.Appearance.isDiscrete) {
            ErrorReportControl.checkCrash(this@StartupActivity)
        }
        if (SwordDocumentFacade.bibles.isEmpty()) {
            Log.i(TAG, "Invoking download activity because no bibles exist")
            // only show the splash screen if user has no bibles
            if(!checkPoorTranslations(this@StartupActivity)) return@withContext
            showFirstLayout()

        } else {
            Log.i(TAG, "Going to main bible view")
            spinnerBinding.progressText.text =getString(R.string.initializing_app)
            gotoMainBibleActivity()
        }
    }

    private fun showFirstLayout() {
        setContentView(startupViewBinding.root)

        val versionMsg = BibleApplication.application.getString(R.string.version_text, CommonUtils.applicationVersionName)

        startupViewBinding.run {
            val welcome = getString(R.string.welcome_message, getString(R.string.app_name_long))
            val zip = getString(R.string.format_zip, getString(R.string.app_name_andbible))
            val myBible = getString(R.string.format_mybible)
            val mySword = getString(R.string.format_mysword)
            val epub = getString(R.string.format_epub)
            val fromFiles = getString(R.string.install_zip)
            val formats = getString(R.string.supported_formats, "$zip, $myBible, $mySword, $epub")
            fromFilesMessage.text = htmlToSpan("<b>$fromFiles</b><br/><br/>$formats")
            welcomeMessage.text = welcome
            versionText.text = versionMsg
            downloadButton.setOnClickListener { doGotoDownloadActivity() }
            importButton.setOnClickListener { onLoadFromZip() }
            if (previousInstallDetected) {
                Log.i(TAG, "A previous install was detected")
                redownloadMessage.visibility = View.VISIBLE
                redownloadButton.visibility = View.VISIBLE
                restoreDatabaseButton.visibility = View.GONE
                redownloadButton.setOnClickListener {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val books = getListOfBooksUserWantsToRedownload(this@StartupActivity);
                        if (books != null) {
                            val intent = Intent(this@StartupActivity, FirstDownload::class.java)
                            intent.putExtra(DownloadActivity.DOCUMENT_IDS_EXTRA, json.encodeToString(serializer(), books))
                            lifecycleScope.launch {
                                awaitIntent(intent)
                                afterDownload()
                            }
                        }
                    }
                }
            } else {
                Log.i(TAG, "Showing restore button because nothing to redownload")
                restoreDatabaseButton.visibility = View.VISIBLE
                restoreDatabaseButton.setOnClickListener { restoreDatabase() }
            }
            // Enabling this for english only in 4.0. Later we may enable this for other languages.
            if(Locale.getDefault().language == "en") {
                easyStartMessage.visibility = View.VISIBLE
                easyStartButton.visibility = View.VISIBLE
                easyStartButton.setOnClickListener { easyStart() }
            }
        }

    }

    private fun easyStart() {
        val intent = Intent(this@StartupActivity, FirstDownload::class.java)
        intent.putExtra("download-recommended", true)
        lifecycleScope.launch {
            awaitIntent(intent)
            afterDownload()
        }
    }

    private fun afterDownload() {
        Log.i(TAG, "Returned from Download")
        if (SwordDocumentFacade.bibles.isNotEmpty()) {
            Log.i(TAG, "Bibles now exist so go to main bible view")
            // select appropriate default verse e.g. John 3.16 if NT only
            lifecycleScope.launch(Dispatchers.Main) {
                gotoMainBibleActivity()
            }

        } else {
            Log.i(TAG, "No Bibles exist so start again")
            lifecycleScope.launch(Dispatchers.Main) {
                postBasicInitialisationControl()
            }
        }
    }

    private fun restoreDatabase() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "application/*"
        lifecycleScope.launch {
            val result = awaitIntent(intent)
            CurrentActivityHolder.activate(this@StartupActivity)
            if (result.resultCode == Activity.RESULT_OK) {
                val inputStream = contentResolver.openInputStream(result.data!!.data!!) ?: return@launch
                if (BackupControl.restoreAppDatabaseFromInputStreamWithUI(this@StartupActivity, inputStream)) {
                    Log.i(TAG, "Restored database successfully")
                    postBasicInitialisationControl()
                }
            }
        }
    }

    private fun doGotoDownloadActivity() {
        var errorMessage: String? = null

        if (CommonUtils.megabytesFree < SharedConstants.REQUIRED_MEGS_FOR_DOWNLOADS) {
            errorMessage = getString(R.string.storage_space_warning)
        }

        if (StringUtils.isBlank(errorMessage)) {
            val handlerIntent = Intent(this, FirstDownload::class.java)
            lifecycleScope.launch {
                awaitIntent(handlerIntent)
                afterDownload()
            }
        } else {
            Dialogs.showErrorMsg(errorMessage) { finish() }
        }
    }

    /**
     * Load from Zip link on first_time_dialog has been clicked
     */
    private fun onLoadFromZip() {
        Log.i(TAG, "Load from Zip clicked")
        val handlerIntent = Intent(this, InstallZip::class.java).apply { putExtra("doNotInitializeApp", true) }
        lifecycleScope.launch {
            awaitIntent(handlerIntent)
            afterDownload()
        }
    }

    private suspend fun checkCalculator(): Boolean {
        if(CommonUtils.showCalculator) {
            Log.i(TAG, "Going to Calculator")
            val handlerIntent = Intent(this, CalculatorActivity::class.java)
            while(true) {
                when(awaitIntent(handlerIntent).resultCode) {
                    RESULT_OK -> break
                    RESULT_CANCELED -> {
                        finish()
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun gotoMainBibleActivity() {
        Log.i(TAG, "Going to MainBibleActivity")
        val handlerIntent = Intent(this, MainBibleActivity::class.java)
        if(intent?.action == Intent.ACTION_VIEW) {
            handlerIntent.putExtra("openLink", intent.dataString)
            handlerIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        } else {
            handlerIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        lifecycleScope.launch(Dispatchers.Main) {
            if(SwordDocumentFacade.bibles.none { !it.isLocked }) {
                for (it in SwordDocumentFacade.bibles.filter { it.isLocked }) {
                    CommonUtils.unlockDocument(this@StartupActivity, it)
                }
                if (SwordDocumentFacade.bibles.none { !it.isLocked }) {
                    showFirstLayout()
                    return@launch
                }
            }
            CommonUtils.initializeAppCoroutine()
            comingFromStartupActivity = true
            startActivity(handlerIntent)
            finish()
        }
    }

    fun onEventMainThread(e: InstallZipEvent) {
        spinnerBinding.progressText.text = e.message
    }

    companion object {
        private val TAG = "StartupActivity"
    }
}
