/*-
 * ============LICENSE_START=======================================================
 * ONAP - SO
 * ================================================================================
 * Copyright (C) 2017 AT&T Intellectual Property. All rights reserved.
 * ================================================================================
 * Copyright (C) 2018 Nokia.
 * ================================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ============LICENSE_END=========================================================
 */

package org.onap.so.bpmn.infrastructure.pnf.dmaap;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.core.UriBuilder;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.onap.so.client.aai.entities.uri.AAIResourceUri;
import org.onap.so.client.aai.entities.uri.AAIUriFactory;
import org.onap.so.client.aai.AAIResourcesClient;
import org.onap.so.client.aai.AAIObjectType;

@Component
public class PnfEventReadyDmaapClient implements DmaapClient {

    private static final Logger logger = LoggerFactory.getLogger(PnfEventReadyDmaapClient.class);

    private HttpClient httpClient;
    private Map<String, Runnable> pnfCorrelationIdToThreadMap;
    private HttpGet getRequest;
    private int topicListenerDelayInSeconds;
    private volatile ScheduledThreadPoolExecutor executor;
    private volatile boolean dmaapThreadListenerIsRunning;

    public volatile List<HashMap<String, String>> updateInfoMap;

    @Autowired
    public PnfEventReadyDmaapClient(Environment env) {
        httpClient = HttpClientBuilder.create().build();
        pnfCorrelationIdToThreadMap = new ConcurrentHashMap<>();
        topicListenerDelayInSeconds = env.getProperty("pnf.dmaap.topicListenerDelayInSeconds", Integer.class);
        executor = null;
        getRequest = new HttpGet(UriBuilder.fromUri(env.getProperty("pnf.dmaap.uriPathPrefix"))
                .scheme(env.getProperty("pnf.dmaap.protocol")).host(env.getProperty("pnf.dmaap.host"))
                .port(env.getProperty("pnf.dmaap.port", Integer.class)).path(env.getProperty("pnf.dmaap.topicName"))
                .path(env.getProperty("pnf.dmaap.consumerGroup")).path(env.getProperty("pnf.dmaap.consumerId"))
                .build());
        updateInfoMap = new ArrayList<>();
    }

    @Override
    public synchronized void registerForUpdate(String pnfCorrelationId, Runnable informConsumer,
            Optional<HashMap<String, String>> updateInfo) {
        logger.debug("registering for pnf ready dmaap event for pnf correlation id: {}", pnfCorrelationId);
        HashMap<String, String> map = updateInfo.get();
        if (map != null && map.size() > 0) {
            synchronized (updateInfoMap) {
                updateInfoMap.add(map);
            }
        }
        pnfCorrelationIdToThreadMap.put(pnfCorrelationId, informConsumer);
        if (!dmaapThreadListenerIsRunning) {
            startDmaapThreadListener();
        }
    }

    @Override
    public synchronized Runnable unregister(String pnfCorrelationId) {
        logger.debug("unregistering from pnf ready dmaap event for pnf correlation id: {}", pnfCorrelationId);
        Runnable runnable = runnable = pnfCorrelationIdToThreadMap.remove(pnfCorrelationId);
        synchronized (updateInfoMap) {
            for (int i = updateInfoMap.size() - 1; i >= 0; i--) {
                if (!updateInfoMap.get(i).containsKey("pnfCorrelationId"))
                    continue;
                String id = updateInfoMap.get(i).get("pnfCorrelationId");
                if (id != pnfCorrelationId)
                    continue;
                updateInfoMap.remove(i);
            }
        }
        if (pnfCorrelationIdToThreadMap.isEmpty()) {
            stopDmaapThreadListener();
        }
        return runnable;
    }

    private synchronized void startDmaapThreadListener() {
        if (!dmaapThreadListenerIsRunning) {
            executor = new ScheduledThreadPoolExecutor(1);
            executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            executor.scheduleWithFixedDelay(new DmaapTopicListenerThread(), 0, topicListenerDelayInSeconds,
                    TimeUnit.SECONDS);
            dmaapThreadListenerIsRunning = true;
        }
    }

    private synchronized void stopDmaapThreadListener() {
        if (dmaapThreadListenerIsRunning) {
            executor.shutdown();
            dmaapThreadListenerIsRunning = false;
            executor = null;
        }
    }

    class DmaapTopicListenerThread implements Runnable {

        @Override
        public void run() {
            try {
                logger.debug("dmaap listener starts listening pnf ready dmaap topic");
                HttpResponse response = httpClient.execute(getRequest);
                List<String> idList = getPnfCorrelationIdListFromResponse(response);

                // idList is never null
                if (!idList.isEmpty()) {
                    // send only body of response
                    registerClientResponse(idList.get(0), EntityUtils.toString(response.getEntity(), "UTF-8"));
                }

                if (idList != null) {
                    idList.forEach(this::informAboutPnfReadyIfPnfCorrelationIdFound);
                }
            } catch (IOException e) {
                logger.error("Exception caught during sending rest request to dmaap for listening event topic", e);
            } finally {
                getRequest.reset();
            }
        }

        private List<String> getPnfCorrelationIdListFromResponse(HttpResponse response) throws IOException {
            if (response.getStatusLine().getStatusCode() == 200) {
                String responseString = EntityUtils.toString(response.getEntity(), "UTF-8");
                if (responseString != null) {
                    return JsonUtilForPnfCorrelationId.parseJsonToGelAllPnfCorrelationId(responseString);
                }
            }
            return Collections.emptyList();
        }

        private void informAboutPnfReadyIfPnfCorrelationIdFound(String pnfCorrelationId) {
            Runnable runnable = unregister(pnfCorrelationId);
            if (runnable != null) {
                logger.debug("dmaap listener gets pnf ready event for pnfCorrelationId: {}", pnfCorrelationId);
                runnable.run();
            }
        }

        private void registerClientResponse(String pnfCorrelationId, String response) {

            String customerId = null;
            String serviceType = null;
            String serId = null;
            synchronized (updateInfoMap) {
                for (HashMap<String, String> map : updateInfoMap) {
                    if (!map.containsKey("pnfCorrelationId"))
                        continue;
                    if (pnfCorrelationId != map.get("pnfCorrelationId"))
                        continue;
                    if (!map.containsKey("globalSubscriberID"))
                        continue;
                    if (!map.containsKey("serviceType"))
                        continue;
                    if (!map.containsKey("serviceInstanceId"))
                        continue;
                    customerId = map.get("pnfCorrelationId");
                    serviceType = map.get("serviceType");
                    serId = map.get("serviceInstanceId");
                }
            }
            if (customerId == null || serviceType == null || serId == null)
                return;
            AAIResourcesClient client = new AAIResourcesClient();
            AAIResourceUri uri = AAIUriFactory.createResourceUri(AAIObjectType.SERVICE_INSTANCE_METADATA, customerId,
                    serviceType, serId);
            client.update(uri, response);
        }

    }
}
