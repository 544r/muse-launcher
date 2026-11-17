package com.seekstars.muselauncher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import static org.junit.Assert.*;

public class DownloadTransportTest {
    private final HeldCertificate certificate = new HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build();
    private final HandshakeCertificates serverTls = new HandshakeCertificates.Builder()
            .heldCertificate(certificate).build();
    private final HandshakeCertificates clientTls = new HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate()).build();

    private MockWebServer server() throws IOException {
        MockWebServer server = new MockWebServer();
        server.useHttps(serverTls.sslSocketFactory(), false);
        server.start();
        return server;
    }

    private OkHttpClient client() {
        return DownloadTransport.client(ProxySpec.parse("")).newBuilder()
                .proxy(java.net.Proxy.NO_PROXY)
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build();
    }

    @Test public void redirectGetsFreshCookiesForItsOwnUrl() throws Exception {
        try (MockWebServer first = server(); MockWebServer second = server()) {
            first.enqueue(new MockResponse().setResponseCode(302).addHeader("Location",
                    second.url("/file").newBuilder().host("localhost").build()));
            second.enqueue(new MockResponse().setBody("downloaded"));
            List<String> queried = new ArrayList<>();
            try (Response response = DownloadTransport.open(client(), first.url("/start").toString(), "test", url -> {
                queried.add(url);
                return url.equals(first.url("/start").toString()) ? "session=first" : null;
            })) {
                assertEquals("downloaded", response.body().string());
            }
            assertEquals("session=first", first.takeRequest().getHeader("Cookie"));
            assertNull(second.takeRequest().getHeader("Cookie"));
            assertEquals(2, queried.size());
        }
    }

    @Test public void httpErrorsAreNotSavedAsSuccessfulFiles() throws Exception {
        try (MockWebServer server = server()) {
            server.enqueue(new MockResponse().setResponseCode(403).setBody("denied"));
            try { DownloadTransport.open(client(), server.url("/").toString(), null, url -> null); fail(); }
            catch (IOException e) { assertTrue(e.getMessage().contains("403")); }
        }
    }

    @Test public void redirectsCannotDowngradeToCleartext() throws Exception {
        try (MockWebServer server = server()) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "http://localhost/file"));
            try { DownloadTransport.open(client(), server.url("/").toString(), null, url -> null); fail(); }
            catch (IOException e) { assertTrue(e.getMessage().contains("HTTPS")); }
        }
    }

    @Test public void proxyConfigurationCannotSilentlyFallBackToDirect() {
        OkHttpClient client = DownloadTransport.client(ProxySpec.parse("127.0.0.1:7890"));
        assertEquals(java.net.Proxy.Type.HTTP, client.proxy().type());
        assertFalse(client.retryOnConnectionFailure());
        try { DownloadTransport.client(ProxySpec.parse("https://localhost:443")); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void cancellationCanStopCallBeforeItConnects() throws Exception {
        try (MockWebServer server = server()) {
            try {
                DownloadTransport.open(client(), server.url("/").toString(), null, url -> null,
                        okhttp3.Call::cancel);
                fail();
            } catch (IOException expected) {
                assertEquals(0, server.getRequestCount());
            }
        }
    }
}
