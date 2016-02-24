//
// Copyright 2015 Amazon.com, Inc. or its affiliates (Amazon). All Rights Reserved.
//
// Code generated by AWS Mobile Hub. Amazon gives unlimited permission to 
// copy, distribute and modify it.
//
// Source code generated from template: aws-my-sample-app-android v0.4
//
package com.amazonaws.mobile;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.mobile.content.ContentManager;
import com.amazonaws.mobile.content.UserFileManager;
import com.amazonaws.mobile.push.GCMTokenHelper;
import com.amazonaws.mobile.push.PushManager;
import com.amazonaws.mobile.user.IdentityManager;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.AnalyticsConfig;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.EventClient;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.InitializationException;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.MobileAnalyticsManager;
import com.amazonaws.mobileconnectors.amazonmobileanalytics.SessionClient;
import com.amazonaws.regions.Regions;

import us.gravwith.android.BuildConfig;
import us.gravwith.android.DataHandlingService;
import us.gravwith.android.user.AuthenticationManager;

/**
 * The AWS Mobile Client bootstraps the application to make calls to AWS 
 * services. It creates clients which can be used to call services backing the
 * features you selected in your project.
 */
public class AWSMobileClient {

    private final static String LOG_TAG = AWSMobileClient.class.getSimpleName();

    private static AWSMobileClient instance;

    private final Context context;

    private ClientConfiguration clientConfiguration;
    private AuthenticationManager authenticationManager;
    private GCMTokenHelper gcmTokenHelper;
    private PushManager pushManager;
    private MobileAnalyticsManager mobileAnalyticsManager;

    /**
     * Build class used to create the AWS mobile client.
     */
    public static class Builder {

        private Context applicationContext;
        private String  cognitoIdentityPoolID;
        private Regions cognitoRegion;
        private String  mobileAnalyticsAppID;
        private ClientConfiguration clientConfiguration;
        private IdentityManager identityManager;
        private AuthenticationManager authenticationManager;

	/**
	 * Constructor.
	 * @param context Android context.
	 */
        public Builder(final Context context) {
            this.applicationContext = context.getApplicationContext();
        };

	/**
	 * Provides the Amazon Cognito Identity Pool ID.
	 * @param cognitoIdentityPoolID identity pool ID
	 * @return builder
	 */
        public Builder withCognitoIdentityPoolID(final String cognitoIdentityPoolID) {
            this.cognitoIdentityPoolID = cognitoIdentityPoolID;
            return this;
        };

	/**
	 * Provides the Amazon Cognito service region.
	 * @param cognitoRegion service region
	 * @return builder
	 */
        public Builder withCognitoRegion(final Regions cognitoRegion) {
            this.cognitoRegion = cognitoRegion;
            return this;
        }

        /**
	 * Provides the Amazon Mobile Analytics App ID.
	 * @param mobileAnalyticsAppID application ID
	 * @return builder
	 */
        public Builder withMobileAnalyticsAppID(final String mobileAnalyticsAppID) {
            this.mobileAnalyticsAppID = mobileAnalyticsAppID;
            return this;
        };

        /**
         * Provides the identity manager.
         * @param identityManager identity manager
         * @return builder
         */
        public Builder withIdentityManager(final IdentityManager identityManager) {
            this.identityManager = identityManager;
            return this;
        }

        public Builder withAuthenticationManager(final AuthenticationManager manager) {
            this.authenticationManager = manager;
            return this;
        }

        /**
         * Provides the client configuration
         * @param clientConfiguration client configuration
         * @return builder
         */
        public Builder withClientConfiguration(final ClientConfiguration clientConfiguration) {
            this.clientConfiguration = clientConfiguration;
            return this;
        }

	/**
	 * Creates the AWS mobile client instance and initializes it.
	 * @return AWS mobile client
	 */
        public AWSMobileClient build() {
            return
                new AWSMobileClient(applicationContext,
                                    cognitoIdentityPoolID,
                                    cognitoRegion,
                                    mobileAnalyticsAppID,
                                    authenticationManager,
                                    clientConfiguration);
        }
    }

    private AWSMobileClient(final Context context,
                            final String  cognitoIdentityPoolID,
                            final Regions cognitoRegion,
                            final String mobileAnalyticsAppID,
                            final AuthenticationManager manager,
                            final ClientConfiguration clientConfiguration) {

        this.context = context;
        this.authenticationManager = manager;
        this.clientConfiguration = clientConfiguration;

        try {
            this.mobileAnalyticsManager =
                MobileAnalyticsManager.
                    getOrCreateInstance(context,
                                        AWSConfiguration.AMAZON_MOBILE_ANALYTICS_APP_ID,
                                        AWSConfiguration.AMAZON_MOBILE_ANALYTICS_REGION,
                                        authenticationManager.getCredentialsProvider(),
                                        new AnalyticsConfig(clientConfiguration));
        }
        catch (final InitializationException ie) {
            Log.e(LOG_TAG, "Unable to initalize Amazon Mobile Analytics. " + ie.getMessage(), ie);
        }

        this.gcmTokenHelper = new GCMTokenHelper(context, AWSConfiguration.GOOGLE_CLOUD_MESSAGING_SENDER_ID);
        this.pushManager =
            new PushManager(context,
                            gcmTokenHelper,
                            authenticationManager.getCredentialsProvider(),
                            AWSConfiguration.AMAZON_SNS_PLATFORM_APPLICATION_ARN,
                            clientConfiguration,
                            AWSConfiguration.AMAZON_SNS_DEFAULT_TOPIC_ARN,
                            AWSConfiguration.AMAZON_SNS_TOPIC_ARNS);
        gcmTokenHelper.init();
    }

