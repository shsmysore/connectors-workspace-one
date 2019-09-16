package com.vmware.connectors.common.utils;

import com.vmware.connectors.common.payloads.response.Card;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Common utility functions to be used across connectors parent.
 */
public final class CommonUtils {

    public static final String APPROVAL_ACTIONS = "approval-actions";

    private static final String DEFAULT_IMAGE_PATH = "/images/connector.png";

    public final static String BACKEND_STATUS = "X-Backend-Status";

    private CommonUtils() {
        // Utility class.
    }

    public static void buildConnectorImageUrl(final Card.Builder card, final ServerHttpRequest request) {
        final String uri = buildConnectorImageUrl(request);

        if (StringUtils.isNotBlank(uri)) {
            card.setImageUrl(uri);
        }
    }

    public static String buildConnectorImageUrl(final HttpRequest request) {
        return buildConnectorUrl(request, DEFAULT_IMAGE_PATH);
    }

    public static String buildConnectorUrl(final HttpRequest request, final String path) {
        return UriComponentsBuilder.fromHttpRequest(request).replacePath(path).build().toString();
    }
}
