package com.fabahaba.jedipus.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.impl.DefaultEvictionPolicy;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import com.fabahaba.jedipus.IJedis;
import com.fabahaba.jedipus.primitive.JedisFactory;

import redis.clients.jedis.exceptions.JedisAskDataException;
import redis.clients.jedis.exceptions.JedisClusterMaxRedirectionsException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisRedirectionException;

public final class Jedipus implements JedisClusterExecutor {

  private static final int DEFAULT_MAX_REDIRECTIONS = 2;
  private static final int DEFAULT_MAX_RETRIES = 2;
  private static final ElementRetryDelay<ClusterNode> DEFAULT_RETRY_DELAY =
      ElementRetryDelay.startBuilding().create();

  private static final int DEFAULT_TRY_RANDOM_AFTER = 1;

  private static final Duration DEFAULT_DURATION_BETWEEN_CACHE_REFRESH = Duration.ofMillis(20);
  // 0 blocks forever, timed out request with retry or throw a JedisConnectionException if no pools
  // are available.
  private static final Duration DEFAULT_MAX_AWAIT_CACHE_REFRESH = Duration.ofNanos(0);

  private static final GenericObjectPoolConfig DEFAULT_POOL_CONFIG = new GenericObjectPoolConfig();

  static {
    DEFAULT_POOL_CONFIG.setMaxIdle(2);
    DEFAULT_POOL_CONFIG.setMaxTotal(GenericObjectPoolConfig.DEFAULT_MAX_TOTAL); // 8

    DEFAULT_POOL_CONFIG.setMinEvictableIdleTimeMillis(30000);
    DEFAULT_POOL_CONFIG.setTimeBetweenEvictionRunsMillis(15000);
    DEFAULT_POOL_CONFIG.setEvictionPolicyClassName(DefaultEvictionPolicy.class.getName());

    DEFAULT_POOL_CONFIG.setTestWhileIdle(true);
    // test all idle
    DEFAULT_POOL_CONFIG.setNumTestsPerEvictionRun(DEFAULT_POOL_CONFIG.getMaxTotal());

    // block forever
    DEFAULT_POOL_CONFIG.setBlockWhenExhausted(true);
    DEFAULT_POOL_CONFIG.setMaxWaitMillis(GenericObjectPoolConfig.DEFAULT_MAX_WAIT_MILLIS);
  }

  private static final JedisFactory.Builder DEFAULT_JEDIS_FACTORY = JedisFactory.startBuilding();

  private static final Function<ClusterNode, ObjectPool<IJedis>> DEFAULT_MASTER_POOL_FACTORY =
      node -> new GenericObjectPool<>(DEFAULT_JEDIS_FACTORY.createPooled(node),
          DEFAULT_POOL_CONFIG);

  private static final Function<ClusterNode, ObjectPool<IJedis>> DEFAULT_SLAVE_POOL_FACTORY =
      node -> new GenericObjectPool<>(DEFAULT_JEDIS_FACTORY.createPooled(node, true),
          DEFAULT_POOL_CONFIG);

  private static final Function<ClusterNode, IJedis> DEFAULT_JEDIS_ASK_DISCOVERY_FACTORY =
      DEFAULT_JEDIS_FACTORY::create;

  private static final BiFunction<ReadMode, ObjectPool<IJedis>[], LoadBalancedPools> DEFAULT_LB_FACTORIES =
      (defaultReadMode, slavePools) -> {

        if (slavePools.length == 0) {
          // will fall back to master pool
          return rm -> null;
        }

        switch (defaultReadMode) {
          case MASTER:
            // will never reach here.
            return null;
          case SLAVES:

            if (slavePools.length == 1) {

              final ObjectPool<IJedis> pool = slavePools[0];

              return rm -> pool;
            }

            return new RoundRobinPools(slavePools);
          case MIXED_SLAVES:

            if (slavePools.length == 1) {

              final ObjectPool<IJedis> pool = slavePools[0];

              return rm -> {
                switch (rm) {
                  case MASTER:
                    // will fall back to master pool
                    return null;
                  case MIXED:
                    // ignore request to lb across master. Should use MIXED as default instead.
                  case MIXED_SLAVES:
                  case SLAVES:
                  default:
                    return pool;
                }
              };
            }

            return new RoundRobinPools(slavePools);
          case MIXED:
          default:
            return new RoundRobinPools(slavePools);
        }
      };

