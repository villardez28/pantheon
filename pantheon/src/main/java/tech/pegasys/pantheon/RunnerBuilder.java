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
package tech.pegasys.pantheon;

import tech.pegasys.pantheon.cli.EthNetworkConfig;
import tech.pegasys.pantheon.controller.PantheonController;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.blockcreation.MiningCoordinator;
import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.PrivacyParameters;
import tech.pegasys.pantheon.ethereum.core.Synchronizer;
import tech.pegasys.pantheon.ethereum.eth.transactions.TransactionPool;
import tech.pegasys.pantheon.ethereum.graphql.GraphQLConfiguration;
import tech.pegasys.pantheon.ethereum.graphql.GraphQLDataFetcherContext;
import tech.pegasys.pantheon.ethereum.graphql.GraphQLDataFetchers;
import tech.pegasys.pantheon.ethereum.graphql.GraphQLHttpService;
import tech.pegasys.pantheon.ethereum.graphql.GraphQLProvider;
import tech.pegasys.pantheon.ethereum.jsonrpc.JsonRpcConfiguration;
import tech.pegasys.pantheon.ethereum.jsonrpc.JsonRpcHttpService;
import tech.pegasys.pantheon.ethereum.jsonrpc.JsonRpcMethodsFactory;
import tech.pegasys.pantheon.ethereum.jsonrpc.RpcApi;
import tech.pegasys.pantheon.ethereum.jsonrpc.health.HealthService;
import tech.pegasys.pantheon.ethereum.jsonrpc.health.LivenessCheck;
import tech.pegasys.pantheon.ethereum.jsonrpc.health.ReadinessCheck;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.filter.FilterIdGenerator;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.filter.FilterManager;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.filter.FilterRepository;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods.JsonRpcMethod;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.WebSocketConfiguration;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.WebSocketRequestHandler;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.WebSocketService;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.methods.WebSocketMethodsFactory;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription.SubscriptionManager;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription.blockheaders.NewBlockHeadersSubscriptionService;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription.logs.LogsSubscriptionService;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription.pending.PendingTransactionDroppedSubscriptionService;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription.pending.PendingTransactionSubscriptionService;
import tech.pegasys.pantheon.ethereum.jsonrpc.websocket.subscription.syncing.SyncingSubscriptionService;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.p2p.config.DiscoveryConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.config.NetworkingConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.config.RlpxConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.config.SubProtocolConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.network.DefaultP2PNetwork;
import tech.pegasys.pantheon.ethereum.p2p.network.NetworkRunner;
import tech.pegasys.pantheon.ethereum.p2p.network.NetworkRunner.NetworkBuilder;
import tech.pegasys.pantheon.ethereum.p2p.network.NoopP2PNetwork;
import tech.pegasys.pantheon.ethereum.p2p.network.P2PNetwork;
import tech.pegasys.pantheon.ethereum.p2p.network.ProtocolManager;
import tech.pegasys.pantheon.ethereum.p2p.peers.DefaultPeer;
import tech.pegasys.pantheon.ethereum.p2p.peers.EnodeURL;
import tech.pegasys.pantheon.ethereum.p2p.permissions.PeerPermissions;
import tech.pegasys.pantheon.ethereum.p2p.permissions.PeerPermissionsBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.wire.Capability;
import tech.pegasys.pantheon.ethereum.p2p.rlpx.wire.SubProtocol;
import tech.pegasys.pantheon.ethereum.permissioning.AccountLocalConfigPermissioningController;
import tech.pegasys.pantheon.ethereum.permissioning.LocalPermissioningConfiguration;
import tech.pegasys.pantheon.ethereum.permissioning.NodeLocalConfigPermissioningController;
import tech.pegasys.pantheon.ethereum.permissioning.NodePermissioningControllerFactory;
import tech.pegasys.pantheon.ethereum.permissioning.PermissioningConfiguration;
import tech.pegasys.pantheon.ethereum.permissioning.SmartContractPermissioningConfiguration;
import tech.pegasys.pantheon.ethereum.permissioning.TransactionSmartContractPermissioningController;
import tech.pegasys.pantheon.ethereum.permissioning.account.AccountPermissioningController;
import tech.pegasys.pantheon.ethereum.permissioning.node.InsufficientPeersPermissioningProvider;
import tech.pegasys.pantheon.ethereum.permissioning.node.NodePermissioningController;
import tech.pegasys.pantheon.ethereum.permissioning.node.PeerPermissionsAdapter;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;
import tech.pegasys.pantheon.ethereum.worldstate.WorldStateArchive;
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.metrics.prometheus.MetricsConfiguration;
import tech.pegasys.pantheon.metrics.prometheus.MetricsService;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import graphql.GraphQL;
import io.vertx.core.Vertx;

