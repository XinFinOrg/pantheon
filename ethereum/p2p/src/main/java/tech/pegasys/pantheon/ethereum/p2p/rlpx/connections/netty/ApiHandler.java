/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.p2p.rlpx.connections.netty;

import tech.pegasys.pantheon.ethereum.p2p.api.MessageData;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection.PeerNotConnected;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.connections.PeerConnectionEventDispatcher;
import tech.pegasys.pantheon.ethereum.p2p.wire.CapabilityMultiplexer;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage.DisconnectReason;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.PongMessage;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.WireMessageCodes;
import tech.pegasys.pantheon.ethereum.rlp.RLPException;

import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class ApiHandler extends SimpleChannelInboundHandler<MessageData> {

  private static final Logger LOG = LogManager.getLogger();

  private final CapabilityMultiplexer multiplexer;
  private final AtomicBoolean waitingForPong;

  private final PeerConnectionEventDispatcher connectionEventDispatcher;

  private final PeerConnection connection;

  ApiHandler(
      final CapabilityMultiplexer multiplexer,
      final PeerConnection connection,
      final PeerConnectionEventDispatcher connectionEventDispatcher,
      final AtomicBoolean waitingForPong) {
    this.multiplexer = multiplexer;
    this.connectionEventDispatcher = connectionEventDispatcher;
    this.connection = connection;
    this.waitingForPong = waitingForPong;
  }

  @Override
  protected void channelRead0(final ChannelHandlerContext ctx, final MessageData originalMessage) {
    final CapabilityMultiplexer.ProtocolMessage demultiplexed =
        multiplexer.demultiplex(originalMessage);

    final MessageData message = demultiplexed.getMessage();

    // Handle Wire messages
    if (demultiplexed.getCapability() == null) {
      switch (message.getCode()) {
        case WireMessageCodes.PING:
          LOG.debug("Received Wire PING");
          try {
            connection.send(null, PongMessage.get());
          } catch (final PeerNotConnected peerNotConnected) {
            // Nothing to do
          }
          break;
        case WireMessageCodes.PONG:
          LOG.debug("Received Wire PONG");
          waitingForPong.set(false);
          break;
        case WireMessageCodes.DISCONNECT:
          final DisconnectMessage disconnect = DisconnectMessage.readFrom(message);
          DisconnectReason reason = DisconnectReason.UNKNOWN;
          try {
            reason = disconnect.getReason();
            LOG.debug(
                "Received Wire DISCONNECT ({}) from peer: {}",
                reason.name(),
                connection.getPeerInfo().getClientId());
          } catch (final RLPException e) {
            LOG.debug(
                "Received Wire DISCONNECT with invalid RLP. Peer: {}",
                connection.getPeerInfo().getClientId());
          } catch (final Exception e) {
            LOG.error(
                "Received Wire DISCONNECT, but unable to parse reason. Peer: {}",
                connection.getPeerInfo().getClientId(),
                e);
          }
          connection.terminateConnection(reason, true);
      }
      return;
    }
    connectionEventDispatcher.dispatchMessage(demultiplexed.getCapability(), connection, message);
  }

  @Override
  public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable throwable) {
    LOG.error("Error:", throwable);
    connectionEventDispatcher.dispatchDisconnect(
        connection, DisconnectReason.TCP_SUBSYSTEM_ERROR, false);
    ctx.close();
  }
}
