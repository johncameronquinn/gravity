package us.gravwith.android;

/**
 * Created by John C. Quinn on 8/19/15.
 */
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;


import java.lang.ref.WeakReference;

/**
 * This class extends the standard Android ImageView View class with some features
 * that are useful for downloading, decoding, and displaying Picasa images.
 *
 */
public class PhotoView extends ImageView {

    // Indicates if caching should be used
    private boolean mCacheFlag;

    // Status flag that indicates if onDraw has completed
    private boolean mIsDrawn;

    private final boolean VERBOSE = false;

    private final String TAG = "PhotoView";

    /*
     * Creates a weak reference to the ImageView in this object. The weak
     * reference prevents memory leaks and crashes, because it automatically tracks the "state" of
     * the variable it backs. If the reference becomes invalid, the weak reference is garbage-
     * collected.
     * This technique is important for referring to objects that are part of a component lifecycle.
     * Using a hard reference may cause memory leaks as the value continues to change; even worse,
     * it can cause crashes if the underlying component is destroyed. Using a weak reference to
     * a View ensures that the reference is more transitory in nature.
     */
    private WeakReference<View> mThisView;

    // Contains the ID of the internal View
    private int mHideShowResId = -1;

    // The URL that points to the source of the image for this ImageView
    private String mImageKey;

    private String mImageDirectory;

    // The Thread that will be used to download the image for this ImageView
    private PhotoTask mDownloadThread;

    /**
     * Creates an ImageDownloadView with no settings
     * @param context A context for the View
     */
    public PhotoView(Context context) {
        super(context);
    }