  private final int maxRedirections;
  private final int maxRetries;
  private final int tryRandomAfter;

  private final JedisClusterConnHandler connHandler;

  private Jedipus(final ReadMode defaultReadMode, final Collection<ClusterNode> discoveryNodes,
      final int maxRedirections, final int maxRetries, final int tryRandomAfter,
      final ElementRetryDelay<ClusterNode> clusterNodeRetryDelay, final boolean optimisticReads,
      final Duration durationBetweenCacheRefresh, final Duration maxAwaitCacheRefresh,
      final Function<ClusterNode, ObjectPool<IJedis>> masterPoolFactory,
      final Function<ClusterNode, ObjectPool<IJedis>> slavePoolFactory,
      final Function<ClusterNode, IJedis> nodeUnknownFactory,
      final Function<ObjectPool<IJedis>[], LoadBalancedPools> lbFactory) {

    this.connHandler = new JedisClusterConnHandler(defaultReadMode, optimisticReads,
        durationBetweenCacheRefresh, maxAwaitCacheRefresh, discoveryNodes, masterPoolFactory,
        slavePoolFactory, nodeUnknownFactory, lbFactory, clusterNodeRetryDelay);

    this.maxRedirections = maxRedirections;
    this.maxRetries = maxRetries;
    this.tryRandomAfter = tryRandomAfter;
  }

  @Override
  public ReadMode getDefaultReadMode() {

    return connHandler.getDefaultReadMode();
  }

  @Override
  public int getMaxRedirections() {

    return maxRedirections;
  }

  @Override
  public int getMaxRetries() {

    return maxRetries;
  }

  @Override
  public <R> R applyJedis(final ReadMode readMode, final int slot,
      final Function<IJedis, R> jedisConsumer, final int maxRetries) {

    ClusterNode askNode = null;

    long retries = 0;

    // Optimistic first try
    ObjectPool<IJedis> pool = null;
    IJedis jedis = null;
    try {

      pool = connHandler.getSlotPool(readMode, slot);
      jedis = JedisPool.borrowObject(pool);
      final R result = jedisConsumer.apply(jedis);
      connHandler.getClusterNodeRetryDelay().markSuccess(jedis.getClusterNode(), retries);
      return result;
    } catch (final JedisConnectionException jce) {

      retries = connHandler.getClusterNodeRetryDelay()
          .markFailure(jedis == null ? null : jedis.getClusterNode(), maxRetries, jce, 0);
    } catch (final JedisAskDataException askEx) {

      try {
        JedisPool.returnJedis(pool, jedis);
      } finally {
        jedis = null;
      }

      askNode = ClusterNode.create(askEx.getTargetNode());
    } catch (final JedisRedirectionException moveEx) {

      if (jedis == null) {
        connHandler.renewSlotCache(readMode);
      } else {
        connHandler.renewSlotCache(readMode, jedis);
      }
    } finally {
      JedisPool.returnJedis(pool, jedis);
      pool = null;
    }

    for (int redirections = retries == 0 && askNode == null ? 1 : 0;;) {

      ObjectPool<IJedis> clientPool = null;
      IJedis client = null;
      try {

        if (askNode == null) {

          clientPool = retries > tryRandomAfter ? connHandler.getRandomPool(readMode)
              : connHandler.getSlotPool(readMode, slot);
          client = JedisPool.borrowObject(clientPool);

          final R result = jedisConsumer.apply(client);
          connHandler.getClusterNodeRetryDelay().markSuccess(client.getClusterNode(), retries);
          return result;
        }

        clientPool = connHandler.getAskPool(askNode);
        client = JedisPool.borrowObject(clientPool);
        client.asking();
        final R result = jedisConsumer.apply(client);
        connHandler.getClusterNodeRetryDelay().markSuccess(client.getClusterNode(), 0);
        return result;
      } catch (final JedisConnectionException jce) {

        retries = connHandler.getClusterNodeRetryDelay()
            .markFailure(client == null ? null : client.getClusterNode(), maxRetries, jce, retries);
        continue;
      } catch (final JedisAskDataException askEx) {

        try {
          JedisPool.returnJedis(clientPool, client);
        } finally {
          client = null;
        }

        askNode = ClusterNode.create(askEx.getTargetNode());
        continue;
      } catch (final JedisRedirectionException moveEx) {

        if (++redirections > maxRedirections) {
          throw new JedisClusterMaxRedirectionsException(moveEx);
        }

        askNode = null;

        if (client == null) {
          connHandler.renewSlotCache(readMode);
        } else {
          connHandler.renewSlotCache(readMode, client);
        }
        continue;
      } finally {
        JedisPool.returnJedis(clientPool, client);
      }
    }
  }

