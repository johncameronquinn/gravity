package com.jokrapp.android;
//
// Copyright 2015 Amazon.com, Inc. or its affiliates (Amazon). All Rights Reserved.
//
// Code generated by AWS Mobile Hub. Amazon gives unlimited permission to 
// copy, distribute and modify it.
//


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.jokrapp.android.AWSConfiguration;
import com.jokrapp.android.util.ThreadUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The identity manager keeps track of the current sign-in provider and is responsible
 * for caching credentials.
 */
public class IdentityManager {
    /** Log tag. */
    private static final String LOG_TAG = IdentityManager.class.getSimpleName();

    /** Cognito caching credentials provider. */
    private CognitoCachingCredentialsProvider credentialsProvider;

    /** Current provider being used to obtain a Cognito access token. */
    private IdentityProvider currentIdentityProvider = null;

    /** Executor service for obtaining credentials in a background thread. */
    private final ExecutorService executorService;

    /** Results adapter for adapting results that came from logging in with a provider. */
    private SignInResultsAdapter resultsAdapter;

    /** Keep tract of the currently registered SignInStateChangeListiners. */
    private final HashSet<SignInStateChangeListener> signInStateChangeListeners;

    /**
     * Constructor. Initializes the cognito credentials provider.
     * @param appContext the application context.
     */
    public IdentityManager(final Context appContext) {
        Log.d(LOG_TAG, "IdentityManager init");
        initializeCognito(appContext);
        executorService = Executors.newFixedThreadPool(2);
        signInStateChangeListeners = new HashSet<>();
    }

    /**
     *  Implement this interface to get callbacks for the results to a sign-in operation.
     */
    public interface SignInResultsHandler {

        /**
         * Sign-in was successful.
         * @param provider sign-in identity provider
         */
        void onSuccess(IdentityProvider provider);

        /**
         * Sign-in was cancelled by the user.
         * @param provider sign-in identity provider
         */
        void onCancel(IdentityProvider provider);

        /**
         * Sign-in failed.
         * @param provider sign-in identity provider
         * @param ex exception that occurred
         */
        void onError(IdentityProvider provider, Exception ex);
    }

    /**
     * Implement this interface to receive callbacks when the user's sign-in state changes
     * from signed-in to not signed-in or vice versa.
     */
    public interface SignInStateChangeListener {

        /**
         * Invoked when the user completes sign-in.
         */
        void onUserSignedIn();

        /**
         * Invoked when the user signs out.
         */
        void onUserSignedOut();
    }

    /**
     * The adapter to handle results that come back from Cognito as well as handle the result from
     * any login providers.
     */
    private class SignInResultsAdapter implements SignInResultsHandler {
        final private SignInResultsHandler handler;

        public SignInResultsAdapter(final SignInResultsHandler handler) {
            this.handler = handler;
        }

        public void onSuccess(final IdentityProvider provider) {
            Log.d(LOG_TAG,
                String.format("SignInResultsAdapter.onSuccess(): %s provider sign-in succeeded.",
                    provider.getDisplayName()));
            // Update cognito login with the token.
            loginWithProvider(provider);
        }

        private void onCognitoSuccess() {
            Log.d(LOG_TAG, "SignInResultsAdapter.onCognitoSuccess()");
            handler.onSuccess(currentIdentityProvider);
        }

        private void onCognitoError(final Exception ex) {
            Log.d(LOG_TAG, "SignInResultsAdapter.onCognitoError()", ex);
            final IdentityProvider provider = currentIdentityProvider;
            // Sign out of parent provider. This clears the currentIdentityProvider.
            IdentityManager.this.signOut();
            handler.onError(provider, ex);

        }

        public void onCancel(final IdentityProvider provider) {
            Log.d(LOG_TAG,
                String.format("SignInResultsAdapter.onCancel(): %s provider sign-in canceled.",
                    provider.getDisplayName()));
            handler.onCancel(provider);
        }

