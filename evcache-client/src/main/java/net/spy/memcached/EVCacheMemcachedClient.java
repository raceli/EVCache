package net.spy.memcached;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.config.ChainedDynamicProperty;
import com.netflix.config.DynamicLongProperty;
import com.netflix.evcache.EVCacheGetOperationListener;
import com.netflix.evcache.EVCacheLatch;
import com.netflix.evcache.metrics.EVCacheMetricsFactory;
import com.netflix.evcache.operation.EVCacheBulkGetFuture;
import com.netflix.evcache.operation.EVCacheLatchImpl;
import com.netflix.evcache.operation.EVCacheOperationFuture;
import com.netflix.evcache.pool.EVCacheClient;
import com.netflix.evcache.util.EVCacheConfig;
import com.netflix.spectator.api.DistributionSummary;
import com.netflix.spectator.api.Timer;

import net.spy.memcached.internal.GetFuture;
import net.spy.memcached.internal.OperationFuture;
import net.spy.memcached.ops.ConcatenationType;
import net.spy.memcached.ops.DeleteOperation;
import net.spy.memcached.ops.GetAndTouchOperation;
import net.spy.memcached.ops.GetOperation;
import net.spy.memcached.ops.Mutator;
import net.spy.memcached.ops.Operation;
import net.spy.memcached.ops.OperationCallback;
import net.spy.memcached.ops.OperationStatus;
import net.spy.memcached.ops.StatusCode;
import net.spy.memcached.ops.StoreOperation;
import net.spy.memcached.ops.StoreType;
import net.spy.memcached.protocol.binary.BinaryOperationFactory;
import net.spy.memcached.protocol.binary.EVCacheNodeImpl;
import net.spy.memcached.transcoders.Transcoder;
import net.spy.memcached.util.StringUtils;

@edu.umd.cs.findbugs.annotations.SuppressFBWarnings({ "PRMC_POSSIBLY_REDUNDANT_METHOD_CALLS",
"SIC_INNER_SHOULD_BE_STATIC_ANON" })
public class EVCacheMemcachedClient extends MemcachedClient {

    private static final Logger log = LoggerFactory.getLogger(EVCacheMemcachedClient.class);
    private final String appName;
    private final ChainedDynamicProperty.IntProperty readTimeout;
    private final EVCacheClient client;
    private DistributionSummary getDataSize, bulkDataSize, getAndTouchDataSize;
    private DynamicLongProperty mutateOperationTimeout;

    public EVCacheMemcachedClient(ConnectionFactory cf, List<InetSocketAddress> addrs,
            ChainedDynamicProperty.IntProperty readTimeout, EVCacheClient client) throws IOException {
        super(cf, addrs);
        this.readTimeout = readTimeout;
        this.client = client;
        this.appName = client.getAppName();
    }

    public NodeLocator getNodeLocator() {
        return this.mconn.getLocator();
    }

    public MemcachedNode getEVCacheNode(String key) {
        return this.mconn.getLocator().getPrimary(key);
    }

    public <T> GetFuture<T> asyncGet(final String key, final Transcoder<T> tc) {
        throw new UnsupportedOperationException("asyncGet");
    }

