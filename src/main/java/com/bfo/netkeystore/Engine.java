package com.bfo.netkeystore;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.concurrent.atomic.*;
import java.net.*;
import javax.net.ssl.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.*;
import java.security.spec.*;
import javax.security.auth.callback.*;
import javax.security.auth.login.*;
import java.security.*;
import java.nio.*;
import java.io.*;
import com.bfo.json.*;
import com.bfo.zeroconf.*;

class Engine {

    private static final String SERVICE = "_netkeystore._tcp";
    private Zeroconf zc;
    boolean debug;
    private String name;
    private Server server;
    private NetProvider provider;
    private Json config;
    private SSLContext selfSignedSSLContext, sslContext, serverSslContext;
    private ZeroconfListener clientListener;
    private Map<String,RemoteSupplier> remoteSuppliers = new ConcurrentHashMap<String,RemoteSupplier>();
    private long initializedAfter;
    private int serverPort;
    private boolean serverPortAuto;
    private String serverPath;
    private Service serverService;
    private boolean serverAnnounce;


    Engine() {
    }

    String getName() {
        return name;
    }

    void setProvider(NetProvider provider) {
        this.provider = provider;
    }

    Provider getProvider() {
        if (provider == null) {
            provider = new NetProvider(this);
        }
        return provider;
    }

    //---------------------------------------------------------

    void startClient(boolean search, boolean selfSigned) {
        if (search) {
            if (zc == null) {
                zc = new Zeroconf();
            }
            zc.query(SERVICE, null);
            zc.addListener(clientListener = new ZeroconfListener() {
                @Override public void serviceNamed(String type, String name) {
                    if (type.equals(SERVICE)) {
                        zc.query(type, name);
                    }
                }
                @Override public void serviceAnnounced(Service service) {
                    if (SERVICE.equals(service.getType()) && !service.getAddresses().isEmpty()) {
                        InetSocketAddress address = new InetSocketAddress(service.getAddresses().iterator().next(), service.getPort());
                        Map<String,String> m = new HashMap<String,String>(service.getText());
                        m.put("self_signed", selfSigned ? "true" : "false");
                        if (debug) System.out.println("# Found server name=\"" + service.getName() + "\" host="+service.getFQDN()+" port="+service.getPort()+" text="+service.getText()+" addresses=\"" + service.getAddresses() + "\"");
                        addRemoteNode(service.getName(), service.getFQDN(), address, m);
                    }
                }
                @Override public void serviceExpired(Service service) {
                    removeRemoteNode(service.getName());
                }
            });
        }
    }

    private void stopClient() {
        if (clientListener != null) {
            zc.removeListener(clientListener);
        }
    }

    Server getServer() {
        return server;
    }

    private boolean addRemoteNode(String name, String fqdn, InetSocketAddress address, Map<String,String> properties) {
        RemoteSupplier supplier = new RemoteSupplier(this, name, fqdn, address, properties);
        if (remoteSuppliers.putIfAbsent(name, supplier) == null) {
            return true;
        }
        return false;
    }

    private boolean removeRemoteNode(String name) {
        RemoteSupplier rnode = remoteSuppliers.remove(name);
        if (rnode != null) {
            return true;
        } else {
            return false;
        }
    }

    //---------------------------------------------------------

    boolean isConfigured() {
        return config == null;
    }

    int getServerPort() {
        return serverPort;
    }
    String getServerPath() {
        return serverPath;
    }
    SSLContext getServerSSLContext() {
        return serverSslContext;
    }

    void announce(boolean announce, int port) {
        if (announce) {
            if (serverService != null) {
                serverService.cancel();
                serverService = null;
            }
            Service.Builder builder = new Service.Builder().setName(name).setType(SERVICE).setPort(port);
            if (serverSslContext != null) {
                builder.put("secure", "true");
            }
            if (serverPath != null && serverPath.length() > 0) {
                builder.put("path", serverPath);
            }
            serverService = builder.build(zc);
            serverService.announce();
            serverPort = port;
        } else {
            if (serverService != null) {
                serverService.cancel();
                serverService = null;
            }
            if (serverPortAuto) {
                serverPort = 0;
            }
        }
    }

