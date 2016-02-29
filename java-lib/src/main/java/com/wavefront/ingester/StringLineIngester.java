package com.wavefront.ingester;

import com.google.common.base.Charsets;
import com.google.common.base.Function;

import java.util.ArrayList;
import java.util.List;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;

/**
 * Default Ingester thread that sets up decoders and a command handler to listen for metrics that are string formatted lines on a port.
 *
 * @author Clement Pang (clement@wavefront.com).
 */
public class StringLineIngester extends TcpIngester {

  public StringLineIngester(List<Function<Channel, ChannelHandler>> decoders,
                            ChannelHandler commandHandler, int port) {
    super(createDecoderList(decoders), commandHandler, port);
  }

  public StringLineIngester(ChannelHandler commandHandler, int port) {
    super(commandHandler, port);
  }

  /**
   * Returns a copy of the given list plus inserts the 2 decoders needed for
   * this specific ingester (LineBasedFrameDecoder and StringDecoder)
   * @param decoders the starting list
   * @return copy of the provided list with additional decodiers prepended
   */
  private static List<Function<Channel, ChannelHandler>> createDecoderList(final List<Function<Channel, ChannelHandler>> decoders) {
    final List<Function<Channel, ChannelHandler>> copy =
      new ArrayList<>(decoders);
    copy.add(0, new Function<Channel, ChannelHandler>() {
        @Override
        public ChannelHandler apply(Channel input) {
          return new LineBasedFrameDecoder(4096, true, true);
        }
      });
    copy.add(1, new Function<Channel, ChannelHandler>() {
        @Override
        public ChannelHandler apply(Channel input) {
          return new StringDecoder(Charsets.UTF_8);
        }
      });

    return copy;
  }
}
