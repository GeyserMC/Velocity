/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.netty;

import static io.netty.util.ByteProcessor.FIND_NON_NUL;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import com.velocitypowered.proxy.util.except.QuietRuntimeException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Frames Minecraft server packets which are prefixed by a 21-bit VarInt encoding.
 */
public class MinecraftVarintFrameDecoder extends ByteToMessageDecoder {

  private static final Logger LOGGER = LogManager.getLogger(MinecraftVarintFrameDecoder.class);
  private static final QuietRuntimeException FRAME_DECODER_FAILED =
      new QuietRuntimeException("A packet frame decoder failed. For more information, launch "
          + "Velocity with -Dvelocity.packet-decode-logging=true to see more.");
  private static final QuietDecoderException BAD_PACKET_LENGTH =
      new QuietDecoderException("Bad packet length");
  private static final QuietDecoderException VARINT_TOO_BIG =
      new QuietDecoderException("VarInt too big");
  private static final QuietDecoderException UNKNOWN_PACKET =
      new QuietDecoderException("Unknown packet");

  private final ProtocolUtils.Direction direction;
  private final StateRegistry.PacketRegistry.ProtocolRegistry registry;
  private StateRegistry state;

  /**
   * Creates a new {@code MinecraftVarintFrameDecoder} decoding packets from the specified {@code Direction}.
   *
   * @param direction the direction from which we decode from
   */
  public MinecraftVarintFrameDecoder(ProtocolUtils.Direction direction) {
    this.direction = direction;
    this.registry = StateRegistry.HANDSHAKE.getProtocolRegistry(
        direction, ProtocolVersion.MINIMUM_VERSION);
    this.state = StateRegistry.HANDSHAKE;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
      throws Exception {
    if (!ctx.channel().isActive()) {
      in.clear();
      return;
    }

    // skip any runs of 0x00 we might find
    int packetStart = in.forEachByte(FIND_NON_NUL);
    if (packetStart == -1) {
      in.clear();
      return;
    }
    in.readerIndex(packetStart);

    // try to read the length of the packet
    in.markReaderIndex();
    int preIndex = in.readerIndex();
    int length = readRawVarInt21(in);
    if (preIndex == in.readerIndex()) {
      return;
    }
    if (length < 0) {
      throw BAD_PACKET_LENGTH;
    }

    if (length > 0) {
      if (state == StateRegistry.HANDSHAKE && direction == ProtocolUtils.Direction.SERVERBOUND) {
        StateRegistry.PacketRegistry.ProtocolRegistry registry =
            state.getProtocolRegistry(direction, ProtocolVersion.MINIMUM_VERSION);

        final int index = in.readerIndex();
        final int packetId = ProtocolUtils.readVarInt(in);
        final int payloadLength = length - ProtocolUtils.varIntBytes(packetId);

        MinecraftPacket packet = registry.createPacket(packetId);

        // We handle every packet in this phase, if you said something we don't know, something is really wrong
        if (packet == null) {
          throw UNKNOWN_PACKET;
        }

        // We 'technically' have the incoming bytes of a payload here, and so, these can actually parse
        // the packet if needed, so, we'll take advantage of the existing methods
        int expectedMinLen = packet.expectedMinLength(in, direction, registry.version);
        int expectedMaxLen = packet.expectedMaxLength(in, direction, registry.version);
        if (expectedMaxLen != -1 && payloadLength > expectedMaxLen) {
          throw handleOverflow(packet, expectedMaxLen, in.readableBytes());
        }
        if (payloadLength < expectedMinLen) {
          throw handleUnderflow(packet, expectedMaxLen, in.readableBytes());
        }


        in.readerIndex(index);
      }
    }

    // note that zero-length packets are ignored
    if (length > 0) {
      if (in.readableBytes() < length) {
        in.resetReaderIndex();
      } else {
        out.add(in.readRetainedSlice(length));
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (MinecraftDecoder.DEBUG) {
      LOGGER.atWarn()
          .withThrowable(cause)
          .log("Exception caught while decoding frame for {}", ctx.channel().remoteAddress());
    }
  }

  /**
   * Reads a VarInt from the buffer of up to 21 bits in size.
   *
   * @param buffer the buffer to read from
   * @return the VarInt decoded, {@code 0} if no varint could be read
   * @throws QuietDecoderException if the VarInt is too big to be decoded
   */
  private static int readRawVarInt21(ByteBuf buffer) {
    if (buffer.readableBytes() < 4) {
      // we don't have enough that we can read a potentially full varint, so fall back to
      // the slow path.
      return readRawVarintSmallBuf(buffer);
    }
    int wholeOrMore = buffer.getIntLE(buffer.readerIndex());

    // take the last three bytes and check if any of them have the high bit set
    int atStop = ~wholeOrMore & 0x808080;
    if (atStop == 0) {
      // all bytes have the high bit set, so the varint we are trying to decode is too wide
      throw VARINT_TOO_BIG;
    }

    int bitsToKeep = Integer.numberOfTrailingZeros(atStop) + 1;
    buffer.skipBytes(bitsToKeep >> 3);

    // remove all bits we don't need to keep, a trick from
    // https://github.com/netty/netty/pull/14050#issuecomment-2107750734:
    //
    // > The idea is that thisVarintMask has 0s above the first one of firstOneOnStop, and 1s at
    // > and below it. For example if firstOneOnStop is 0x800080 (where the last 0x80 is the only
    // > one that matters), then thisVarintMask is 0xFF.
    //
    // this is also documented in Hacker's Delight, section 2-1 "Manipulating Rightmost Bits"
    int preservedBytes = wholeOrMore & (atStop ^ (atStop - 1));

    // merge together using this trick: https://github.com/netty/netty/pull/14050#discussion_r1597896639
    preservedBytes = (preservedBytes & 0x007F007F) | ((preservedBytes & 0x00007F00) >> 1);
    preservedBytes = (preservedBytes & 0x00003FFF) | ((preservedBytes & 0x3FFF0000) >> 2);
    return preservedBytes;
  }

  private static int readRawVarintSmallBuf(ByteBuf buffer) {
    if (!buffer.isReadable()) {
      return 0;
    }
    buffer.markReaderIndex();

    byte tmp = buffer.readByte();
    if (tmp >= 0) {
      return tmp;
    }
    int result = tmp & 0x7F;
    if (!buffer.isReadable()) {
      buffer.resetReaderIndex();
      return 0;
    }
    if ((tmp = buffer.readByte()) >= 0) {
      return result | tmp << 7;
    }
    result |= (tmp & 0x7F) << 7;
    if (!buffer.isReadable()) {
      buffer.resetReaderIndex();
      return 0;
    }
    if ((tmp = buffer.readByte()) >= 0) {
      return result | tmp << 14;
    }
    return result | (tmp & 0x7F) << 14;
  }

  private Exception handleOverflow(MinecraftPacket packet, int expected, int actual) {
    if (MinecraftDecoder.DEBUG) {
      return new CorruptedFrameException("Packet sent for " + packet.getClass() + " was too "
          + "big (expected " + expected + " bytes, got " + actual + " bytes)");
    } else {
      return FRAME_DECODER_FAILED;
    }
  }

  private Exception handleUnderflow(MinecraftPacket packet, int expected, int actual) {
    if (MinecraftDecoder.DEBUG) {
      return new CorruptedFrameException("Packet sent for " + packet.getClass() + " was too "
          + "small (expected " + expected + " bytes, got " + actual + " bytes)");
    } else {
      return FRAME_DECODER_FAILED;
    }
  }

  public void setState(StateRegistry stateRegistry) {
    this.state = stateRegistry;
  }
}
