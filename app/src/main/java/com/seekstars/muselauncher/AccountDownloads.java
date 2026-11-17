package com.seekstars.muselauncher;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.URLUtil;
import android.widget.Toast;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.Call;

/** 需要账号会话/代理的下载；使用应用 Context，避免持有 Activity。 */
final class AccountDownloads {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Map<Long, List<OkHttpClient>> JOBS = new HashMap<>();
    private static final Map<OkHttpClient, Call> CALLS = new HashMap<>();

    static void start(Context context, long id, String url, String userAgent, String disposition,
                      String mimeType, ProxySpec proxy) {
        Context app = context.getApplicationContext();
        final OkHttpClient client;
        try { client = DownloadTransport.client(proxy); }
        catch (IllegalArgumentException e) { notify(app, e.getMessage()); return; }
        synchronized (JOBS) { JOBS.computeIfAbsent(id, ignored -> new ArrayList<>()).add(client); }
        notify(app, "正在下载，请保持应用运行");
        IO.execute(() -> {
            Uri destination = null;
            boolean complete = false;
            try (Response response = DownloadTransport.open(client, url, userAgent,
                    address -> cookies(app, id, client, address), call -> {
                        synchronized (JOBS) {
                            if (registered(id, client)) CALLS.put(client, call);
                            else call.cancel();
                        }
                    })) {
                String mime = response.header("Content-Type", mimeType == null ? "application/octet-stream" : mimeType);
                String filename = URLUtil.guessFileName(response.request().url().toString(),
                        response.header("Content-Disposition", disposition), mime)
                        .replace('/', '_').replace('\\', '_');
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mime.split(";")[0]);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                destination = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) throw new IOException("无法创建下载文件");
                try (InputStream input = response.body().byteStream();
                     OutputStream output = app.getContentResolver().openOutputStream(destination)) {
                    if (output == null) throw new IOException("无法写入下载文件");
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (!registered(id, client)) throw new IOException("下载已取消");
                        output.write(buffer, 0, count);
                    }
                }
                // 删除账号后，即便响应刚好已读完，也不能发布该账号的下载。
                if (!registered(id, client)) throw new IOException("下载已取消");
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                if (app.getContentResolver().update(destination, values, null, null) == 0) {
                    throw new IOException("无法完成下载文件");
                }
                complete = true;
                notify(app, "文件已保存到下载目录");
            } catch (Exception e) {
                notify(app, "下载失败或已取消，请检查网络、代理和存储空间");
            } finally {
                if (!complete && destination != null) {
                    try { app.getContentResolver().delete(destination, null, null); }
                    catch (RuntimeException ignored) { }
                }
                synchronized (JOBS) {
                    CALLS.remove(client);
                    List<OkHttpClient> jobs = JOBS.get(id);
                    if (jobs != null) {
                        jobs.remove(client);
                        if (jobs.isEmpty()) JOBS.remove(id);
                    }
                }
                client.connectionPool().evictAll();
            }
        });
    }

    private static String cookies(Context app, long id, OkHttpClient client, String url) throws IOException {
        FutureTask<String> result = new FutureTask<>(() -> {
            if (!registered(id, client) || new AccountsStore(app).findById(id) == null) {
                throw new IOException("账号已删除");
            }
            Profile profile = ProfileStore.getInstance().getProfile("acct_" + id);
            if (profile == null) throw new IOException("账号环境已删除");
            return profile.getCookieManager().getCookie(url);
        });
        MAIN.post(result);
        try { return result.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { result.cancel(false); throw new IOException("无法读取下载会话"); }
    }

    private static boolean registered(long id, OkHttpClient client) {
        synchronized (JOBS) { return JOBS.containsKey(id) && JOBS.get(id).contains(client); }
    }

    static void cancelAccount(long id) {
        synchronized (JOBS) {
            List<OkHttpClient> jobs = JOBS.remove(id);
            if (jobs != null) for (OkHttpClient client : jobs) {
                Call call = CALLS.get(client);
                if (call != null) call.cancel();
            }
        }
    }

    private static void notify(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
