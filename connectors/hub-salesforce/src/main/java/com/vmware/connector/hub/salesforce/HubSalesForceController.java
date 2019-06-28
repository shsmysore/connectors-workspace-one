/*
 * Copyright © 2018 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.vmware.connector.hub.salesforce;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.util.StandardCharset;
import com.vmware.connectors.common.json.JsonDocument;
import com.vmware.connectors.common.payloads.request.CardRequest;
import com.vmware.connectors.common.payloads.response.*;
import com.vmware.connectors.common.utils.AuthUtil;
import com.vmware.connectors.common.utils.CardTextAccessor;
import com.vmware.connectors.common.utils.Reactive;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.vmware.connectors.common.utils.CommonUtils.APPROVAL_ACTIONS;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.web.util.UriComponentsBuilder.fromHttpUrl;

@RestController
public class HubSalesForceController {

    private static final Logger logger = LoggerFactory.getLogger(HubSalesForceController.class);

    private static final String AUTH_HEADER = "X-Connector-Authorization";
    private static final String BASE_URL_HEADER = "X-Connector-Base-Url";
    private static final String ROUTING_PREFIX = "x-routing-prefix";

    private final WebClient rest;
    private final CardTextAccessor cardTextAccessor;
    private final Resource metadata;
    private final String sfSoqlQueryPath;
    private final String workflowPath;

    private final static String REASON = "reason";

    private final static String WORK_ITEMS_QUERY = "SELECT Id,TargetObjectid, Status,(select id,actor.name, actor.id, actor.email, actor.username from Workitems Where actor.email = '%s'),(SELECT Id, StepStatus, Comments,Actor.Name, Actor.Id, actor.email, actor.username FROM Steps) FROM ProcessInstance Where Status = 'Pending'";
    private final static String OPPORTUNITY_QUERY = "SELECT Id, Name, FORMAT(ExpectedRevenue), Account.Owner.Name, %s, %s FROM opportunity WHERE Id IN ('%s')";

    private final static String DISCOUNT_PERCENTAGE = "Discount Percentage";
    private final static String REASON_FOR_DISCOUNT = "Reason for Discount";

    @Autowired
    public HubSalesForceController(final WebClient rest,
                                   final CardTextAccessor cardTextAccessor,
                                   @Value("${sf.soqlQueryPath}") final String sfSoqlQueryPath,
                                   @Value("${sf.workflowPath}") final String workflowPath,
                                   @Value("classpath:static/discovery/metadata.json") final Resource metadata) {
        this.rest = rest;
        this.cardTextAccessor = cardTextAccessor;
        this.sfSoqlQueryPath = sfSoqlQueryPath;
        this.workflowPath = workflowPath;
        this.metadata = metadata;
    }

    @PostMapping(
            path = "/cards/requests",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Cards>> getCards(
            @RequestHeader(AUTHORIZATION) final String auth,
            @RequestHeader(AUTH_HEADER) final String connectorAuth,
            @RequestHeader(BASE_URL_HEADER) final String baseUrl,
            @RequestHeader(ROUTING_PREFIX) final String routingPrefix,
            @Valid @RequestBody final CardRequest cardRequest,
            final Locale locale
    ) throws IOException {
        logger.trace("getCards called with baseUrl: {} and routingPrefix: {}", baseUrl, routingPrefix);

        final String userEmail = AuthUtil.extractUserEmail(auth);
        if (StringUtils.isBlank(userEmail)) {
            logger.error("User email (eml) is empty in jwt access token.");
            return Mono.just(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
        }

        final Map<String, String> configParams = cardRequest.getConfig();
        validateAPIFieldValues(configParams);

        return retrieveWorkItems(connectorAuth, baseUrl, userEmail)
                .flatMapMany(Reactive.wrapFlatMapMany(result -> processWorkItemResult(result, baseUrl, connectorAuth, locale, routingPrefix, configParams)))
                .collectList()
                .map(this::toCards)
                .map(ResponseEntity::ok);
    }

    private void validateAPIFieldValues(final Map<String, String> configParams) throws IOException {
        if (CollectionUtils.isEmpty(configParams)) {
            throw new InvalidConfigParamException("Connector configuration parameters map is empty.");
        }

        if (StringUtils.isBlank(configParams.get(DISCOUNT_PERCENTAGE))) {
            throw new InvalidConfigParamException("Discount Percentage field API name should not be empty.");
        }

        if (StringUtils.isBlank(configParams.get(REASON_FOR_DISCOUNT))) {
            throw new InvalidConfigParamException("Reason for Discount field API name should not be empty.");
        }

        final String metadata = IOUtils.toString(this.metadata.getInputStream(), StandardCharset.UTF_8);
        validateField(configParams, metadata,
                "$.config.['Discount Percentage'].validators[1].value",
                DISCOUNT_PERCENTAGE,
                "Discount Percentage field API value is not valid.");

        validateField(configParams, metadata,
                "$.config.['Reason for Discount'].validators[1].value",
                REASON_FOR_DISCOUNT,
                "Reason for Discount field API value is not valid.");
    }

    private void validateField(final Map<String, String> configParams,
                               final String metadata,
                               final String path,
                               final String fieldName,
                               final String errorMessage) {
        final String regex = JsonPath.using(Configuration.defaultConfiguration())
                .parse(metadata)
                .read(path);

        final String discountPercentageFieldValue = configParams.get(fieldName);
        if (!discountPercentageFieldValue.matches(regex)) {
            throw new InvalidConfigParamException(errorMessage);
        }
    }

    @PostMapping(
            path = "/api/expense/approve/{userId}",
            consumes = APPLICATION_FORM_URLENCODED_VALUE
    )
    public Mono<Void> approveWorkFlow(
            @RequestHeader(AUTH_HEADER) final String connectorAuth,
            @RequestHeader(BASE_URL_HEADER) final String baseUrl,
            @Valid ActionForm form,
            @PathVariable("userId") final String userId
    ) {
        final ApprovalRequest request = new ApprovalRequest();
        request.setActionType(ApprovalRequestType.APPROVE.getType());
        request.setComment(form.getReason());
        request.setContextId(userId);

        final ApprovalRequests requests = new ApprovalRequests();
        requests.setRequests(List.of(request));

        return rest.post()
                .uri(fromHttpUrl(baseUrl).path(workflowPath).build().toUri())
                .header(AUTHORIZATION, connectorAuth)
                .contentType(APPLICATION_JSON)
                .syncBody(requests)
                .retrieve()
                .bodyToMono(Void.class);
    }

    @PostMapping(
            path = "/api/expense/reject/{userId}",
            consumes = APPLICATION_FORM_URLENCODED_VALUE
    )
    public Mono<Void> rejectWorkFlow(
            @RequestHeader(AUTH_HEADER) final String connectorAuth,
            @RequestHeader(BASE_URL_HEADER) final String baseUrl,
            @Valid ActionForm form,
            @PathVariable("userId") final String userId
    ) {
        final ApprovalRequest request = new ApprovalRequest();
        request.setContextId(userId);
        request.setComment(form.getReason());
        request.setActionType(ApprovalRequestType.REJECT.getType());

        final ApprovalRequests requests = new ApprovalRequests();
        requests.setRequests(List.of(request));

        return rest.post()
                .uri(fromHttpUrl(baseUrl).path(workflowPath).build().toUri())
                .header(AUTHORIZATION, connectorAuth)
                .contentType(APPLICATION_JSON)
                .syncBody(requests)
                .retrieve()
                .bodyToMono(Void.class);
    }

    private Cards toCards(final List<Card> cardList) {
        final Cards cards = new Cards();
        cards.getCards().addAll(cardList);
        return cards;
    }

    private Flux<Card> processWorkItemResult(final JsonDocument workItemResponse,
                                             final String baseUrl,
                                             final String connectorAuth,
                                             final Locale locale,
                                             final String routingPrefix,
                                             final Map<String, String> configParams) {
        final List<String> opportunityIds = workItemResponse.read("$.records[*].TargetObjectId");
        if (CollectionUtils.isEmpty(opportunityIds)) {
            logger.warn("TargetObjectIds are empty for the base url [{}]", baseUrl);
            return Flux.empty();
        }

        return retrieveOpportunities(baseUrl, opportunityIds, connectorAuth, configParams)
                .flatMapMany(opportunityResponse -> buildCards(workItemResponse, opportunityResponse, locale, routingPrefix, configParams));
    }

    private Mono<JsonDocument> retrieveOpportunities(final String baseUrl,
                                                     final List<String> opportunityIds,
                                                     final String connectorAuth,
                                                     final Map<String, String> configParams) {
        final String idsFormat = opportunityIds.stream()
                .map(this::soqlEscape)
                .collect(Collectors.joining("', '"));

        final String soql = String.format(OPPORTUNITY_QUERY, configParams.get(DISCOUNT_PERCENTAGE), configParams.get(REASON_FOR_DISCOUNT), idsFormat);
        return rest.get()
                .uri(makeSoqlQueryUri(baseUrl, soql))
                .header(AUTHORIZATION, connectorAuth)
                .retrieve()
                .bodyToMono(JsonDocument.class);
    }

    private Flux<Card> buildCards(final JsonDocument workItemResponse,
                                  final JsonDocument opportunityResponse,
                                  final Locale locale,
                                  final String routingPrefix,
                                  final Map<String, String> configParams) {
        final int totalSize = workItemResponse.read("$.totalSize");
        final List<Card> cardList = new ArrayList<>();

        for (int i = 0; i < totalSize; i++) {
            if (workItemResponse.read(String.format("$.records[%s].Workitems", i)) == null) {
                logger.trace("No work items found for the record id [{}]", (String) workItemResponse.read(String.format("$.records[%s].Id", i)));
                continue;
            }

            final String userId = workItemResponse.read(String.format("$.records[%s].Workitems.records[0].Id", i));

            final Card.Builder card = new Card.Builder()
                    .setName("Salesforce for WS1 Hub")
                    .setTemplate(routingPrefix + "templates/generic.hbs")
                    .setHeader(this.cardTextAccessor.getMessage("ws1.sf.card.header", locale))
                    .setBody(buildCardBody(opportunityResponse, i, locale, configParams))
                    .addAction(buildApproveAction(routingPrefix, locale, userId))
                    .addAction(buildRejectAction(routingPrefix, locale, userId));

            card.setImageUrl("https://s3.amazonaws.com/vmw-mf-assets/connector-images/hub-salesforce.png");
            cardList.add(card.build());
        }

        return Flux.fromIterable(cardList);
    }

    private CardBody buildCardBody(final JsonDocument opportunityResponse,
                                   final int index,
                                   final Locale locale,
                                   final Map<String, String> configParams) {
        final String opportunityName = opportunityResponse.read(String.format("$.records[%s].Name", index));
        final String opportunityOwnerName = opportunityResponse.read(String.format("$.records[%s].Account.Owner.Name", index));
        final String expectedRevenue = opportunityResponse.read(String.format("$.records[%s].ExpectedRevenue", index));

        final CardBody.Builder cardBodyBuilder = new CardBody.Builder()
                .addField(buildCardBodyField("customer.name", opportunityName, locale))
                .addField(buildCardBodyField("opportunity.owner", opportunityOwnerName, locale))
                .addField(buildCardBodyField("revenue.opportunity", expectedRevenue, locale));

        final Integer discountPercent = opportunityResponse.read(String.format("$.records[%s].%s", index, configParams.get(DISCOUNT_PERCENTAGE)));
        cardBodyBuilder.addField(buildCardBodyField("discount.percent", String.valueOf(discountPercent), locale));

        final String reasonForDiscount = opportunityResponse.read(String.format("$.records[%s].%s", index, configParams.get(REASON_FOR_DISCOUNT)));
        cardBodyBuilder.addField(buildCardBodyField("reason.for.discount", reasonForDiscount, locale));

        return cardBodyBuilder.build();
    }

    private CardAction buildApproveAction(final String routingPrefix,
                                          final Locale locale,
                                          final String userId) {
        final String approveUrl = "api/expense/approve/" + userId;

        return new CardAction.Builder()
                .setLabel(this.cardTextAccessor.getActionLabel("ws1.sf.approve", locale))
                .setCompletedLabel(this.cardTextAccessor.getActionCompletedLabel("ws1.sf.approve", locale))
                .setActionKey(CardActionKey.USER_INPUT)
                .setType(HttpMethod.POST)
                .setUrl(routingPrefix + approveUrl)
                .setPrimary(true)
                .setMutuallyExclusiveSetId(APPROVAL_ACTIONS)
                .addUserInputField(
                        new CardActionInputField.Builder()
                                .setId(REASON)
                                .setMinLength(1)
                                .setFormat("textarea")
                                .setLabel(cardTextAccessor.getMessage("ws1.sf.approve.reason.label", locale))
                                .build()
                ).build();
    }

    private CardAction buildRejectAction(final String routingPrefix,
                                         final Locale locale,
                                         final String userId) {
        final String rejectUrl = "api/expense/reject/" + userId;

        return new CardAction.Builder()
                .setLabel(this.cardTextAccessor.getActionLabel("ws1.sf.reject", locale))
                .setCompletedLabel(this.cardTextAccessor.getActionCompletedLabel("ws1.sf.reject", locale))
                .setActionKey(CardActionKey.USER_INPUT)
                .setType(HttpMethod.POST)
                .setUrl(routingPrefix + rejectUrl)
                .setPrimary(false)
                .setMutuallyExclusiveSetId(APPROVAL_ACTIONS)
                .addUserInputField(
                        new CardActionInputField.Builder()
                                .setId(REASON)
                                .setMinLength(1)
                                .setFormat("textarea")
                                .setLabel(cardTextAccessor.getMessage("ws1.sf.reject.reason.label", locale))
                                .build()
                ).build();
    }

    private CardBodyField buildCardBodyField(final String title,
                                             final String description,
                                             final Locale locale) {
        if (StringUtils.isBlank(description)) {
            return null;
        }

        return new CardBodyField.Builder()
                .setTitle(this.cardTextAccessor.getMessage("ws1.sf." + title, locale))
                .setDescription(description)
                .setType(CardBodyFieldType.GENERAL)
                .build();
    }

    private Mono<JsonDocument> retrieveWorkItems(final String connectorAuth,
                                                 final String baseUrl,
                                                 final String userEmail) {
        final String sql = String.format(WORK_ITEMS_QUERY, soqlEscape(userEmail));

        return rest.get()
                .uri(makeSoqlQueryUri(baseUrl, sql))
                .header(AUTHORIZATION, connectorAuth)
                .retrieve()
                .bodyToMono(JsonDocument.class);
    }

    private URI makeSoqlQueryUri(
            final String baseUrl,
            final String soql
    ) {

        return fromHttpUrl(baseUrl)
                .path(sfSoqlQueryPath)
                .queryParam("q", soql)
                .build()
                .toUri();
    }

    private String soqlEscape(String value) {
        return value.replace("\\", "\\\\").replace("\'", "\\\'");
    }

    @ExceptionHandler(InvalidConfigParamException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, String> handleInvalidConnectorConfigError(InvalidConfigParamException e) {
        return Map.of("message", e.getMessage());
    }
}