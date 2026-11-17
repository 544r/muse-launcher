package com.seekstars.muselauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

final class ProxyProbeClient {
    interface Callback { void done(boolean ok, String error, long elapsed); }
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ServiceConnection connection;
    private Callback callback;
    private int generation;

    ProxyProbeClient(Context context) { this.context = context; }

    void test(String proxy, Callback result) {
        cancel();
        int token = ++generation;
        callback = result;
        Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what == ProxyProbeService.RESULT && message.arg1 == token && token == generation) {
                Bundle data = message.getData();
                finish(data.getBoolean("ok"), data.getString("error"), data.getLong("elapsed"));
            }
            return true;
        }));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (token != generation) return;
                Message request = Message.obtain(null, ProxyProbeService.TEST, token, 0);
                Bundle data = new Bundle();
                data.putString("proxy", proxy);
                request.setData(data);
                request.replyTo = receiver;
                try { new Messenger(binder).send(request); }
                catch (RemoteException e) { finish(false, "无法启动代理测活", 0); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (token == generation) finish(false, "代理测活已中断", 0);
            }
        };
        boolean bound;
        try { bound = context.bindService(new Intent(context, ProxyProbeService.class), connection, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException e) { bound = false; }
        if (!bound) { connection = null; finish(false, "无法启动代理测活", 0); return; }
        handler.postDelayed(() -> {
            if (token == generation) finish(false, "代理测活超时", 0);
        }, 15000);
    }

    private void finish(boolean ok, String error, long elapsed) {
        Callback result = callback;
        cancel();
        if (result != null) result.done(ok, error, elapsed);
    }

    void cancel() {
        ++generation;
        callback = null;
        handler.removeCallbacksAndMessages(null);
        if (connection != null) {
            context.unbindService(connection);
            connection = null;
        }
    }
}