        public void onError(final IdentityProvider provider, final Exception ex) {
            Log.e(LOG_TAG,
                String.format("SignInResultsAdapter.onError(): %s provider error. %s",
                    provider.getDisplayName(), ex.getMessage()), ex);
            handler.onError(provider, ex);
        }
    }

    /**
     * Add a listener to receive callbacks when sign-in or sign-out occur.  The listener
     * methods will always be called on a background thread.
     * @param listener the sign-in state change listener.
     */
    public void addSignInStateChangeListener(final SignInStateChangeListener listener) {
        synchronized (signInStateChangeListeners) {
            signInStateChangeListeners.add(listener);
        }
    }

    /**
     * Remove a listener from receiving callbacks when sign-in or sign-out occur.
     * @param listener the sign-in state change listener.
     */
    public void removeSignInStateChangeListener(final SignInStateChangeListener listener) {
        synchronized (signInStateChangeListeners) {
            signInStateChangeListeners.remove(listener);
        }
    }

    /**
     * Set the results handler that will be used for results when calling loginWithProvider.
     * @param signInResultsHandler the results handler.
     */
    public void setResultsHandler(final SignInResultsHandler signInResultsHandler) {
        if (signInResultsHandler == null) {
            throw new IllegalArgumentException("signInResultsHandler cannot be null.");
        }
        this.resultsAdapter = new SignInResultsAdapter(signInResultsHandler);
    }

    /**
     * Call getResultsAdapter to get the IdentityManager's handler that adapts results before
     * sending them back to the handler set by {@link #setResultsHandler(SignInResultsHandler)}
     * @return the Identity Manager's results adapter.
     */
    public SignInResultsAdapter getResultsAdapter() {
        return resultsAdapter;
    }

    private void initializeCognito(final Context context) {
        credentialsProvider =
            new CognitoCachingCredentialsProvider(context,
                AWSConfiguration.AMAZON_COGNITO_IDENTITY_POOL_ID,
                AWSConfiguration.AMAZON_COGNITO_REGION
            );

        // Note: The CognitoCachingCredentialProvider loads cached credentials when it is
        //       instantiated, however, it does not reload the login map, which must be reloaded
        //       in order to refresh the credentials.  Therefore, currently cached credentials are
        //       only useful for unauthenticated users.

        // if we have cached Cognito credentials that are non-expired
        // if (!areCredentialsExpired()) {
            // we should have the logins in our persisted login map determining which provider
            // we are currently logged in with.
        //}

        // WARNING: These do network access, don't call in the main thread
        // Log.d(getClass().getSimpleName(), "Cognito ID: " + credentialsProvider.getIdentityId());
        // Log.d(getClass().getSimpleName(), "Cognito Credentials: " + credentialsProvider.getCredentials());
    }

    /**
     * @return true if the cached Cognito credentials are expired, otherwise false.
     */
    public boolean areCredentialsExpired() {
        final Date credentialsExpirationDate =
            credentialsProvider.getSessionCredentitalsExpiration();
        if (credentialsExpirationDate == null) {
            return true;
        }
        long currentTime = System.currentTimeMillis() -
            (long)(SDKGlobalConfiguration.getGlobalTimeOffset() * 1000);
        return (credentialsExpirationDate.getTime() - currentTime) < 0;
    }

    /**
     * @return true if Cognito credentials have been obtained with at least one provider.
     */
    public boolean isUserSignedIn() {
        final Map<String, String> logins = credentialsProvider.getLogins();
        if (logins == null || logins.size() == 0)
            return false;
        return true;
    }

    /**
     * Sign out of the currently in use credentials provider and clear Cognito credentials.
     */
    public void signOut() {
        if (currentIdentityProvider != null) {
            currentIdentityProvider.signOut();
            credentialsProvider.clear();
            currentIdentityProvider = null;
            // Notify state change listeners of sign out.
            synchronized (signInStateChangeListeners) {
                for (final SignInStateChangeListener listener : signInStateChangeListeners) {
                    listener.onUserSignedOut();
                }
            }
        }
    }

