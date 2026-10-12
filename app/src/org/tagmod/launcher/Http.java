package org.tagmod.launcher;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP(S) GET for Android 4.4: enables TLS 1.1/1.2 (supported but off by default before API 20)
 * and trusts Mozilla's current root store (res/raw/mozilla_roots.pem, from certifi) plus newer roots
 * not in it yet (res/raw/extra_roots.pem) in addition to the device's outdated system store.
 */
final class Http {
    private static final String PEM_BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String PEM_END = "-----END CERTIFICATE-----";
    private static SSLSocketFactory factory;

    private Http() {
    }

    static synchronized void init(Context context) throws Exception {
        if (factory != null) return;
        KeyStore bundled = KeyStore.getInstance(KeyStore.getDefaultType());
        bundled.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        int i = 0;
        for (int res : new int[] {R.raw.mozilla_roots, R.raw.extra_roots}) {
            // Android 4.4's CertificateFactory stops at the "# ..." comments in these bundles, so
            // each PEM block is cut out and parsed on its own.
            String pem = read(context.getResources().openRawResource(res));
            int start;
            int from = 0;
            while ((start = pem.indexOf(PEM_BEGIN, from)) >= 0) {
                int end = pem.indexOf(PEM_END, start);
                if (end < 0) break;
                from = end + PEM_END.length();
                byte[] block = pem.substring(start, from).getBytes("US-ASCII");
                bundled.setCertificateEntry("root" + i++, cf.generateCertificate(new ByteArrayInputStream(block)));
            }
        }
        if (i == 0) throw new IllegalStateException("no bundled trust roots");
        final X509TrustManager system = trustManager(null);
        final X509TrustManager extra = trustManager(bundled);
        X509TrustManager both = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    extra.checkServerTrusted(chain, authType);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> all = new ArrayList<X509Certificate>();
                for (X509Certificate c : system.getAcceptedIssuers()) all.add(c);
                for (X509Certificate c : extra.getAcceptedIssuers()) all.add(c);
                return all.toArray(new X509Certificate[all.size()]);
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {both}, null);
        factory = new ModernTls(ctx.getSocketFactory());
    }

    static String get(String url) throws Exception {
        return get(url, null);
    }

    static String get(String url, Map<String, String> headers) throws Exception {
        if (url.startsWith("file:")) {
            // A feed dropped on the device (USB, sync app...) instead of served over the network.
            return read(new URL(url).openStream());
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection) {
            if (factory == null) throw new IllegalStateException("Http.init() not called");
            ((HttpsURLConnection) c).setSSLSocketFactory(factory);
        }
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "tagmod");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        }
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + url);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** Status code of a GET (redirects followed); throws only when there is no HTTP answer. */
    static int status(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection) {
            if (factory == null) throw new IllegalStateException("Http.init() not called");
            ((HttpsURLConnection) c).setSSLSocketFactory(factory);
        }
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "tagmod");
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** Delegating factory that turns on every supported TLS version on each socket. */
    private static final class ModernTls extends SSLSocketFactory {
        private final SSLSocketFactory base;

        ModernTls(SSLSocketFactory base) {
            this.base = base;
        }

        private Socket enable(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                ssl.setEnabledProtocols(ssl.getSupportedProtocols());
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enable(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enable(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return enable(base.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enable(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            return enable(base.createSocket(address, port, local, localPort));
        }
    }
}