  @Override
  public <R> R applyNodeIfPresent(final ClusterNode node, final Function<IJedis, R> jedisConsumer,
      final int maxRetries) {

    for (long retries = 0;;) {

      ObjectPool<IJedis> pool = connHandler.getPoolIfPresent(node);
      if (pool == null) {

        connHandler.renewSlotCache(getDefaultReadMode());
        pool = connHandler.getPoolIfPresent(node);
        if (pool == null) {
          return null;
        }
      }

      IJedis jedis = null;
      try {
        jedis = JedisPool.borrowObject(pool);

        final R result = jedisConsumer.apply(jedis);
        connHandler.getClusterNodeRetryDelay().markSuccess(jedis.getClusterNode(), retries);
        return result;
      } catch (final JedisConnectionException jce) {

        retries = connHandler.getClusterNodeRetryDelay()
            .markFailure(jedis == null ? node : jedis.getClusterNode(), maxRetries, jce, retries);
        continue;
      } finally {
        JedisPool.returnJedis(pool, jedis);
      }
    }
  }

  @Override
  public <R> R applyUnknownNode(final ClusterNode node, final Function<IJedis, R> jedisConsumer,
      final int maxRetries) {

    for (long retries = 0;;) {

      try (IJedis jedis = connHandler.createUnknownNode(node)) {

        final R result = jedisConsumer.apply(jedis);
        connHandler.getClusterNodeRetryDelay().markSuccess(node, retries);
        return result;
      } catch (final JedisConnectionException jce) {

        retries =
            connHandler.getClusterNodeRetryDelay().markFailure(node, maxRetries, jce, retries);
        continue;
      }
    }
  }

  @Override
  public <R> List<CompletableFuture<R>> applyAllMasters(final Function<IJedis, R> jedisConsumer,
      final int maxRetries, final ExecutorService executor) {

    return applyAll(connHandler.getMasterPools(), jedisConsumer, maxRetries, executor);
  }

  @Override
  public <R> List<CompletableFuture<R>> applyAllSlaves(final Function<IJedis, R> jedisConsumer,
      final int maxRetries, final ExecutorService executor) {

    return applyAll(connHandler.getSlavePools(), jedisConsumer, maxRetries, executor);
  }

  @Override
  public <R> List<CompletableFuture<R>> applyAll(final Function<IJedis, R> jedisConsumer,
      final int maxRetries, final ExecutorService executor) {

    return applyAll(connHandler.getAllPools(), jedisConsumer, maxRetries, executor);
  }

  private <R> List<CompletableFuture<R>> applyAll(final Map<ClusterNode, ObjectPool<IJedis>> pools,
      final Function<IJedis, R> jedisConsumer, final int maxRetries,
      final ExecutorService executor) {

    if (executor == null) {
      pools.forEach((node, pool) -> acceptPool(node, pool, jedisConsumer, maxRetries));

      return Collections.emptyList();
    }

    final List<CompletableFuture<R>> futures = new ArrayList<>(pools.size());

    pools.forEach((node, pool) -> {
      futures.add(CompletableFuture
          .supplyAsync(() -> acceptPool(node, pool, jedisConsumer, maxRetries), executor));
    });

    return futures;
  }