    /**
     * Sets the singleton instance of the AWS mobile client.
     * @param client client instance
     */
    public static void setDefaultMobileClient(AWSMobileClient client) {
        instance = client;
    }

    /**
     * Gets the default singleton instance of the AWS mobile client.
     * @return client
     */
    public static AWSMobileClient defaultMobileClient() {
        return instance;
    }

    /**
     * Gets the identity manager.
     * @return identity manager
     */
    public AuthenticationManager getAuthenticationManager() {
        return this.authenticationManager;
    }

    /**
     * Gets the push notifications manager.
     * @return push manager
     */
    public PushManager getPushManager() {
        return this.pushManager;
    }

    /**
     * Creates and initialize the default AWSMobileClient if it doesn't already
     * exist using configuration constants from {@link AWSConfiguration}.
     *
     * @param context an application context.
     */
    public static void initializeMobileClientIfNecessary(final Context context) {
        if (AWSMobileClient.defaultMobileClient() == null) {
            Log.d(LOG_TAG, "Initializing AWS Mobile Client...");
            final ClientConfiguration clientConfiguration = new ClientConfiguration();
            clientConfiguration.setUserAgent(AWSConfiguration.AWS_MOBILEHUB_USER_AGENT);
            final IdentityManager identityManager = new IdentityManager(context, clientConfiguration);
            final AWSMobileClient awsClient;
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Using sandbox client.");
                awsClient =
                        new Builder(context)
                                .withCognitoRegion(AWSConfiguration.AMAZON_COGNITO_REGION)
                                .withCognitoIdentityPoolID(AWSConfiguration.AMAZON_COGNITO_SANDBOX_IDENTITY_POOL_ID)
                                .withMobileAnalyticsAppID(AWSConfiguration.AMAZON_MOBILE_ANALYTICS_APP_ID)
                                .withIdentityManager(identityManager)
                                .withClientConfiguration(clientConfiguration)
                                .build();
            } else {
                awsClient =
                        new Builder(context)
                                .withCognitoRegion(AWSConfiguration.AMAZON_COGNITO_REGION)
                                .withCognitoIdentityPoolID(AWSConfiguration.AMAZON_COGNITO_IDENTITY_POOL_ID)
                                .withMobileAnalyticsAppID(AWSConfiguration.AMAZON_MOBILE_ANALYTICS_APP_ID)
                                .withIdentityManager(identityManager)
                                .withClientConfiguration(clientConfiguration)
                                .build();
            }

            AWSMobileClient.setDefaultMobileClient(awsClient);
        }
        Log.d(LOG_TAG, "AWS Mobile Client is OK");
    }

    /**
     * Creates and initialize the default AWSMobileClient if it doesn't already
     * exist using configuration constants from {@link AWSConfiguration}.
     *
     */
    public static void initializeGravityMobileClientIfNecessary(final DataHandlingService dataHandlingService) {
        if (AWSMobileClient.defaultMobileClient() == null) {
            Log.d(LOG_TAG, "Initializing AWS Mobile Client...");
            final ClientConfiguration clientConfiguration = new ClientConfiguration();
            clientConfiguration.setUserAgent(AWSConfiguration.AWS_MOBILEHUB_USER_AGENT);
            final AuthenticationManager manager = new AuthenticationManager(dataHandlingService);
            final AWSMobileClient awsClient;
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "Using sandbox client.");
                awsClient =
                        new Builder(dataHandlingService)
                                .withCognitoRegion(AWSConfiguration.AMAZON_COGNITO_REGION)
                                .withCognitoIdentityPoolID(AWSConfiguration.AMAZON_COGNITO_SANDBOX_IDENTITY_POOL_ID)
                                .withMobileAnalyticsAppID(AWSConfiguration.AMAZON_MOBILE_ANALYTICS_APP_ID)
                                .withAuthenticationManager(manager)
                                .withClientConfiguration(clientConfiguration)
                                .build();
            } else {
                awsClient =
                        new Builder(dataHandlingService)
                                .withCognitoRegion(AWSConfiguration.AMAZON_COGNITO_REGION)
                                .withCognitoIdentityPoolID(AWSConfiguration.AMAZON_COGNITO_IDENTITY_POOL_ID)
                                .withMobileAnalyticsAppID(AWSConfiguration.AMAZON_MOBILE_ANALYTICS_APP_ID)
                                .withAuthenticationManager(manager)
                                .withClientConfiguration(clientConfiguration)
                                .build();
            }

            AWSMobileClient.setDefaultMobileClient(awsClient);
        }
        Log.d(LOG_TAG, "AWS Mobile Client is OK");
    }

    /**
     * Gets the Amazon Mobile Analytics Manager, which allows you to submit
     * custom and monetization events to the Amazon Mobile Analytics system. It
     * also handles recording user session data events.
     * @return mobile analytics manager
     */
    public MobileAnalyticsManager getMobileAnalyticsManager() {
        return this.mobileAnalyticsManager;
    }

    /**
     * This method should be invoked when each activity is paused. It is used
     * to assist in tracking user session data in Amazon Mobile Analytics system.
     */
    public void handleOnPause() {

        SessionClient sessionClient = null;
        EventClient eventClient = null;

        try {
            if (mobileAnalyticsManager != null &&
                (sessionClient = mobileAnalyticsManager.getSessionClient()) != null &&
                (eventClient = mobileAnalyticsManager.getEventClient()) != null) {
                sessionClient.pauseSession();
                eventClient.submitEvents();
            }
        }
        catch (final Exception e) {
            Log.w(LOG_TAG, "Unable to report analytics. " + e.getMessage(), e);
        }
    }

    /**
     * This method should be called whenever any activity resumes. It assists in
     * tracking user session data in the Amazon Mobile Analytics system.
     */
    public void handleOnResume() {
        SessionClient sessionClient = null;

        try {
            if (mobileAnalyticsManager != null &&
                (sessionClient = mobileAnalyticsManager.getSessionClient()) != null) {
                sessionClient.resumeSession();
            }
        }
        catch (final Exception e) {
            Log.w(LOG_TAG, "Unable to resume analytics. " + e.getMessage(), e);
        }
    }

    /**
     * Creates the default Content Manager, which allows files to be downloaded from
     * the Amazon S3 (Simple Storage Service) bucket associated with the App Content
     * Delivery feature (optionally through Amazon CloudFront if Multi-Region CDN option
     * was selected).
     * @param resultHandler handles the resulting ContentManager instance
     */
    public void createDefaultContentManager(final ContentManager.BuilderResultHandler resultHandler) {
        new ContentManager.Builder()
                .withContext(context)
                .withCredentialsProvider(authenticationManager.getCredentialsProvider())
                .withS3Bucket(AWSConfiguration.AMAZON_CONTENT_DELIVERY_S3_BUCKET)
                .withLocalBasePath(context.getFilesDir().getAbsolutePath())
                .withClientConfiguration(clientConfiguration)
                .build(resultHandler);
    }

    /**
     * Creates a Cache Content Manager, which differs from the default in that its local base
     * directory is in the "cache" instead of files
     *
     * @param resultHandler handles the resulting ContentManager instance
     */
    public void createCacheContentManager(final ContentManager.BuilderResultHandler resultHandler) {
        if (BuildConfig.DEBUG) {
            Log.i(LOG_TAG,"Creating sandbox content manager...");
            new ContentManager.Builder()
                    .withContext(context)
                    .withCredentialsProvider(authenticationManager.getCredentialsProvider())
                    .withS3Bucket(AWSConfiguration.AMAZON_CONTENT_DELIVERY_SANDBOX_S3_BUCKET)
                    .withLocalBasePath(context.getCacheDir().getAbsolutePath())
                    .withClientConfiguration(clientConfiguration)
                    .build(resultHandler);
        } else {
            new ContentManager.Builder()
                    .withContext(context)
                    .withCredentialsProvider(authenticationManager.getCredentialsProvider())
                    .withS3Bucket(AWSConfiguration.AMAZON_CONTENT_DELIVERY_S3_BUCKET)
                    .withLocalBasePath(context.getCacheDir().getAbsolutePath())
                    .withClientConfiguration(clientConfiguration)
                    .build(resultHandler);
        }
    }

    public void createUserFileManager(final UserFileManager.BuilderResultHandler resultHandler) {
        if (BuildConfig.DEBUG) {
            Log.i(LOG_TAG, "Creating sandbox content manager...");
            new UserFileManager.Builder()
                    .withContext(context)
                    .withCredentialsProvider(authenticationManager.getCredentialsProvider())
                    .withS3Bucket(AWSConfiguration.AMAZON_CONTENT_DELIVERY_SANDBOX_S3_BUCKET)
                    .withLocalBasePath(context.getCacheDir().getAbsolutePath())
                    .withClientConfiguration(clientConfiguration)
                    .build(resultHandler);
        } else {
            new UserFileManager.Builder()
                    .withContext(context)
                    .withCredentialsProvider(authenticationManager.getCredentialsProvider())
                    .withS3Bucket(AWSConfiguration.AMAZON_CONTENT_DELIVERY_S3_BUCKET)
                    .withLocalBasePath(context.getCacheDir().getAbsolutePath())
                    .withClientConfiguration(clientConfiguration)
                    .build(resultHandler);
        }
    }

}

