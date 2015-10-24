package com.jokrapp.android;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;

import android.net.Uri;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.Hashtable;
import java.util.Map;

/**
 * Author/Copyright John C. Quinn, All Rights Reserved.
 *
 * class 'ImageStackCursorAdapter'
 *
 * custom implementation of SimpleCursorAdapter to allow images to be dynamically decoded
 * and loaded into the view. Maintains a reference to the database using cursors,
 * and dynamically swaps the cursors with their actual data once the data is finished loading
 */
public class ImageStackCursorAdapter extends CursorAdapter {
    private final boolean VERBOSE = true;
    private final String TAG = "ImageCursorStackAdapter";

        private MainActivity context;
        private int layout;
        private Uri table;
        private Map<String,ViewHolder> viewKey = new Hashtable<>();

    private LayoutInflater mLayoutInflater;

        /**
         * class 'ViewHolder'
         *
         * holds view data, that persists between newView and bindView
         * this allows bindView to store data to the correct objects
         */
        private class ViewHolder {
            //TextView textView;
            ImageView imageView;
            ProgressBar progressBar;

            ViewHolder(View v, String s) {
                progressBar = (ProgressBar) v.findViewById(R.id.local_post_progressbar);
                progressBar.setTag(s);
                imageView = (ImageView) v.findViewById(R.id.local_post_imageView);
            }
        }


    /**
     * method 'ImageStackCursorAdapter'
     *
     * constructor
     *
     * @param activity activity in which this adapter is created
     * @param layout the layout to be inflated
     * @param c data to be loaded
     * @param table projection of what columns to be loaded
     * @param to resource values to for those columns to be loaded to
     * @param flags options
     */
    public ImageStackCursorAdapter(MainActivity activity, int layout, Cursor c, Uri table, int[] to, int flags) {
        super(activity, c, flags);
        this.context = activity;
        this.layout = layout;
        mLayoutInflater = LayoutInflater.from(context);
        this.table = table;
    }

    /**
     * method 'newView'
     *
     * creates a new view with cursor references to its data.
     * in this method, the data is not actually loaded
     *
     * as each view is created, the buttons on each view have a listener, which is set to
     * a buttonlistener, so that interaction with them can be broadcasted, and received in the
     * main activity
     *
     * @param ctx contect in which the view exists
     * @param cursor data to be used tor reference
     * @param parent parent in which the view is held
     * @return View to be displayed
     */
    @Override
        public View newView(Context ctx, Cursor cursor, ViewGroup parent) {
               View vView =  mLayoutInflater.inflate(layout, parent, false);
        if (VERBOSE) {
            Log.v(TAG,"entering newView...");
            Log.v(TAG,"Cursor used for newView is: " + cursor.toString());

            String[] names = cursor.getColumnNames();
            for (int i = 0; i < names.length; i++) {
                Log.v(TAG,"column: " + i + " name: " + names[i]);
            }
        }

        vView.setTag(new ViewHolder(vView, cursor.getString
                (cursor.getColumnIndex(SQLiteDbContract.LocalEntry.COLUMN_NAME_FILEPATH))));

            /*
             * sets tags of the buttons, so then when they are clicked, the method in mainactivity
             * knows which card they were attached to.
             */

        String UUID = cursor.getString(cursor.getColumnIndex(SQLiteDbContract.MessageEntry.COLUMN_FROM_USER));

        ((TextView)vView.findViewById(R.id.userID)).setText(UUID);



        context.findViewById(R.id.button_local_message).setTag(UUID);
        context.findViewById(R.id.button_local_block).setTag(UUID);

        if (VERBOSE) {
            Log.v(TAG,"exiting newView...");
        }

        return vView;// **EDITED:**need to return the view
        }

    /**
     * method 'bindView'
     *
     * resolves the data from the cursor, and then loads the data
     * note that the data binding is performed as a seperate process from the view creation
     *
     * @param v the view
     * @param ctx the context in which this view is created
     * @param c the data to be bound.
     */
        @Override
        public void bindView(View v, Context ctx, Cursor c) {
            if (VERBOSE) {
                Log.v(TAG,"entering bindView...");
            }


            /* grab the caption from incoming files*/
            String caption_text = c.getString(
                    c.getColumnIndex(
                            SQLiteDbContract.LocalEntry.COLUMN_NAME_TEXT)
            );


            /* if the caption is not empty (or null) set and display*/
            if (!caption_text.equals("")) {
                TextView caption = ((TextView) v.findViewById(R.id.textView_local_caption));
                caption.setText(caption_text);
                caption.setVisibility(View.VISIBLE);
            }


            // you might want to cache these too
            int iCol_filepath = c.getColumnIndex(SQLiteDbContract.LocalEntry.COLUMN_NAME_FILEPATH);
            String sText = c.getString(iCol_filepath);


            ViewHolder vh = (ViewHolder) v.getTag();

            String[] params = {ctx.getCacheDir() + "/" + sText};

            File f = new File(sText);

            if (f.exists()) {
                if (VERBOSE) Log.v(TAG,"file exists, decoding image...");
                new ImageLoadTask(vh.imageView, vh.progressBar).execute(params);
            } else {
                Log.i(TAG,"file did not exist at path: " + sText + " not decoding...");
                ((MainActivity)ctx).sendMsgDownloadImage(Constants.KEY_S3_LOCAL_DIRECTORY,sText);
            }

            v.setTag(params[0]);

            if (VERBOSE) {
                Log.v(TAG,"exiting bindView...");
            }
        }


    @Override
    public Cursor swapCursor(Cursor newCursor) {
        Log.d(TAG, "swapCursor called");
        return super.swapCursor(newCursor);
    }


    @Override
    protected void onContentChanged() {
        super.onContentChanged();
        Log.d(TAG,"onContentChanged called");
    }

    /**
     * method 'pop'
     *
     * removes the topmost item from the view, then deletes it from the database, then requests
     * a replacement image
     */
    public String pop() {
        if (VERBOSE) {
            Log.v(TAG,"entering pop...");
        }

        Cursor c =  ((Cursor)getItem(0));


        if (c.getCount() == 0) { //todo this shouldn't have to be here
            Log.e(TAG,"there was no image to pop");
            return "huh";
        }


        String filePath = c.getString(c.getColumnIndex(SQLiteDbContract.LocalEntry.
                COLUMN_NAME_FILEPATH));
        String out = "'" + filePath + "'";

        context.getContentResolver().
                delete(table,
                        SQLiteDbContract.LocalEntry.COLUMN_NAME_FILEPATH + " = " + out,
                        null);

        context.sendMsgRequestLocalPosts(1);

        if (VERBOSE) {
            Log.v(TAG,"exiting pop...");
        }

        return filePath;
    }



}