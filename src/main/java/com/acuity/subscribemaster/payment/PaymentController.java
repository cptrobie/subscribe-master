package com.acuity.subscribemaster.payment;

import com.acuity.subscribemaster.error.ApiError;
import com.acuity.subscribemaster.payment.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment")
@Tag(name = "Payment", description = "Payment operations")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @Operation(
      summary = "Get a subscription payment",
      description = "Get a subscription payment's details ")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Payment found",
        content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Parameter validation failure",
        content = @Content(schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Payment not found",
        content = @Content(schema = @Schema(implementation = ApiError.class)))
  })
  @GetMapping(
      path = "/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<PaymentResponse> getPayment(@Valid @PathVariable UUID id) {
    var response = paymentService.getPaymentById(id);
    return ResponseEntity.status(HttpStatus.OK).body(response);
  }

  @Operation(
      summary = "Make a subscription payment",
      description = "Make a new subscription payment ")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Payment created",
        content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
  })
  @PostMapping(
      path = "/register",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<PaymentResponse> foo() {
    return null;
  }
}
