/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.river.oai;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.logging.ESLogger;
import org.xbib.rdf.Resource;

/**
 * Write bulk data to Elasticsearch
 * 
 * @author <a href="mailto:joergprante@gmail.com">J&ouml;rg Prante</a>
 */
public class BulkWrite extends AbstractWrite {

    private ESLogger logger;
    private int bulkSize = 100;
    private int maxActiveRequests = 30;
    private long millisBeforeContinue = 60000L;
    private int totalTimeouts;
    private static final int MAX_TOTAL_TIMEOUTS = 10;
    private static final AtomicInteger onGoingBulks = new AtomicInteger(0);
    private static final AtomicInteger counter = new AtomicInteger(0);
    private BulkRequestBuilder currentBulk;

    public BulkWrite(ESLogger logger, String index, String type) {
        super(index, type, ':');
        this.logger = logger;
        this.totalTimeouts = 0;
    }
    
    public BulkWrite setBulkSize(int bulkSize) {
        this.bulkSize = bulkSize;
        return this; 
    }
    
    public BulkWrite setMaxActiveRequests(int maxActiveRequests) {
        this.maxActiveRequests = maxActiveRequests;
        return this;
    }
    
    public BulkWrite setMillisBeforeContinue(long millis) {
        this.millisBeforeContinue = millis;
        return this;        
    }
    
    @Override
    public void write(Client client, Resource resource) throws IOException {       
        if (currentBulk == null) {
            currentBulk = client.prepareBulk();
        }
        build(resource);
        if (resource.isDeleted()) {
            currentBulk.add(Requests.deleteRequest(index).type(type).id(createId(resource)));
        } else {
            currentBulk.add(Requests.indexRequest(index).type(type).id(createId(resource)).create(false).source(getBuilder()));
        }
        if (currentBulk.numberOfActions() >= bulkSize) {
            processBulk(client);
        }
    }
    
    @Override
    public void flush(Client client)  throws IOException {
        if (currentBulk == null) {
            return;
        }
        if (totalTimeouts > MAX_TOTAL_TIMEOUTS) {
            // waiting some minutes is much too long, do not wait any longer            
            throw new IOException("total flush() timeouts exceeded limit of + " + MAX_TOTAL_TIMEOUTS + ", aborting");
        }
        if (currentBulk.numberOfActions() > 0) {
            processBulk(client);
        }
        // wait for all outstanding bulk requests
        while (onGoingBulks.intValue() > 0) {
            logger.info("waiting for {} active bulk requests", onGoingBulks);
            synchronized (onGoingBulks) {
                try {
                    onGoingBulks.wait(millisBeforeContinue);
                } catch (InterruptedException e) {
                    logger.warn("timeout while waiting, continuing after {} ms", millisBeforeContinue);
                    totalTimeouts++;
                }
            }
        }
    }

    private void processBulk(Client client) {
        while (onGoingBulks.intValue() >= maxActiveRequests) {
            logger.info("waiting for {} active bulk requests", onGoingBulks);
            synchronized (onGoingBulks) {
                try {
                    onGoingBulks.wait(millisBeforeContinue);
                } catch (InterruptedException e) {
                    logger.warn("timeout while waiting, continuing after {} ms", millisBeforeContinue);
                    totalTimeouts++;
                }
            }
        }
        int currentOnGoingBulks = onGoingBulks.incrementAndGet();
        final int numberOfActions = currentBulk.numberOfActions();
        logger.info("submitting new bulk index request ({} docs, {} requests currently active)", new Object[]{numberOfActions, currentOnGoingBulks});
        try {
            currentBulk.execute(new ActionListener<BulkResponse>() {

                @Override
                public void onResponse(BulkResponse bulkResponse) {
                    if (bulkResponse.hasFailures()) {
                        logger.error("bulk index has failures: {}", bulkResponse.buildFailureMessage());
                    } else {
                        final int totalActions = counter.addAndGet(numberOfActions);
                        logger.info("bulk index success ({} millis, {} docs, total of {} docs)", new Object[]{bulkResponse.tookInMillis(), numberOfActions, totalActions});
                    }
                    onGoingBulks.decrementAndGet();
                    synchronized (onGoingBulks) {
                        onGoingBulks.notifyAll();
                    }
                }

                @Override
                public void onFailure(Throwable e) {
                    logger.error("bulk request failed", e);
                }
            });
        } catch (Exception e) {
            logger.error("unhandled exception, failed to execute bulk request", e);
        } finally {
            currentBulk = client.prepareBulk();
        }
    }
}