public class RunnerBuilder {

  private Vertx vertx;
  private PantheonController<?> pantheonController;
  private boolean p2pEnabled = true;
  private boolean discovery;
  private EthNetworkConfig ethNetworkConfig;
  private String p2pAdvertisedHost;
  private int p2pListenPort;
  private int maxPeers;
  private JsonRpcConfiguration jsonRpcConfiguration;
  private GraphQLConfiguration graphQLConfiguration;
  private WebSocketConfiguration webSocketConfiguration;
  private Path dataDir;
  private Collection<BytesValue> bannedNodeIds = new ArrayList<>();
  private MetricsConfiguration metricsConfiguration;
  private MetricsSystem metricsSystem;
  private Optional<PermissioningConfiguration> permissioningConfiguration = Optional.empty();
  private Collection<EnodeURL> staticNodes = Collections.emptyList();

  public RunnerBuilder vertx(final Vertx vertx) {
    this.vertx = vertx;
    return this;
  }

  public RunnerBuilder pantheonController(final PantheonController<?> pantheonController) {
    this.pantheonController = pantheonController;
    return this;
  }

  public RunnerBuilder p2pEnabled(final boolean p2pEnabled) {
    this.p2pEnabled = p2pEnabled;
    return this;
  }

  public RunnerBuilder discovery(final boolean discovery) {
    this.discovery = discovery;
    return this;
  }

  public RunnerBuilder ethNetworkConfig(final EthNetworkConfig ethNetworkConfig) {
    this.ethNetworkConfig = ethNetworkConfig;
    return this;
  }

  public RunnerBuilder p2pAdvertisedHost(final String p2pAdvertisedHost) {
    this.p2pAdvertisedHost = p2pAdvertisedHost;
    return this;
  }

  public RunnerBuilder p2pListenPort(final int p2pListenPort) {
    this.p2pListenPort = p2pListenPort;
    return this;
  }

  public RunnerBuilder maxPeers(final int maxPeers) {
    this.maxPeers = maxPeers;
    return this;
  }

  public RunnerBuilder jsonRpcConfiguration(final JsonRpcConfiguration jsonRpcConfiguration) {
    this.jsonRpcConfiguration = jsonRpcConfiguration;
    return this;
  }

  public RunnerBuilder graphQLConfiguration(final GraphQLConfiguration graphQLConfiguration) {
    this.graphQLConfiguration = graphQLConfiguration;
    return this;
  }

  public RunnerBuilder webSocketConfiguration(final WebSocketConfiguration webSocketConfiguration) {
    this.webSocketConfiguration = webSocketConfiguration;
    return this;
  }

  public RunnerBuilder permissioningConfiguration(
      final PermissioningConfiguration permissioningConfiguration) {
    this.permissioningConfiguration = Optional.of(permissioningConfiguration);
    return this;
  }

  public RunnerBuilder dataDir(final Path dataDir) {
    this.dataDir = dataDir;
    return this;
  }

  public RunnerBuilder bannedNodeIds(final Collection<BytesValue> bannedNodeIds) {
    this.bannedNodeIds.addAll(bannedNodeIds);
    return this;
  }

  public RunnerBuilder metricsConfiguration(final MetricsConfiguration metricsConfiguration) {
    this.metricsConfiguration = metricsConfiguration;
    return this;
  }

  public RunnerBuilder metricsSystem(final MetricsSystem metricsSystem) {
    this.metricsSystem = metricsSystem;
    return this;
  }

  public RunnerBuilder staticNodes(final Collection<EnodeURL> staticNodes) {
    this.staticNodes = staticNodes;
    return this;
  }

