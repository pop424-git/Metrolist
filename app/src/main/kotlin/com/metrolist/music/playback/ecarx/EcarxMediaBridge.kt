/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.metrolist.music.playback.ecarx

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import androidx.media3.common.Player
import com.metrolist.music.extensions.metadata
import timber.log.Timber

/**
 * Bridges Metrolist's ExoPlayer to the ECARX head-unit MediaCenter so the
 * steering-wheel media keys (next / previous / play / pause) drive playback.
 *
 * Why this exists: on ECARX units (Geely/Flyme) the wheel buttons do NOT emit
 * standard Android KEYCODE_MEDIA_* events. AutoKeyServer routes them straight
 * to `ecarx.xsf.mediacenter`, which only forwards transport controls to an app
 * that has registered itself as a MediaCenter "music source". Standard Android
 * MediaSession is never consulted. So we register directly against
 * MediaCenterService over its AIDL (reconstructed below as raw Binder/Parcel
 * calls — no ECARX SDK, no license file).
 *
 * Requires the app to hold `ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION`
 * (protectionLevel="system|signature"), which is auto-granted only when the APK
 * is installed as a system/privileged app (/system/priv-app). A normally
 * side-loaded build cannot bind MediaCenterService and this bridge is a no-op.
 *
 * ponytail: hand-written Parcel marshalling against fixed transaction codes
 * instead of full generated AIDL — we need 6 outbound + 2 inbound calls, not
 * all ~44 interface methods. Codes/descriptors reverse-engineered from the
 * on-device services; if an OTA renumbers them, update the constants here.
 */