    void load(InputStream in) throws IOException {
        try {
            config = null;
            if (in == null) {
                config = Json.read("{}");
            } else {
                config = Json.read(new YamlReader().setInput(in));
            }
            Json server = null, client = null;
            serverAnnounce = false;
            boolean clientSearch = false;
            name = config.stringValue("name");
            if (name == null) {
                try {
                    name = InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
                } catch (Throwable e) {
                    name = InetAddress.getLocalHost().getHostName();
                }
            }
            if (config.isMap("server")) {
                server = config.get("server");
                int port = 0;
                if (server.isNumber("port") && server.numberValue("port") instanceof Integer) {
                    port = server.intValue("port");
                    if (port < 0 || port > 65535) {
                        throw new IllegalArgumentException("Invalid port " + port);
                    }
                } else if (server.has("port")) {
                    throw new IllegalArgumentException("Invalid port " + server.get("port"));
                }
                serverPort = port;
                if (!server.isBoolean("zeroconf") || server.booleanValue("zeroconf")) {
                    serverAnnounce = true;
                }
                if (serverAnnounce && zc == null) {
                    zc = new Zeroconf();
                }
                this.server = new Server(this, server);
                serverSslContext = null;
                if (server.isMap("https")) {
                    Json https = server.get("https");
                    String password = https.stringValue("password");
                    if (password == null || !https.isString("type")) {
                        throw new IllegalArgumentException("https requires \"alias\", \"password\" and \"type\" keys");
                    }
                    KeyStore keystore = loadLocalKeyStore(https);
                    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(keystore);
                    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(keystore, password.toCharArray());
                    serverSslContext = SSLContext.getInstance("TLS");
                    serverSslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
                }
                serverPortAuto = serverPort == 0;
            }

            if (config.isMap("client") || server == null) {
                client = config.get("client");
                if (client == null) {
                    client = Json.read("{}");
                }
                clientSearch = true;
                debug = client.booleanValue("debug");
                if (client.isMap("servers")) {
                    for (Map.Entry<Object,Json> e : client.get("servers").mapValue().entrySet()) {
                        if (e.getValue().isString()) {
                            String name = e.getKey().toString();
                            String s = e.getValue().stringValue();
                            try {
                                URI uri = new URI(s);
                                boolean secure;
                                Map<String,String> props = new HashMap<String,String>();
                                if (uri.getScheme().equals("https")) {
                                    props.put("secure", "true");
                                } else if (!uri.getScheme().equals("http")) {
                                    throw new IOException("Invalid server URL \"" + s + "\": not http or https");
                                }
                                InetSocketAddress address = new InetSocketAddress(uri.getHost(), uri.getPort());
                                props.put("path", uri.getPath());
                                props.put("self_signed", client.booleanValue("self_signed") ? "true": "false"); // Manual servers are NOT self-signed by default
                                addRemoteNode(name, uri.getHost(), address, props);
                                clientSearch = false;
                            } catch (URISyntaxException e2) {
                                throw new IOException("Invalid server URL \"" + s + "\": bad URL");
                            } catch (Exception e2) {
                                throw new IOException("Invalid server URL \"" + s + "\"", e2);
                            }
                        } else {
                            throw new IOException("client.servers." + e.getKey() + " is not a map");
                        }
                    }
                } else if (client.has("servers")) {
                    throw new IOException("client.servers is not a map");
                }
                if (clientSearch) {
                    initializedAfter = System.currentTimeMillis() + 1000;   // Takes a bit for servers to announce
                    if (zc == null) {
                        zc = new Zeroconf();
                    }
                }
                sslContext = SSLContext.getInstance("TLS");
                selfSignedSSLContext = SSLContext.getInstance("TLS");
                selfSignedSSLContext.init(null, new javax.net.ssl.TrustManager[] { new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                    public void checkServerTrusted(X509Certificate[] chain, String auth) { }
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                } }, null);
                boolean autoSelfSigned = !client.isBoolean("self_signed") || client.booleanValue("self_signed");        // Auto-server are self-signed by default
                startClient(clientSearch, autoSelfSigned);
            } else {
                initializedAfter = System.currentTimeMillis();;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    synchronized SSLContext getSSLContext(boolean selfSigned) {
        return selfSigned ? selfSignedSSLContext : sslContext;
    }

    //----------------------------------------------------------------------------
    // Comms
    //----------------------------------------------------------------------------

    void processCallbacks(Json req, Json auth, CallbackHandler handler) {
        // auth looks like [ { "type": "password", "prompt": } ]
        Map<Callback,Json> callbacks = new LinkedHashMap<Callback,Json>();
        for (int i=0;i<auth.size();i++) {
            Json j = auth.get(i);
            if (j.isMap() && "password".equals(j.stringValue("type"))) {
                callbacks.put(new PasswordCallback(j.stringValue("prompt") + ": ", true), j);
            }
        }
        Callback[] ca = callbacks.keySet().toArray(new Callback[0]);
        if (handler != null) {
            try {
                handler.handle(ca);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (UnsupportedCallbackException e) {
                e.printStackTrace();
            }
        }
        for (Map.Entry<Callback,Json> e : callbacks.entrySet()) {
            Callback c = e.getKey();
            Json j = e.getValue();
            j.remove("message");
            if (c instanceof PasswordCallback && ((PasswordCallback)c).getPassword() != null) {
                j.put("password", new String(((PasswordCallback)c).getPassword()));
            }
        }
        // auth [ { "type": "password", ... } ]
        // Just a password for now.
        req.put("auth", auth);
    }

    /**
     * Request the KeyStores from all remote suppliers
     */
    Map<String,KeyStore.Entry> requestKeyStores(KeyStore.ProtectionParameter prot) throws IOException, UnrecoverableKeyException {
        // Request the keys from all remotes suppliers.
        long d;
        while ((d=initializedAfter - System.currentTimeMillis()) > 0) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {}
        }
        if (remoteSuppliers.size() == 1) {
            return remoteSuppliers.values().iterator().next().getKeyStore(prot);
        }
        // Store names in temp map. If the key name is unique, use that, otherwise prefix with supplier name
        final Map<String,List<Map.Entry<String,KeyStore.Entry>>> temp = new LinkedHashMap<>();
        final Throwable[] exception = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(remoteSuppliers.size());
        for (RemoteSupplier s : remoteSuppliers.values()) {
            final RemoteSupplier supplier = s;
            ForkJoinPool.commonPool().execute(new Runnable() {
                public void run() {
                    try {
                        Map<String,KeyStore.Entry> map = supplier.getKeyStore(prot);
                        synchronized(temp) {
                            for (Map.Entry<String,KeyStore.Entry> e : map.entrySet()) {
                                List<Map.Entry<String,KeyStore.Entry>> l = temp.get(e.getKey());
                                if (l == null) {
                                    temp.put(e.getKey(), l = new ArrayList<Map.Entry<String,KeyStore.Entry>>());
                                }
                                l.add(new AbstractMap.SimpleImmutableEntry<String,KeyStore.Entry>(supplier.getName(), e.getValue()));
                            }
                        }
                    } catch (Exception e) {
                        synchronized(exception) {
                            if (exception[0] == null) {
                                exception[0] = e;
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (temp.isEmpty() && exception[0] != null) {
            if (exception[0] instanceof IOException) {
                throw (IOException)exception[0];
            } else if (exception[0] instanceof UnrecoverableKeyException) {
                throw (UnrecoverableKeyException)exception[0];
            } else {
                throw (RuntimeException)exception[0];
            }
        }
        final Map<String,KeyStore.Entry> entries = new LinkedHashMap<String,KeyStore.Entry>();
        for (Map.Entry<String,List<Map.Entry<String,KeyStore.Entry>>> e : temp.entrySet()) {
            String keyName = e.getKey();
            List<Map.Entry<String,KeyStore.Entry>> l = e.getValue();
            if (l.size() == 1) {
                entries.put(keyName, l.get(0).getValue());
            } else {
                for (Map.Entry<String,KeyStore.Entry> e2 : l) {
                    String supplierName = e2.getKey();
                    KeyStore.Entry entry = e2.getValue();
                    entries.put(supplierName + "." + keyName, entry);
                }
            }
        }
        return entries;
    }

    Map<String,KeyStore.Entry> requestKeyStore(final RemoteSupplier supplier, final KeyStore.ProtectionParameter prot) throws UnrecoverableKeyException, IOException {
        Json req = Json.read("{}");
        CallbackHandler callbackHandler = null;
        if (prot != null) {
            if (prot instanceof KeyStore.PasswordProtection) {
                char[] password = ((KeyStore.PasswordProtection)prot).getPassword();
                if (password != null) {
                    req.put("auth", Json.read("[]"));
                    req.get("auth").put(0, Json.read("{}"));
                    req.get("auth").get(0).put("type", "password");
                    req.get("auth").get(0).put("password", new String(password));
                }
            } else if (prot instanceof KeyStore.CallbackHandlerProtection) {
                callbackHandler = ((KeyStore.CallbackHandlerProtection)prot).getCallbackHandler();
            }
        }
        Json res = null;
        Map<String,KeyStore.Entry> entries = null;

        HttpURLConnection con = null;
        try {
            con = supplier.getURLConnection("/list-v1");
            if (debug) System.out.println("# TX " + con.getURL() + ": " + req);
            OutputStream out = con.getOutputStream();
            req.write(new CborWriter().setOutput(out));
            out.close();
            if (con.getResponseCode() == 200) {
                // { "type": "error": "message": "..." }
                // { "type": "list-v1", "keys": { "foo.n": ..., }, "auth": [ {"type":"password", "prompt": "xxx} ] }
                InputStream in = con.getInputStream();
                res = Json.readCbor(in);
                in.close();
                if (debug) System.out.println("# RX " + con.getURL() + ": " + res);
                String type = res.stringValue("type");
                if ("auth".equals(type) && res.isList("auth") && callbackHandler != null) {
                    con.disconnect();
                    processCallbacks(req, res.get("auth"), callbackHandler);
                    con = supplier.getURLConnection("/list-v1");
                    if (debug) System.out.println("# TX " + con.getURL() + ": " + req);
                    out = con.getOutputStream();
                    req.write(new CborWriter().setOutput(out));
                    out.close();
                    if (con.getResponseCode() == 200) {
                        in = con.getInputStream();
                        res = Json.readCbor(in);
                        if (debug) System.out.println("# RX " + con.getURL() + ": " + res);
                        in.close();
                        type = res.stringValue("type");
                    }
                }
                if ("list-v1".equals(type) && res.isMap("keys")) {
                    entries = new LinkedHashMap<String,KeyStore.Entry>();
                    char[] storepassword = null;
                    Json auth = req.get("auth");
                    if (auth != null && auth.isList()) {
                        for (int i=0;i<auth.size();i++) {
                            if (auth.get(i).stringValue("type").equals("password") && auth.get(i).isString("password")) {
                                storepassword = auth.get(i).stringValue("password").toCharArray();
                                break;
                            }
                        }
                    }
                    for (Map.Entry<Object,Json> e : res.mapValue("keys").entrySet()) {
                        String name = e.getKey().toString();
                        String alias = e.getValue().isString("alias") ? e.getValue().remove("alias").stringValue() : name;
                        JWK jwk = new JWK(e.getValue());
                        String kty = jwk.stringValue("kty");
                        PrivateKey key = new NetPrivateKey(supplier, name, kty, jwk, storepassword);
                        if (key != null) {
                            List<X509Certificate> certs = jwk.getCertificates();
                            Certificate[] c = new Certificate[certs.size()];
                            for (int i=0;i<c.length;i++) {
                                c[i] = certs.get(i);
                            }
                            entries.put(alias, new KeyStore.PrivateKeyEntry(key, c));
                        }
                    }
                } else if ("auth".equals(type)) {
                    throw new UnrecoverableKeyException(res.has("auth") ? res.get("auth").toString() : "Unauthorized");
                } else {
                    throw new IOException("Invalid response: " + res);
                }
            } else {
                throw new IOException("HTTP " + con.getResponseCode());
            }
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
        return entries;
    }

    /**
     * Send a signature request
     * @param supplier the supplier to send to
     * @param keyName the name of a Key hosted by that supplier
     * @param prot the protection parameter (password or callback) used to decrypt that key
     * @param keyAlg the key algorithm, eg RSA or RC (not currently used)
     * @param digestAlg the digest algorith, eg SHA256 or SHA1
     * @param digest the digest computed using the specified Digest algorithm
     * @return a Future which will resolve to the digested bytes when completed.
     */
    byte[] requestSignature(final RemoteSupplier supplier, final String keyName, final char[] storePassword, final KeyStore.ProtectionParameter prot, final String sigAlg, final String digestAlg, final AlgorithmParameters params, final byte[] digest) throws IOException, UnrecoverableKeyException {
        // Request signature from a remote supplier.
        // send format is { "key": name, "digest": digest, "digest_alg": alg }
        // return format is { "signature": bytes }
        Json req = Json.read("{}");
        req.put("key", keyName);
        req.put("sig_alg", sigAlg);
        req.put("digest_alg", digestAlg);
        req.put("digest", digest);
        if (params != null) {
            req.put("sig_params", params.getEncoded());
        }
        Json auth = Json.read("[]");
        req.put("auth", auth);
        if (storePassword != null) {
            Json j = Json.read("{}");
            j.put("type", "password");
            j.put("for", keyName.substring(0, keyName.indexOf(".")));       // Presumes supplier has no period!
            j.put("password", new String(storePassword));
            auth.put(auth.size(), j);
        }
        CallbackHandler callbackHandler = null;
        if (prot != null) {
            if (prot instanceof KeyStore.PasswordProtection) {
                char[] password = ((KeyStore.PasswordProtection)prot).getPassword();
                if (password != null) {
                    Json j = Json.read("{}");
                    j.put("type", "password");
                    j.put("for", keyName);
                    j.put("password", new String(password));
                    auth.put(auth.size(), j);
                }
            } else if (prot instanceof KeyStore.CallbackHandlerProtection) {
                callbackHandler = ((KeyStore.CallbackHandlerProtection)prot).getCallbackHandler();
            }
        }
        Json res = null;
        byte[] signature = null;

        HttpURLConnection con = null;
        try {
            con = supplier.getURLConnection("/sign-v1");
            if (debug) System.out.println("# TX " + con.getURL() + ": " + req);
            OutputStream out = con.getOutputStream();
            req.write(new CborWriter().setOutput(out));
            out.close();
            if (con.getResponseCode() == 200) {
                // { "type": "error": "message": "..." }
                InputStream in = con.getInputStream();
                res = Json.readCbor(in);
                in.close();
                if (debug) System.out.println("# RX " + con.getURL() + ": " + res);
                String type = res.stringValue("type");
                if ("auth".equals(type) && res.isList("auth") && callbackHandler != null) {
                    processCallbacks(req, res.get("auth"), callbackHandler);
                    con.disconnect();
                    con = supplier.getURLConnection("/sign-v1");
                    if (debug) System.out.println("# TX " + con.getURL() + ": " + req);
                    out = con.getOutputStream();
                    req.write(new CborWriter().setOutput(out));
                    out.close();
                    if (con.getResponseCode() == 200) {
                        in = con.getInputStream();
                        res = Json.readCbor(in);
                        System.out.println("RX /sign-v1: " + res);
                        if (debug) System.out.println("# RX " + con.getURL() + ": " + res);
                        in.close();
                        type = res.stringValue("type");
                    }
                }
                if ("sign-v1".equals(type)) {
                    signature = res.bufferValue("signature").array();
                } else if ("auth".equals(type)) {
                    throw new UnrecoverableKeyException(res.has("auth") ? res.get("auth").toString() : "Unauthorized");
                } else {
                    throw new IOException("Invalid response: " + res);
                }
            } else {
                throw new IOException("HTTP " + con.getResponseCode());
            }
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
        return signature;
    }

    //-------------------------------------------------------

    KeyStore loadLocalKeyStore(Json config) throws IOException, GeneralSecurityException {
        try {
            KeyStore.ProtectionParameter prot = null;    // localcallback?
            if (config.isString("password")) {
                prot = new KeyStore.PasswordProtection(config.stringValue("password").toCharArray());
            }
            String engineName = getName();
            String type = config.stringValue("type");
            String path = config.stringValue("path");
            String providerName = config.stringValue("provider");
            Provider provider = null;
            if ("pkcs11".equals(type)) {
                provider = Security.getProvider("SunPKCS11");
                StringBuilder sb = new StringBuilder();
                sb.append("--");
                if (!config.isString("name")) {
                    sb.append("name = " + name + "\n");
                }
                for (Map.Entry<Object,Json> e : config.mapValue().entrySet()) {
                    String key = e.getKey().toString();
                    switch (key.toLowerCase()) {
                        case "name":
                        case "library":
                        case "slotlistindex":
                        case "slot":
                        case "description":
                        case "enabledmechanisms":
                        case "disabedmechanisms":
                            sb.append(key + " = " + e.getValue().stringValue()+ "\n");
                            break;
                    }
                    // TODO attributes
                }
                provider = provider.configure(sb.toString());
                path = null;
            } else {
                if (providerName != null) {
                    for (Provider p : Security.getProviders()) {
                        if (p.getClass().getName().equals(providerName) || p.getName().equals(providerName)) {
                            provider = p;
                            break;
                        }
                    }
                    if (provider == null) {
                        throw new IllegalArgumentException("Provider \"" + providerName+ "\" not found");
                    }
                }
            }

            final Provider fprovider = provider;
            if (provider instanceof AuthProvider) {
                CallbackHandler cbhandler = null;
                if (prot instanceof KeyStore.CallbackHandlerProtection) {
                    cbhandler = ((KeyStore.CallbackHandlerProtection)prot).getCallbackHandler();
                } else {
                    final char[] password = ((KeyStore.PasswordProtection)prot).getPassword();
                    cbhandler = new CallbackHandler() {
                        public void handle(Callback[] callbacks) {
                            for (Callback cb : callbacks) {
                                if (cb instanceof PasswordCallback) {
                                    ((PasswordCallback)cb).setPassword(password);
                                }
                            }
                        }
                    };
                }
                ((AuthProvider)provider).setCallbackHandler(cbhandler);
            }
            final KeyStore.ProtectionParameter fprot = prot;
            final KeyStore.LoadStoreParameter loadParam = new KeyStore.LoadStoreParameter() {
                public KeyStore.ProtectionParameter getProtectionParameter() {
                    return fprot;
                }
            };
            KeyStore keystore = null;
            if (provider == null && path != null) {
                keystore = KeyStore.getInstance(new File(path), loadParam);
            } else {
                if (provider == null) {
                    keystore = KeyStore.getInstance(type);
                } else {
                    keystore = KeyStore.getInstance(type, provider);
                }
                keystore.load(loadParam);
            }
            return keystore;
        } catch (IOException e) {
            if (e.getCause() instanceof UnrecoverableKeyException) {
                throw (UnrecoverableKeyException)e.getCause();
            } else if (e.getCause() instanceof LoginException) {
                throw (LoginException)e.getCause();
            }
            throw e;
        }
    }

}
