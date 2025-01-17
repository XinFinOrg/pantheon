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
package tech.pegasys.pantheon.ethereum.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tech.pegasys.pantheon.ethereum.blockcreation.EthHashMiningCoordinator;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.Synchronizer;
import tech.pegasys.pantheon.ethereum.core.Wei;
import tech.pegasys.pantheon.ethereum.eth.EthProtocol;
import tech.pegasys.pantheon.ethereum.eth.transactions.TransactionPool;
import tech.pegasys.pantheon.ethereum.graphql.internal.BlockWithMetadata;
import tech.pegasys.pantheon.ethereum.graphql.internal.BlockchainQuery;
import tech.pegasys.pantheon.ethereum.graphql.internal.TransactionWithMetadata;
import tech.pegasys.pantheon.ethereum.p2p.wire.Capability;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.net.InetSocketAddress;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import graphql.GraphQL;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GraphQLHttpServiceTest {

  @ClassRule public static final TemporaryFolder folder = new TemporaryFolder();

  private static final Vertx vertx = Vertx.vertx();

  private static GraphQLHttpService service;
  private static OkHttpClient client;
  private static String baseUrl;
  protected static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  protected static final MediaType GRAPHQL = MediaType.parse("application/graphql; charset=utf-8");
  private static BlockchainQuery blockchainQueries;
  private static Synchronizer synchronizer;
  private static GraphQL graphQL;
  private static GraphQLDataFetchers dataFetchers;
  private static GraphQLDataFetcherContext dataFetcherContext;
  private static EthHashMiningCoordinator miningCoordinatorMock;

  private final GraphQLTestHelper testHelper = new GraphQLTestHelper();

  @BeforeClass
  public static void initServerAndClient() throws Exception {
    blockchainQueries = mock(BlockchainQuery.class);
    synchronizer = mock(Synchronizer.class);
    graphQL = mock(GraphQL.class);

    miningCoordinatorMock = mock(EthHashMiningCoordinator.class);

    dataFetcherContext = mock(GraphQLDataFetcherContext.class);
    when(dataFetcherContext.getBlockchainQuery()).thenReturn(blockchainQueries);
    when(dataFetcherContext.getMiningCoordinator()).thenReturn(miningCoordinatorMock);

    when(dataFetcherContext.getTransactionPool()).thenReturn(mock(TransactionPool.class));
    when(dataFetcherContext.getSynchronizer()).thenReturn(synchronizer);

    final Set<Capability> supportedCapabilities = new HashSet<>();
    supportedCapabilities.add(EthProtocol.ETH62);
    supportedCapabilities.add(EthProtocol.ETH63);
    dataFetchers = new GraphQLDataFetchers(supportedCapabilities);
    graphQL = GraphQLProvider.buildGraphQL(dataFetchers);
    service = createGraphQLHttpService();
    service.start().join();
    // Build an OkHttp client.
    client = new OkHttpClient();
    baseUrl = service.url() + "/graphql/";
  }

  private static GraphQLHttpService createGraphQLHttpService(final GraphQLConfiguration config)
      throws Exception {
    return new GraphQLHttpService(
        vertx, folder.newFolder().toPath(), config, graphQL, dataFetcherContext);
  }

  private static GraphQLHttpService createGraphQLHttpService() throws Exception {
    return new GraphQLHttpService(
        vertx, folder.newFolder().toPath(), createGraphQLConfig(), graphQL, dataFetcherContext);
  }

  private static GraphQLConfiguration createGraphQLConfig() {
    final GraphQLConfiguration config = GraphQLConfiguration.createDefault();
    config.setPort(0);
    return config;
  }

  @BeforeClass
  public static void setupConstants() {

    final URL blocksUrl =
        GraphQLHttpServiceTest.class
            .getClassLoader()
            .getResource("tech/pegasys/pantheon/ethereum/graphql/graphQLTestBlockchain.blocks");

    final URL genesisJsonUrl =
        GraphQLHttpServiceTest.class
            .getClassLoader()
            .getResource("tech/pegasys/pantheon/ethereum/graphql/graphQLTestGenesis.json");

    assertThat(blocksUrl).isNotNull();
    assertThat(genesisJsonUrl).isNotNull();
  }

  /** Tears down the HTTP server. */
  @AfterClass
  public static void shutdownServer() {
    client.dispatcher().executorService().shutdown();
    client.connectionPool().evictAll();
    service.stop().join();
    vertx.close();
  }

  @Test
  public void invalidCallToStart() {
    service
        .start()
        .whenComplete(
            (unused, exception) -> assertThat(exception).isInstanceOf(IllegalStateException.class));
  }

  @Test
  public void http404() throws Exception {
    try (final Response resp = client.newCall(buildGetRequest("/foo")).execute()) {
      assertThat(resp.code()).isEqualTo(404);
    }
  }

  @Test
  public void handleEmptyRequest() throws Exception {
    try (final Response resp =
        client.newCall(new Request.Builder().get().url(service.url()).build()).execute()) {
      assertThat(resp.code()).isEqualTo(201);
    }
  }

  @Test
  public void handleInvalidQuerySchema() throws Exception {
    final RequestBody body = RequestBody.create(GRAPHQL, "{gasPrice1}");

    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      final JsonObject json = new JsonObject(resp.body().string());
      testHelper.assertValidGraphQLError(json);
      assertThat(resp.code()).isEqualTo(400);
    }
  }

  @Test
  public void query_get() throws Exception {
    final Wei price = Wei.of(16);
    when(miningCoordinatorMock.getMinTransactionGasPrice()).thenReturn(price);

    try (final Response resp = client.newCall(buildGetRequest("?query={gasPrice}")).execute()) {
      assertThat(resp.code()).isEqualTo(200);
      final JsonObject json = new JsonObject(resp.body().string());
      testHelper.assertValidGraphQLResult(json);
      final String result = json.getJsonObject("data").getString("gasPrice");
      assertThat(result).isEqualTo("0x10");
    }
  }

  @Test
  public void query_jsonPost() throws Exception {
    final RequestBody body = RequestBody.create(JSON, "{\"query\":\"{gasPrice}\"}");
    final Wei price = Wei.of(16);
    when(miningCoordinatorMock.getMinTransactionGasPrice()).thenReturn(price);

    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      assertThat(resp.code()).isEqualTo(200); // Check general format of result
      final JsonObject json = new JsonObject(resp.body().string());
      testHelper.assertValidGraphQLResult(json);
      final String result = json.getJsonObject("data").getString("gasPrice");
      assertThat(result).isEqualTo("0x10");
    }
  }

  @Test
  public void query_graphqlPost() throws Exception {
    final RequestBody body = RequestBody.create(GRAPHQL, "{gasPrice}");
    final Wei price = Wei.of(16);
    when(miningCoordinatorMock.getMinTransactionGasPrice()).thenReturn(price);

    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      assertThat(resp.code()).isEqualTo(200); // Check general format of result
      final JsonObject json = new JsonObject(resp.body().string());
      testHelper.assertValidGraphQLResult(json);
      final String result = json.getJsonObject("data").getString("gasPrice");
      assertThat(result).isEqualTo("0x10");
    }
  }

  @Test
  public void query_untypedPost() throws Exception {
    final RequestBody body = RequestBody.create(null, "{gasPrice}");
    final Wei price = Wei.of(16);
    when(miningCoordinatorMock.getMinTransactionGasPrice()).thenReturn(price);

    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      assertThat(resp.code()).isEqualTo(200); // Check general format of result
      final JsonObject json = new JsonObject(resp.body().string());
      testHelper.assertValidGraphQLResult(json);
      final String result = json.getJsonObject("data").getString("gasPrice");
      assertThat(result).isEqualTo("0x10");
    }
  }

  @Test
  public void getSocketAddressWhenActive() {
    final InetSocketAddress socketAddress = service.socketAddress();
    assertThat("127.0.0.1").isEqualTo(socketAddress.getAddress().getHostAddress());
    assertThat(socketAddress.getPort() > 0).isTrue();
  }

  @Test
  public void getSocketAddressWhenStoppedIsEmpty() throws Exception {
    final GraphQLHttpService service = createGraphQLHttpService();

    final InetSocketAddress socketAddress = service.socketAddress();
    assertThat("0.0.0.0").isEqualTo(socketAddress.getAddress().getHostAddress());
    assertThat(0).isEqualTo(socketAddress.getPort());
    assertThat("").isEqualTo(service.url());
  }

  @Test
  public void getSocketAddressWhenBindingToAllInterfaces() throws Exception {
    final GraphQLConfiguration config = createGraphQLConfig();
    config.setHost("0.0.0.0");
    final GraphQLHttpService service = createGraphQLHttpService(config);
    service.start().join();

    try {
      final InetSocketAddress socketAddress = service.socketAddress();
      assertThat("0.0.0.0").isEqualTo(socketAddress.getAddress().getHostAddress());
      assertThat(socketAddress.getPort() > 0).isTrue();
      assertThat(!service.url().contains("0.0.0.0")).isTrue();
    } finally {
      service.stop().join();
    }
  }

  @Test
  public void responseContainsJsonContentTypeHeader() throws Exception {

    final RequestBody body = RequestBody.create(GRAPHQL, "{gasPrice}");

    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      assertThat(resp.header("Content-Type")).isEqualTo(JSON.toString());
    }
  }

  @Test
  public void ethGetUncleCountByBlockHash() throws Exception {
    final int uncleCount = 4;
    final Hash blockHash = Hash.hash(BytesValue.of(1));
    @SuppressWarnings("unchecked")
    final BlockWithMetadata<TransactionWithMetadata, Hash> block = mock(BlockWithMetadata.class);
    @SuppressWarnings("unchecked")
    final List<Hash> list = mock(List.class);

    when(blockchainQueries.blockByHash(eq(blockHash))).thenReturn(Optional.of(block));
    when(block.getOmmers()).thenReturn(list);
    when(list.size()).thenReturn(uncleCount);

    final String query = "{block(hash:\"" + blockHash.toString() + "\") {ommerCount}}";

    final RequestBody body = RequestBody.create(GRAPHQL, query);
    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      assertThat(resp.code()).isEqualTo(200);
      final String jsonStr = resp.body().string();
      final JsonObject json = new JsonObject(jsonStr);
      testHelper.assertValidGraphQLResult(json);
      final int result = json.getJsonObject("data").getJsonObject("block").getInteger("ommerCount");
      assertThat(result).isEqualTo(uncleCount);
    }
  }

  @Test
  public void ethGetUncleCountByBlockNumber() throws Exception {
    final int uncleCount = 5;
    @SuppressWarnings("unchecked")
    final BlockWithMetadata<TransactionWithMetadata, Hash> block = mock(BlockWithMetadata.class);
    @SuppressWarnings("unchecked")
    final List<Hash> list = mock(List.class);
    when(blockchainQueries.blockByNumber(anyLong())).thenReturn(Optional.of(block));
    when(block.getOmmers()).thenReturn(list);
    when(list.size()).thenReturn(uncleCount);

    final String query = "{block(number:\"3\") {ommerCount}}";

    final RequestBody body = RequestBody.create(GRAPHQL, query);
    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      assertThat(resp.code()).isEqualTo(200);
      final String jsonStr = resp.body().string();
      final JsonObject json = new JsonObject(jsonStr);
      testHelper.assertValidGraphQLResult(json);
      final int result = json.getJsonObject("data").getJsonObject("block").getInteger("ommerCount");
      assertThat(result).isEqualTo(uncleCount);
    }
  }

  @Test
  public void ethGetUncleCountByBlockLatest() throws Exception {
    final int uncleCount = 5;
    @SuppressWarnings("unchecked")
    final BlockWithMetadata<TransactionWithMetadata, Hash> block = mock(BlockWithMetadata.class);
    @SuppressWarnings("unchecked")
    final List<Hash> list = mock(List.class);
    when(blockchainQueries.latestBlock()).thenReturn(Optional.of(block));
    when(block.getOmmers()).thenReturn(list);
    when(list.size()).thenReturn(uncleCount);

    final String query = "{block {ommerCount}}";

    final RequestBody body = RequestBody.create(GRAPHQL, query);
    try (final Response resp = client.newCall(buildPostRequest(body)).execute()) {
      assertThat(resp.code()).isEqualTo(200);
      final String jsonStr = resp.body().string();
      final JsonObject json = new JsonObject(jsonStr);
      testHelper.assertValidGraphQLResult(json);
      final int result = json.getJsonObject("data").getJsonObject("block").getInteger("ommerCount");
      assertThat(result).isEqualTo(uncleCount);
    }
  }

  private Request buildPostRequest(final RequestBody body) {
    return new Request.Builder().post(body).url(baseUrl).build();
  }

  private Request buildGetRequest(final String path) {
    return new Request.Builder().get().url(baseUrl + path).build();
  }
}
