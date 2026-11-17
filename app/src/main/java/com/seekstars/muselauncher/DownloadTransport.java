package com.seekstars.muselauncher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 下载使用固定账号代理；每次重定向重新获取匹配 URL 的 Cookie。 */
final class DownloadTransport {
    interface Cookies { String forUrl(String url) throws IOException; }

    static OkHttpClient client(ProxySpec proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
        if (!proxy.rule.isEmpty()) {
            URI uri = URI.create(proxy.rule);
            if ("https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("下载暂不支持 HTTPS 代理，请通过本机 HTTP/SOCKS5 代理转接");
            }
            builder.proxy(new Proxy("socks5".equals(uri.getScheme()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                    new InetSocketAddress(uri.getHost(), uri.getPort())));
            if (proxy.username != null) {
                builder.proxyAuthenticator((route, response) -> {
                    if (response.request().header("Proxy-Authorization") != null) return null;
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(proxy.username, proxy.password)).build();
                });
            }
        }
        return builder.build();
    }

    static Response open(OkHttpClient client, String address, String userAgent, Cookies cookies)
            throws IOException {
        return open(client, address, userAgent, cookies, call -> {});
    }

    static Response open(OkHttpClient client, String address, String userAgent, Cookies cookies,
                         java.util.function.Consumer<Call> started) throws IOException {
        HttpUrl url;
        try { url = HttpUrl.get(address); }
        catch (IllegalArgumentException e) { throw new IOException("下载地址无效"); }
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()) {
                throw new IOException("下载仅允许 HTTPS 地址");
            }
            String cookie = cookies.forUrl(url.toString());
            Request.Builder request = new Request.Builder().url(url);
            if (userAgent != null) request.header("User-Agent", userAgent);
            if (cookie != null && !cookie.isEmpty()) request.header("Cookie", cookie);
            Call call = client.newCall(request.build());
            started.accept(call);
            Response response = call.execute();
            if (response.isRedirect()) {
                String location = response.header("Location");
                HttpUrl target = location == null ? null : url.resolve(location);
                response.close();
                if (target == null) throw new IOException("下载重定向无效");
                url = target;
                continue;
            }
            if (response.code() < 200 || response.code() >= 300 || response.body() == null) {
                int code = response.code();
                response.close();
                throw new IOException("下载失败：HTTP " + code);
            }
            return response;
        }
        throw new IOException("下载重定向次数过多");
    }
}
