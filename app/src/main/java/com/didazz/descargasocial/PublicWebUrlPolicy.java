// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Network boundary for arbitrary web pages and their media resources. */
public final class PublicWebUrlPolicy {
    public static final int MAX_URL_LENGTH = 4096;

    private PublicWebUrlPolicy() {
    }

    /** Validates syntax, resolves DNS and returns a normalized public HTTPS URI. */
    public static URI requirePublicHttps(String value) throws IOException {
        URI uri = requirePublicHttpsSyntax(value, false);
        verifyPublicDns(uri.getHost());
        return uri;
    }

    /** Resolves one page-provided URL and applies the same public-network boundary. */
    public static URI resolvePublicHttps(URI base, String reference) throws IOException {
        if (base == null || reference == null || reference.trim().isEmpty()) {
            throw new IOException("La página entregó una dirección vacía.");
        }
        URI resolved;
        try {
            resolved = base.resolve(reference.trim());
            if (resolved.getFragment() != null) {
                String withoutFragment = resolved.toASCIIString();
                int hash = withoutFragment.indexOf('#');
                resolved = new URI(hash < 0 ? withoutFragment : withoutFragment.substring(0, hash));
            }
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            throw new IOException("La página entregó una dirección inválida.", invalid);
        }
        return requirePublicHttps(resolved.toASCIIString());
    }

    /** Returns only the HTTPS origin; paths and signed query parameters never leak as Referer. */
    public static String origin(URI page) throws IOException {
        URI safe = requirePublicHttpsSyntax(page == null ? null : page.toASCIIString(), true);
        String host = safe.getHost().contains(":") ? "[" + safe.getHost() + "]" : safe.getHost();
        return "https://" + host + "/";
    }

    static URI requirePublicHttpsSyntax(String value, boolean permitFragmentRemoval)
            throws IOException {
        if (value == null) {
            throw new IOException("El enlace está vacío.");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH || containsControl(trimmed)) {
            throw new IOException("El enlace es inválido o demasiado largo.");
        }
        final URI parsed;
        try {
            parsed = new URI(trimmed);
        } catch (URISyntaxException invalid) {
            throw new IOException("El enlace no es una dirección válida.", invalid);
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme())
                || parsed.getHost() == null
                || parsed.getRawUserInfo() != null
                || parsed.getPort() != -1 && parsed.getPort() != 443) {
            throw new IOException("Por seguridad, el extractor web solo admite HTTPS público.");
        }
        if (parsed.getFragment() != null && !permitFragmentRemoval) {
            throw new IOException("El enlace web no debe contener un fragmento.");
        }
        String asciiHost;
        try {
            asciiHost = IDN.toASCII(parsed.getHost(), IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.US);
        } catch (IllegalArgumentException invalidHost) {
            throw new IOException("El dominio del enlace no es válido.", invalidHost);
        }
        if (isForbiddenHostName(asciiHost)) {
            throw new IOException("El extractor no puede acceder a redes locales o reservadas.");
        }
        return parsed;
    }

    static boolean isForbiddenHostName(String host) {
        if (host == null || host.isEmpty() || host.indexOf('.') < 0 || host.contains(":")) {
            return true;
        }
        String lower = host.toLowerCase(Locale.US);
        return lower.endsWith(".")
                || lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.endsWith(".local")
                || lower.endsWith(".lan")
                || lower.endsWith(".internal")
                || lower.endsWith(".home")
                || lower.endsWith(".corp")
                || lower.endsWith(".onion")
                || lower.endsWith(".invalid")
                || lower.endsWith(".test")
                || lower.matches("[0-9.]+");
    }

    static boolean isForbiddenAddress(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            return a == 0
                    || a == 10
                    || a == 100 && b >= 64 && b <= 127
                    || a == 127
                    || a == 169 && b == 254
                    || a == 172 && b >= 16 && b <= 31
                    || a == 192 && b == 0 && c == 0
                    || a == 192 && b == 0 && c == 2
                    || a == 192 && b == 168
                    || a == 198 && (b == 18 || b == 19)
                    || a == 198 && b == 51 && c == 100
                    || a == 203 && b == 0 && c == 113
                    || a >= 224;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            boolean documentation = first == 0x20 && second == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8;
            if ((first & 0xfe) == 0xfc || documentation) {
                return true;
            }
            if (isIpv4Mapped(bytes)) {
                return isForbiddenIpv4Bytes(bytes, 12);
            }
            // 6to4 embeds its IPv4 destination in bytes 2..5.
            if (first == 0x20 && second == 0x02 && isForbiddenIpv4Bytes(bytes, 2)) {
                return true;
            }
            // Teredo encodes the client IPv4 address, inverted, in the final four bytes.
            if (first == 0x20 && second == 0x01
                    && bytes[2] == 0 && bytes[3] == 0) {
                byte[] decoded = bytes.clone();
                for (int index = 12; index < 16; index++) {
                    decoded[index] = (byte) ~decoded[index];
                }
                if (isForbiddenIpv4Bytes(decoded, 12)) {
                    return true;
                }
            }
            // RFC 6052 well-known NAT64 prefix.
            if (first == 0x00 && second == 0x64
                    && (bytes[2] & 0xff) == 0xff && (bytes[3] & 0xff) == 0x9b
                    && isForbiddenIpv4Bytes(bytes, 12)) {
                return true;
            }
            return false;
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isForbiddenIpv4Bytes(byte[] bytes, int offset) {
        int a = bytes[offset] & 0xff;
        int b = bytes[offset + 1] & 0xff;
        int c = bytes[offset + 2] & 0xff;
        return a == 0
                || a == 10
                || a == 100 && b >= 64 && b <= 127
                || a == 127
                || a == 169 && b == 254
                || a == 172 && b >= 16 && b <= 31
                || a == 192 && b == 0 && c == 0
                || a == 192 && b == 0 && c == 2
                || a == 192 && b == 168
                || a == 198 && (b == 18 || b == 19)
                || a == 198 && b == 51 && c == 100
                || a == 203 && b == 0 && c == 113
                || a >= 224;
    }

    private static void verifyPublicDns(String host) throws IOException {
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException failure) {
            throw failure;
        }
        if (addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) {
                throw new IOException("El dominio apunta a una red local o reservada.");
            }
        }
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x1f || character == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
