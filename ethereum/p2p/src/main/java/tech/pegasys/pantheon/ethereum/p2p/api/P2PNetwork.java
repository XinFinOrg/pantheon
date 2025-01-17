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
package tech.pegasys.pantheon.ethereum.p2p.api;

import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.wire.Capability;
import tech.pegasys.pantheon.util.enode.EnodeURL;

import java.io.Closeable;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** P2P Network Interface. */
public interface P2PNetwork extends Closeable {

  void start();

  /**
   * Returns a snapshot of the currently connected peer connections.
   *
   * @return Peers currently connected.
   */
  Collection<PeerConnection> getPeers();

  /**
   * Returns a stream of peers that have been discovered on the network. These peers are not
   * necessarily connected.
   *
   * @return A stream of discovered peers on the network.
   */
  Stream<DiscoveryPeer> streamDiscoveredPeers();

  /**
   * Connects to a {@link Peer}.
   *
   * @param peer Peer to connect to.
   * @return Future of the established {@link PeerConnection}
   */
  CompletableFuture<PeerConnection> connect(Peer peer);

  /**
   * Subscribe a {@link Consumer} to all incoming {@link Message} of a given sub-protocol. Calling
   * {@link #start()} on an implementation without at least having one subscribed {@link Consumer}
   * per supported sub-protocol should throw a {@link RuntimeException}.
   *
   * @param capability Capability (sub-protocol) to subscribe to.
   * @param callback The callback to invoke when a message for the given capability arrives
   */
  void subscribe(final Capability capability, final MessageCallback callback);

  /**
   * Subscribe a {@link Consumer} to all incoming new Peer connection events.
   *
   * @param callback The callback to invoke when a new connection is established
   */
  void subscribeConnect(ConnectCallback callback);

  /**
   * Subscribe a {@link Consumer} to all incoming new Peer disconnect events.
   *
   * @param callback The callback to invoke when a peer disconnects
   */
  void subscribeDisconnect(DisconnectCallback callback);

  /**
   * Adds a {@link Peer} to a list indicating efforts should be made to always stay connected
   * regardless of maxPeer limits. Non-permitted peers may be added to this list, but will not
   * actually be connected to as long as they are prohibited.
   *
   * @param peer The peer that should be connected to
   * @return boolean representing whether or not the peer has been added to the list, false is
   *     returned if the peer was already on the list
   */
  boolean addMaintainConnectionPeer(final Peer peer);

  /**
   * Disconnect and remove the given {@link Peer} from the maintained peer list. Peer is
   * disconnected even if it is not in the maintained peer list. See {@link
   * #addMaintainConnectionPeer(Peer)} for details on the maintained peer list.
   *
   * @param peer The peer to which connections are not longer required
   * @return boolean representing whether the peer was removed from the maintained peer list
   */
  boolean removeMaintainedConnectionPeer(final Peer peer);

  /** Stops the P2P network layer. */
  void stop();

  /** Blocks until the P2P network layer has stopped. */
  void awaitStop();

  /**
   * Checks if the node is listening for network connections
   *
   * @return true if the node is listening for network connections, false, otherwise.
   */
  boolean isListening();

  /**
   * Returns whether the P2P network is enabled
   *
   * @return true if the P2P network is enabled, false, otherwise.
   */
  boolean isP2pEnabled();

  /** @return Return true if peer discovery is enabled. */
  boolean isDiscoveryEnabled();

  /**
   * Returns the EnodeURL used to identify this peer in the network.
   *
   * @return the enodeURL associated with this node if P2P has been enabled. Returns empty
   *     otherwise.
   */
  Optional<EnodeURL> getLocalEnode();
}