    /**
     * Creates an ImageDownloadView and gets attribute values
     * @param context A Context to use with the View
     * @param attributeSet The entire set of attributes for the View
     */
    public PhotoView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);

        // Gets attributes associated with the attribute set
        getAttributes(attributeSet);
    }

    /**
     * Creates an ImageDownloadView, gets attribute values, and applies a default style
     * @param context A context for the View
     * @param attributeSet The entire set of attributes for the View
     * @param defaultStyle The default style to use with the View
     */
    public PhotoView(Context context, AttributeSet attributeSet, int defaultStyle) {
        super(context, attributeSet, defaultStyle);

        // Gets attributes associated with the attribute set
        getAttributes(attributeSet);
    }

    /**
     * Gets the resource ID for the hideShowSibling resource
     * @param attributeSet The entire set of attributes for the View
     */
    private void getAttributes(AttributeSet attributeSet) {

        // Gets an array of attributes for the View
        TypedArray attributes =
                getContext().obtainStyledAttributes(attributeSet, R.styleable.ImageDownloaderView);

        // Gets the resource Id of the View to hide or show
        mHideShowResId =
                attributes.getResourceId(R.styleable.ImageDownloaderView_hideShowSibling, -1);

        // Returns the array for re-use
        attributes.recycle();
    }

    /**
     * Sets the visibility of the PhotoView
     * @param visState The visibility state (see View.setVisibility)
     */
    private void showView(int visState) {
        if (VERBOSE) Log.v(TAG,"entering showView... : " + mImageKey);

        // If the View contains something
        if (mThisView != null) {
            if (VERBOSE) Log.v(TAG,"there was a view to show... so... setting...");
            if (VERBOSE) Log.v(TAG, "storing view with key :" + mThisView.get().toString());

            // Gets a local hard reference to the View
            View localView = mThisView.get();

            // If the weak reference actually contains something, set the visibility
            if (localView != null)
                localView.setVisibility(visState);
        } else {
            if (VERBOSE) Log.e(TAG,"underlying imageView was null!");

            //attempt to regrab view

            // If the sibling View is set and the parent of the ImageView is itself a View
            /*if ((this.mHideShowResId != -1) && ((getParent() instanceof View))) {

                // Gets a handle to the sibling View
                View localView = ((View) getParent()).findViewById(this.mHideShowResId);

                // If the sibling View contains something, make it the weak reference for this View
                if (localView != null) {
                    this.mThisView = new WeakReference<>(localView);
                    localView.setVisibility(visState);

                    if (VERBOSE) Log.d(TAG,"successful reference recover");
                } else {
                    if (VERBOSE) Log.e(TAG,"unable to recover reference");
                }
            } else {
                Log.e(TAG,"parent of this view : " + getParent().toString());
            }*/

        }

        if (VERBOSE) Log.v(TAG,"exiting showView...: " + mImageKey);
    }

    /**
     * Sets the image in this ImageView to null, and makes the View visible
     */
    public void clearImage() {
        if (VERBOSE) Log.v(TAG,"entering clearImage..." + mImageKey);
        setImageDrawable(null);
        showView(View.VISIBLE);
        if (VERBOSE) Log.v(TAG,"exiting clearImage..." + mImageKey);
    }

    /**
     * Returns the key of the picture associated with this ImageView
     * @return a Key
     */
    final String getImageKey() {
        return mImageKey;
    }

    final String getmImageDirectory() {
        return mImageDirectory;
    }

    /*
     * This callback is invoked when the system attaches the ImageView to a Window. The callback
     * is invoked before onDraw(), but may be invoked after onMeasure()
     */
    @Override
    protected void onAttachedToWindow() {
        // Always call the supermethod first
        super.onAttachedToWindow();

        if (VERBOSE) Log.v(TAG,"entering onAttachToWindow...");

        // If the sibling View is set and the parent of the ImageView is itself a View
        if ((this.mHideShowResId != -1) && ((getParent() instanceof View))) {

            // Gets a handle to the sibling View
            View localView = ((View) getParent()).findViewById(this.mHideShowResId);

            // If the sibling View contains something, make it the weak reference for this View
            if (localView != null) {
                this.mThisView = new WeakReference<>(localView);
            }
        }

        if (VERBOSE) Log.v(TAG,"exiting onAttachToWindow...");
    }

    /*
     * This callback is invoked when the ImageView is removed from a Window. It "unsets" variables
     * to prevent memory leaks.
     */
    @Override
    protected void onDetachedFromWindow() {
        if (VERBOSE) Log.v(TAG,"entering onDetachedFromWindow...");

        if (VERBOSE) Log.v(TAG,"clearing drawable, disabling cache, disconnecting view.");
        // Clears out the image drawable, turns off the cache, disconnects the view from a URL
  //      setImageKey(null,null,false,null);

        // Gets the current Drawable, or null if no Drawable is attached
        Drawable localDrawable = getDrawable();

        // if the Drawable is nulImageKeyl, unbind it from this View
        if (localDrawable != null)
            localDrawable.setCallback(null);

        // If this View still exists, clears the weak reference, then sets the reference to null
        if (mThisView != null) {
            mThisView.clear();
            mThisView = null;
        }

        // Sets the downloader thread to null
        this.mDownloadThread = null;

        // Always call the super method last
        super.onDetachedFromWindow();

        if (VERBOSE) Log.v(TAG,"exiting onDetachedFromWindow...");
    }

    /*
     * This callback is invoked when the system tells the View to draw itself. If the View isn't
     * already drawn, and its URL isn't null, it invokes a Thread to download the image. Otherwise,
     * it simply passes the existing Canvas to the super method
     */
    @Override
    protected void onDraw(Canvas canvas) {
        if (VERBOSE) Log.v(TAG,"entering onDraw...");
        // If the image isn't already drawn, and the URL is set
        if ((!mIsDrawn) && (mImageKey != null)) {
            if (VERBOSE) Log.v(TAG,"image is not drawn and has a set key...");

            // Starts downloading this View, using the current cache setting
            mDownloadThread = PhotoManager.startDownload(this, mCacheFlag);

            // After successfully downloading the image, this marks that it's available.
            mIsDrawn = true;
        } else {
            if (mIsDrawn) {
                if (VERBOSE) Log.v(TAG,"image was drawn");
            }
            if (mImageKey == null) {
                if (VERBOSE) Log.v(TAG, "mImageKey was null " + mImageKey);
            }
            mIsDrawn = true;
        }
        // Always call the super method last
        super.onDraw(canvas);

        if (VERBOSE) Log.v(TAG,"exiting onDraw...");
    }

    /**
     * Sets the current View weak reference to be the incoming View. See the definition of
     * mThisView
     * @param view the View to use as the new WeakReference
     */
    public void setHideView(View view)  {
        this.mThisView = new WeakReference<>(view);
    }

    @Override
    public void setImageBitmap(Bitmap paramBitmap) {
        super.setImageBitmap(paramBitmap);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        // The visibility of the View
        int viewState;

        /*
         * Sets the View state to visible if the method is called with a null argument (the
         * image is being cleared). Otherwise, sets the View state to invisible before refreshing
         * it.
         */
        if (drawable == null) {

            viewState = View.VISIBLE;
        } else {

            viewState = View.INVISIBLE;
        }
        // Either hides or shows the View, depending on the view state
        showView(viewState);

        // Invokes the supermethod with the provided drawable
        super.setImageDrawable(drawable);
    }

    /*
     * Displays a drawable in the View
     */
    @Override
    public void setImageResource(int resId) {
        super.setImageResource(resId);
    }

    /*
     * Sets the URI for the Image
     */
    @Override
    public void setImageURI(Uri uri) {
        super.setImageURI(uri);
    }

    /**
     * Attempts to set the picture URL for this ImageView and then download the picture.
     * <p>
     * If the picture URL for this view is already set, and the input URL is not the same as the
     * stored URL, then the picture has moved and any existing downloads are stopped.
     * <p>
     * If the input URL is the same as the stored URL, then nothing needs to be done.
     * <p>
     * If the stored URL is null, then this method starts a download and decode of the picture
     * @param imageKey key to reference the image, either by storage or from s3
     * @param cacheFlag Whether to use caching when doing downloading and decoding
     * @param imageDrawable The Drawable to use for this ImageView
     */
    public void setImageKey(String directory, String imageKey, boolean cacheFlag, Drawable imageDrawable) {
        if (VERBOSE) Log.v(TAG,"entering setImageKey with key: " + imageKey + " and directory : " + directory);

        // If the picture URL for this ImageView is already set\
        if (mImageKey != null) {
            if (VERBOSE) Log.v(TAG,"stored image key was not null...");

            // If the stored URL doesn't match the incoming URL, then the picture has changed.
            if (!mImageKey.equals(imageKey)) {
                if (VERBOSE) Log.v(TAG,"image key has changed, removing and resetting...");
                // Stops any ongoing downloads for this ImageView
                PhotoManager.removeDownload(mDownloadThread, mImageKey);
            } else {
                if (VERBOSE) Log.v(TAG,"image key is the same as prior. Do nothing. : " + imageKey);
                // The stored URL matches the incoming URL. Returns without doing any work.
//                return;
            }
        }

        // Sets the Drawable for this ImageView
        setImageDrawable(imageDrawable);

        // Stores the picture URL for this ImageView
        mImageKey = imageKey;

        mImageDirectory = directory;

        // If the draw operation for this ImageVIew has completed, and the picture URL isn't empty
        if ((mIsDrawn) && (imageKey != null)) {
            if (VERBOSE) Log.v(TAG,"the draw operation has completed, and the incoming key isn't null.");

            // Sets the cache flag
            mCacheFlag = cacheFlag;

            /*
             * Starts a download of the picture file. Notice that if caching is on, the picture
             * file's contents may be taken from the cache.
             */
            mDownloadThread = PhotoManager.startDownload(this, cacheFlag);
        } else {
            if (VERBOSE) Log.v(TAG,"incoming image was null or has already drawn" +
                    " so do nothing " + mImageKey);
        }

        if (VERBOSE) Log.v(TAG,"exiting setImageKey...");
    }

    /**
     * Sets the Drawable for this ImageView
     * @param drawable A Drawable to use for the ImageView
     */
    public void setStatusDrawable(Drawable drawable) {
        if (Constants.LOGV) Log.v(TAG,"entering setStatusDrawable..");
        // If the View is empty, sets a Drawable as its content
        if (mThisView == null) {
            if (Constants.LOGV) Log.v(TAG,"setting drawable now...");
            setImageDrawable(drawable);
        }
    }

    /**
     * Sets the content of this ImageView to be a Drawable resource
     * @param resId
     */
    public void setStatusResource(int resId) {
        if (VERBOSE) Log.v(TAG,"entering setStatusResource..");
        // If the View is empty, provides it with a Drawable resource as its content
        if (mThisView == null) {
            if (VERBOSE) Log.v(TAG,"setting resource now...");
            setImageResource(resId);
        } else {
            if (VERBOSE) Log.v(TAG,"mThisView was not null, not setting status...");
        }

        if (VERBOSE) Log.v(TAG,"exiting setStatusResource..");
    }
}