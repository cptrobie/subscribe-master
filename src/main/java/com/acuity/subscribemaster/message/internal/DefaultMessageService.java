package com.acuity.subscribemaster.message.internal;

import com.acuity.subscribemaster.message.MessageChannel;
import com.acuity.subscribemaster.message.MessageService;
import com.acuity.subscribemaster.message.OutboundMessage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes each {@link OutboundMessage} to the {@link ChannelSender} registered for its {@link
 * MessageChannel} -- the Strategy-pattern core of the {@code message} module (FR-20,
 * ARCHITECTURE.md &sect;2.4).
 *
 * <p>Spring injects every {@link ChannelSender} bean; each declares its own channel via {@link
 * ChannelSender#channel()}, so adding a channel (FR-37's {@code SmsSender}) means adding a sender
 * bean, with no change here. Channel routing and fallback rules (FR-39) belong in this class, never
 * in callers.
 *
 * <p>Two misconfigurations are treated as bugs, not delivery failures, and so throw {@link
 * IllegalStateException} rather than {@link
 * com.acuity.subscribemaster.message.MessageDeliveryException}: two senders claiming the same
 * channel (fails at startup, since which one "won" would otherwise depend on bean order), and a
 * message for a channel with no sender. Callers that tolerate a delivery failure must not silently
 * absorb either of these.
 */
@Service
public class DefaultMessageService implements MessageService {
  private final Map<MessageChannel, ChannelSender> sendersByChannel;

  private static final Logger logger = LoggerFactory.getLogger(DefaultMessageService.class);

  public DefaultMessageService(List<ChannelSender> senders) {
    this.sendersByChannel =
        Collections.unmodifiableMap(
            senders.stream()
                .collect(
                    Collectors.toMap(
                        ChannelSender::channel,
                        Function.identity(),
                        DefaultMessageService::rejectDuplicate,
                        () -> new EnumMap<>(MessageChannel.class))));

    logger.info("message channels registered: {}", sendersByChannel.keySet());
  }

  @Override
  public void send(OutboundMessage message) {
    ChannelSender sender = sendersByChannel.get(message.channel());
    if (sender == null) {
      throw new IllegalStateException("no ChannelSender registered for " + message.channel());
    }
    sender.send(message);
  }

  private static ChannelSender rejectDuplicate(ChannelSender first, ChannelSender second) {
    throw new IllegalStateException(
        "multiple ChannelSenders registered for "
            + first.channel()
            + ": "
            + first.getClass().getName()
            + " and "
            + second.getClass().getName());
  }
}
