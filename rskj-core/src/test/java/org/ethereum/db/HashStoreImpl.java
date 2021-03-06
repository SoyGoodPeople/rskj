/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.db;

import org.apache.commons.collections4.IterableUtils;
import org.ethereum.datasource.mapdb.MapDBFactory;
import org.ethereum.util.FastByteComparisons;
import org.mapdb.DB;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static co.rsk.config.RskSystemProperties.CONFIG;

/**
 * @author Mikhail Kalinin
 * @since 07.07.2015
 */
public class HashStoreImpl implements HashStore {

    private static final Logger logger = LoggerFactory.getLogger("blockqueue");

    private static final String STORE_NAME = "hashstore";
    private MapDBFactory mapDBFactory;

    private DB db;
    private Map<Long, byte[]> hashes;
    private List<Long> index;

    private boolean initDone = false;
    private final ReentrantLock initLock = new ReentrantLock();
    private final Condition init = initLock.newCondition();

    @Override
    public void open() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                initLock.lock();
                try {
                    db = mapDBFactory.createTransactionalDB(dbName());
                    hashes = db.hashMapCreate(STORE_NAME)
                            .keySerializer(Serializer.LONG)
                            .valueSerializer(Serializer.BYTE_ARRAY)
                            .makeOrGet();

                    if(CONFIG.databaseReset()) {
                        hashes.clear();
                        db.commit();
                    }

                    index = new ArrayList<>(hashes.keySet());
                    sortIndex();

                    initDone = true;
                    init.signalAll();

                    logger.info("Hash store loaded, size [{}]", size());
                } finally {
                    initLock.unlock();
                }
            }
        }).start();
    }

    private String dbName() {
        return String.format("%s/%s", STORE_NAME, STORE_NAME);
    }

    @Override
    public void close() {
        awaitInit();
        db.close();
        initDone = false;
    }

    @Override
    public void add(byte[] hash) {
        awaitInit();
        addInner(false, hash);
        dbCommit("add");
    }

    @Override
    public void addFirst(byte[] hash) {
        awaitInit();
        addInner(true, hash);
        dbCommit("addFirst");
    }

    @Override
    public void addBatch(Collection<byte[]> hashes) {
        awaitInit();
        for (byte[] hash : hashes) {
            addInner(false, hash);
        }
        dbCommit("addBatch: " + hashes.size());
    }

    @Override
    public void addFirstBatch(Collection<byte[]> hashes) {
        awaitInit();
        for (byte[] hash : hashes) {
            addInner(true, hash);
        }
        dbCommit("addFirstBatch: " + hashes.size());
    }

    private synchronized void addInner(boolean first, byte[] hash) {
        Long idx = createIndex(first);
        hashes.put(idx, hash);
    }

    @Override
    public byte[] peek() {
        awaitInit();
        synchronized (this) {
            if(index.isEmpty()) {
                return null;
            }

            Long idx = index.get(0);
            return hashes.get(idx);
        }
    }

    @Override
    public byte[] poll() {
        awaitInit();
        byte[] hash = pollInner();
        dbCommit();
        return hash;
    }

    @Override
    public List<byte[]> pollBatch(int qty) {
        awaitInit();
        if(index.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> hashes = new ArrayList<>(qty > size() ? qty : size());
        while (hashes.size() < qty) {
            byte[] hash = pollInner();
            if(hash == null) {
                break;
            }
            hashes.add(hash);
        }
        dbCommit("pollBatch: " + hashes.size());
        return hashes;
    }

    private byte[] pollInner() {
        byte[] hash;
        synchronized (this) {
            if(index.isEmpty()) {
                return null;
            }

            Long idx = index.get(0);
            hash = hashes.get(idx);
            hashes.remove(idx);
            index.remove(0);
        }
        return hash;
    }

    @Override
    public boolean isEmpty() {
        awaitInit();
        return index.isEmpty();
    }

    @Override
    public Set<Long> getKeys() {
        awaitInit();
        return hashes.keySet();
    }

    @Override
    public int size() {
        awaitInit();
        return index.size();
    }

    @Override
    public void clear() {
        awaitInit();
        synchronized (this) {
            index.clear();
            hashes.clear();
        }
        dbCommit();
    }

    @Override
    public void removeAll(Collection<byte[]> removing) {
        awaitInit();
        Set<Long> removed = new HashSet<>();
        for(final Map.Entry<Long, byte[]> e : hashes.entrySet()) {
            byte[] hash = IterableUtils.find(removing, hash1 -> FastByteComparisons.compareTo(hash1, 0, 32, e.getValue(), 0, 32) == 0);
            if(hash != null) {
                removed.add(e.getKey());
            }
        }
        index.removeAll(removed);
        for(Long idx : removed) {
            hashes.remove(idx);
        }
        dbCommit();
    }

    private void dbCommit() {
        dbCommit("");
    }

    private void dbCommit(String info) {
        long s = System.currentTimeMillis();
        db.commit();
        logger.debug("HashStoreImpl: db.commit took " + (System.currentTimeMillis() - s) + " ms (" + info + ") " + Thread.currentThread().getName());
    }

    private Long createIndex(boolean first) {
        Long idx;
        if(index.isEmpty()) {
            idx = 0L;
            index.add(idx);
        } else if(first) {
            idx = index.get(0) - 1;
            index.add(0, idx);
        } else {
            idx = index.get(index.size() - 1) + 1;
            index.add(idx);
        }
        return idx;
    }

    private void sortIndex() {
        Collections.sort(index);
    }

    public void setMapDBFactory(MapDBFactory mapDBFactory) {
        this.mapDBFactory = mapDBFactory;
    }


    private void awaitInit() {
        initLock.lock();
        try {
            if(!initDone) {
                init.awaitUninterruptibly();
            }
        } finally {
            initLock.unlock();
        }
    }
}
