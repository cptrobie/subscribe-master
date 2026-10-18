package com.acuity.subscribemaster.message.internal;

import com.acuity.subscribemaster.message.MessageChannel;
import com.acuity.subscribemaster.message.OutboundMessage;

/**
 * Strategy interface for one delivery channel (FR-20): {@link #channel()} declares which channel
 * this sender handles, and {@code DefaultMessageService} routes to it by that value. Internal to
 * the {@code message} module -- adding a channel means adding an implementation bean, with no
 * change to callers or to the dispatcher.
 */
public interface ChannelSender {

  MessageChannel channel();

  void send(OutboundMessage message);
}
