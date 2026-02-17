package ca.uhn.fhir.jpa.starter.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Rewrites transaction/batch Patient POST entries with client IDs into PUT upserts.
 */
@Component
@Order(1)
public class TransactionPatientUpsertFilter extends OncePerRequestFilter {

    private static final Logger ourLog = LoggerFactory.getLogger(TransactionPatientUpsertFilter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !isFhirRequest(request) || !isJsonRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] originalBody = request.getInputStream().readAllBytes();
        if (originalBody.length == 0) {
            filterChain.doFilter(request, response);
            return;
        }

        RewriteResult rewrite = maybeRewriteRequest(request, originalBody);
        if (rewrite == null) {
            filterChain.doFilter(new CachedBodyRequestWrapper(request, originalBody), response);
            return;
        }

        HttpServletRequest wrapped = new CachedBodyRequestWrapper(
                request,
                rewrite.body,
                rewrite.method,
                rewrite.requestUri,
                rewrite.servletPath,
                rewrite.pathInfo
        );
        filterChain.doFilter(wrapped, response);
    }

    private boolean isFhirRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return false;
        String normalized = uri.toLowerCase();
        return normalized.endsWith("/fhir") || normalized.endsWith("/fhir/");
    }

    private boolean isJsonRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) return false;
        String ct = contentType.toLowerCase();
        return ct.contains("application/json") || ct.contains("application/fhir+json");
    }

    private RewriteResult maybeRewriteRequest(HttpServletRequest request, byte[] body) {
        RewriteResult txRewrite = maybeRewriteTransactionBundle(body);
        if (txRewrite != null) return txRewrite;
        return maybeRewriteSinglePatientCreate(request, body);
    }

    private RewriteResult maybeRewriteTransactionBundle(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!(root instanceof ObjectNode rootObj)) return null;

            if (!"Bundle".equals(rootObj.path("resourceType").asText())) return null;

            String bundleType = rootObj.path("type").asText();
            if (!("transaction".equalsIgnoreCase(bundleType) || "batch".equalsIgnoreCase(bundleType))) return null;

            JsonNode entriesNode = rootObj.get("entry");
            if (!(entriesNode instanceof ArrayNode entries)) return null;

            boolean changed = false;
            for (JsonNode entryNode : entries) {
                if (!(entryNode instanceof ObjectNode entryObj)) continue;

                JsonNode resourceNode = entryObj.get("resource");
                JsonNode requestNode = entryObj.get("request");
                if (!(resourceNode instanceof ObjectNode resourceObj) || !(requestNode instanceof ObjectNode reqObj)) continue;

                String method = reqObj.path("method").asText();
                String url = reqObj.path("url").asText();
                String resourceType = resourceObj.path("resourceType").asText();
                String id = resourceObj.path("id").asText();

                if ("POST".equalsIgnoreCase(method)
                        && "Patient".equals(url)
                        && "Patient".equals(resourceType)
                        && id != null
                        && !id.isBlank()) {
                    reqObj.put("method", "PUT");
                    reqObj.put("url", "Patient/" + id.trim());
                    changed = true;
                }
            }

            if (!changed) return null;

            ourLog.info("Rewrote transaction/batch Patient POST entries to PUT upserts");
            return new RewriteResult(
                    objectMapper.writeValueAsBytes(rootObj),
                    null,
                    null,
                    null,
                    null
            );
        } catch (IOException ignored) {
            return null;
        }
    }

    private RewriteResult maybeRewriteSinglePatientCreate(HttpServletRequest request, byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!(root instanceof ObjectNode resourceObj)) return null;

            if (!"Patient".equals(resourceObj.path("resourceType").asText())) return null;

            String id = resourceObj.path("id").asText();
            if (id == null || id.isBlank()) return null;

            String requestUri = request.getRequestURI();
            if (requestUri == null || !requestUri.toLowerCase().endsWith("/fhir/patient")) return null;

            String normalizedId = id.trim();
            String newRequestUri = requestUri.endsWith("/")
                    ? requestUri + normalizedId
                    : requestUri + "/" + normalizedId;

            String servletPath = request.getServletPath();
            String newServletPath = servletPath;
            String pathInfo = request.getPathInfo();
            String newPathInfo = pathInfo;

            if (pathInfo != null) {
                newPathInfo = pathInfo.endsWith("/") ? pathInfo + normalizedId : pathInfo + "/" + normalizedId;
            } else if (servletPath != null && servletPath.toLowerCase().endsWith("/fhir/patient")) {
                newServletPath = servletPath + "/" + normalizedId;
            }

            ourLog.info("Rewrote single Patient POST with client ID '{}' to PUT /Patient/{}", normalizedId, normalizedId);
            return new RewriteResult(body, "PUT", newRequestUri, newServletPath, newPathInfo);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static class RewriteResult {
        final byte[] body;
        final String method;
        final String requestUri;
        final String servletPath;
        final String pathInfo;

        RewriteResult(byte[] body, String method, String requestUri, String servletPath, String pathInfo) {
            this.body = body;
            this.method = method;
            this.requestUri = requestUri;
            this.servletPath = servletPath;
            this.pathInfo = pathInfo;
        }
    }

    private static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;
        private final String method;
        private final String requestUri;
        private final String servletPath;
        private final String pathInfo;

        CachedBodyRequestWrapper(HttpServletRequest request,
                                 byte[] cachedBody,
                                 String method,
                                 String requestUri,
                                 String servletPath,
                                 String pathInfo) {
            super(request);
            this.cachedBody = cachedBody;
            this.method = method;
            this.requestUri = requestUri;
            this.servletPath = servletPath;
            this.pathInfo = pathInfo;
        }

        CachedBodyRequestWrapper(HttpServletRequest request, byte[] cachedBody) {
            this(request, cachedBody, null, null, null, null);
        }

        @Override
        public String getMethod() {
            return method != null ? method : super.getMethod();
        }

        @Override
        public String getRequestURI() {
            return requestUri != null ? requestUri : super.getRequestURI();
        }

        @Override
        public StringBuffer getRequestURL() {
            if (requestUri == null) return super.getRequestURL();
            StringBuffer url = new StringBuffer();
            url.append(getScheme()).append("://").append(getServerName());
            if (("http".equalsIgnoreCase(getScheme()) && getServerPort() != 80)
                    || ("https".equalsIgnoreCase(getScheme()) && getServerPort() != 443)) {
                url.append(":").append(getServerPort());
            }
            url.append(requestUri);
            return url;
        }

        @Override
        public String getServletPath() {
            return servletPath != null ? servletPath : super.getServletPath();
        }

        @Override
        public String getPathInfo() {
            return pathInfo != null ? pathInfo : super.getPathInfo();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return byteArrayInputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // no-op
                }

                @Override
                public int read() {
                    return byteArrayInputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return cachedBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return cachedBody.length;
        }
    }
}
