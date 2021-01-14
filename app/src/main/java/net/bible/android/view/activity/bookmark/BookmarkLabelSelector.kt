/*
 * Copyright (c) 2020 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */
package net.bible.android.view.activity.bookmark

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ListView
import net.bible.android.activity.R
import net.bible.android.control.bookmark.BookmarkControl
import net.bible.android.view.activity.base.IntentHelper
import net.bible.android.view.activity.base.ListActivityBase
import net.bible.android.database.bookmarks.BookmarkEntities.Label
import java.util.*
import javax.inject.Inject

/**
 * Choose which labels to associate with a bookmark
 *
 * @author Martin Denham [mjdenham at gmail dot com]
 */
class BookmarkLabelSelector : ListActivityBase() {
    @Inject lateinit var bookmarkControl: BookmarkControl

    private val labels: MutableList<Label> = ArrayList()
    @Inject lateinit var labelDialogs: LabelDialogs
    var showUnassigned = false

    /** Called when the activity is first created.  */
    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState, false)
        setContentView(R.layout.bookmark_labels)
        buildActivityComponent().inject(this)
        val selectedLabelIds = intent.getLongArrayExtra(BookmarkControl.LABEL_IDS_EXTRA)!!
        showUnassigned = intent.getBooleanExtra("showUnassigned", false)
        val title = intent.getStringExtra("title")
        if(title!=null) {
            setTitle(title)
        }
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        loadLabelList()
        val listArrayAdapter = BookmarkLabelItemAdapter(this, labels)
        listAdapter = listArrayAdapter
        checkedLabels = labels.filter { it.id in selectedLabelIds }
    }

    /** Finished selecting labels
     */
    fun onOkay(v: View?) {
        Log.i(TAG, "Okay clicked")
        val result = Intent()
        val labelIds = checkedLabels.map { it.id }.toLongArray()
        result.putExtra(BookmarkControl.LABEL_IDS_EXTRA, labelIds)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    fun onCancel(v: View?) {
        setResult(Activity.RESULT_CANCELED)
        finish();
    }

    /**
     * New Label requested
     */
    fun onNewLabel(v: View) {
        Log.i(TAG, "New label clicked")
        val newLabel = Label()
        labelDialogs.createLabel(this, newLabel) {
            val selectedLabels = checkedLabels
            Log.d(TAG, "Num labels checked pre reload:" + selectedLabels.size)
            loadLabelList()
            checkedLabels = selectedLabels
            Log.d(TAG, "Num labels checked finally:" + selectedLabels.size)
        }
    }

    /** load list of docs to display
     *
     */
    private fun loadLabelList() {

        // get long book names to show in the select list
        // must clear rather than create because the adapter is linked to this specific list
        labels.clear()
        labels.addAll(bookmarkControl.assignableLabels)
        if(showUnassigned) {
            labels.add(bookmarkControl.LABEL_UNLABELLED)
        }

        // ensure ui is updated
        notifyDataSetChanged()
    }

    private var checkedLabels: List<Label>
        get() {
            // get selected labels
            val listView = listView
            val checkedLabels: MutableList<Label> = ArrayList()
            for (i in labels.indices) {
                if (listView.isItemChecked(i)) {
                    val label = labels[i]
                    checkedLabels.add(label)
                    Log.d(TAG, "Selected " + label.name)
                }
            }
            return checkedLabels
        }
        set(labelsToCheck) {
            for (i in labels.indices) {
                if (labelsToCheck.contains(labels[i])) {
                    listView.setItemChecked(i, true)
                } else {
                    listView.setItemChecked(i, false)
                }
            }

            // ensure ui is updated
            notifyDataSetChanged()
        }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        val inflater = menuInflater
        inflater.inflate(R.menu.bookmark_labels_actionbar_menu, menu)
        return true
    }

    fun onManageLabels(v: View?) {
        val intent = Intent(this, ManageLabels::class.java)
        startActivityForResult(intent, IntentHelper.REFRESH_DISPLAY_ON_FINISH)
    }

    /**
     * on Click handlers
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        var isHandled = false
        when (item.itemId) {
            R.id.manageLabels -> {
                isHandled = true
                val intent = Intent(this, ManageLabels::class.java)
                startActivityForResult(intent, IntentHelper.REFRESH_DISPLAY_ON_FINISH)
            }
        }
        if (!isHandled) {
            isHandled = super.onOptionsItemSelected(item)
        }
        return isHandled
    }

    @SuppressLint("MissingSuperCall")
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "Restoring state after return from label editing")
        if (requestCode == IntentHelper.REFRESH_DISPLAY_ON_FINISH) {
            // find checked labels prior to refresh
            val selectedLabels = checkedLabels

            // reload labels with new and/or amended labels
            loadLabelList()

            // re-check labels as they were before leaving this screen
            checkedLabels = selectedLabels
        }
    }

    companion object {
        private const val TAG = "BookmarkLabels"

        // this resource returns a CheckedTextView which has setChecked(..), isChecked(), and toggle() methods
        private const val LIST_ITEM_TYPE = android.R.layout.simple_list_item_multiple_choice
    }
}
