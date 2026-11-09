/* WolfSSLInternalVerifyCb.java
 *
 * Copyright (C) 2006-2022 wolfSSL Inc.
 *
 * This file is part of wolfSSL.
 *
 * wolfSSL is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * wolfSSL is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
 */
package com.wolfssl.provider.jsse;

import com.wolfssl.WolfSSL;
import com.wolfssl.WolfSSLVerifyCallback;
import com.wolfssl.WolfSSLException;
import com.wolfssl.WolfSSLSession;
import com.wolfssl.WolfSSLCertificate;
import com.wolfssl.WolfSSLX509StoreCtx;
import com.wolfssl.provider.jsse.WolfSSLInternalVerifyCb;
import java.util.Arrays;
import java.util.List;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;

/**
 * Internal verify callback.
 * This is used when a user registers a TrustManager which is NOT
 * com.wolfssl.provider.jsse.WolfSSLTrustManager. It is used to call
 * TrustManager checkClientTrusted() or checkServerTrusted().
 * If wolfJSSE TrustManager is used, native wolfSSL does certificate
 * verification internally. Used by WolfSSLEngineHelper.
 */
public class WolfSSLInternalVerifyCb implements WolfSSLVerifyCallback {

    private X509TrustManager tm = null;
    private boolean clientMode;

    /**
     * Create new WolfSSLInternalVerifyCb
     *
     * @param xtm X509TrustManager to use with this object
     * @param client boolean representing if this is client side
     */
    public WolfSSLInternalVerifyCb(X509TrustManager xtm, boolean client) {
        this.tm = xtm;
        this.clientMode = client;
    }

    /**
     * Native wolfSSL verify callback.
     *
     * @param preverify_ok Will be 1 if native wolfSSL verification
     *        procedured have passed, otherwise 0.
     * @param x509StorePtr Native pointer to WOLFSSL_X509_STORE structure
     *
     * @return 1 if verification should be considered successful and
     *         SSL/TLS handshake should continue. Otherwise return 0
     *         to mark verification failure and stop/abort handshake.
     */
    /* index of the peer certificate: the one that issued no other certificate
     * of the chain; 0 if that is not unique */
    private static int findLeafIndex(X509Certificate[] chain) {
        int leaf = -1;
        if (chain == null) {
            return 0;
        }
        for (int i = 0; i < chain.length; i++) {
            boolean issuesOther = false;
            for (int j = 0; j < chain.length; j++) {
                if (i != j && chain[j].getIssuerX500Principal().equals(
                        chain[i].getSubjectX500Principal()) &&
                        !chain[j].getSubjectX500Principal().equals(
                        chain[j].getIssuerX500Principal())) {
                    issuesOther = true;
                    break;
                }
            }
            if (!issuesOther) {
                if (leaf != -1) {
                    return 0;
                }
                leaf = i;
            }
        }
        return leaf == -1 ? 0 : leaf;
    }

    /* chain ordered peer certificate, its issuer, that one's issuer, ...;
     * certificates not reached that way follow in their old order */
    private static X509Certificate[] sortLeafFirst(X509Certificate[] chain,
                                                    int leaf) {
        X509Certificate[] out = new X509Certificate[chain.length];
        boolean[] used = new boolean[chain.length];
        int n = 0;
        int cur = leaf;
        while (cur != -1 && !used[cur]) {
            used[cur] = true;
            out[n++] = chain[cur];
            int next = -1;
            for (int j = 0; j < chain.length; j++) {
                if (!used[j] && chain[j].getSubjectX500Principal().equals(
                        chain[cur].getIssuerX500Principal())) {
                    next = j;
                    break;
                }
            }
            cur = next;
        }
        for (int j = 0; j < chain.length; j++) {
            if (!used[j]) {
                out[n++] = chain[j];
            }
        }
        return out;
    }

    public int verifyCallback(int preverify_ok, long x509StorePtr) {

        WolfSSLCertificate[] certs = null;
        X509Certificate[] x509certs = null;
        String authType = null;

        if (preverify_ok == 1) {
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                    "Native wolfSSL peer verification passed");
        } else {
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                    "NOTE: Native wolfSSL peer verification failed");
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                    "      Continuing with X509TrustManager verification");
        }

        try {
            /* get WolfSSLCertificate[] from x509StorePtr */
            WolfSSLX509StoreCtx store =
                new WolfSSLX509StoreCtx(x509StorePtr);
            certs = store.getCerts();

        } catch (WolfSSLException e) {
            /* failed to get certs from native, give app null array */
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                "Failed to get certs from x509StorePtr, certs = null");
            certs = null;
        }

        if (certs != null && certs.length > 0) {
            try {
                /* Convert WolfSSLCertificate[] to X509Certificate[] */
                x509certs = new X509Certificate[certs.length];
                for (int i = 0; i < certs.length; i++) {
                    x509certs[i] = certs[i].getX509Certificate();
                    WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                        "Peer cert: " + x509certs[i].getSubjectDN().getName());
                }
            } catch (CertificateException | IOException ce) {
                /* failed to get cert array, give app null array */
                WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                    "Failed to get X509Certificate[] array, set to null");
                x509certs = null;
            }

            /* get authType, use first cert */
            /* The store chain is not in the order TrustManager.checkServerTrusted()
             * is specified for (peer certificate first): wolfSSL lists the
             * topmost certificate first. A TrustManager that does not sort
             * the chain itself (e.g. one that wraps the default one, as K-9
             * does) got the chain upside down and rejected it ("Failed to
             * verify intermediate chain cert", error -329). Put the peer
             * certificate first, then each issuer. */
            int leaf = findLeafIndex(x509certs);
            if (x509certs != null) {
                x509certs = sortLeafFirst(x509certs, leaf);
            }

            String sigType = certs[leaf].getSignatureType();
            if (sigType.contains("RSA")) {
                authType = "RSA";
            } else if (sigType.contains("ECDSA")) {
                authType = "ECDSA";
            } else if (sigType.contains("DSA")) {
                authType = "DSA";
            } else if (sigType.contains("ED25519")) {
                authType = "ED25519";
            }
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                "Auth type: " + authType);

            /* Free native WolfSSLCertificate memory. At this
             * point x509certs[] is all Java managed memory now. */
            for (int i = 0; i < certs.length; i++) {
                certs[i].free();
            }
        }

        try {
            /* poll TrustManager for cert verification, should throw
             * CertificateException if verification fails */
            if (clientMode) {
                WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                    "Calling TrustManager.checkServerTrusted()");
                tm.checkServerTrusted(x509certs, authType);
            } else {
                WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                    "Calling TrustManager.checkClientTrusted()");
                tm.checkClientTrusted(x509certs, authType);
            }
        } catch (Exception e) {
            /* TrustManager rejected certificate, not valid */
            WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
                "TrustManager rejected certificates, verification failed");
            return 0;
        }

        WolfSSLDebug.log(getClass(), WolfSSLDebug.INFO,
            "TrustManager verification successful");
        /* continue handshake, verification succeeded */
        return 1;
    }
}