  public Runner build() {

    Preconditions.checkNotNull(pantheonController);

    final DiscoveryConfiguration discoveryConfiguration;
    if (discovery) {
      final List<EnodeURL> bootstrap;
      if (ethNetworkConfig.getBootNodes() == null) {
        bootstrap = DiscoveryConfiguration.MAINNET_BOOTSTRAP_NODES;
      } else {
        bootstrap = ethNetworkConfig.getBootNodes();
      }
      discoveryConfiguration =
          DiscoveryConfiguration.create()
              .setBindPort(p2pListenPort)
              .setAdvertisedHost(p2pAdvertisedHost)
              .setBootnodes(bootstrap);
    } else {
      discoveryConfiguration = DiscoveryConfiguration.create().setActive(false);
    }

    final KeyPair keyPair = pantheonController.getLocalNodeKeyPair();

    final SubProtocolConfiguration subProtocolConfiguration =
        pantheonController.getSubProtocolConfiguration();

    final ProtocolSchedule<?> protocolSchedule = pantheonController.getProtocolSchedule();
    final ProtocolContext<?> context = pantheonController.getProtocolContext();

    final List<SubProtocol> subProtocols = subProtocolConfiguration.getSubProtocols();
    final List<ProtocolManager> protocolManagers = subProtocolConfiguration.getProtocolManagers();
    final Set<Capability> supportedCapabilities =
        protocolManagers.stream()
            .flatMap(protocolManager -> protocolManager.getSupportedCapabilities().stream())
            .collect(Collectors.toSet());

    final RlpxConfiguration rlpxConfiguration =
        RlpxConfiguration.create()
            .setBindPort(p2pListenPort)
            .setMaxPeers(maxPeers)
            .setSupportedProtocols(subProtocols)
            .setClientId(PantheonInfo.version());
    final NetworkingConfiguration networkConfig =
        new NetworkingConfiguration()
            .setRlpx(rlpxConfiguration)
            .setDiscovery(discoveryConfiguration);

    final PeerPermissionsBlacklist bannedNodes = PeerPermissionsBlacklist.create();
    bannedNodeIds.forEach(bannedNodes::add);

    final List<EnodeURL> bootnodes = discoveryConfiguration.getBootnodes();

    final Synchronizer synchronizer = pantheonController.getSynchronizer();

    final TransactionSimulator transactionSimulator =
        new TransactionSimulator(
            context.getBlockchain(), context.getWorldStateArchive(), protocolSchedule);

    final BytesValue localNodeId = keyPair.getPublicKey().getEncodedBytes();
    final Optional<NodePermissioningController> nodePermissioningController =
        buildNodePermissioningController(
            bootnodes, synchronizer, transactionSimulator, localNodeId);

    final PeerPermissions peerPermissions =
        nodePermissioningController
            .map(nodePC -> new PeerPermissionsAdapter(nodePC, bootnodes, context.getBlockchain()))
            .map(nodePerms -> PeerPermissions.combine(nodePerms, bannedNodes))
            .orElse(bannedNodes);

    NetworkBuilder inactiveNetwork = (caps) -> new NoopP2PNetwork();
    NetworkBuilder activeNetwork =
        (caps) ->
            DefaultP2PNetwork.builder()
                .vertx(vertx)
                .keyPair(keyPair)
                .config(networkConfig)
                .peerPermissions(peerPermissions)
                .metricsSystem(metricsSystem)
                .supportedCapabilities(caps)
                .build();

    final NetworkRunner networkRunner =
        NetworkRunner.builder()
            .protocolManagers(protocolManagers)
            .subProtocols(subProtocols)
            .network(p2pEnabled ? activeNetwork : inactiveNetwork)
            .metricsSystem(metricsSystem)
            .build();

    final P2PNetwork network = networkRunner.getNetwork();
    nodePermissioningController.ifPresent(
        n ->
            n.setInsufficientPeersPermissioningProvider(
                new InsufficientPeersPermissioningProvider(network, bootnodes)));

    final TransactionPool transactionPool = pantheonController.getTransactionPool();
    final MiningCoordinator miningCoordinator = pantheonController.getMiningCoordinator();

    final PrivacyParameters privacyParameters = pantheonController.getPrivacyParameters();
    final FilterManager filterManager = createFilterManager(vertx, context, transactionPool);

    final P2PNetwork peerNetwork = networkRunner.getNetwork();

    staticNodes.stream()
        .map(DefaultPeer::fromEnodeURL)
        .forEach(peerNetwork::addMaintainConnectionPeer);

    final Optional<NodeLocalConfigPermissioningController> nodeLocalConfigPermissioningController =
        nodePermissioningController.flatMap(NodePermissioningController::localConfigController);

    final Optional<AccountPermissioningController> accountPermissioningController =
        buildAccountPermissioningController(
            permissioningConfiguration, pantheonController, transactionSimulator);

    final Optional<AccountLocalConfigPermissioningController>
        accountLocalConfigPermissioningController =
            accountPermissioningController.flatMap(
                AccountPermissioningController::getAccountLocalConfigPermissioningController);

    Optional<JsonRpcHttpService> jsonRpcHttpService = Optional.empty();
    if (jsonRpcConfiguration.isEnabled()) {
      final Map<String, JsonRpcMethod> jsonRpcMethods =
          jsonRpcMethods(
              context,
              protocolSchedule,
              pantheonController,
              peerNetwork,
              synchronizer,
              transactionPool,
              miningCoordinator,
              metricsSystem,
              supportedCapabilities,
              jsonRpcConfiguration.getRpcApis(),
              filterManager,
              accountLocalConfigPermissioningController,
              nodeLocalConfigPermissioningController,
              privacyParameters,
              jsonRpcConfiguration,
              webSocketConfiguration,
              metricsConfiguration);
      jsonRpcHttpService =
          Optional.of(
              new JsonRpcHttpService(
                  vertx,
                  dataDir,
                  jsonRpcConfiguration,
                  metricsSystem,
                  jsonRpcMethods,
                  new HealthService(new LivenessCheck()),
                  new HealthService(new ReadinessCheck(peerNetwork, synchronizer))));
    }

    Optional<GraphQLHttpService> graphQLHttpService = Optional.empty();
    if (graphQLConfiguration.isEnabled()) {
      final GraphQLDataFetchers fetchers = new GraphQLDataFetchers(supportedCapabilities);
      final GraphQLDataFetcherContext dataFetcherContext =
          new GraphQLDataFetcherContext(
              context.getBlockchain(),
              context.getWorldStateArchive(),
              protocolSchedule,
              transactionPool,
              miningCoordinator,
              synchronizer);
      final GraphQL graphQL;
      try {
        graphQL = GraphQLProvider.buildGraphQL(fetchers);
      } catch (final IOException ioe) {
        throw new RuntimeException(ioe);
      }

      graphQLHttpService =
          Optional.of(
              new GraphQLHttpService(
                  vertx, dataDir, graphQLConfiguration, graphQL, dataFetcherContext));
    }

    Optional<WebSocketService> webSocketService = Optional.empty();
    if (webSocketConfiguration.isEnabled()) {
      final Map<String, JsonRpcMethod> webSocketsJsonRpcMethods =
          jsonRpcMethods(
              context,
              protocolSchedule,
              pantheonController,
              peerNetwork,
              synchronizer,
              transactionPool,
              miningCoordinator,
              metricsSystem,
              supportedCapabilities,
              webSocketConfiguration.getRpcApis(),
              filterManager,
              accountLocalConfigPermissioningController,
              nodeLocalConfigPermissioningController,
              privacyParameters,
              jsonRpcConfiguration,
              webSocketConfiguration,
              metricsConfiguration);

      final SubscriptionManager subscriptionManager =
          createSubscriptionManager(vertx, transactionPool);

      createLogsSubscriptionService(
          context.getBlockchain(), context.getWorldStateArchive(), subscriptionManager);

      createNewBlockHeadersSubscriptionService(
          context.getBlockchain(), context.getWorldStateArchive(), subscriptionManager);

      createSyncingSubscriptionService(synchronizer, subscriptionManager);

      webSocketService =
          Optional.of(
              createWebsocketService(
                  vertx, webSocketConfiguration, subscriptionManager, webSocketsJsonRpcMethods));
    }

    Optional<MetricsService> metricsService = Optional.empty();
    if (metricsConfiguration.isEnabled() || metricsConfiguration.isPushEnabled()) {
      metricsService = Optional.of(createMetricsService(vertx, metricsConfiguration));
    }

    return new Runner(
        vertx,
        networkRunner,
        jsonRpcHttpService,
        graphQLHttpService,
        webSocketService,
        metricsService,
        pantheonController,
        dataDir);
  }

