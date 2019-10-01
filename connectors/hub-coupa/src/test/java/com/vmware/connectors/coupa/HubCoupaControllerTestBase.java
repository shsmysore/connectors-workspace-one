/*
 * Copyright © 2019 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.vmware.connectors.coupa;

import com.vmware.connectors.test.ControllerTestsBase;
import com.vmware.connectors.test.JsonNormalizer;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.springframework.http.HttpHeaders.*;
import static org.springframework.http.HttpMethod.*;
import static org.springframework.http.MediaType.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

class HubCoupaControllerTestBase extends ControllerTestsBase {

    static final String CALLER_SERVICE_CREDS = "service-creds-from-http-request";
    static final String CONFIG_SERVICE_CREDS = "service-creds-from-config";

    private static final String AUTHORIZATION_HEADER_NAME = "X-COUPA-API-KEY";

    @Value("classpath:fake/attachment/attachment.jpg")
    private Resource attachment;

    @ParameterizedTest
    @ValueSource(strings = {
            "/cards/requests",
            "/api/approve/123",
            "/api/reject/123"
    })
    void testProtectedResources(String uri) throws Exception {
        testProtectedResource(POST, uri);
    }

    @Test
    void testDiscovery() throws Exception {
        testConnectorDiscovery();
    }

    WebTestClient.ResponseSpec approveRequest(String authHeader) {
        String uri = "/api/approve/182964?comment=Approved";
        return webClient.post()
                .uri(uri)
                .header(AUTHORIZATION, "Bearer " + accessToken(uri))
                .header(X_AUTH_HEADER, authHeader)
                .header(X_BASE_URL_HEADER, mockBackend.url(""))
                .contentType(APPLICATION_FORM_URLENCODED)
                .exchange();
    }

    WebTestClient.ResponseSpec rejectRequest(String authHeader) {
        String uri = "/api/decline/182964?comment=Declined";
        return webClient.post().uri("/api/decline/182964?comment=Declined")
                .header(AUTHORIZATION, "Bearer " + accessToken(uri))
                .header(X_AUTH_HEADER, authHeader)
                .header(X_BASE_URL_HEADER, mockBackend.url(""))
                .contentType(APPLICATION_FORM_URLENCODED)
                .exchange();
    }

    WebTestClient.ResponseSpec cardsRequest(String lang, String authHeader) {
        String uri = "/cards/requests";
        WebTestClient.RequestHeadersSpec<?> spec = webClient.post()
                .uri(uri)
                 .header(X_BASE_URL_HEADER, mockBackend.url(""))
                .header(X_AUTH_HEADER, authHeader)
                .header("x-routing-prefix", "https://hero/connectors/coupa/")
                .headers(headers -> headers(headers, uri))
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON);

        if (StringUtils.isNotBlank(lang)) {
            spec.header(ACCEPT_LANGUAGE, lang);
        }

        return spec.exchange();
    }

    void fetchAttachment(String authHeader) throws IOException {
        byte[] result = getAttachment(authHeader)
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();

        byte[] expected = this.attachment.getInputStream().readAllBytes();
        Arrays.equals(result, expected);
    }

    void fetchAttachmentForInvalidDetails(String authHeader) {
        getAttachment(authHeader)
                .expectStatus().isNotFound();
    }

    void unauthorizedAttachmentRequest(String authHeader) throws IOException {
        getAttachment(authHeader)
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("X-Backend-Status", "401")
                .expectBody().json(fromFile("/connector/responses/invalid_connector_token.json"));
    }

    void fetchAttachmentWithServerError(String authHeader) {
        getAttachment(authHeader)
                .expectStatus().is5xxServerError();
    }

    void fetchInvalidAttachmentId(String authHeader) {
        String uri = "/api/user/15882/182964/attachment/response.png/invalid-attachment-id";
        webClient.get()
                .uri(uri)
                .header(AUTHORIZATION, "Bearer " + accessToken(uri))
                .header(X_BASE_URL_HEADER, mockBackend.url(""))
                .header(X_AUTH_HEADER, authHeader)
                .exchange()
                .expectStatus().isNotFound();

    }

    private WebTestClient.ResponseSpec getAttachment(String authHeader) {
        String uri = "/api/user/15882/182964/attachment/response.png/2701685";
        return webClient.get()
                .uri(uri)
                .header(AUTHORIZATION, "Bearer " + accessToken(uri))
                .header(X_BASE_URL_HEADER, mockBackend.url(""))
                .header(X_AUTH_HEADER, authHeader)
                .exchange();
    }

    void cardsRequest(String lang, String expected, String authHeader) throws Exception {
        String body = cardsRequest(lang, authHeader)
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .returnResult(String.class)
                .getResponseBody()
                .collect(Collectors.joining())
                .map(JsonNormalizer::forCards)
                .block()
                .replaceAll("[0-9]{4}[-][0-9]{2}[-][0-9]{2}T[0-9]{2}[:][0-9]{2}[:][0-9]{2}Z?", "1970-01-01T00:00:00Z")
                .replaceAll("[a-z0-9]{40,}", "test-hash");

        assertThat(
                body,
                sameJSONAs(fromFile("connector/responses/" + expected).replace("${COUPA_URL}", mockBackend.url("")))
                        .allowingAnyArrayOrdering()
                        .allowingExtraUnexpectedFields()
        );
    }

    void mockRejectActions(String serviceCredential) throws Exception {
        mockRequisitionDetails(serviceCredential);
        mockRejectAction(serviceCredential);
    }

    void mockApproveActions(String serviceCredential) throws Exception {
        mockRequisitionDetails(serviceCredential);
        mockApproveAction(serviceCredential);
    }

    void mockCoupaRequest(String serviceCredential) throws Exception {
        mockUserDetails(serviceCredential);
        mockApproval(serviceCredential);
        mockRequisitionDetails(serviceCredential);
    }

    void mockApproval(String serviceCredential) throws Exception {
        mockBackend.expect(requestTo("/api/approvals?approver_id=15882&status=pending_approval"))
                .andExpect(method(GET))
                .andExpect(header(ACCEPT, APPLICATION_JSON_VALUE))
                .andExpect(header(AUTHORIZATION_HEADER_NAME, serviceCredential))
                .andRespond(withSuccess(
                        fromFile("/fake/approvals.json").replace("${coupa_host}", mockBackend.url("")),
                        APPLICATION_JSON
                ));
    }

    void mockUserDetails(String serviceCredential) throws Exception {
        mockBackend.expect(requestTo("/api/users?email=admin%40acme.com"))
                .andExpect(method(GET))
                .andExpect(header(ACCEPT, APPLICATION_JSON_VALUE))
                .andExpect(header(AUTHORIZATION_HEADER_NAME, serviceCredential))
                .andRespond(withSuccess(
                        fromFile("/fake/user-details.json").replace("${coupa_host}", mockBackend.url("")),
                        APPLICATION_JSON
                ));
    }

    void mockApproveAction(String serviceCredential) {
        mockBackend.expect(requestTo("/api/approvals/6609559/approve?reason=Approved"))
                .andExpect(method(PUT))
                .andExpect(header(ACCEPT, APPLICATION_JSON_VALUE))
                .andExpect(header(AUTHORIZATION_HEADER_NAME, serviceCredential))
                .andRespond(withSuccess());
    }

    void mockRejectAction(String serviceCredential) {
        mockBackend.expect(requestTo("/api/approvals/6609559/reject?reason=Declined"))
                .andExpect(method(PUT))
                .andExpect(header(ACCEPT, APPLICATION_JSON_VALUE))
                .andExpect(header(AUTHORIZATION_HEADER_NAME, serviceCredential))
                .andRespond(withSuccess());
    }

    void mockRequisitionDetails(String serviceCredential) throws Exception {
        mockBackend.expect(requestTo("/api/requisitions?id=182964&status=pending_approval"))
                .andExpect(method(GET))
                .andExpect(header(ACCEPT, APPLICATION_JSON_VALUE))
                .andExpect(header(AUTHORIZATION_HEADER_NAME, serviceCredential))
                .andRespond(withSuccess(
                        fromFile("/fake/requisition-details.json").replace("${coupa_host}", mockBackend.url("")),
                        APPLICATION_JSON
                ));
    }

    void mockOtherRequisitionDetails(String serviceCredential) throws Exception {
        mockBackend.expect(requestTo("/api/requisitions?id=182964&status=pending_approval"))
                .andExpect(method(GET))
                .andExpect(header(ACCEPT, APPLICATION_JSON_VALUE))
                .andExpect(header(AUTHORIZATION_HEADER_NAME, serviceCredential))
                .andRespond(withSuccess(
                        fromFile("/fake/not-for-admin-at-acme-requisition-details.json").replace("${coupa_host}", mockBackend.url("")),
                        APPLICATION_JSON
                ));
    }

    void mockUnauthorizedAttachmentReq(String serviceCredential) {
        mockAttachment(serviceCredential)
                .andRespond(withUnauthorizedRequest());
    }

    void mockAttachmentServerError(String serviceCredential) {
        mockAttachment(serviceCredential)
                .andRespond(withServerError());
    }

    void mockFetchAttachment(String serviceCredential) {
        mockAttachment(serviceCredential)
                .andRespond(withSuccess(attachment, IMAGE_PNG));
    }

    ResponseActions mockAttachment(String serviceCredential) {
        return mockBackend.expect(requestTo("/api/users/15882/attachments/2701685"))
                .andExpect(method(GET))
                .andExpect(header(ACCEPT, APPLICATION_JSON_VALUE))
                .andExpect(header(AUTHORIZATION_HEADER_NAME, serviceCredential));
    }

}
