package net.bible.android.view.activity.navigation;

import java.util.List;

import net.bible.android.activity.R;
import net.bible.android.control.ControlFactory;
import net.bible.android.control.document.DocumentControl;
import net.bible.android.control.download.DownloadControl;
import net.bible.android.view.activity.base.Dialogs;
import net.bible.android.view.activity.base.DocumentSelectionBase;
import net.bible.android.view.activity.download.Download;
import net.bible.android.view.activity.page.MenuCommandHandler;
import net.bible.service.sword.SwordDocumentFacade;

import org.crosswire.jsword.book.Book;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

/**
 * Choose a bible or commentary to use
 * 
 * @author Martin Denham [mjdenham at gmail dot com]
 * @see gnu.lgpl.License for license details.<br>
 *      The copyright to this program is held by it's author.
 */
public class ChooseDocument extends DocumentSelectionBase {
	private static final String TAG = "ChooseDocument";
	
	private DocumentControl documentControl = ControlFactory.getInstance().getDocumentControl();
	
	private DownloadControl downloadControl = ControlFactory.getInstance().getDownloadControl();
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	setInstallStatusIconsShown(false);
    	
        super.onCreate(savedInstanceState);
        
        setDeletePossible(true);
    	populateMasterDocumentList(false);
    }

	/** load list of docs to display
	 * 
	 */
    @Override
    protected List<Book> getDocumentsFromSource(boolean refresh) {
		Log.d(TAG, "get document list from source");
		return SwordDocumentFacade.getInstance().getDocuments();
	}

    @Override
    protected void handleDocumentSelection(Book selectedBook) {
    	Log.d(TAG, "Book selected:"+selectedBook.getInitials());
    	try {
    		documentControl.changeDocument(selectedBook);

    		// if key is valid then the new doc will have been shown already
			returnToPreviousScreen();
    	} catch (Exception e) {
    		Log.e(TAG, "error on select of bible book", e);
    	}
    }

    @Override
    protected void setInitialDocumentType() {
    	setSelectedBookCategory(documentControl.getCurrentCategory());
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.choose_document_menu, menu);
        return true;
    }

	/** 
     * on Click handlers
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean isHandled = false;
        
        switch (item.getItemId()) {
        // Change sort order
		case (R.id.downloadButton):
			isHandled = true;
	    	try {
	    		if (downloadControl.checkDownloadOkay()) {
	        		Intent handlerIntent = new Intent(this, Download.class);
	        		int requestCode = MenuCommandHandler.UPDATE_SUGGESTED_DOCUMENTS_ON_FINISH;
	        		startActivityForResult(handlerIntent, requestCode);
	        		
	        		// do not return here after download
	        		finish();
	    		}
	        } catch (Exception e) {
	        	Log.e(TAG, "Error sorting bookmarks", e);
	        	Dialogs.getInstance().showErrorMsg(R.string.error_occurred);
	        }

			break;
        }
        
		if (!isHandled) {
            isHandled = super.onOptionsItemSelected(item);
        }
        
     	return isHandled;
    }
}
