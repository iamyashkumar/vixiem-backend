package com.velorix.backend.security;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class UrlSecurityValidator {

    /**
     * Prevent SSRF probes targeting private or loopback infrastructure.
     */
    public URI validatePublicHttpUrl(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Only absolute HTTP(S) URLs without credentials are allowed");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivateAddress(address)) {
                    throw new IllegalArgumentException("Private, loopback, and link-local addresses cannot be monitored");
                }
            }
            return uri;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("The endpoint host could not be resolved");
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid endpoint URL");
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean carrierGradeNat = bytes.length == 4 && (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127);
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress() || carrierGradeNat;
    }
}
