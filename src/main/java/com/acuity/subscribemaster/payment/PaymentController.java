package com.acuity.subscribemaster.subscribe;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscribe")
@Tag(name = "Authentication", description = "Subscription operations")
public class SubscriptionController {

}
