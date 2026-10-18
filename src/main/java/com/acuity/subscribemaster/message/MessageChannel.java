package com.acuity.subscribemaster.message;

/**
 * The delivery channel for an {@link OutboundMessage}; each value has exactly one {@code
 * ChannelSender} handling it. Only {@code EMAIL} exists today -- {@code SMS} is added together with
 * its sender in FR-37, not before, so no channel ever exists without a way to deliver it.
 */
public enum MessageChannel {
  EMAIL
}