class EcarxMediaBridge(
    private val context: Context,
    private val player: Player,
) {
    private val main = Handler(Looper.getMainLooper())
    private var svc: IBinder? = null
    private var token: IBinder? = null
    private var bound = false

    // IMusicClient callback mediacenter invokes for transport control.
    private val musicClient = MusicClientStub()
    // IMusicPlaybackInfo mediacenter pulls now-playing display data from.
    private val playbackInfo = PlaybackInfoStub()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = binder
            try {
                binder.linkToDeath({ onLost() }, 0)
            } catch (_: Exception) {
            }
            register()
        }

        override fun onServiceDisconnected(name: ComponentName) = onLost()
    }

    fun connect() {
        try {
            val intent = Intent(ACTION_MEDIA_CENTER).apply {
                component = ComponentName(MC_PKG, MC_SVC)
            }
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Timber.tag(TAG).d("bindService -> %s", bound)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "bindService failed")
        }
    }

    fun release() {
        try {
            token?.let { unregister(it) }
        } catch (_: Exception) {
        }
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (_: Exception) {
            }
            bound = false
        }
        svc = null
        token = null
    }

    private fun onLost() {
        svc = null
        token = null
    }

    /** Call after playback state changes so the car UI + key gate stay in sync. */
    fun onPlaybackChanged() {
        val t = token ?: return
        try {
            updateMusicPlaybackState(t, playbackInfo)
            updateCurrentProgress(t, playerPositionMs())
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "onPlaybackChanged push failed")
        }
    }

    private fun register() {
        try {
            val t = registerInMusic(context.packageName, musicClient) ?: run {
                Timber.tag(TAG).w("registerInMusic returned null token")
                return
            }
            token = t
            declareCapability(t, intArrayOf(SOURCE_TYPE))
            updateCurrentSourceType(t, SOURCE_TYPE)
            requestPlay(t)
            updateMusicPlaybackState(t, playbackInfo)
            Timber.tag(TAG).d("registered with mediacenter, token=%s", t)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "register failed")
        }
    }

    private fun playerPositionMs(): Long =
        try {
            player.currentPosition
        } catch (_: Exception) {
            0L
        }

    // ---- player control, always on the player's (main) looper ----

    private fun post(action: Player.() -> Unit) {
        main.post {
            try {
                player.action()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "player action failed")
            }
        }
    }

    // ======================================================================
    // Outbound: IMediaCenterSvc  ("ecarx.xsf.mediacenter.IMediaCenterSvc")
    // txn: 4 unregister, 5 requestPlay, 6 updateMusicPlaybackState,
    //      7 declareMediaCenterCapability, 8 updateCurrentSourceType,
    //      10 updateCurrentProgress, 19 registerInMusic
    // ======================================================================

    private fun registerInMusic(pkg: String, client: IBinder): IBinder? {
        val binder = svc ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SVC_DESC)
            data.writeString(pkg)
            data.writeStrongBinder(client)
            binder.transact(TX_REGISTER_IN_MUSIC, data, reply, 0)
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun requestPlay(t: IBinder): Boolean =
        callTokenBool(TX_REQUEST_PLAY, t)

    private fun unregister(t: IBinder): Boolean =
        callTokenBool(TX_UNREGISTER, t)

    private fun declareCapability(t: IBinder, types: IntArray) {
        val binder = svc ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SVC_DESC)
            data.writeStrongBinder(t)
            data.writeIntArray(types)
            binder.transact(TX_DECLARE_CAPABILITY, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun updateCurrentSourceType(t: IBinder, type: Int) {
        val binder = svc ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SVC_DESC)
            data.writeStrongBinder(t)
            data.writeInt(type)
            binder.transact(TX_UPDATE_SOURCE_TYPE, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun updateCurrentProgress(t: IBinder, posMs: Long) {
        val binder = svc ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SVC_DESC)
            data.writeStrongBinder(t)
            data.writeLong(posMs)
            binder.transact(TX_UPDATE_PROGRESS, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun updateMusicPlaybackState(t: IBinder, info: IBinder) {
        val binder = svc ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SVC_DESC)
            data.writeStrongBinder(t)
            data.writeStrongBinder(info)
            binder.transact(TX_UPDATE_PLAYBACK_STATE, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun callTokenBool(code: Int, t: IBinder): Boolean {
        val binder = svc ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SVC_DESC)
            data.writeStrongBinder(t)
            binder.transact(code, data, reply, 0)
            reply.readException()
            return reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    // ======================================================================
    // Inbound: IMusicClient  ("ecarx.xsf.mediacenter.IMusicClient")
    // txn: 1 onPlay, 2 onPause, 3 onNext, 4 onPrevious, 5 onForward,
    //      6 onRewind, 10 getMusicPlaybackInfo, 11 getCurrentSourceType
    // ======================================================================

    private inner class MusicClientStub : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(MUSIC_CLIENT_DESC)
                    return true
                }
                TX_ON_PLAY -> {
                    data.enforceInterface(MUSIC_CLIENT_DESC)
                    post { play() }
                    reply?.writeNoException(); reply?.writeInt(1); return true
                }
                TX_ON_PAUSE -> {
                    data.enforceInterface(MUSIC_CLIENT_DESC)
                    post { pause() }
                    reply?.writeNoException(); reply?.writeInt(1); return true
                }
                TX_ON_NEXT -> {
                    data.enforceInterface(MUSIC_CLIENT_DESC)
                    post { seekToNext() }
                    reply?.writeNoException(); reply?.writeInt(1); return true
                }
                TX_ON_PREVIOUS -> {
                    data.enforceInterface(MUSIC_CLIENT_DESC)
                    post { seekToPrevious() }
                    reply?.writeNoException(); reply?.writeInt(1); return true
                }
                TX_ON_FORWARD, TX_ON_REWIND -> {
                    data.enforceInterface(MUSIC_CLIENT_DESC)
                    reply?.writeNoException(); reply?.writeInt(1); return true
                }
                TX_GET_PLAYBACK_INFO -> {
                    data.enforceInterface(MUSIC_CLIENT_DESC)
                    reply?.writeNoException(); reply?.writeStrongBinder(playbackInfo); return true
                }
                TX_GET_SOURCE_TYPE -> {
                    data.enforceInterface(MUSIC_CLIENT_DESC)
                    reply?.writeNoException(); reply?.writeInt(SOURCE_TYPE); return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    // ======================================================================
    // Inbound: IMusicPlaybackInfo ("ecarx.xsf.mediacenter.IMusicPlaybackInfo")
    // 33 getters; we answer the display-relevant ones, default the rest.
    // txn: 2 getTitle, 3 getArtist, 4 getAlbum, 7 getDuration, 9 getSourceType,
    //      11 getPlaybackStatus, 24 getUuid, 25 getAppName, 27 getPackageName
    // ======================================================================

    private inner class PlaybackInfoStub : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(PLAYBACK_INFO_DESC)
                    return true
                }
                TX_PB_TITLE -> return replyString(data, reply) { meta()?.title }
                TX_PB_ARTIST -> return replyString(data, reply) {
                    meta()?.artists?.joinToString(", ") { it.name }
                }
                TX_PB_ALBUM -> return replyString(data, reply) { meta()?.album?.title }
                TX_PB_DURATION -> {
                    data.enforceInterface(PLAYBACK_INFO_DESC)
                    reply?.writeNoException()
                    reply?.writeLong((meta()?.duration ?: 0).toLong() * 1000L)
                    return true
                }
                TX_PB_SOURCE_TYPE -> {
                    data.enforceInterface(PLAYBACK_INFO_DESC)
                    reply?.writeNoException(); reply?.writeInt(SOURCE_TYPE); return true
                }
                TX_PB_STATUS -> {
                    data.enforceInterface(PLAYBACK_INFO_DESC)
                    reply?.writeNoException()
                    reply?.writeInt(if (isPlayingSafe()) 1 else 0)
                    return true
                }
                TX_PB_UUID -> return replyString(data, reply) { meta()?.id }
                TX_PB_APP_NAME -> return replyString(data, reply) { APP_NAME }
                TX_PB_PACKAGE -> return replyString(data, reply) { context.packageName }
            }
            // Unhandled getters: ack with a null/zero-safe default so mediacenter
            // never blocks. String/Uri getters expect a readString() slot.
            if (code in FIRST_CALL..LAST_PB_CALL) {
                data.enforceInterface(PLAYBACK_INFO_DESC)
                reply?.writeNoException()
                reply?.writeString(null)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private inline fun replyString(data: Parcel, reply: Parcel?, value: () -> String?): Boolean {
        data.enforceInterface(PLAYBACK_INFO_DESC)
        reply?.writeNoException()
        reply?.writeString(value())
        return true
    }

    private fun meta() =
        try {
            player.currentMediaItem?.metadata
        } catch (_: Exception) {
            null
        }

    private fun isPlayingSafe(): Boolean =
        try {
            player.isPlaying
        } catch (_: Exception) {
            false
        }

    companion object {
        private const val TAG = "EcarxMediaBridge"

        private const val MC_PKG = "ecarx.xsf.mediacenter"
        private const val MC_SVC = "ecarx.xsf.mediacenter.MediaCenterService"
        private const val ACTION_MEDIA_CENTER = "ecarx.xsf.MEDIA_CENTER_SERVICE"

        private const val SVC_DESC = "ecarx.xsf.mediacenter.IMediaCenterSvc"
        private const val MUSIC_CLIENT_DESC = "ecarx.xsf.mediacenter.IMusicClient"
        private const val PLAYBACK_INFO_DESC = "ecarx.xsf.mediacenter.IMusicPlaybackInfo"

        private const val APP_NAME = "Metrolist"

        // ponytail: 14 is the source-type XCAndroidAuto registers and that
        // AutoKeyServer already routes next/prev for. Reused as a known-good
        // value; retune if the car mislabels the source on screen.
        private const val SOURCE_TYPE = 14

        // IMediaCenterSvc transaction codes
        private const val TX_UNREGISTER = 4
        private const val TX_REQUEST_PLAY = 5
        private const val TX_UPDATE_PLAYBACK_STATE = 6
        private const val TX_DECLARE_CAPABILITY = 7
        private const val TX_UPDATE_SOURCE_TYPE = 8
        private const val TX_UPDATE_PROGRESS = 10
        private const val TX_REGISTER_IN_MUSIC = 19

        // IMusicClient transaction codes
        private const val TX_ON_PLAY = 1
        private const val TX_ON_PAUSE = 2
        private const val TX_ON_NEXT = 3
        private const val TX_ON_PREVIOUS = 4
        private const val TX_ON_FORWARD = 5
        private const val TX_ON_REWIND = 6
        private const val TX_GET_PLAYBACK_INFO = 10
        private const val TX_GET_SOURCE_TYPE = 11

        // IMusicPlaybackInfo transaction codes
        private const val TX_PB_TITLE = 2
        private const val TX_PB_ARTIST = 3
        private const val TX_PB_ALBUM = 4
        private const val TX_PB_DURATION = 7
        private const val TX_PB_SOURCE_TYPE = 9
        private const val TX_PB_STATUS = 11
        private const val TX_PB_UUID = 24
        private const val TX_PB_APP_NAME = 25
        private const val TX_PB_PACKAGE = 27

        private const val FIRST_CALL = 1
        private const val LAST_PB_CALL = 33
    }
}
