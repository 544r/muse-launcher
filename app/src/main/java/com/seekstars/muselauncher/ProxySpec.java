package com.seekstars.muselauncher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** 校验与凭据分离；错误消息不能包含代理输入。 */
final class ProxySpec {
    final String rule;
    final String host;
    final String username;
    final String password;
    final String value;

    private ProxySpec(String value, String rule, String host, String username, String password) {
        this.value = value;
        this.rule = rule;
        this.host = host;
        this.username = username;
        this.password = password;
    }

    static ProxySpec parse(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return new ProxySpec("", "", "", null, null);
        if (!value.contains("://")) value = "http://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("socks5")) {
                throw new IllegalArgumentException("代理仅支持 http、https 和 socks5");
            }
            if (uri.getHost() == null || uri.getPort() == 0 || uri.getPort() < -1
                    || uri.getPort() > 65535 || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getRawAuthority().endsWith(":")) {
                throw new IllegalArgumentException("代理地址无效，请检查主机和端口");
            }
            String user = null;
            String pass = null;
            if (uri.getUserInfo() != null) {
                if (scheme.equals("socks5")) {
                    throw new IllegalArgumentException("系统 WebView 不支持 SOCKS5 账密认证，请使用本地代理转接");
                }
                String info = uri.getUserInfo();
                int colon = info.indexOf(':');
                user = colon < 0 ? info : info.substring(0, colon);
                pass = colon < 0 ? "" : info.substring(colon + 1);
                if (user.isEmpty() || info.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("代理凭据格式无效");
                }
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort() >= 0 ? uri.getPort()
                    : scheme.equals("https") ? 443 : scheme.equals("socks5") ? 1080 : 80;
            String rule = scheme + "://" + host + ":" + port;
            String normalized = scheme + "://"
                    + (uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo() + "@")
                    + host + ":" + port;
            return new ProxySpec(normalized, rule, host, user, pass);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("代理地址格式无效，特殊字符请使用 URL 编码");
        }
    }

    boolean authenticates(String challengeHost) {
        return username != null && host.equalsIgnoreCase(challengeHost);
    }
}