  private <R> R acceptPool(final ClusterNode node, final ObjectPool<IJedis> pool,
      final Function<IJedis, R> jedisConsumer, final int maxRetries) {

    for (long retries = 0;;) {

      IJedis jedis = null;
      try {
        jedis = JedisPool.borrowObject(pool);

        final R result = jedisConsumer.apply(jedis);
        connHandler.getClusterNodeRetryDelay().markSuccess(jedis.getClusterNode(), retries);
        return result;
      } catch (final JedisConnectionException jce) {

        retries = connHandler.getClusterNodeRetryDelay()
            .markFailure(jedis == null ? node : jedis.getClusterNode(), maxRetries, jce, retries);
        continue;
      } finally {
        JedisPool.returnJedis(pool, jedis);
      }
    }
  }

  @Override
  public void refreshSlotCache() {

    connHandler.renewSlotCache(getDefaultReadMode());
  }

  @Override
  public void close() {

    connHandler.close();
  }

  public static final class Builder {

    private ReadMode defaultReadMode = ReadMode.MASTER;
    private Collection<ClusterNode> discoveryNodes;
    private int maxRedirections = DEFAULT_MAX_REDIRECTIONS;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private ElementRetryDelay<ClusterNode> clusterNodeRetryDelay = DEFAULT_RETRY_DELAY;
    private int tryRandomAfter = DEFAULT_TRY_RANDOM_AFTER;
    private GenericObjectPoolConfig poolConfig = DEFAULT_POOL_CONFIG;
    private Function<ClusterNode, ObjectPool<IJedis>> masterPoolFactory =
        DEFAULT_MASTER_POOL_FACTORY;
    private Function<ClusterNode, ObjectPool<IJedis>> slavePoolFactory = DEFAULT_SLAVE_POOL_FACTORY;
    // Used for ASK requests if no pool exists and random cluster discovery.
    private Function<ClusterNode, IJedis> nodeUnknownFactory = DEFAULT_JEDIS_ASK_DISCOVERY_FACTORY;
    private BiFunction<ReadMode, ObjectPool<IJedis>[], LoadBalancedPools> lbFactory =
        DEFAULT_LB_FACTORIES;
    // If true, access to slot pool cache will not lock when retreiving a pool/client during a slot
    // re-configuration.
    private boolean optimisticReads = true;
    private Duration durationBetweenCacheRefresh = DEFAULT_DURATION_BETWEEN_CACHE_REFRESH;
    // 0 blocks forever, timed out request with retry or throw a JedisConnectionException if no
    // pools are available.
    private Duration maxAwaitCacheRefresh = DEFAULT_MAX_AWAIT_CACHE_REFRESH;

    Builder(final Collection<ClusterNode> discoveryNodes) {

      this.discoveryNodes = discoveryNodes;
    }

    public JedisClusterExecutor create() {

      return new Jedipus(defaultReadMode, discoveryNodes, maxRedirections, maxRetries,
          tryRandomAfter, clusterNodeRetryDelay, optimisticReads, durationBetweenCacheRefresh,
          maxAwaitCacheRefresh, masterPoolFactory, slavePoolFactory, nodeUnknownFactory,
          slavePools -> lbFactory.apply(defaultReadMode, slavePools));
    }

    public ReadMode getReadMode() {
      return defaultReadMode;
    }

    public Builder withReadMode(final ReadMode defaultReadMode) {
      this.defaultReadMode = defaultReadMode;
      return this;
    }

    public Collection<ClusterNode> getDiscoveryNodes() {
      return discoveryNodes;
    }

    public Builder withDiscoveryNodes(final Collection<ClusterNode> discoveryNodes) {
      this.discoveryNodes = discoveryNodes;
      return this;
    }

    public int getMaxRedirections() {
      return maxRedirections;
    }

    public Builder withMaxRedirections(final int maxRedirections) {
      this.maxRedirections = maxRedirections;
      return this;
    }

    public int getMaxRetries() {
      return maxRetries;
    }