    private void refreshCredentialWithLogins(final Map<String, String> loginMap) {
        credentialsProvider.clear();
        credentialsProvider.withLogins(loginMap);
        // Calling refresh is equivalent to calling getIdentityId() + getCredentials().
        Log.d(getClass().getSimpleName(), "refresh credentials");
        credentialsProvider.refresh();
        Log.d(getClass().getSimpleName(), "Cognito ID: " + credentialsProvider.getIdentityId());
        Log.d(getClass().getSimpleName(), "Cognito Credentials: " + credentialsProvider.getCredentials());
    }

    /**
     * Login with an identity provider (ie. Facebook, Twitter, etc.).
     * @param provider A sign-in provider.
     */
    public void loginWithProvider(final IdentityProvider provider) {
        Log.d(LOG_TAG, "loginWithProvider");
        final Map<String, String> loginMap = new HashMap<String, String>();
        loginMap.put(provider.getCognitoLoginKey(), provider.getToken());
        currentIdentityProvider = provider;

        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshCredentialWithLogins(loginMap);
                } catch (Exception ex) {
                    resultsAdapter.onCognitoError(ex);
                    return;
                }

                resultsAdapter.onCognitoSuccess();

                // Notify state change listeners of sign out.
                synchronized (signInStateChangeListeners) {
                    for (final SignInStateChangeListener listener : signInStateChangeListeners) {
                        listener.onUserSignedIn();
                    }
                }
            }
        });
    }

    /**
     * @return the Cognito credentials provider.
     */
    public CognitoCachingCredentialsProvider getCredentialsProvider() {
        return this.credentialsProvider;
    }

    /**
     * Checks if the current sign-in provider is as expected.
     * @param clazz class of IdentityProvider in question
     * @return true if signed-in with that provider, else false
     */
    public boolean isSignedInWithProviderClass(Class<? extends IdentityProvider> clazz) {
        if (currentIdentityProvider == null) {
            return false;
        }

        if (currentIdentityProvider.getClass().equals(clazz)) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * Gets the current provider.
     * @return current provider or null if not signed-in
     */
    public IdentityProvider getCurrentIdentityProvider() {
        return currentIdentityProvider;
    }

    // local cache of the user image of currentIdentityProvider.getUserImageUrl();
    private Bitmap userImage = null;

    private void loadUserImage(final String userImageUrl) {
        if (userImageUrl == null) {
            userImage = null;
            return;
        }

        try {
            final InputStream is = new URL(userImageUrl).openStream();
            userImage = BitmapFactory.decodeStream(is);
            is.close();
        } catch (IOException e) {
            Log.w(LOG_TAG, "Failed ot prefetch user image: " + userImageUrl, e);
            // clear user image
            userImage = null;
        }
    }

    /**
     * Reload the user info and image in the background.
     *
     * @param provider sign-in provider
     * @param onReloadComplete Runnable to be executed on the main thread after user info
     *                         and user image is reloaded.
     */
    public void loadUserInfoAndImage(final IdentityProvider provider, final Runnable onReloadComplete) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                provider.reloadUserInfo();
                // preload user image
                loadUserImage(provider.getUserImageUrl());
                ThreadUtils.runOnUiThread(onReloadComplete);
            }
        });
    }

    /**
     * Convenient method to get the user image of the current identity provider.
     * @return user image of the current identity provider, or null if not signed in or unavailable
     */
    public Bitmap getUserImage() {
        return userImage;
    }

    /**
     * Convenient method to get the user name from the current identity provider.
     * @return user name from the current identity provider, or null if not signed in
     */
    public String getUserName() {
        return currentIdentityProvider == null ? null : currentIdentityProvider.getUserName();
    }
}
