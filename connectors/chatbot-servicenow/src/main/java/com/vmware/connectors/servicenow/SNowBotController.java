/*
 * Copyright © 2019 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */

package com.vmware.connectors.servicenow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vmware.connectors.common.json.JsonDocument;
import com.vmware.connectors.common.payloads.request.CardRequest;
import com.vmware.connectors.common.payloads.response.Link;
import com.vmware.connectors.common.utils.AuthUtil;
import com.vmware.connectors.servicenow.domain.BotAction;
import com.vmware.connectors.servicenow.domain.BotActionUserInput;
import com.vmware.connectors.servicenow.domain.BotItem;
import com.vmware.connectors.servicenow.domain.BotObjects;
import com.vmware.connectors.servicenow.domain.snow.*;
import com.vmware.connectors.servicenow.exception.CatalogReadException;
import com.vmware.connectors.servicenow.forms.AddToCartForm;
import com.vmware.connectors.servicenow.forms.CreateTaskForm;
import com.vmware.connectors.servicenow.forms.ViewTaskForm;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import java.net.URI;
import java.util.*;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class SNowBotController {

    private static final Logger logger = LoggerFactory.getLogger(SNowBotController.class);
    private static final int MAX_NO_OF_RECENT_TICKETS_TO_FETCH = 5;
    public static final String VIEW_TASK_MSG_PROPS = "view.task.msg";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String AUTH_HEADER = "X-Connector-Authorization";
    private static final String BASE_URL_HEADER = "X-Connector-Base-Url";
    private static final String ROUTING_PREFIX = "x-routing-prefix";
    private static final String ROUTING_PREFIX_TEMPLATE = "X-Routing-Template";

    private static final String SNOW_CATALOG_ENDPOINT = "/api/sn_sc/servicecatalog/catalogs";

    private static final String SNOW_CATALOG_CATEGORY_ENDPOINT = "/api/sn_sc/servicecatalog/catalogs/{catalog_id}";

    private static final String SNOW_ADD_TO_CART_ENDPOINT = "/api/sn_sc/servicecatalog/items/{item_id}/add_to_cart";

    private static final String SNOW_CHECKOUT_ENDPOINT = "/api/sn_sc/servicecatalog/cart/checkout";

    private static final String SNOW_DELETE_FROM_CART_ENDPOINT = "/api/sn_sc/servicecatalog/cart/{cart_item_id}";

    private static final String SNOW_DELETE_CART_ENDPOINT = "/api/sn_sc/servicecatalog/cart/{cart_id}/empty";

    private static final String SNOW_DELETE_TASK_ENDPOINT = "/api/now/table/task/{task_id}";


    private static final String SNOW_SYS_PARAM_LIMIT = "sysparm_limit";

    private static final String SNOW_SYS_PARAM_TEXT = "sysparm_text";

    private static final String SNOW_SYS_PARAM_CAT = "sysparm_category";

    private static final String SNOW_SYS_PARAM_QUAN = "sysparm_quantity";

    private static final String SNOW_SYS_PARAM_OFFSET = "sysparm_offset";

    private static final String NUMBER = "number";

    private static final String INSERT_OBJECT_TYPE = "INSERT_OBJECT_TYPE";

    private static final String ITEM_DETAILS = "itemDetails";

    private static final String ACTION_RESULT_KEY = "result";

    private static final String OBJECT_TYPE_BOT_DISCOVERY = "botDiscovery";

    private static final String OBJECT_TYPE_TASK = "task";

    private static final String OBJECT_TYPE_CART = "cart";

    private static final String CONTEXT_ID = "contextId";

    // Workflow Ids for task flow.
    private static final String WF_ID_CREATE_TASK = "vmw_FILE_GENERAL_TICKET";
    private static final String WF_ID_VIEW_TASK = "vmw_GET_TICKET_STATUS";
    private static final String WF_ID_VIEW_TASK_BY_NUMBER = "vmw_VIEW_SPECIFIC_TICKET";

    // Workflow Ids for catalog-cart flow.
    private static final String WF_ID_CATALOG = "ViewItem";
    private static final String WF_ID_CART = "ViewCart";
    private static final String WF_ID_ADD_TO_CART = "AddItem";
    private static final String WF_ID_EMPTY_CART = "EmptyCart";
    private static final String WF_ID_CHECKOUT = "Checkout";
    private static final String WF_ID_REMOVE_FROM_CART = "RemoveItem";

    private static final String CONFIG_FILE_TICKET_TABLE_NAME = "file_ticket_table_name";

    private final WebClient rest;
    private final BotTextAccessor botTextAccessor;

    @Autowired
    public SNowBotController(
            WebClient rest,
            BotTextAccessor botTextAccessor
    ) {
        this.rest = rest;
        this.botTextAccessor = botTextAccessor;
    }

    @PostMapping(
            path = "/api/v1/catalog-items",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<BotObjects> getCatalogItems(
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            @RequestHeader(ROUTING_PREFIX) String routingPrefix,
            Locale locale,
            @RequestParam(name = "limit", required = false, defaultValue = "10") String limit,
            @RequestParam(name = "offset", required = false, defaultValue = "0") String offset,
            @Valid @RequestBody CardRequest cardRequest
    ) {

        var catalogName = cardRequest.getTokenSingleValue("catalog");
        var categoryName = cardRequest.getTokenSingleValue("category");
        var searchText = cardRequest.getTokenSingleValue("text");
        String contextId = cardRequest.getTokenSingleValue(CONTEXT_ID);

        logger.trace("getItems for catalog: {}, category: {}, searchText: {}", catalogName, categoryName, searchText);
        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();
        return getCatalogId(catalogName, auth, baseUri)
                .flatMap(catalogId -> getCategoryId(categoryName, catalogId, auth, baseUri))
                .flatMap(categoryId -> getItems(searchText, categoryId,
                        auth, baseUri,
                        limit, offset))
                .map(itemList -> toCatalogBotObj(baseUrl, itemList, routingPrefix, contextId, locale));
    }

    private Mono<String> getCatalogId(String catalogTitle, String auth, URI baseUri) {
        return rest.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path(SNOW_CATALOG_ENDPOINT)
                        .build())
                .header(AUTHORIZATION, auth)
                .retrieve()
                .bodyToMono(JsonDocument.class)
                .flatMap(doc -> {
                    List<String> sysIds = doc.read(String.format("$.result[?(@.title=~/.*%s/i)].sys_id", catalogTitle));
                    if (sysIds != null && sysIds.size() == 1) {
                        return Mono.just(sysIds.get(0));
                    }

                    logger.debug("Couldn't find the sys_id for title:{}, endpoint:{}, baseUrl:{}",
                            catalogTitle, SNOW_CATALOG_ENDPOINT, baseUri);
                    return Mono.error(new CatalogReadException("Can't find " + catalogTitle));
                });
    }

    private Mono<String> getCategoryId(String categoryTitle, String catalogId, String auth, URI baseUri) {
        return rest.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path(SNOW_CATALOG_CATEGORY_ENDPOINT)
                        .build(Map.of("catalog_id", catalogId))
                )
                .header(AUTHORIZATION, auth)
                .retrieve()
                .bodyToMono(JsonDocument.class)
                .flatMap(doc -> {
                    List<String> sysIds = doc.read(String.format("$.result.categories[?(@.title=~/.*%s/i)].sys_id", categoryTitle));
                    if (sysIds != null && sysIds.size() == 1) {
                        return Mono.just(sysIds.get(0));
                    }

                    logger.debug("Couldn't find the sys_id for title:{}, endpoint:{}, category_id:{}, baseUrl:{}",
                            categoryTitle, SNOW_CATALOG_CATEGORY_ENDPOINT, catalogId, baseUri);
                    return Mono.error(new CatalogReadException("Can't find " + categoryTitle));
                });
    }

    // ToDo: If it can filter, don't ask much from SNow. Go only with those needed for chatbot.
    private Mono<List<CatalogItem>> getItems(String searchText, String categoryId, String auth, URI baseUri,
                                             String limit, String offset) {
        logger.trace("getItems searchText:{}, categoryId:{}, baseUrl={}.", searchText, categoryId, baseUri);
        return rest.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path("/api/sn_sc/servicecatalog/items")
                        .queryParam(SNOW_SYS_PARAM_TEXT, searchText)
                        .queryParam(SNOW_SYS_PARAM_CAT, categoryId)
                        .queryParam(SNOW_SYS_PARAM_LIMIT, limit)
                        .queryParam(SNOW_SYS_PARAM_OFFSET, offset)
                        .build())
                .header(AUTHORIZATION, auth)
                .retrieve()
                .bodyToMono(CatalogItemResults.class)
                .map(CatalogItemResults::getResult);
    }

    private BotObjects toCatalogBotObj(String baseUrl, List<CatalogItem> itemList, String routingPrefix, String contextId, Locale locale) {
        BotObjects.Builder objectsBuilder = new BotObjects.Builder();

        itemList.forEach(catalogItem ->
                objectsBuilder.addObject(
                        new BotItem.Builder()
                                .setTitle(catalogItem.getName())
                                .setDescription(catalogItem.getShortDescription())
                                .setImage(getItemImageLink(baseUrl, catalogItem.getPicture()))
                                .addAction(getAddToCartAction(catalogItem.getId(), routingPrefix, locale))
                                .setContextId(contextId)
                                .setWorkflowId(WF_ID_CATALOG)
                                .build())
        );

        return objectsBuilder.build();
    }

    private Link getItemImageLink(String baseUrl, String itemPicture) {
        // When there isn't a picture associated, it says - "picture": ""
        if (StringUtils.isBlank(itemPicture)) {
            return null;
        }
        return new Link(
                UriComponentsBuilder.fromUriString(baseUrl)
                        .replacePath(itemPicture)
                        .build()
                        .toUriString());
    }

    @DeleteMapping(
            path = "/api/v1/tasks/{taskId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Map<String, Object>>> deleteTask(
            @RequestHeader(AUTHORIZATION) String mfToken,
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            @PathVariable String taskId) {

        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();
        return rest.delete()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path(SNOW_DELETE_TASK_ENDPOINT)
                        .build(Map.of("task_id", taskId))
                )
                .header(AUTHORIZATION, auth)
                .exchange()
                .flatMap(this::toDeleteItemResponse);
    }


    @PostMapping(
            path = "/api/v1/tasks",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public Mono<Map<String, List<Map<String, BotItem>>>> getTasks(
            @RequestHeader(AUTHORIZATION) String mfToken,
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(ROUTING_PREFIX_TEMPLATE) String routingPrefixTemplate,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            ViewTaskForm form,
            Locale locale) {
        // User asks something like "What are my open tickets ?"

        // ToDo: If a customer doesn't call their parent ticket as "task", this flow would break.
        // A potential solution is to let admin configure the parent task name.

        String taskType = "task";
        String taskNumber = form.getNumber();

        // ToDo: Technically there can be too many open tickets. How many of them will be displayed in the bot UI ?
        int ticketsLimit = MAX_NO_OF_RECENT_TICKETS_TO_FETCH;

        var userEmail = AuthUtil.extractUserEmail(mfToken);

        logger.trace("getTasks for type={}, baseUrl={}, userEmail={}, ticketsLimit={}, routingTemplate={}",
                taskType, baseUrl, userEmail, ticketsLimit, routingPrefixTemplate);

        URI taskUri;
        if (StringUtils.isBlank(taskNumber)) {
            taskUri = buildTaskUriReadUserTickets(taskType, userEmail, baseUrl, ticketsLimit);
        } else {
            taskUri = buildTaskUriReadByNumber(taskType, taskNumber, baseUrl);
        }

        String routingPrefix = routingPrefixTemplate.replace(INSERT_OBJECT_TYPE, OBJECT_TYPE_BOT_DISCOVERY);

        return retrieveTasks(taskUri, auth)
                .map(taskList -> toTaskBotObj(taskList, routingPrefix, baseUrl, "status", locale));
    }

    private URI buildTaskUriReadByNumber(String taskType, String taskNumber, String baseUrl) {
        // If task number is provided, maybe it doesn't matter to apply the filter about who created the ticket.
        // Also, we will show the ticket even if its closed.
        return UriComponentsBuilder
                .fromUriString(baseUrl)
                .replacePath("/api/now/table/")
                .path(taskType)
                .queryParam("sysparm_display_value", true)
                .queryParam(NUMBER, taskNumber)
                .encode().build().toUri();
    }

    private URI buildTaskUriReadUserTickets(String taskType, String userEmail, String baseUrl, int limit) {
        return UriComponentsBuilder
                .fromUriString(baseUrl)
                .replacePath("/api/now/table/")
                .path(taskType)
                .queryParam("sysparm_display_value", true) // We want to show label, not code.
                .queryParam(SNOW_SYS_PARAM_LIMIT, limit)
                .queryParam(SNOW_SYS_PARAM_OFFSET, 0)
                .queryParam("opened_by.email", userEmail)
                .queryParam("active", true)   // Ignore already closed tickets.
                .queryParam("sysparm_query", "ORDERBYDESCsys_created_on") // Order by latest created tickets.
                .encode().build().toUri();

    }

    private Mono<List<Task>> retrieveTasks(URI taskUri, String auth) {
        return rest.get()
                .uri(taskUri)
                .header(AUTHORIZATION, auth)
                .retrieve()
                .bodyToMono(TaskResults.class)
                .map(TaskResults::getResult);
    }

    private Map<String, List<Map<String, BotItem>>> toTaskBotObj(List<Task> tasks, String routingPrefix, String baseUrl,
                                                                 String uiType, Locale locale) {
        List<Map<String, BotItem>> taskObjects = new ArrayList<>();

        tasks.forEach(task ->
                taskObjects.add(Map.of(ITEM_DETAILS,
                        new BotItem.Builder()
                                .setTitle(botTextAccessor.getObjectTitle(OBJECT_TYPE_TASK, locale, task.getNumber()))
                                .setSubtitle(task.getState())
                                .setDescription(task.getShortDescription())
                                .setUrl(new Link(
                                                UriComponentsBuilder.fromUriString(baseUrl)
                                                        .replacePath("task.do")
                                                        .queryParam("sys_id", task.getSysId())
                                                        .build()
                                                        .toUriString()
                                        )
                                )
                                .setType(uiType)
                                .addAction(getDeleteTaskAction(task.getSysId(), routingPrefix, locale))
                                .setWorkflowId(WF_ID_VIEW_TASK)
                                .build()))
        );
        if(tasks.size() == MAX_NO_OF_RECENT_TICKETS_TO_FETCH){
            taskObjects.add(Map.of(ITEM_DETAILS,
                    new BotItem.Builder()
                            .setTitle(botTextAccessor.getMessage(VIEW_TASK_MSG_PROPS, locale, baseUrl))
                            .build()));
        }
        return Map.of("objects", List.copyOf(taskObjects));
    }

    @PostMapping(
            path = "/api/v1/cart",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<BotObjects> lookupCart(
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            @RequestHeader(ROUTING_PREFIX) String routingPrefix,
            Locale locale,
            @RequestBody CardRequest cardRequest) {

        String contextId = cardRequest.getTokenSingleValue(CONTEXT_ID);

        return retrieveUserCart(baseUrl, auth)
                .map(cartDocument -> toCartBotObj(baseUrl, cartDocument, routingPrefix, contextId, locale));
    }

    private Mono<JsonDocument> retrieveUserCart(String baseUrl, String auth) {
        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();

        return rest.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path("/api/sn_sc/servicecatalog/cart")
                        .build()
                )
                .header(AUTHORIZATION, auth)
                .retrieve()
                .bodyToMono(JsonDocument.class);
    }

    private BotObjects toCartBotObj(String baseUrl, JsonDocument cartResponse, String routingPrefix, String contextId, Locale locale) {
        BotItem.Builder botObjectBuilder = new BotItem.Builder()
                .setTitle(botTextAccessor.getObjectTitle(OBJECT_TYPE_CART, locale))
                .setContextId(contextId)
                .setWorkflowId(WF_ID_CART);

        List<LinkedHashMap> items = cartResponse.read("$.result.*.items.*");
        if (CollectionUtils.isEmpty(items)) {
            botObjectBuilder.setDescription(botTextAccessor.getMessage(OBJECT_TYPE_CART + ".description.empty", locale));
        } else {
            botObjectBuilder.setDescription(botTextAccessor.getObjectDescription(OBJECT_TYPE_CART, locale))
                    .addAction(getEmptyCartAction(routingPrefix, locale))
                    .addAction(getCheckoutCartAction(routingPrefix, locale));

            List<CartItem> cartItems = objectMapper.convertValue(items, new TypeReference<List<CartItem>>(){});
            cartItems.forEach(
                    cartItem -> botObjectBuilder.addChild(getCartItemChildObject(baseUrl, cartItem, routingPrefix, contextId, locale))
            );
        }

        return new BotObjects.Builder()
                .addObject(botObjectBuilder.build())
                .build();
    }

    private BotItem getCartItemChildObject(String baseUrl, CartItem cartItem, String routingPrefix, String contextId, Locale locale) {
        return new BotItem.Builder()
                .setTitle(cartItem.getName())
                .setContextId(contextId)
                .setWorkflowId(WF_ID_CART)
                .setDescription(cartItem.getShortDescription())
                .setImage(getItemImageLink(baseUrl, cartItem.getPicture()))
                .addAction(getRemoveFromCartAction(cartItem.getEntryId(), routingPrefix, locale))
                .build();
    }

    private BotAction getRemoveFromCartAction(String entryId, String routingPrefix, Locale locale) {
        return new BotAction.Builder()
                .setTitle(botTextAccessor.getActionTitle("removeFromCart", locale))
                .setDescription(botTextAccessor.getActionDescription("removeFromCart", locale))
                .setWorkflowId(WF_ID_REMOVE_FROM_CART)
                .setType(HttpMethod.DELETE)
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .setUrl(new Link(routingPrefix + "api/v1/cart/" + entryId))
                .build();
    }

    private BotAction getCheckoutCartAction(String routingPrefix, Locale locale) {
        return new BotAction.Builder()
                .setTitle(botTextAccessor.getActionTitle("checkout", locale))
                .setDescription(botTextAccessor.getActionDescription("checkout", locale))
                .setWorkflowId(WF_ID_CHECKOUT)
                .setType(HttpMethod.POST)
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .setUrl(new Link(routingPrefix + "api/v1/checkout"))
                .build();
    }

    private BotAction getEmptyCartAction(String routingPrefix, Locale locale) {
        return new BotAction.Builder()
                .setTitle(botTextAccessor.getActionTitle("emptyCart", locale))
                .setDescription(botTextAccessor.getActionDescription("emptyCart", locale))
                .setWorkflowId(WF_ID_EMPTY_CART)
                .setType(HttpMethod.DELETE)
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .setUrl(new Link(routingPrefix + "api/v1/cart"))
                .build();
    }

    private BotAction getAddToCartAction(String itemId, String routingPrefix, Locale locale) {
        return new BotAction.Builder()
                .setTitle(botTextAccessor.getActionTitle("addToCart", locale))
                .setDescription(botTextAccessor.getActionDescription("addToCart", locale))
                .setWorkflowId(WF_ID_ADD_TO_CART)
                .setType(HttpMethod.PUT)
                .addReqParam("itemId", itemId)
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .addUserInput(getCartItemCountUserInput(locale))
                .setUrl(new Link(routingPrefix + "api/v1/cart"))
                .build();
    }

    private BotActionUserInput getCartItemCountUserInput(Locale locale) {
        return new BotActionUserInput.Builder()
                .setId("itemCount")
                .setFormat("textarea")
                .setLabel(botTextAccessor.getActionUserInputLabel("addToCart", "itemCount", locale))
                .setMinLength(1)
                .build();
    }

    private BotAction getDeleteTaskAction(String taskSysId, String routingPrefix, Locale locale) {
        return new BotAction.Builder()
                .setTitle(botTextAccessor.getActionTitle("deleteTicket", locale))
                .setDescription(botTextAccessor.getActionDescription("deleteTicket", locale))
                // Workflow ids not required in actions ?
                .setType(HttpMethod.DELETE)
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .setUrl(new Link(routingPrefix + "api/v1/tasks/" + taskSysId))
                .build();
    }

    private BotAction getCreateTaskAction(String taskType, String routingPrefix, Locale locale) {
        return new BotAction.Builder()
                .setTitle(botTextAccessor.getMessage("createTaskAction.title", locale))
                .setDescription(botTextAccessor.getMessage("createTaskAction.description", locale))
                .setType(HttpMethod.POST)
                .setUrl(new Link(routingPrefix + "api/v1/task/create"))
                .addReqParam("type", taskType)
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .addUserInput(getTicketDescriptionUserInput(locale))
                .build();
    }

    private BotAction getViewMyTaskAction(String routingPrefix) {
        return new BotAction.Builder()
                .setTitle("View my tickets")
                .setDescription("View the status of my open tickets")
                .setType(HttpMethod.POST)
                .setUrl(new Link(routingPrefix + "api/v1/tasks"))
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .build();
    }

    private BotAction getViewTaskByNumberAction(String routingPrefix) {
        return new BotAction.Builder()
                .setTitle("View a ticket")
                .setDescription("View a ticket by its numer")
                .setType(HttpMethod.POST)
                .setUrl(new Link(routingPrefix + "api/v1/tasks"))
                .addReqHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .addUserInput(getTicketNumberUserInput())
                .build();
    }

    private BotActionUserInput getTicketDescriptionUserInput(Locale locale) {
        return new BotActionUserInput.Builder()
                .setId("shortDescription")
                .setFormat("textarea")
                .setLabel(botTextAccessor.getMessage("createTaskAction.shortDescription.label", locale))
                // MinLength is unnecessary from ServiceNow point of view.
                // API allows to create without any description.
                .setMinLength(1)
                // I see it as 160 characters in the ServiceNow dev instance.
                // If you try with more than limit, ServiceNow would just ignore whatever is beyond 160.
                .setMaxLength(160)
                .build();
    }

    private BotActionUserInput getTicketNumberUserInput() {
        return new BotActionUserInput.Builder()
                .setId("number")
                .setFormat("textarea")
                .setLabel("Please enter the ticket number you would like to view.")
                .setMinLength(1)
                // For now ignoring a max limit on this.
                .build();
    }

    // An object, 'botDiscovery', advertises all the capabilities of this connector, for bot use cases.
    // ToDo: After 1 flow works en-end, advertise remaining capabilities as well.
    @PostMapping(
            path = "/bot-discovery",
            produces = MediaType.APPLICATION_JSON_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> getBotDiscovery(
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            @RequestHeader(ROUTING_PREFIX) String routingPrefix,
            @Valid @RequestBody final CardRequest cardRequest,
            Locale locale
    ) {
        Map<String, String> connConfig = cardRequest.getConfig();
        String taskType = connConfig.get(CONFIG_FILE_TICKET_TABLE_NAME);
        logger.trace("getBotDiscovery object. baseUrl: {}, routingPrefix: {}, fileTaskType: {}", baseUrl, routingPrefix, taskType);

        if (StringUtils.isBlank(taskType)) {
            logger.debug("Table name isn't specified for ticket filing flow. Taking `task` as default type.");
            taskType = "task"; // ToDo: Not required after APF-2570.
        }

        return ResponseEntity.ok(
                buildBotDiscovery(taskType, routingPrefix, locale)
        );
    }

    private Map<String, Object> buildBotDiscovery(String taskType, String routingPrefix, Locale locale) {
        BotItem.Builder botItemBuilder = new BotItem.Builder()
                .setTitle("ServiceNow capabilities")
                .setDescription("ServiceNow can be used to file issue tickets.")
                .setWorkflowId(WF_ID_CREATE_TASK)
                .addAction(getCreateTaskAction(taskType, routingPrefix, locale));

        return Map.of("objects", List.of(
                Map.of(
                        ITEM_DETAILS, botItemBuilder.build(),
                        "children", List.of(
                                Map.of(ITEM_DETAILS, describeFileATicketFlow(taskType, routingPrefix, locale)),
                                Map.of(ITEM_DETAILS, describeReadMyTicketsFlow(routingPrefix)),
                                Map.of(ITEM_DETAILS, describeReadTicketByNumberFlow(routingPrefix))
                        )
                )
                )
        );
    }

    private BotItem describeFileATicketFlow(String taskType, String routingPrefix, Locale locale) {
        return new BotItem.Builder()
                .setTitle(botTextAccessor.getMessage("createTaskObject.title", locale))
                .setDescription(botTextAccessor.getMessage("createTaskObject.description", locale))
                .setWorkflowId(WF_ID_CREATE_TASK)
                .addAction(getCreateTaskAction(taskType, routingPrefix, locale))
                .build();
    }

    private BotItem describeReadMyTicketsFlow(String routingPrefix) {
        return new BotItem.Builder()
                .setTitle("View my tickets")
                .setDescription("View the tickets that I currently have open")
                .setWorkflowId(WF_ID_VIEW_TASK)
                .addAction(getViewMyTaskAction(routingPrefix))
                .build();
    }

    private BotItem describeReadTicketByNumberFlow(String routingPrefix) {
        return new BotItem.Builder()
                .setTitle("View a ticket.")
                .setDescription("View a ticket by its number.")
                .setWorkflowId(WF_ID_VIEW_TASK_BY_NUMBER)
                .addAction(getViewTaskByNumberAction(routingPrefix))
                .build();
    }


    @PostMapping(
            path = "/api/v1/task/create",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<Map<String, List<Map<String, BotItem>>>> createTask(
            @RequestHeader(AUTHORIZATION) String mfToken,
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            @RequestHeader(ROUTING_PREFIX_TEMPLATE) String routingPrefixTemplate,
            Locale locale,
            @Valid CreateTaskForm form) {

        // ToDo: Validate.
        // ToDo: Should we make it optional and default it to "task" ?
        String taskType = form.getType();

        String shortDescription = form.getShortDescription();

        var userEmail = AuthUtil.extractUserEmail(mfToken);

        logger.trace("createTicket for baseUrl={}, taskType={}, userEmail={}, routingTemplate={}", baseUrl, taskType, userEmail, routingPrefixTemplate);

        String routingPrefix = routingPrefixTemplate.replace(INSERT_OBJECT_TYPE, OBJECT_TYPE_BOT_DISCOVERY);

        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();
        return this.createTask(taskType, shortDescription, userEmail, baseUri, auth)
                .map(taskNumber -> buildTaskUriReadByNumber(taskType, taskNumber, baseUrl))
                .flatMap(readTaskUri -> retrieveTasks(readTaskUri, auth))
                .map(retrieved -> toTaskBotObj(retrieved, routingPrefix, baseUrl, "confirmation", locale));
    }

    private Mono<String> createTask(String taskType, String shortDescription, String callerEmailId,
                                    URI baseUri, String auth) {
        return rest.post()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path("/api/now/table/")
                        .path(taskType)
                        .build()
                )
                .header(AUTHORIZATION, auth)
                // ToDo: Improve this request body, if somehow chat-bot is able to supply more info.
                .syncBody(Map.of(
                        "short_description", shortDescription,
                        "caller_id", callerEmailId))
                .retrieve()
                .bodyToMono(JsonDocument.class)
                .map(doc -> doc.read("$.result.number"));
    }

    @PutMapping(
            path = "/api/v1/cart",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE
    )
    public Mono<BotObjects> addToCart(
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            @RequestHeader(ROUTING_PREFIX_TEMPLATE) String routingPrefixTemplate,
            Locale locale,
            @Valid AddToCartForm form) {

        String itemId = form.getItemId();
        // ToDo: Default itemCount to 1 ?
        Integer itemCount = form.getItemCount();

        logger.trace("addToCart itemId={}, count={}, baseUrl={}", itemId, itemCount, baseUrl);

        String routingPrefix = routingPrefixTemplate.replace(INSERT_OBJECT_TYPE, OBJECT_TYPE_CART);

        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();
        return rest.post()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path(SNOW_ADD_TO_CART_ENDPOINT)
                        .build(Map.of("item_id", itemId))
                )
                .header(AUTHORIZATION, auth)
                .syncBody(Map.of(SNOW_SYS_PARAM_QUAN, itemCount))
                .retrieve()
                .bodyToMono(Void.class)
                .then(this.lookupCart(auth, baseUrl, routingPrefix, locale, new CardRequest(null, null)));
    }

    @DeleteMapping(
            path = "/api/v1/cart",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<ResponseEntity<Map<String, Object>>> emptyCart(
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl) {
        logger.trace("emptyCart baseUrl={}", baseUrl);

        return retrieveUserCart(baseUrl, auth)
                .map(cartDocument -> cartDocument.read("$.result.cart_id"))
                .flatMap(cartId -> deleteCart(baseUrl, auth, (String) cartId))
                .flatMap(this::toDeleteItemResponse);

    }

    private Mono<ClientResponse> deleteCart(String baseUrl, String auth, String cartId) {
        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();

        return rest.delete()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path(SNOW_DELETE_CART_ENDPOINT)
                        .build(Map.of("cart_id", cartId))
                )
                .header(AUTHORIZATION, auth)
                .exchange();
    }

    //ToDo: Add path variable to take cart-item-id.
    @DeleteMapping(
            path = "/api/v1/cart/{cartItemId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Mono<BotObjects> deleteFromCart(
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            Locale locale,
            @PathVariable("cartItemId") String cartItemId,
            @RequestHeader(ROUTING_PREFIX_TEMPLATE) String routingPrefixTemplate) {
        logger.trace("deleteFromCart cartItem entryId={}, baseUrl={}", cartItemId, baseUrl);

        String routingPrefix = routingPrefixTemplate.replace(INSERT_OBJECT_TYPE, OBJECT_TYPE_CART);
        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();
        return rest.delete()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path(SNOW_DELETE_FROM_CART_ENDPOINT)
                        .build(Map.of("cart_item_id", cartItemId))
                )
                .header(AUTHORIZATION, auth)
                .exchange()
                .then(this.lookupCart(auth, baseUrl, routingPrefix, locale, new CardRequest(null, null)));
    }

    private Mono<ResponseEntity<Map<String, Object>>> toDeleteItemResponse(ClientResponse sNowResponse) {
        if (sNowResponse.statusCode().is2xxSuccessful()) {
            return Mono.just(
                    ResponseEntity
                            .status(sNowResponse.statusCode()).build());
        } else {
            return sNowResponse.bodyToMono(JsonDocument.class)
                    .map(body -> ResponseEntity.status(sNowResponse.statusCode())
                            .body(Map.of(ACTION_RESULT_KEY, Map.of(
                                    "message", body.read("$.error.message")))
                            )
                    );
        }
    }

    @PostMapping(
            path = "/api/v1/checkout"
    )
    public Mono<Map<String, List<Map<String, BotItem>>>> checkout(
            @RequestHeader(AUTHORIZATION) String mfToken,
            @RequestHeader(AUTH_HEADER) String auth,
            @RequestHeader(BASE_URL_HEADER) String baseUrl,
            @RequestHeader(ROUTING_PREFIX_TEMPLATE) String routingPrefixTemplate,
            Locale locale) {

        var userEmail = AuthUtil.extractUserEmail(mfToken);

        logger.trace("checkout cart for user={}, baseUrl={}, routingTemplate={}", userEmail, baseUrl, routingPrefixTemplate);

        String routingPrefix = routingPrefixTemplate.replace(INSERT_OBJECT_TYPE, OBJECT_TYPE_TASK);

        URI baseUri = UriComponentsBuilder.fromUriString(baseUrl).build().toUri();
        // If Bot needs cart subtotal, include that by making an extra call to SNow.
        return rest.post()
                .uri(uriBuilder -> uriBuilder
                        .scheme(baseUri.getScheme())
                        .host(baseUri.getHost())
                        .port(baseUri.getPort())
                        .path(SNOW_CHECKOUT_ENDPOINT)
                        .build()
                )
                .header(AUTHORIZATION, auth)
                .retrieve()
                .bodyToMono(JsonDocument.class)
                .map(doc -> doc.<String>read("$.result.request_number"))
                .doOnSuccess(no -> logger.debug("Ticket created {}", no))
                .map(ViewTaskForm::new)
                .flatMap(viewTaskForm -> getTasks(mfToken, auth, routingPrefix, baseUrl, viewTaskForm, locale));

    }

    @ExceptionHandler(CatalogReadException.class)
    @ResponseStatus(BAD_REQUEST)
    @ResponseBody
    public Map<String, String> connectorDisabledErrorHandler(CatalogReadException e) {
        return Map.of("message", e.getMessage());
    }
}