  private Optional<NodePermissioningController> buildNodePermissioningController(
      final List<EnodeURL> bootnodesAsEnodeURLs,
      final Synchronizer synchronizer,
      final TransactionSimulator transactionSimulator,
      final BytesValue localNodeId) {
    final Collection<EnodeURL> fixedNodes = getFixedNodes(bootnodesAsEnodeURLs, staticNodes);
    return permissioningConfiguration.map(
        config ->
            new NodePermissioningControllerFactory()
                .create(
                    config,
                    synchronizer,
                    fixedNodes,
                    localNodeId,
                    transactionSimulator,
                    metricsSystem));
  }

  private Optional<AccountPermissioningController> buildAccountPermissioningController(
      final Optional<PermissioningConfiguration> permissioningConfiguration,
      final PantheonController<?> pantheonController,
      final TransactionSimulator transactionSimulator) {

    Optional<AccountLocalConfigPermissioningController> accountLocalConfigPermissioningController =
        Optional.empty();
    Optional<TransactionSmartContractPermissioningController>
        transactionSmartContractPermissioningController = Optional.empty();
    Optional<AccountPermissioningController> accountPermissioningController = Optional.empty();

    if (permissioningConfiguration.isPresent()) {
      final PermissioningConfiguration config = permissioningConfiguration.get();
      if (config.getLocalConfig().isPresent()) {
        final LocalPermissioningConfiguration localPermissioningConfiguration =
            config.getLocalConfig().get();

        if (localPermissioningConfiguration.isAccountWhitelistEnabled()) {
          accountLocalConfigPermissioningController =
              Optional.of(
                  new AccountLocalConfigPermissioningController(
                      localPermissioningConfiguration, metricsSystem));
        }
      }

      if (config.getSmartContractConfig().isPresent()) {
        final SmartContractPermissioningConfiguration smartContractPermissioningConfiguration =
            config.getSmartContractConfig().get();

        if (smartContractPermissioningConfiguration.isSmartContractAccountWhitelistEnabled()) {
          final Address accountSmartContractAddress =
              smartContractPermissioningConfiguration.getAccountSmartContractAddress();

          transactionSmartContractPermissioningController =
              Optional.of(
                  new TransactionSmartContractPermissioningController(
                      accountSmartContractAddress, transactionSimulator, metricsSystem));
        }
      }

      if (accountLocalConfigPermissioningController.isPresent()
          || transactionSmartContractPermissioningController.isPresent()) {
        final AccountPermissioningController controller =
            new AccountPermissioningController(
                accountLocalConfigPermissioningController,
                transactionSmartContractPermissioningController);

        pantheonController.getProtocolSchedule().setTransactionFilter(controller::isPermitted);

        accountPermissioningController = Optional.of(controller);
      }
    }

    return accountPermissioningController;
  }

