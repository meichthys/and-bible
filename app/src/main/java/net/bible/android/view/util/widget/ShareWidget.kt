/*
 * Copyright (c) 2021 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
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

package net.bible.android.view.util.widget

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import net.bible.android.activity.R
import net.bible.android.activity.databinding.ShareVersesBinding
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.event.ToastEvent
import net.bible.android.database.bookmarks.BookmarkEntities
import net.bible.android.view.activity.page.Selection
import net.bible.service.common.CommonUtils
import net.bible.service.sword.SwordContentFacade
import javax.inject.Inject

class ShareWidget(context: Context, attributeSet: AttributeSet?, val selection: Selection):
    LinearLayout(context, attributeSet) {
    val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    private val bindings = ShareVersesBinding.inflate(inflater, this, true)
    @Inject lateinit var swordContentFacade: SwordContentFacade

    init {
        CommonUtils.buildActivityComponent().inject(this)
        bindings.run {
            toggleFullVerses.isChecked = CommonUtils.sharedPreferences.getBoolean("share_toggle_full", false)
            toggleVersenumbers.isChecked = CommonUtils.sharedPreferences.getBoolean("share_verse_numbers", true)
            advertise.isChecked = CommonUtils.sharedPreferences.getBoolean("share_show_add", true)
            abbreviateReference.isChecked = CommonUtils.sharedPreferences.getBoolean("share_abbreviate_reference", true)
            toggleNotes.visibility = if(selection.notes!= null) View.VISIBLE else View.GONE
            toggleNotes.isChecked = CommonUtils.sharedPreferences.getBoolean("show_notes", true)

            toggleFullVerses.setOnClickListener { updateText()}
            toggleVersenumbers.setOnClickListener { updateText()}
            advertise.setOnClickListener { updateText()}
            abbreviateReference.setOnClickListener { updateText()}
            toggleNotes.setOnClickListener { updateText()}
        }
        updateText()
    }

    private fun updateText() {
        val text = swordContentFacade.getSelectionText(selection,
            showVerseNumbers = bindings.toggleVersenumbers.isChecked,
            showFull = bindings.toggleFullVerses.isChecked,
            advertiseApp = bindings.advertise.isChecked,
            abbreviateReference = bindings.abbreviateReference.isChecked,
            showNotes = bindings.toggleNotes.isChecked,
        )
        bindings.preview.text = text
        CommonUtils.sharedPreferences.edit()
            .putBoolean("share_toggle_full", bindings.toggleFullVerses.isChecked)
            .putBoolean("share_verse_numbers", bindings.toggleVersenumbers.isChecked)
            .putBoolean("share_show_add", bindings.advertise.isChecked)
            .putBoolean("share_abbreviate_reference", bindings.abbreviateReference.isChecked)
            .putBoolean("show_notes", bindings.toggleNotes.isChecked)
            .apply()
    }

    companion object {
        fun dialog(context: Context, selection: Selection) {
            AlertDialog.Builder(context).apply {
                val layout = ShareWidget(context, null, selection)
                setView(layout)
                setPositiveButton(R.string.share_verse_ok) {
                    _, _ ->

                    val emailIntent = Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_TEXT, layout.bindings.preview.text)
                        type = "text/plain"
                    }
                    val chooserIntent = Intent.createChooser(emailIntent, context.getString(R.string.share_verse_menu_title))
                    context.startActivity(chooserIntent)

                }
                setCancelable(true)
                setNeutralButton(R.string.cancel, null)
                setNegativeButton(R.string.verse_action_copy) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(selection.verseRange.name, layout.bindings.preview.text)
                    clipboard.setPrimaryClip(clip)
                    ABEventBus.getDefault().post(ToastEvent(context.getString(R.string.text_copied_to_clicpboard)))
                }
                setTitle(R.string.share_verse_widget_title)
                create().show()
            }
        }
        fun dialog(context: Context, bookmark: BookmarkEntities.Bookmark) =
            dialog(context, Selection(bookmark))
    }
}
