/*
 * Copyright © 2019 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.vmware.connectors.concur;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;

import static com.vmware.connectors.common.utils.CommonUtils.BACKEND_STATUS;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Test cases with empty concur service account auth header from configuration.
 */
@TestPropertySource(locations = "classpath:empty-concur-service-credential.properties")
class HubConcurOotbTest extends HubConcurControllerTestBase {

    @ParameterizedTest
    @CsvSource({
            ", success.json",
            "xx, success_xx.json"
    })
    void testCardsRequests(String lang, String expected) throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockConcurRequests(EXPECTED_AUTH_HEADER);
        cardsRequest(lang, expected, CALLER_SERVICE_CREDS);
    }

    @Test
    void testCardsRequestsWithMissingAuthHeader() throws Exception {
        cardsRequest("", "")
                .expectStatus().isBadRequest();
    }

    @Test
    void testApproveRequest() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockActionRequests(EXPECTED_AUTH_HEADER);

        approveRequest(CALLER_SERVICE_CREDS)
                .expectStatus().isOk();
    }

    @Test
    void testApprovedReqWithMissingAuthHeader() {
        approveRequest("")
                .expectStatus().isBadRequest();
    }

    @Test
    void testRejectRequest() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockActionRequests(EXPECTED_AUTH_HEADER);

        rejectRequest(CALLER_SERVICE_CREDS)
                .expectStatus().isOk();
    }

    @Test
    void testRejectRequestWithMissingAuthHeader() {
        rejectRequest("")
                .expectStatus().isBadRequest();
    }

    @Test
    void testUnauthorizedRejectRequest() throws Exception {
        // User tries to reject an expense report that isn't theirs
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockEmptyReportsDigest(EXPECTED_AUTH_HEADER);

        rejectRequest(CALLER_SERVICE_CREDS)
                .expectStatus().isNotFound();
    }

    @Test
    void fetchAttachmentForValidUser() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockReport1(EXPECTED_AUTH_HEADER);
        mockFetchAttachment(EXPECTED_AUTH_HEADER);
        mockUserReportsDigest(EXPECTED_AUTH_HEADER);

        fetchAttachment(CALLER_SERVICE_CREDS, "1D3BD2E14D144508B05F");
    }

    @Test
    void fetchAttachmentForInvalidUserLoginID() throws Exception {
        // Invalid user tries to fetch an expense report attachment.
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockUserDetailReport(EXPECTED_AUTH_HEADER, "/fake/invalid-user-details.json");
        mockReportsDigest(EXPECTED_AUTH_HEADER, "invalid%40acme.com");

        fetchAttachmentForInvalidDetails(CALLER_SERVICE_CREDS, "1D3BD2E14D144508B05F");
    }

    @Test
    void fetchAttachmentForInvalidAttachmentID() throws Exception {
        // Valid user tries to fetch an expense report attachment which does not belong to them.
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockUserDetailReport(EXPECTED_AUTH_HEADER, "/fake/user-details.json");
        mockReportsDigest(EXPECTED_AUTH_HEADER, "admin%40acme.com");

        fetchAttachmentForInvalidDetails(CALLER_SERVICE_CREDS, "invalid-attachment-id");
    }

    @Test
    void testAttachmentUrlNotFound() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockUserReportsDigest(EXPECTED_AUTH_HEADER);
        mockReportWithEmptyAttachmentURL(EXPECTED_AUTH_HEADER);

        fetchAttachmentForInvalidDetails(CALLER_SERVICE_CREDS, "1D3BD2E14D144508B05F");
    }

    @Test
    void testUnauthorizedError() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockReport1(EXPECTED_AUTH_HEADER);
        mockUserReportsDigest(EXPECTED_AUTH_HEADER);
        mockFetchAttachmentWithUnauthorized(EXPECTED_AUTH_HEADER);

        fetchAttachmentForUnauthorizedCredential(CALLER_SERVICE_CREDS, "1D3BD2E14D144508B05F");
    }

    @Test
    void testBadStatusCode() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockReport1(EXPECTED_AUTH_HEADER);
        mockUserReportsDigest(EXPECTED_AUTH_HEADER);
        mockFetchAttachmentWithInternalServerError(EXPECTED_AUTH_HEADER);

        fetchAttachmentWithBadStatusCode(CALLER_SERVICE_CREDS, "1D3BD2E14D144508B05F");
    }

    @ParameterizedTest
    @CsvSource({
            // Service account credential length < 4
            "username:client-id:client-secret",
            // Service account credential length > 4
            "username:password:client-id:client-secret:junk"
    })
    void testInvalidServiceAccountCredential(String serviceCredential) {
        testServiceAccountCredential(serviceCredential);
    }

    @Test
    void testUnauthorizedServiceAccountCredential() {
        unauthorizedServiceAccountCredential();
    }

    @Test
    void testCardReqWhenUserNotFound() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockInvalidUserDetails("fake/empty-user-details.json");

        cardsRequest("", CALLER_SERVICE_CREDS)
                .expectStatus().isNotFound()
                .expectBody().json(fromFile("connector/responses/user_email_not_found.json"));
    }

    @Test
    void testCardReqWithEmptyApprovals() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockEmptyReportsDigest(EXPECTED_AUTH_HEADER);

        cardsRequest("", CALLER_SERVICE_CREDS)
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(APPLICATION_JSON)
                .expectBody().json(fromFile("connector/responses/empty_response.json"));
    }

    @Test
    void testApproveWhenUserNotFound() throws IOException {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockInvalidUserDetails("fake/empty-user-details.json");

        approveRequest(CALLER_SERVICE_CREDS)
                .expectStatus().isNotFound()
                .expectBody().json(fromFile("connector/responses/user_email_not_found.json"));
    }

    @Test
    void testApproveForAnInvalidUser() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockUserDetailReport(EXPECTED_AUTH_HEADER, "/fake/invalid-user-details.json");
        mockReportsDigest(EXPECTED_AUTH_HEADER, "invalid%40acme.com");

        approveRequest(CALLER_SERVICE_CREDS)
                .expectStatus().isNotFound();
    }

    @Test
    void testRejectWhenUserNotFound() throws IOException {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockInvalidUserDetails("fake/empty-user-details.json");

        rejectRequest(CALLER_SERVICE_CREDS)
                .expectStatus().isNotFound()
                .expectBody().json(fromFile("connector/responses/user_email_not_found.json"));
    }

    @Test
    void testRejectForAnInvalidUser() throws Exception {
        mockOAuthToken(CALLER_SERVICE_CREDS);
        mockUserDetailReport(EXPECTED_AUTH_HEADER, "/fake/invalid-user-details.json");
        mockReportsDigest(EXPECTED_AUTH_HEADER, "invalid%40acme.com");

        rejectRequest(CALLER_SERVICE_CREDS)
                .expectStatus().isNotFound()
                .expectBody().json(fromFile("connector/responses/user_login_not_found.json"));
    }

    @Test
    void testForbiddenException() throws Exception {
        mockOAuthForbiddenException(CALLER_SERVICE_CREDS);

        cardsRequest("", CALLER_SERVICE_CREDS)
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals(BACKEND_STATUS, "401");
    }
}