  @VisibleForTesting
  public static Collection<EnodeURL> getFixedNodes(
      final Collection<EnodeURL> someFixedNodes, final Collection<EnodeURL> moreFixedNodes) {
    final Collection<EnodeURL> fixedNodes = new ArrayList<>(someFixedNodes);
    fixedNodes.addAll(moreFixedNodes);
    return fixedNodes;
  }

  private FilterManager createFilterManager(
      final Vertx vertx, final ProtocolContext<?> context, final TransactionPool transactionPool) {
    final FilterManager filterManager =
        new FilterManager(
            new BlockchainQueries(context.getBlockchain(), context.getWorldStateArchive()),
            transactionPool,
            new FilterIdGenerator(),
            new FilterRepository());
    vertx.deployVerticle(filterManager);
    return filterManager;
  }

  private Map<String, JsonRpcMethod> jsonRpcMethods(
      final ProtocolContext<?> context,
      final ProtocolSchedule<?> protocolSchedule,
      final PantheonController<?> pantheonController,
      final P2PNetwork network,
      final Synchronizer synchronizer,
      final TransactionPool transactionPool,
      final MiningCoordinator miningCoordinator,
      final MetricsSystem metricsSystem,
      final Set<Capability> supportedCapabilities,
      final Collection<RpcApi> jsonRpcApis,
      final FilterManager filterManager,
      final Optional<AccountLocalConfigPermissioningController> accountWhitelistController,
      final Optional<NodeLocalConfigPermissioningController> nodeWhitelistController,
      final PrivacyParameters privacyParameters,
      final JsonRpcConfiguration jsonRpcConfiguration,
      final WebSocketConfiguration webSocketConfiguration,
      final MetricsConfiguration metricsConfiguration) {
    final Map<String, JsonRpcMethod> methods =
        new JsonRpcMethodsFactory()
            .methods(
                PantheonInfo.version(),
                ethNetworkConfig.getNetworkId(),
                pantheonController.getGenesisConfigOptions(),
                network,
                context.getBlockchain(),
                context.getWorldStateArchive(),
                synchronizer,
                transactionPool,
                protocolSchedule,
                miningCoordinator,
                metricsSystem,
                supportedCapabilities,
                jsonRpcApis,
                filterManager,
                accountWhitelistController,
                nodeWhitelistController,
                privacyParameters,
                jsonRpcConfiguration,
                webSocketConfiguration,
                metricsConfiguration);
    methods.putAll(pantheonController.getAdditionalJsonRpcMethods(jsonRpcApis));
    return methods;
  }