    public Builder withMaxRetries(final int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    public int getTryRandomAfter() {
      return tryRandomAfter;
    }

    public Builder withTryRandomAfter(final int tryRandomAfter) {
      this.tryRandomAfter = tryRandomAfter;
      return this;
    }

    public ElementRetryDelay<ClusterNode> getHostPortRetryDelay() {
      return clusterNodeRetryDelay;
    }

    public Builder withHostPortRetryDelay(final ElementRetryDelay<ClusterNode> hostPortRetryDelay) {
      this.clusterNodeRetryDelay = hostPortRetryDelay;
      return this;
    }

    public boolean isOptimisticReads() {
      return optimisticReads;
    }

    public Builder withOptimisticReads(final boolean optimisticReads) {
      this.optimisticReads = optimisticReads;
      return this;
    }

    public Duration getDurationBetweenCacheRefresh() {
      return durationBetweenCacheRefresh;
    }

    public Builder withDurationBetweenCacheRefresh(final Duration durationBetweenCacheRefresh) {
      this.durationBetweenCacheRefresh = durationBetweenCacheRefresh;
      return this;
    }

    public Duration getMaxAwaitCacheRefresh() {
      return maxAwaitCacheRefresh;
    }

    public Builder withMaxAwaitCacheRefresh(final Duration maxAwaitCacheRefresh) {
      this.maxAwaitCacheRefresh = maxAwaitCacheRefresh;
      return this;
    }

    public GenericObjectPoolConfig getPoolConfig() {
      return poolConfig;
    }

    public Builder withPoolConfig(final GenericObjectPoolConfig poolConfig) {
      this.poolConfig = poolConfig;
      return this;
    }

    public Function<ClusterNode, ObjectPool<IJedis>> getMasterPoolFactory() {
      return masterPoolFactory;
    }

    public Builder withMasterPoolFactory(
        final Function<ClusterNode, ObjectPool<IJedis>> masterPoolFactory) {
      this.masterPoolFactory = masterPoolFactory;
      return this;
    }

    public Function<ClusterNode, ObjectPool<IJedis>> getSlavePoolFactory() {
      return slavePoolFactory;
    }

    public Builder withSlavePoolFactory(
        final Function<ClusterNode, ObjectPool<IJedis>> slavePoolFactory) {
      this.slavePoolFactory = slavePoolFactory;
      return this;
    }

    public Function<ClusterNode, IJedis> getNodeUnknownFactory() {
      return nodeUnknownFactory;
    }

    public Builder withNodeUnknownFactory(final Function<ClusterNode, IJedis> nodeUnknownFactory) {
      this.nodeUnknownFactory = nodeUnknownFactory;
      return this;
    }

    public BiFunction<ReadMode, ObjectPool<IJedis>[], LoadBalancedPools> getLbFactory() {
      return lbFactory;
    }

    public Builder withLbFactory(
        final BiFunction<ReadMode, ObjectPool<IJedis>[], LoadBalancedPools> lbFactory) {
      this.lbFactory = lbFactory;
      return this;
    }

    @Override
    public String toString() {

      return new StringBuilder("JedisClusterExecutor.Builder [defaultReadMode=")
          .append(defaultReadMode).append(", discoveryNodes=").append(discoveryNodes)
          .append(", maxRedirections=").append(maxRedirections).append(", maxRetries=")
          .append(maxRetries).append(", tryRandomAfter=").append(tryRandomAfter)
          .append(", clusterNodeRetryDelay=").append(clusterNodeRetryDelay).append(", poolConfig=")
          .append(poolConfig).append(", masterPoolFactory=").append(masterPoolFactory)
          .append(", slavePoolFactory=").append(slavePoolFactory)
          .append(", jedisAskDiscoveryFactory=").append(nodeUnknownFactory).append(", lbFactory=")
          .append(lbFactory).append(", optimisticReads=").append(optimisticReads)
          .append(", durationBetweenCacheRefresh=").append(durationBetweenCacheRefresh)
          .append(", maxAwaitCacheRefresh=").append(maxAwaitCacheRefresh).append("]").toString();
    }
  }
}