    public <T> EVCacheOperationFuture<T> asyncGet(final String key, final Transcoder<T> tc, EVCacheGetOperationListener<T> listener) {
        final CountDownLatch latch = new CountDownLatch(1);
        final EVCacheOperationFuture<T> rv = new EVCacheOperationFuture<T>(key, latch, new AtomicReference<T>(null), readTimeout.get().intValue(), executorService, client, "GetOperation");
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-GetOperation", client.getTagList());
        Operation op = opFact.get(key, new GetOperation.Callback() {
            private Future<T> val = null;

            public void receivedStatus(OperationStatus status) {
                operationDuration.record(System.currentTimeMillis() - rv.getStartTime(), TimeUnit.MILLISECONDS);

                try {
                    if (val != null) {
                        rv.set(val.get(), status);
                    } else {
                        rv.set(null, status);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    rv.set(null, status);
                }
            }

            @SuppressWarnings("unchecked")
            public void gotData(String k, int flags, byte[] data) {

                if (data != null)  {
                    if(getDataSize == null) getDataSize = EVCacheMetricsFactory.getInstance().getDistributionSummary(appName + "-GetOperation-DataSize", client.getTagList());
                    if (getDataSize != null) getDataSize.record(data.length);
                }
                if (!key.equals(k)) log.warn("Wrong key returned. Key - " + key + "; Returned Key " + k);
                if (tc == null) {
                    if (tcService == null) {
                        log.error("tcService is null, will not be able to decode");
                        throw new RuntimeException("TranscoderSevice is null. Not able to decode");
                    } else {
                        final Transcoder<T> t = (Transcoder<T>) getTranscoder();
                        val = tcService.decode(t, new CachedData(flags, data, t.getMaxSize()));
                    }
                } else {
                    if (tcService == null) {
                        log.error("tcService is null, will not be able to decode");
                        throw new RuntimeException("TranscoderSevice is null. Not able to decode");
                    } else {
                        val = tcService.decode(tc, new CachedData(flags, data, tc.getMaxSize()));
                    }
                }
            }

            public void complete() {
                latch.countDown();
                rv.signalComplete();
            }
        });
        rv.setOperation(op);
        if (listener != null) rv.addListener(listener);
        mconn.enqueueOperation(key, op);
        return rv;
    }

    public <T> EVCacheBulkGetFuture<T> asyncGetBulk(Collection<String> keys, final Transcoder<T> tc, EVCacheGetOperationListener<T> listener, String metricName) {
        final Map<String, Future<T>> m = new ConcurrentHashMap<String, Future<T>>();

        // Break the gets down into groups by key
        final Map<MemcachedNode, Collection<String>> chunks = new HashMap<MemcachedNode, Collection<String>>();
        final NodeLocator locator = mconn.getLocator();

        final Iterator<String> keyIter = keys.iterator();
        while (keyIter.hasNext()) {
            final String key = keyIter.next();
            StringUtils.validateKey(key, opFact instanceof BinaryOperationFactory);
            final MemcachedNode primaryNode = locator.getPrimary(key);
            if (primaryNode.isActive()) {
                Collection<String> ks = chunks.get(primaryNode);
                if (ks == null) {
                    ks = new ArrayList<String>();
                    chunks.put(primaryNode, ks);
                }
                ks.add(key);
            }
        }

        final AtomicInteger pendingChunks = new AtomicInteger(chunks.size());
        int initialLatchCount = chunks.isEmpty() ? 0 : 1;
        final CountDownLatch latch = new CountDownLatch(initialLatchCount);
        final Collection<Operation> ops = new ArrayList<Operation>(chunks.size());
        final EVCacheBulkGetFuture<T> rv = new EVCacheBulkGetFuture<T>(m, ops, latch, executorService, client, metricName);
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-BulkOperation", client.getTagList()); 
        GetOperation.Callback cb = new GetOperation.Callback() {
            @Override
            @SuppressWarnings("synthetic-access")
            public void receivedStatus(OperationStatus status) {
                operationDuration.record(System.currentTimeMillis() - rv.getStartTime(), TimeUnit.MILLISECONDS);
                rv.setStatus(status);
            }

            @Override
            public void gotData(String k, int flags, byte[] data) {
                if (data != null)  {
                    if(bulkDataSize == null) bulkDataSize = EVCacheMetricsFactory.getInstance().getDistributionSummary(appName + "-BulkOperation-DataSize", client.getTagList());
                    if (bulkDataSize != null) bulkDataSize.record(data.length);
                }

                m.put(k, tcService.decode(tc, new CachedData(flags, data, tc.getMaxSize())));
            }

            @Override
            public void complete() {
                if (pendingChunks.decrementAndGet() <= 0) {
                    latch.countDown();
                    rv.signalComplete();
                }
            }
        };

        // Now that we know how many servers it breaks down into, and the latch
        // is all set up, convert all of these strings collections to operations
        final Map<MemcachedNode, Operation> mops = new HashMap<MemcachedNode, Operation>();

        for (Map.Entry<MemcachedNode, Collection<String>> me : chunks.entrySet()) {
            Operation op = opFact.get(me.getValue(), cb);
            mops.put(me.getKey(), op);
            ops.add(op);
        }
        assert mops.size() == chunks.size();
        mconn.checkState();
        mconn.addOperations(mops);
        return rv;
    }

    public <T> EVCacheOperationFuture<CASValue<T>> asyncGetAndTouch(final String key, final int exp, final Transcoder<T> tc) {
        final CountDownLatch latch = new CountDownLatch(1);
        final EVCacheOperationFuture<CASValue<T>> rv = new EVCacheOperationFuture<CASValue<T>>(key, latch, new AtomicReference<CASValue<T>>(null), operationTimeout, executorService, client, "GetAndTouchOperation");
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-GetAndTouchOperation", client.getTagList());
        Operation op = opFact.getAndTouch(key, exp, new GetAndTouchOperation.Callback() {
            private CASValue<T> val = null;

            public void receivedStatus(OperationStatus status) {
                operationDuration.record(System.currentTimeMillis() - rv.getStartTime(), TimeUnit.MILLISECONDS);
                rv.set(val, status);
            }

            public void complete() {
                latch.countDown();
                rv.signalComplete();
            }

            public void gotData(String k, int flags, long cas, byte[] data) {
                if (!key.equals(k)) log.warn("Wrong key returned. Key - " + key + "; Returned Key " + k);
                if (data != null)  {
                    if(getAndTouchDataSize == null) getAndTouchDataSize = EVCacheMetricsFactory.getInstance().getDistributionSummary(appName + "-GATOperation-DataSize", client.getTagList());
                    if (getAndTouchDataSize != null) getAndTouchDataSize.record(data.length);
                }

                val = new CASValue<T>(cas, tc.decode(new CachedData(flags, data, tc.getMaxSize())));
            }
        });
        rv.setOperation(op);
        mconn.enqueueOperation(key, op);
        return rv;
    }

    public <T> OperationFuture<Boolean> set(String key, int exp, T o, final Transcoder<T> tc) {
        return asyncStore(StoreType.set, key, exp, o, tc, null);
    }

    public OperationFuture<Boolean> set(String key, int exp, Object o) {
        return asyncStore(StoreType.set, key, exp, o, transcoder, null);
    }

    @SuppressWarnings("unchecked")
    public <T> OperationFuture<Boolean> set(String key, int exp, T o, final Transcoder<T> tc, EVCacheLatch latch) {
        Transcoder<T> t = (Transcoder<T>) ((tc == null) ? transcoder : tc);
        return asyncStore(StoreType.set, key, exp, o, t, latch);
    }

    @SuppressWarnings("unchecked")
    public <T> OperationFuture<Boolean> replace(String key, int exp, T o, final Transcoder<T> tc, EVCacheLatch latch) {
        Transcoder<T> t = (Transcoder<T>) ((tc == null) ? transcoder : tc);
        return asyncStore(StoreType.replace, key, exp, o, t, latch);
    }

    public <T> OperationFuture<Boolean> add(String key, int exp, T o, Transcoder<T> tc) {
        return asyncStore(StoreType.add, key, exp, o, tc, null);
    }

    public OperationFuture<Boolean> delete(String key, EVCacheLatch evcacheLatch) {
        final CountDownLatch latch = new CountDownLatch(1);
        final OperationFuture<Boolean> rv = new OperationFuture<Boolean>(key, latch, operationTimeout, executorService);
        final long start = System.currentTimeMillis();
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-DeleteOperation", client.getTagList());

        final DeleteOperation.Callback callback = new DeleteOperation.Callback() {
            @Override
            public void receivedStatus(OperationStatus status) {
                operationDuration.record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
                rv.set(Boolean.TRUE, status);
                if (status.getStatusCode().equals(StatusCode.SUCCESS)) {
                    EVCacheMetricsFactory.getInstance().increment(appName + "-DeleteOperation-SUCCESS", client.getTagList());
                } else {
                    EVCacheMetricsFactory.getInstance().increment(appName + "-DeleteOperation-"+ status.getStatusCode().name(), client.getTagList());
                }
            }

            @Override
            public void gotData(long cas) {
                rv.setCas(cas);
            }

            @Override
            public void complete() {
                latch.countDown();
                rv.signalComplete();
            }
        };

        final DeleteOperation op = opFact.delete(key, callback);
        rv.setOperation(op);
        if (evcacheLatch != null && evcacheLatch instanceof EVCacheLatchImpl && !client.isInWriteOnly()) ((EVCacheLatchImpl) evcacheLatch).addFuture(rv);
        mconn.enqueueOperation(key, op);
        return rv;
    }

    public <T> OperationFuture<Boolean> touch(final String key, final int exp, EVCacheLatch evcacheLatch) {
        final CountDownLatch latch = new CountDownLatch(1);
        final OperationFuture<Boolean> rv = new OperationFuture<Boolean>(key, latch, operationTimeout, executorService);
        final long start = System.currentTimeMillis();
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-TouchOperation", client.getTagList());

        Operation op = opFact.touch(key, exp, new OperationCallback() {
            @Override
            public void receivedStatus(OperationStatus status) {
                operationDuration.record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
                rv.set(status.isSuccess(), status);

                if (status.getStatusCode().equals(StatusCode.SUCCESS)) {
                    EVCacheMetricsFactory.getInstance().getCounter(appName + "-TouchOperation-SUCCESS", client.getTagList()).increment();
                } else if (status.getStatusCode().equals(StatusCode.ERR_NOT_FOUND) ) {
                    EVCacheMetricsFactory.getInstance().getCounter(appName + "-TouchOperation-"+status.getStatusCode().name(), client.getTagList()).increment();
                } else {
                    EVCacheMetricsFactory.getInstance().getCounter(appName + "-TouchOperation-"+status.getStatusCode().name(), client.getTagList()).increment();
                }
            }

            @Override
            public void complete() {
                latch.countDown();
                rv.signalComplete();
            }
        });
        rv.setOperation(op);
        if (evcacheLatch != null && evcacheLatch instanceof EVCacheLatchImpl && !client.isInWriteOnly()) ((EVCacheLatchImpl) evcacheLatch).addFuture(rv);
        mconn.enqueueOperation(key, op);
        return rv;
    }


    public <T> OperationFuture<Boolean> asyncAppendOrAdd(final String key, int exp, CachedData co, EVCacheLatch evcacheLatch) {
        final CountDownLatch latch = new CountDownLatch(1);
        final EVCacheOperationFuture<Boolean> rv = new EVCacheOperationFuture<Boolean>(key, latch, new AtomicReference<Boolean>(null), operationTimeout, executorService, client, "LatencyAoA" );
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-AoAOperation", client.getTagList());

        Operation op = opFact.cat(ConcatenationType.append, 0, key, co.getData(),
                new OperationCallback() {
            boolean appendSuccess = false;
            @Override
            public void receivedStatus(OperationStatus val) {
                if (val.getStatusCode().equals(StatusCode.SUCCESS)) {
                    final long duration = System.currentTimeMillis() - rv.getStartTime();
                    operationDuration.record(duration, TimeUnit.MILLISECONDS);
                    if (log.isDebugEnabled()) log.debug("AddOrAppend Key (Append Operation): " + key + "; Status : " + val.getStatusCode().name()
                            + "; Message : " + val.getMessage() + "; Elapsed Time - " + (System.currentTimeMillis() - duration ));

                    EVCacheMetricsFactory.getInstance().getCounter(appName + "-AoA-AppendOperation-SUCCESS", client.getTagList()).increment();
                    rv.set(val.isSuccess(), val);
                    appendSuccess = true;
                } else {
                    EVCacheMetricsFactory.getInstance().getCounter(appName + "-AoA-AppendOperation-FAIL", client.getTagList()).increment();
                    appendSuccess = false;
                }
            }

            @Override
            public void complete() {
                if(appendSuccess)  {
                    latch.countDown();
                    rv.signalComplete();
                } else {
                    Operation op = opFact.store(StoreType.add, key, co.getFlags(), exp, co.getData(), new StoreOperation.Callback() {
                        @Override
                        public void receivedStatus(OperationStatus val) {
                            final long duration = System.currentTimeMillis() - rv.getStartTime();
                            operationDuration.record(duration, TimeUnit.MILLISECONDS);
                            if (log.isDebugEnabled()) log.debug("AddOrAppend Key (Ad Operation): " + key + "; Status : " + val.getStatusCode().name()
                                    + "; Message : " + val.getMessage() + "; Elapsed Time - " + (System.currentTimeMillis() - duration));
                            rv.set(val.isSuccess(), val);
                            if(val.isSuccess()) {
                                appendSuccess = true;
                                EVCacheMetricsFactory.getInstance().getCounter(appName + "-AoA-AddOperation-SUCCESS", client.getTagList()).increment();
                            } else {
                                EVCacheMetricsFactory.getInstance().getCounter(appName + "-AoA-AddOperation-FAIL", client.getTagList()).increment();
                                Operation op = opFact.cat(ConcatenationType.append, 0, key, co.getData(),
                                        new OperationCallback() {
                                    public void receivedStatus(OperationStatus val) {
                                        if (val.getStatusCode().equals(StatusCode.SUCCESS)) {
                                            if (log.isDebugEnabled()) log.debug("AddOrAppend Retry append Key (Append Operation): " + key + "; Status : " + val.getStatusCode().name()
                                                    + "; Message : " + val.getMessage() + "; Elapsed Time - " + duration);

                                            EVCacheMetricsFactory.getInstance().getCounter(appName + "-AoA-RetryAppendOperation-SUCCESS", client.getTagList()).increment();
                                            rv.set(val.isSuccess(), val);
                                        } else {
                                            EVCacheMetricsFactory.getInstance().getCounter(appName + "-AoA-RetryAppendOperation-FAIL", client.getTagList()).increment();
                                        }
                                    }
                                    public void complete() {
                                        latch.countDown();
                                        rv.signalComplete();
                                    }
                                });
                                rv.setOperation(op);
                                mconn.enqueueOperation(key, op);
                            }
                        }

                        @Override
                        public void gotData(String key, long cas) {
                            rv.setCas(cas);
                        }

                        @Override
                        public void complete() {
                            if(appendSuccess) {
                                latch.countDown();
                                rv.signalComplete();
                            }
                        }
                    });
                    rv.setOperation(op);
                    mconn.enqueueOperation(key, op);
                }
            }
        });
        rv.setOperation(op);
        mconn.enqueueOperation(key, op);
        if (evcacheLatch != null && evcacheLatch instanceof EVCacheLatchImpl && !client.isInWriteOnly()) ((EVCacheLatchImpl) evcacheLatch).addFuture(rv);
        return rv;
    }



    private <T> OperationFuture<Boolean> asyncStore(final StoreType storeType, final String key, int exp, T value, Transcoder<T> tc, EVCacheLatch evcacheLatch) {
        CachedData co;
        if (value instanceof CachedData) {
            co = (CachedData) value;
        } else {
            co = tc.encode(value);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String operationStr;
        if (storeType == StoreType.set) {
            operationStr = "Set";
        } else if (storeType == StoreType.add) {
            operationStr = "Add";
        } else {
            operationStr = "Replace";
        }
        final EVCacheOperationFuture<Boolean> rv = new EVCacheOperationFuture<Boolean>(key, latch, new AtomicReference<Boolean>(null), operationTimeout, executorService, client, "Latency" + operationStr);
        Operation op = opFact.store(storeType, key, co.getFlags(), exp, co.getData(), new StoreOperation.Callback() {
            final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-" + operationStr + "Operation", client.getTagList());

            @Override
            public void receivedStatus(OperationStatus val) {
                final long duration = System.currentTimeMillis() - rv.getStartTime();
                operationDuration.record(duration, TimeUnit.MILLISECONDS);
                if (log.isDebugEnabled()) log.debug("Storing Key : " + key + "; Status : " + val.getStatusCode().name()
                        + "; Message : " + val.getMessage() + "; Elapsed Time - " + duration);

                if (val.getStatusCode().equals(StatusCode.SUCCESS)) {
                    EVCacheMetricsFactory.getInstance().increment(appName + "-" + operationStr + "Operation-SUCCESS");
                } else if (val.getStatusCode().equals(StatusCode.TIMEDOUT)) {
//                    Tag tag = null;
//                    final MemcachedNode node = getEVCacheNode(key);
//                    if (node.getSocketAddress() instanceof InetSocketAddress) {
//                        tag = new BasicTag("HOST", ((InetSocketAddress) node.getSocketAddress()).getHostName());
//                    }
                    EVCacheMetricsFactory.getInstance().getCounter(appName + "-" + operationStr + "Operation-TIMEDOUT", client.getTagList()).increment();
                } else if (val.getStatusCode().equals(StatusCode.ERR_NOT_FOUND) || val.getStatusCode().equals(StatusCode.ERR_EXISTS)) {
                    EVCacheMetricsFactory.getInstance().increment(appName + "-" + operationStr + "Operation-" + val.getStatusCode().name());
                } else {
//                    Tag tag = null;
//                    final MemcachedNode node = getEVCacheNode(key);
//                    if (node.getSocketAddress() instanceof InetSocketAddress) {
//                        tag = new BasicTag("HOST", ((InetSocketAddress) node.getSocketAddress()).getHostName());
//                    }
                    EVCacheMetricsFactory.getInstance().getCounter(appName + "-" + operationStr + "Operation-" + val.getStatusCode().name(), client.getTagList()).increment();
                }
                rv.set(val.isSuccess(), val);
            }

            @Override
            public void gotData(String key, long cas) {
                rv.setCas(cas);
            }

            @Override
            public void complete() {
                latch.countDown();
                rv.signalComplete();
            }
        });
        rv.setOperation(op);
        if (evcacheLatch != null && evcacheLatch instanceof EVCacheLatchImpl && !client.isInWriteOnly()) ((EVCacheLatchImpl) evcacheLatch).addFuture(rv);
        mconn.enqueueOperation(key, op);
        return rv;
    }

    public String toString() {
        return appName + "-" + client.getZone() + "-" + client.getId();
    }

    @SuppressWarnings("unchecked")
    public <T> OperationFuture<Boolean> add(String key, int exp, T o, final Transcoder<T> tc, EVCacheLatch latch) {
        Transcoder<T> t = (Transcoder<T>) ((tc == null) ? transcoder : tc);
        return asyncStore(StoreType.add, key, exp, o, t, latch);
    }

    public long incr(String key, long by, long def, int exp) {
        final long start = System.currentTimeMillis();
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-IncrOperation", client.getTagList());
        long val = 0;
        try {
            val = mutate(Mutator.incr, key, by, def, exp);
        } finally {
            final long duration = System.currentTimeMillis() - start;
            operationDuration.record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) log.debug("Increment Key : " + key + "; by : " + by + "; default : " + def + "; exp : " + exp 
                    + "; val : " + val + "; Elapsed Time - " + duration);
        }
        return val;
    }


    public long decr(String key, long by, long def, int exp) {
        final long start = System.currentTimeMillis();
        final Timer operationDuration = EVCacheMetricsFactory.getInstance().getPercentileTimer(appName + "-DecrOperation", client.getTagList());
        long val = 0;
        try {
            val = super.decr(key, by, def, exp);
        } finally {
            final long duration = System.currentTimeMillis() - start;
            operationDuration.record(duration, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) log.debug("decrement Key : " + key + "; by : " + by + "; default : " + def + "; exp : " + exp 
                    + "; val : " + val + "; Elapsed Time - " + duration);
        }
        return val;
    }

    public long mutate(Mutator m, String key, long by, long def, int exp) {
        final AtomicLong rv = new AtomicLong();
        final CountDownLatch latch = new CountDownLatch(1);
        mconn.enqueueOperation(key, opFact.mutate(m, key, by, def, exp, new OperationCallback() {
            @Override
            public void receivedStatus(OperationStatus s) {
                rv.set(new Long(s.isSuccess() ? s.getMessage() : "-1"));
            }

            @Override
            public void complete() {
                latch.countDown();
            }
        }));
        try {
            if(mutateOperationTimeout == null) {
                mutateOperationTimeout = EVCacheConfig.getInstance().getDynamicLongProperty("evache.mutate.timeout", operationTimeout);
            }

            if (!latch.await(mutateOperationTimeout.get(), TimeUnit.MILLISECONDS)) {
                return rv.get();
            }
        } catch (InterruptedException e) {
            log.error("InterruptedException", e);

        }
        getLogger().debug("Mutation returned " + rv);
        return rv.get();
    }

    public void reconnectNode(EVCacheNodeImpl evcNode ) {
        EVCacheMetricsFactory.getInstance().getCounter(appName + "-RECONNECT", evcNode.getTags()).increment();
        evcNode.setConnectTime(System.currentTimeMillis());
        mconn.queueReconnect(evcNode);
    }
}
