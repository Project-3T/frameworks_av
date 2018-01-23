/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.media;

import static android.content.Context.NOTIFICATION_SERVICE;
import static android.media.MediaSessionService2.DEFAULT_MEDIA_NOTIFICATION_CHANNEL_ID;
import static android.media.MediaSessionService2.DEFAULT_MEDIA_NOTIFICATION_ID;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaPlayerBase.PlaybackListener;
import android.media.MediaSession2;
import android.media.MediaSessionService2;
import android.media.MediaSessionService2.MediaNotification;
import android.media.session.PlaybackState;
import android.media.update.MediaSessionService2Provider;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.GuardedBy;
import android.util.Log;

// Need a test for session service itself.
public class MediaSessionService2Impl implements MediaSessionService2Provider {

    private static final String TAG = "MPSessionService"; // to meet 23 char limit in Log tag
    private static final boolean DEBUG = true; // TODO(jaewan): Change this.

    private final MediaSessionService2 mInstance;
    private final PlaybackListener mListener = new SessionServicePlaybackListener();

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private NotificationManager mNotificationManager;
    @GuardedBy("mLock")
    private Intent mStartSelfIntent;

    private boolean mIsRunningForeground;
    private NotificationChannel mDefaultNotificationChannel;
    private MediaSession2 mSession;

    public MediaSessionService2Impl(MediaSessionService2 instance) {
        if (DEBUG) {
            Log.d(TAG, "MediaSessionService2Impl(" + instance + ")");
        }
        mInstance = instance;
    }

    @Override
    public MediaSession2 getSession_impl() {
        synchronized (mLock) {
            return mSession;
        }
    }

    @Override
    public MediaNotification onUpdateNotification_impl(PlaybackState state) {
        return createDefaultNotification(state);
    }

    // TODO(jaewan): Remove this for framework release.
    private MediaNotification createDefaultNotification(PlaybackState state) {
        // TODO(jaewan): Place better notification here.
        if (mDefaultNotificationChannel == null) {
            mDefaultNotificationChannel = new NotificationChannel(
                    DEFAULT_MEDIA_NOTIFICATION_CHANNEL_ID,
                    DEFAULT_MEDIA_NOTIFICATION_CHANNEL_ID,
                    NotificationManager.IMPORTANCE_DEFAULT);
            mNotificationManager.createNotificationChannel(mDefaultNotificationChannel);
        }
        Notification notification = new Notification.Builder(
                mInstance, DEFAULT_MEDIA_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(mInstance.getPackageName())
                .setContentText("Playback state: " + state.getState())
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();
        return MediaNotification.create(DEFAULT_MEDIA_NOTIFICATION_ID, notification);
    }


    @Override
    public void onCreate_impl() {
        mNotificationManager = (NotificationManager) mInstance.getSystemService(
                NOTIFICATION_SERVICE);
        mStartSelfIntent = new Intent(mInstance, mInstance.getClass());

        Intent serviceIntent = new Intent(mInstance, mInstance.getClass());
        serviceIntent.setAction(MediaSessionService2.SERVICE_INTERFACE);
        ResolveInfo resolveInfo = mInstance.getPackageManager()
                .resolveService(serviceIntent,
                        PackageManager.GET_META_DATA);
        String id;
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            throw new IllegalArgumentException("service " + mInstance + " doesn't implement"
                    + MediaSessionService2.SERVICE_INTERFACE);
        } else if (resolveInfo.serviceInfo.metaData == null) {
            if (DEBUG) {
                Log.d(TAG, "Failed to resolve ID for " + mInstance + ". Using empty id");
            }
            id = "";
        } else {
            id = resolveInfo.serviceInfo.metaData.getString(
                    MediaSessionService2.SERVICE_META_DATA, "");
        }
        mSession = mInstance.onCreateSession(id);
        if (mSession == null || !id.equals(mSession.getToken().getId())) {
            throw new RuntimeException("Expected session with id " + id + ", but got " + mSession);
        }
    }

    public IBinder onBind_impl(Intent intent) {
        if (MediaSessionService2.SERVICE_INTERFACE.equals(intent.getAction())) {
            return mSession.getToken().getSessionBinder().asBinder();
        }
        return null;
    }

    private void updateNotification(PlaybackState state) {
        MediaNotification mediaNotification = mInstance.onUpdateNotification(state);
        if (mediaNotification == null) {
            mediaNotification = createDefaultNotification(state);
        }
        switch((int) state.getState()) {
            case PlaybackState.STATE_PLAYING:
                if (!mIsRunningForeground) {
                    mIsRunningForeground = true;
                    mInstance.startForegroundService(mStartSelfIntent);
                    mInstance.startForeground(mediaNotification.id, mediaNotification.notification);
                    return;
                }
                break;
            case PlaybackState.STATE_STOPPED:
                if (mIsRunningForeground) {
                    mIsRunningForeground = false;
                    mInstance.stopForeground(true);
                    return;
                }
                break;
        }
        mNotificationManager.notify(mediaNotification.id, mediaNotification.notification);
    }

    private class SessionServicePlaybackListener implements PlaybackListener {
        @Override
        public void onPlaybackChanged(PlaybackState state) {
            if (state == null) {
                Log.w(TAG, "Ignoring null playback state");
                return;
            }
            MediaSession2Impl impl = (MediaSession2Impl) mSession.getProvider();
            if (impl.getHandler().getLooper() != Looper.myLooper()) {
                Log.w(TAG, "Ignoring " + state + ". Expected " + impl.getHandler().getLooper()
                        + " but " + Looper.myLooper());
                return;
            }
            updateNotification(state);
        }
    }
}