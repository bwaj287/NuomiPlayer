package com.example.myapplication;

import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import com.example.myapplication.shared.LyricsHeuristics;
import com.kugou.framework.service.ipc.peripheral.BinderCarrier;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class KugouPrivateBridge {

    interface Listener {
        void onStatus(String status);

        void onLyricsPayload(String payload, String source);
    }

    private static final String DESCRIPTOR_SERVICE_MANAGER =
            "com.kugou.framework.service.ipc.core.IServiceManagerService";
    private static final String DESCRIPTOR_LYRIC_OPERATOR =
            "com.kugou.framework.service.ipc.iservice.lyravataropr.ILyrAvatarOperatorService";
    private static final String DESCRIPTOR_LYRIC_CHANGE_LISTENER =
            "com.kugou.common.player.manager.IKGLyricChangeListener";
    private static final int TRANSACTION_ATTACH_REMOTE = 1;
    private static final int TRANSACTION_GET_BINDER_VM = 3;
    private static final int TRANSACTION_GET_BINDER_EP = 4;
    private static final int TRANSACTION_GET_BINDER_OT = 5;
    private static final int TRANSACTION_REGISTER_LYRIC_LISTENER = 21;
    private static final String CONNECT_METHOD = "method_request_connect";
    private static final String EXTRA_BINDER_CARRIER = "key_binder_carrier";
    private static final String LYR_AVATAR_SERVICE = "@2:@manual:LyrAvatarOperator";
    private static final Uri SUPPORT_PROVIDER_URI =
            Uri.parse("content://com.kugou.android.ipc.SupportProvider");
    private static final Uri FORE_PROVIDER_URI =
            Uri.parse("content://com.kugou.android.ipc.ForeProvider");

    private final Context appContext;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ServiceManagerStub localManager = new ServiceManagerStub();
    private final LyricChangeListenerStub lyricChangeListener = new LyricChangeListenerStub();

    private volatile ServiceManagerProxy remoteManager;
    private volatile LyricOperatorProxy lyricOperator;
    private volatile String lastPublishedLyric = "";
    private volatile String lastProbeSummary = "";
    private volatile boolean lyricListenerRegistered;
    private volatile boolean connectRequested;

    KugouPrivateBridge(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    void attach() {
        requestConnect("初始化");
    }

    void requestConnect(String trigger) {
        executor.execute(() -> performConnect(trigger));
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void performConnect(String trigger) {
        if (remoteManager != null && lyricOperator != null) {
            probeLyricService("refresh:" + trigger);
            return;
        }
        if (!connectRequested) {
            publishStatus("尝试连接酷狗私有歌词接口");
            connectRequested = true;
        }
        requestConnectViaProvider(SUPPORT_PROVIDER_URI, "SupportProvider");
        requestConnectViaProvider(FORE_PROVIDER_URI, "ForeProvider");
    }

    private void requestConnectViaProvider(Uri providerUri, String label) {
        try {
            Bundle extras = new Bundle();
            extras.putParcelable(
                    EXTRA_BINDER_CARRIER,
                    new BinderCarrier((IInterface) localManager)
            );
            appContext.getContentResolver().call(providerUri, CONNECT_METHOD, null, extras);
            publishStatus("已请求 " + label + " 握手");
        } catch (SecurityException e) {
            publishStatus(label + " 拒绝访问: " + safeMessage(e));
        } catch (Exception e) {
            publishStatus(label + " 调用失败: " + safeMessage(e));
        }
    }

    private void onRemoteManagerAttached(IBinder remoteBinder) {
        if (remoteBinder == null) {
            publishStatus("酷狗未回传私有 ServiceManager binder");
            return;
        }
        remoteManager = new ServiceManagerProxy(remoteBinder);
        publishStatus("酷狗私有 ServiceManager 已连接");
        probeLyricService("attach");
    }

    private void probeLyricService(String trigger) {
        ServiceManagerProxy manager = remoteManager;
        if (manager == null) {
            publishStatus("私有 ServiceManager 尚未就绪");
            return;
        }

        IBinder lyricBinder = manager.getBinderEp(LYR_AVATAR_SERVICE, true);
        if (lyricBinder == null) {
            lyricBinder = manager.getBinderOt(LYR_AVATAR_SERVICE, true);
        }
        if (lyricBinder == null) {
            publishStatus("未获取到 LyrAvatarOperator binder");
            return;
        }

        lyricOperator = new LyricOperatorProxy(lyricBinder);
        publishStatus("已连接 LyrAvatarOperator");

        if (!lyricListenerRegistered) {
            boolean songRegistered = lyricOperator.registerLyricListener(lyricChangeListener, 0);
            boolean kuqunRegistered = lyricOperator.registerLyricListener(lyricChangeListener, 1);
            lyricListenerRegistered = songRegistered || kuqunRegistered;
            publishStatus(
                    "私有歌词监听 song="
                            + songRegistered
                            + " kuqun="
                            + kuqunRegistered
            );
        }

        probeSnapshots(trigger);
    }

    private void probeSnapshots(String trigger) {
        LyricOperatorProxy operator = lyricOperator;
        if (operator == null) {
            return;
        }

        String uB = operator.callString(1);
        String zh = operator.callString(2);
        String yJ = operator.callString(4);
        String ya = operator.callString(10);

        long wy = operator.callLong(5);
        long wa = operator.callLong(6);
        long yv = operator.callLong(17);
        long qk = operator.callLong(18);
        long tk = operator.callInt(24);

        maybePublishLyric(uB, "uB");
        maybePublishLyric(zh, "Zh");
        maybePublishLyric(yJ, "yJ");
        maybePublishLyric(ya, "ya");

        String summary = String.format(
                Locale.US,
                "%s uB=%s Zh=%s yJ=%s ya=%s wy=%d WA=%d yv=%d qK=%d tk=%d",
                trigger,
                summarizeValue(uB),
                summarizeValue(zh),
                summarizeValue(yJ),
                summarizeValue(ya),
                wy,
                wa,
                yv,
                qk,
                tk
        );
        if (!summary.equals(lastProbeSummary)) {
            lastProbeSummary = summary;
            publishStatus("私有探针 " + summary);
        }
    }

    private void maybePublishLyric(String candidate, String source) {
        String normalized = LyricsHeuristics.normalizePayload(candidate);
        if (normalized.isEmpty()) {
            return;
        }
        if (LyricsHeuristics.looksLikeTimedLyrics(normalized)
                || LyricsHeuristics.looksLikeLyricsPayload(normalized)
                || looksLikeLyricLine(normalized)) {
            if (!normalized.equals(lastPublishedLyric)) {
                lastPublishedLyric = normalized;
                listener.onLyricsPayload(normalized, "kugou-private:" + source);
            }
        }
    }

    private boolean looksLikeLyricLine(String text) {
        if (text == null) {
            return false;
        }
        String normalized = LyricsHeuristics.normalizePayload(text);
        if (normalized.isEmpty() || normalized.length() > 120) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.US);
        if (lower.startsWith("content://")
                || lower.startsWith("file://")
                || lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.contains("/storage/")
                || lower.contains(".jpg")
                || lower.contains(".png")
                || lower.contains(".jpeg")) {
            return false;
        }
        if (normalized.indexOf('\n') >= 0) {
            return LyricsHeuristics.looksLikeLyricsPayload(normalized)
                    || LyricsHeuristics.looksLikeTimedLyrics(normalized);
        }
        if (normalized.matches("[0-9:._\\- ]+")) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetter(ch)
                    || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private String summarizeValue(String value) {
        String normalized = LyricsHeuristics.normalizePayload(value);
        if (normalized.isEmpty()) {
            return "-";
        }
        String singleLine = normalized.replace('\n', ' ');
        if (singleLine.length() > 28) {
            return singleLine.substring(0, 28) + "...";
        }
        return singleLine;
    }

    private void onLyricPositionChanged(int type, long offsetMs) {
        publishStatus("私有歌词回调 type=" + type + " offset=" + offsetMs);
        probeSnapshots("callback");
    }

    private void publishStatus(String status) {
        if (listener != null && status != null && !status.isEmpty()) {
            listener.onStatus(status);
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private final class ServiceManagerStub extends Binder implements IInterface {
        ServiceManagerStub() {
            attachInterface(this, DESCRIPTOR_SERVICE_MANAGER);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR_SERVICE_MANAGER);
                return true;
            }
            switch (code) {
                case TRANSACTION_ATTACH_REMOTE:
                    data.enforceInterface(DESCRIPTOR_SERVICE_MANAGER);
                    final IBinder remoteBinder = data.readStrongBinder();
                    data.readInt();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    executor.execute(() -> onRemoteManagerAttached(remoteBinder));
                    return true;
                case 2:
                    data.enforceInterface(DESCRIPTOR_SERVICE_MANAGER);
                    data.readString();
                    data.readStrongBinder();
                    data.readInt();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                case TRANSACTION_GET_BINDER_VM:
                case TRANSACTION_GET_BINDER_EP:
                case TRANSACTION_GET_BINDER_OT:
                    data.enforceInterface(DESCRIPTOR_SERVICE_MANAGER);
                    data.readString();
                    data.readInt();
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeStrongBinder(null);
                    }
                    return true;
                case 6:
                    data.enforceInterface(DESCRIPTOR_SERVICE_MANAGER);
                    data.createStringArray();
                    data.readInt();
                    if (reply != null) {
                        reply.writeNoException();
                    }
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private static final class ServiceManagerProxy {
        private final IBinder remote;

        ServiceManagerProxy(IBinder remote) {
            this.remote = remote;
        }

        IBinder getBinderEp(String serviceName, boolean allowRemote) {
            return transactForBinder(TRANSACTION_GET_BINDER_EP, serviceName, allowRemote);
        }

        IBinder getBinderOt(String serviceName, boolean allowRemote) {
            return transactForBinder(TRANSACTION_GET_BINDER_OT, serviceName, allowRemote);
        }

        private IBinder transactForBinder(int transaction, String serviceName, boolean allowRemote) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_SERVICE_MANAGER);
                data.writeString(serviceName);
                data.writeInt(allowRemote ? 1 : 0);
                remote.transact(transaction, data, reply, 0);
                reply.readException();
                return reply.readStrongBinder();
            } catch (RemoteException ignored) {
                return null;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }

    private final class LyricChangeListenerStub extends Binder {
        LyricChangeListenerStub() {
            attachInterface(
                    new IInterface() {
                        @Override
                        public IBinder asBinder() {
                            return LyricChangeListenerStub.this;
                        }
                    },
                    DESCRIPTOR_LYRIC_CHANGE_LISTENER
            );
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR_LYRIC_CHANGE_LISTENER);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(DESCRIPTOR_LYRIC_CHANGE_LISTENER);
                int lyricType = 0;
                long offset = 0L;
                if (data.readInt() != 0) {
                    lyricType = data.readInt();
                    offset = data.readLong();
                }
                if (reply != null) {
                    reply.writeNoException();
                }
                final int finalLyricType = lyricType;
                final long finalOffset = offset;
                executor.execute(() -> onLyricPositionChanged(finalLyricType, finalOffset));
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static final class LyricOperatorProxy {
        private final IBinder remote;

        LyricOperatorProxy(IBinder remote) {
            this.remote = remote;
        }

        String callString(int transaction) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_LYRIC_OPERATOR);
                remote.transact(transaction, data, reply, 0);
                reply.readException();
                return reply.readString();
            } catch (RemoteException ignored) {
                return "";
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        long callLong(int transaction) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_LYRIC_OPERATOR);
                remote.transact(transaction, data, reply, 0);
                reply.readException();
                return reply.readLong();
            } catch (RemoteException ignored) {
                return 0L;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        int callInt(int transaction) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_LYRIC_OPERATOR);
                remote.transact(transaction, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } catch (RemoteException ignored) {
                return 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        boolean registerLyricListener(IBinder callbackBinder, int lyricType) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_LYRIC_OPERATOR);
                data.writeStrongBinder(callbackBinder);
                data.writeInt(lyricType);
                remote.transact(TRANSACTION_REGISTER_LYRIC_LISTENER, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } catch (RemoteException ignored) {
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
