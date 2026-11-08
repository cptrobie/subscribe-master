package com.acuity.subscribemaster.subscribe;

import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.idempotency.IdempotencyService;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionRequest;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for the authenticated customer's own subscriptions, under {@code
 * /api/v1/subscribe}: create, update, get one, list, and the pause / resume / cancel status
 * transitions (FR-07, FR-09). The customer is always the JWT subject, never a client-supplied id; a
 * subscription belonging to another customer is reported as not found.
 */
@RestController
@RequestMapping("/api/v1/subscribe")
@Tag(name = "Subscription", description = "Subscription operations")
public class SubscriptionController {

  public static final String CREATE_ENDPOINT = "POST /api/v1/subscribe";

  private final SubscriptionService subscriptionService;
  private final IdempotencyService idemService;

  public SubscriptionController(
      SubscriptionService subscriptionService, IdempotencyService idempotencyService) {
    this.subscriptionService = subscriptionService;
    this.idemService = idempotencyService;
  }

  /**
   * Creates a new subscription for the authenticated customer.
   *
   * <p>The customer is identified from the JWT subject claim ({@code jwt.getSubject()}), not from
   * the request body — the client cannot create a subscription on another customer's behalf by
   * supplying a different ID.
   *
   * <p>Accepts an optional {@code Idempotency-Key} (NFR-35), so a client can retry after a timeout
   * without creating a second, identical subscription: nothing unique in the data stops a
   * duplicate, since a customer may legitimately hold two subscriptions to the same provider. A
   * stored response is only replayed to the customer who created it; the same key from anyone else
   * is rejected with 422.
   *
   * @param idempotencyKey optional client-generated key; the same key and body on a retry replays
   *     the original 201 instead of creating again
   * @param jwt the authenticated principal, injected by Spring Security's OAuth2 resource server
   *     support
   * @param request the subscription details; see {@link SubscriptionRequest} for the
   *     providerId/customName validation rule
   * @return the created subscription, HTTP 201, or the original response replayed for a retry
   */
  @Operation(
      summary = "Create a new subscription",
      description =
          "Creates a new subscription for the logged-in customer.\n\n"
              + "This endpoint is **idempotent** when an `Idempotency-Key` header is sent."
              + " Sending a request with an existing `Idempotency-Key` returns the original saved"
              + " response without creating another subscription.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Subscription created successfully (or cached successful response).",
        headers =
            @Header(
                name = "Idempotent-Replayed",
                description = "`true` when this is the cached response; absent otherwise.",
                schema = @Schema(type = "string", allowableValues = "true")),
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description =
            "Body validation failure, or an `Idempotency-Key` that is blank or longer than "
                + IdempotencyService.MAX_KEY_LENGTH
                + " characters",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "404",
        description = "providerId does not match a known provider",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "422",
        description =
            "The `Idempotency-Key` is already in use with a different request payload, or by a"
                + " different customer.",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionResponse> create(
      @Parameter(
              description =
                  "A unique UUIDv4 string generated by the client to uniquely identify the"
                      + " request, e.g. `9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d`.",
              schema = @Schema(maxLength = IdempotencyService.MAX_KEY_LENGTH))
          @RequestHeader(name = "Idempotency-Key", required = false)
          String idempotencyKey,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SubscriptionRequest request,
      HttpServletRequest httpRequest) {

    var customerId = UUID.fromString(jwt.getSubject());

    return idemService.execute(
        idempotencyKey,
        CREATE_ENDPOINT,
        customerId,
        request,
        SubscriptionResponse.class,
        () -> {
          var response =
              subscriptionService.create(customerId, request, httpRequest.getRemoteAddr());
          return ResponseEntity.status(HttpStatus.CREATED).body(response);
        });
  }

  @Operation(summary = "Pause a subscription", description = "Pauses an active subscription")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Subscription status change was successful",
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Subscription for status change was not found",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Subscription is not in a pausable state",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Subscription was modified by another request",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(path = "/{id}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionResponse> pause(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest httpRequest) {

    var response =
        subscriptionService.pause(
            UUID.fromString(jwt.getSubject()), id, httpRequest.getRemoteAddr());
    return ResponseEntity.ok(response);
  }

  @Operation(summary = "Cancel a subscription", description = "Cancels a subscription")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Subscription status change was successful",
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Subscription for status change was not found",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Subscription has already been cancelled",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Subscription was modified by another request",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(path = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionResponse> cancel(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest httpRequest) {

    var response =
        subscriptionService.cancel(
            UUID.fromString(jwt.getSubject()), id, httpRequest.getRemoteAddr());
    return ResponseEntity.ok(response);
  }

  @Operation(
      summary = "Resume a subscription",
      description = "Resumes a previously paused subscription")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Subscription status change was successful",
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Subscription for status change was not found",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Subscription is not in a paused state",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Subscription was modified by another request",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(path = "/{id}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionResponse> resume(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest httpRequest) {

    var response =
        subscriptionService.resume(
            UUID.fromString(jwt.getSubject()), id, httpRequest.getRemoteAddr());
    return ResponseEntity.ok(response);
  }

  @Operation(summary = "Update a subscription", description = "Updates an existing subscription")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Subscription updated",
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Body validation failure",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid subscription identity",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Subscription for update was not found",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "409",
        description = "Subscription was modified by another request",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PutMapping(
      path = "/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionResponse> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody SubscriptionUpdateRequest request,
      HttpServletRequest httpRequest) {

    var response =
        subscriptionService.update(
            UUID.fromString(jwt.getSubject()), id, request, httpRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.OK).body(response);
  }

  @Operation(
      summary = "Get a subscription",
      description = "Retrieves a single subscription owned by the authenticated customer")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Subscription returned",
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Subscription id was not found",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionResponse> getOne(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {

    var response = subscriptionService.getOne(UUID.fromString(jwt.getSubject()), id);
    return ResponseEntity.ok(response);
  }

  @Operation(
      summary = "List subscriptions",
      description = "Lists all subscriptions owned by the authenticated customer")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Subscription(s) returned",
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class)))
  })
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<List<SubscriptionResponse>> getAll(@AuthenticationPrincipal Jwt jwt) {

    var responses = subscriptionService.getAll(UUID.fromString(jwt.getSubject()));
    return ResponseEntity.ok(responses);
  }
}
