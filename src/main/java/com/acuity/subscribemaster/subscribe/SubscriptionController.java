package com.acuity.subscribemaster.subscribe;

import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionRequest;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionResponse;
import com.acuity.subscribemaster.subscribe.dto.SubscriptionUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
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

@RestController
@RequestMapping("/api/v1/subscribe")
@Tag(name = "Subscription", description = "Subscription operations")
public class SubscriptionController {

  private final SubscriptionService subscriptionService;

  public SubscriptionController(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  /**
   * Creates a new subscription for the authenticated customer.
   *
   * <p>The customer is identified from the JWT subject claim ({@code jwt.getSubject()}), not from
   * the request body — the client cannot create a subscription on another customer's behalf by
   * supplying a different ID.
   *
   * @param jwt the authenticated principal, injected by Spring Security's OAuth2 resource server
   *     support
   * @param request the subscription details; see {@link SubscriptionRequest} for the
   *     providerId/customName validation rule
   * @return the created subscription, HTTP 201
   */
  @Operation(summary = "Create a new subscription", description = "Creates a new subscription")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Subscription created",
        content = @Content(schema = @Schema(implementation = SubscriptionResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Body validation failure",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "404",
        description = "providerId does not match a known provider",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<SubscriptionResponse> create(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SubscriptionRequest request,
      HttpServletRequest httpRequest) {

    var response =
        subscriptionService.create(
            UUID.fromString(jwt.getSubject()), request, httpRequest.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
