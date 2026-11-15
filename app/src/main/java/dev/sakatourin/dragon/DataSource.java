package dev.sakatourin.dragon;

final class DataSource {
    static boolean isAllowed(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) return false;
            if ("https".equals(uri.getScheme())) return true;
            return "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                && uri.getPort() == 8765 && "/quota".equals(uri.getPath())
                && uri.getQuery() == null;
        } catch (Exception e) { return false; }
    }
}
