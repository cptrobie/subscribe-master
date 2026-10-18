package com.acuity.subscribemaster.message;

/**
 * The single entry point for sending a customer-facing message by any channel (ARCHITECTURE.md
 * &sect;2.4). Callers inject this, never a sender directly; the caller chooses the channel and
 * supplies the recipient, so this module never looks up a customer.
 */
public interface MessageService {

  void send(OutboundMessage message);
}