  private SubscriptionManager createSubscriptionManager(
      final Vertx vertx, final TransactionPool transactionPool) {
    final SubscriptionManager subscriptionManager = new SubscriptionManager(metricsSystem);
    final PendingTransactionSubscriptionService pendingTransactions =
        new PendingTransactionSubscriptionService(subscriptionManager);
    final PendingTransactionDroppedSubscriptionService pendingTransactionsRemoved =
        new PendingTransactionDroppedSubscriptionService(subscriptionManager);
    transactionPool.addTransactionListener(pendingTransactions);
    transactionPool.addTransactionDroppedListener(pendingTransactionsRemoved);
    vertx.deployVerticle(subscriptionManager);

    return subscriptionManager;
  }

  private void createLogsSubscriptionService(
      final Blockchain blockchain,
      final WorldStateArchive worldStateArchive,
      final SubscriptionManager subscriptionManager) {
    final LogsSubscriptionService logsSubscriptionService =
        new LogsSubscriptionService(
            subscriptionManager, new BlockchainQueries(blockchain, worldStateArchive));

    blockchain.observeBlockAdded(logsSubscriptionService);
  }

  private void createSyncingSubscriptionService(
      final Synchronizer synchronizer, final SubscriptionManager subscriptionManager) {
    new SyncingSubscriptionService(subscriptionManager, synchronizer);
  }

  private void createNewBlockHeadersSubscriptionService(
      final Blockchain blockchain,
      final WorldStateArchive worldStateArchive,
      final SubscriptionManager subscriptionManager) {
    final NewBlockHeadersSubscriptionService newBlockHeadersSubscriptionService =
        new NewBlockHeadersSubscriptionService(
            subscriptionManager, new BlockchainQueries(blockchain, worldStateArchive));

    blockchain.observeBlockAdded(newBlockHeadersSubscriptionService);
  }

  private WebSocketService createWebsocketService(
      final Vertx vertx,
      final WebSocketConfiguration configuration,
      final SubscriptionManager subscriptionManager,
      final Map<String, JsonRpcMethod> jsonRpcMethods) {
    final WebSocketMethodsFactory websocketMethodsFactory =
        new WebSocketMethodsFactory(subscriptionManager, jsonRpcMethods);
    final WebSocketRequestHandler websocketRequestHandler =
        new WebSocketRequestHandler(vertx, websocketMethodsFactory.methods());

    return new WebSocketService(vertx, configuration, websocketRequestHandler);
  }

  private MetricsService createMetricsService(
      final Vertx vertx, final MetricsConfiguration configuration) {
    return MetricsService.create(vertx, configuration, metricsSystem);
  }
}
