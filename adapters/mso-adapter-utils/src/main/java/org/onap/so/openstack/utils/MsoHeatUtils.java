/*-
 * ============LICENSE_START=======================================================
 * ONAP - SO
 * ================================================================================
 * Copyright (C) 2017 AT&T Intellectual Property. All rights reserved.
 * Copyright (C) 2017 Huawei Technologies Co., Ltd. All rights reserved.
 * ================================================================================
 * Modifications Copyright (c) 2019 Samsung
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

package org.onap.so.openstack.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.onap.logging.ref.slf4j.ONAPLogConstants;
import org.onap.so.adapters.vdu.CloudInfo;
import org.onap.so.adapters.vdu.PluginAction;
import org.onap.so.adapters.vdu.VduArtifact;
import org.onap.so.adapters.vdu.VduArtifact.ArtifactType;
import org.onap.so.adapters.vdu.VduException;
import org.onap.so.adapters.vdu.VduInstance;
import org.onap.so.adapters.vdu.VduModelInfo;
import org.onap.so.adapters.vdu.VduPlugin;
import org.onap.so.adapters.vdu.VduStateType;
import org.onap.so.adapters.vdu.VduStatus;
import org.onap.so.cloud.CloudConfig;
import org.onap.so.cloud.authentication.AuthenticationMethodFactory;
import org.onap.so.cloud.authentication.KeystoneAuthHolder;
import org.onap.so.cloud.authentication.KeystoneV3Authentication;
import org.onap.so.cloud.authentication.ServiceEndpointNotFoundException;
import org.onap.so.db.catalog.beans.CloudIdentity;
import org.onap.so.db.catalog.beans.CloudSite;
import org.onap.so.db.catalog.beans.HeatTemplate;
import org.onap.so.db.catalog.beans.HeatTemplateParam;
import org.onap.so.db.catalog.beans.ServerType;
import org.onap.so.db.request.beans.CloudApiRequests;
import org.onap.so.db.request.beans.InfraActiveRequests;
import org.onap.so.db.request.client.RequestsDbClient;
import org.onap.so.logger.ErrorCode;
import org.onap.so.logger.MessageEnum;
import org.onap.so.openstack.beans.HeatStatus;
import org.onap.so.openstack.beans.StackInfo;
import org.onap.so.openstack.exceptions.MsoAdapterException;
import org.onap.so.openstack.exceptions.MsoCloudSiteNotFound;
import org.onap.so.openstack.exceptions.MsoException;
import org.onap.so.openstack.exceptions.MsoIOException;
import org.onap.so.openstack.exceptions.MsoOpenstackException;
import org.onap.so.openstack.exceptions.MsoStackAlreadyExists;
import org.onap.so.openstack.exceptions.MsoTenantNotFound;
import org.onap.so.openstack.mappers.StackInfoMapper;
import org.onap.so.utils.CryptoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.woorea.openstack.base.client.OpenStackConnectException;
import com.woorea.openstack.base.client.OpenStackRequest;
import com.woorea.openstack.base.client.OpenStackResponseException;
import com.woorea.openstack.heat.Heat;
import com.woorea.openstack.heat.model.CreateStackParam;
import com.woorea.openstack.heat.model.Events;
import com.woorea.openstack.heat.model.Resources;
import com.woorea.openstack.heat.model.Stack;
import com.woorea.openstack.heat.model.Stack.Output;
import com.woorea.openstack.heat.model.Stacks;
import com.woorea.openstack.keystone.Keystone;
import com.woorea.openstack.keystone.model.Access;
import com.woorea.openstack.keystone.model.Authentication;
import com.woorea.openstack.keystone.utils.KeystoneUtils;

@Primary
@Component
public class MsoHeatUtils extends MsoCommonUtils implements VduPlugin {

    private static final String TOKEN_AUTH = "TokenAuth";

    private static final String QUERY_ALL_STACKS = "QueryAllStacks";

    private static final String DELETE_STACK = "DeleteStack";

    protected static final String HEAT_ERROR = "HeatError";

    protected static final String CREATE_STACK = "CreateStack";
    public static final String FOUND = "Found: {}";
    public static final String EXCEPTION_ROLLING_BACK_STACK =
            "{} Create Stack: Nested exception rolling back stack: {} ";
    public static final String IN_PROGRESS = "in_progress";

    // Fetch cloud configuration each time (may be cached in CloudConfig class)
    @Autowired
    protected CloudConfig cloudConfig;

    @Autowired
    private Environment environment;

    @Autowired
    private AuthenticationMethodFactory authenticationMethodFactory;

    @Autowired
    private MsoTenantUtilsFactory tenantUtilsFactory;

    @Autowired
    private KeystoneV3Authentication keystoneV3Authentication;

    @Autowired
    RequestsDbClient requestDBClient;

    @Autowired
    StackStatusHandler statusHandler;

    private static final Logger logger = LoggerFactory.getLogger(MsoHeatUtils.class);

    // Properties names and variables (with default values)
    protected String createPollIntervalProp = "org.onap.so.adapters.po.pollInterval";
    private String deletePollIntervalProp = "org.onap.so.adapters.po.pollInterval";
    private String deletePollTimeoutProp = "org.onap.so.adapters.po.pollTimeout";

    protected static final String CREATE_POLL_INTERVAL_DEFAULT = "15";
    private static final String DELETE_POLL_INTERVAL_DEFAULT = "15";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * keep this old method signature here to maintain backwards compatibility. keep others as well. this method does
     * not include environment, files, or heatFiles
     */
    public StackInfo createStack(String cloudSiteId, String cloudOwner, String tenantId, String stackName,
            String heatTemplate, Map<String, ?> stackInputs, boolean pollForCompletion, int timeoutMinutes)
            throws MsoException {
        // Just call the new method with the environment & files variable set to null
        return this.createStack(cloudSiteId, cloudOwner, tenantId, stackName, null, heatTemplate, stackInputs,
                pollForCompletion, timeoutMinutes, null, null, null, true);
    }

    // This method has environment, but not files or heatFiles
    public StackInfo createStack(String cloudSiteId, String cloudOwner, String tenantId, String stackName,
            String heatTemplate, Map<String, ?> stackInputs, boolean pollForCompletion, int timeoutMinutes,
            String environment) throws MsoException {
        // Just call the new method with the files/heatFiles variables set to null
        return this.createStack(cloudSiteId, cloudOwner, tenantId, stackName, null, heatTemplate, stackInputs,
                pollForCompletion, timeoutMinutes, environment, null, null, true);
    }

    // This method has environment and files, but not heatFiles.
    public StackInfo createStack(String cloudSiteId, String cloudOwner, String tenantId, String stackName,
            String heatTemplate, Map<String, ?> stackInputs, boolean pollForCompletion, int timeoutMinutes,
            String environment, Map<String, Object> files) throws MsoException {
        return this.createStack(cloudSiteId, cloudOwner, tenantId, stackName, null, heatTemplate, stackInputs,
                pollForCompletion, timeoutMinutes, environment, files, null, true);
    }

    // This method has environment, files, heatfiles
    public StackInfo createStack(String cloudSiteId, String cloudOwner, String tenantId, String stackName,
            String heatTemplate, Map<String, ?> stackInputs, boolean pollForCompletion, int timeoutMinutes,
            String environment, Map<String, Object> files, Map<String, Object> heatFiles) throws MsoException {
        return this.createStack(cloudSiteId, cloudOwner, tenantId, stackName, null, heatTemplate, stackInputs,
                pollForCompletion, timeoutMinutes, environment, files, heatFiles, true);
    }

    /**
     * Create a new Stack in the specified cloud location and tenant. The Heat template and parameter map are passed in
     * as arguments, along with the cloud access credentials. It is expected that parameters have been validated and
     * contain at minimum the required parameters for the given template with no extra (undefined) parameters..
     *
     * The Stack name supplied by the caller must be unique in the scope of this tenant. However, it should also be
     * globally unique, as it will be the identifier for the resource going forward in Inventory. This latter is managed
     * by the higher levels invoking this function.
     *
     * The caller may choose to let this function poll Openstack for completion of the stack creation, or may handle
     * polling itself via separate calls to query the status. In either case, a StackInfo object will be returned
     * containing the current status. When polling is enabled, a status of CREATED is expected. When not polling, a
     * status of BUILDING is expected.
     *
     * An error will be thrown if the requested Stack already exists in the specified Tenant and Cloud.
     *
     * For 1510 - add "environment", "files" (nested templates), and "heatFiles" (get_files) as parameters for
     * createStack. If environment is non-null, it will be added to the stack. The nested templates and get_file entries
     * both end up being added to the "files" on the stack. We must combine them before we add them to the stack if
     * they're both non-null.
     *
     * @param cloudSiteId The cloud (may be a region) in which to create the stack.
     * @param cloudOwner the cloud owner of the cloud site in which to create the stack
     * @param tenantId The Openstack ID of the tenant in which to create the Stack
     * @param stackName The name of the stack to create
     * @param vduModelInfo contains information about the vdu model (added for plugin adapter)
     * @param heatTemplate The Heat template
     * @param stackInputs A map of key/value inputs
     * @param pollForCompletion Indicator that polling should be handled in Java vs. in the client
     * @param environment An optional yaml-format string to specify environmental parameters
     * @param files a Map<String, Object> that lists the child template IDs (file is the string, object is an int of
     *        Template id)
     * @param heatFiles a Map<String, Object> that lists the get_file entries (fileName, fileBody)
     * @param backout Donot delete stack on create Failure - defaulted to True
     * @return A StackInfo object
     * @throws MsoOpenstackException Thrown if the Openstack API call returns an exception.
     */

    @SuppressWarnings("unchecked")
    public StackInfo createStack(String cloudSiteId, String cloudOwner, String tenantId, String stackName,
            VduModelInfo vduModel, String heatTemplate, Map<String, ?> stackInputs, boolean pollForCompletion,
            int timeoutMinutes, String environment, Map<String, Object> files, Map<String, Object> heatFiles,
            boolean backout) throws MsoException {

        // Take out the multicloud inputs, if present.
        for (String key : MsoMulticloudUtils.MULTICLOUD_INPUTS) {
            if (stackInputs.containsKey(key)) {
                stackInputs.remove(key);
                if (stackInputs.isEmpty()) {
                    break;
                }
            }
        }

        CreateStackParam stack =
                createStackParam(stackName, heatTemplate, stackInputs, timeoutMinutes, environment, files, heatFiles);

        // Obtain the cloud site information where we will create the stack
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        logger.debug(FOUND, cloudSite);
        // Get a Heat client. They are cached between calls (keyed by tenantId:cloudId)
        // This could throw MsoTenantNotFound or MsoOpenstackException (both propagated)
        Heat heatClient = getHeatClient(cloudSite, tenantId);
        logger.debug(FOUND, heatClient);

        logger.debug("Ready to Create Stack ({}) with input params: {}", heatTemplate, stackInputs);

        Stack heatStack = null;
        try {
            OpenStackRequest<Stack> request = heatClient.getStacks().create(stack);
            saveStackRequest(request, MDC.get(ONAPLogConstants.MDCs.REQUEST_ID), stackName);
            CloudIdentity cloudIdentity = cloudSite.getIdentityService();
            request.header("X-Auth-User", cloudIdentity.getMsoId());
            request.header("X-Auth-Key", CryptoUtils.decryptCloudConfigPassword(cloudIdentity.getMsoPass()));
            heatStack = executeAndRecordOpenstackRequest(request);
        } catch (OpenStackResponseException e) {
            if (e.getStatus() == 409) {
                MsoStackAlreadyExists me = new MsoStackAlreadyExists(stackName, tenantId, cloudSiteId);
                me.addContext(CREATE_STACK);
                throw me;
            } else {
                logger.debug("ERROR STATUS = {},\n{}\n{}", e.getStatus(), e.getMessage(), e.getLocalizedMessage());
                throw heatExceptionToMsoException(e, CREATE_STACK);
            }
        } catch (OpenStackConnectException e) {
            throw heatExceptionToMsoException(e, CREATE_STACK);
        } catch (RuntimeException e) {
            throw runtimeExceptionToMsoException(e, CREATE_STACK);
        }

        // Subsequent access by the canonical name "<stack name>/<stack-id>".
        // Otherwise, simple query by name returns a 302 redirect.
        // NOTE: This is specific to the v1 Orchestration API.
        String canonicalName = stackName + "/" + heatStack.getId();

        if (pollForCompletion) {
            heatStack = pollStackForCompletion(cloudSiteId, tenantId, stackName, timeoutMinutes, backout, heatClient,
                    heatStack, canonicalName);
        } else {
            // Get initial status, since it will have been null after the create.
            heatStack = queryHeatStack(heatClient, canonicalName);
            logger.debug(heatStack.getStackStatus());
        }
        return new StackInfoMapper(heatStack).map();
    }

    private void saveStackRequest(OpenStackRequest<Stack> request, String requestId, String stackName) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InfraActiveRequests foundRequest = requestDBClient.getInfraActiveRequestbyRequestId(requestId);
            String stackRequest = mapper.writeValueAsString(request.entity());
            CloudApiRequests cloudReq = new CloudApiRequests();
            cloudReq.setCloudIdentifier(stackName);
            cloudReq.setRequestBody(stackRequest);
            cloudReq.setRequestId(requestId);
            foundRequest.getCloudApiRequests().add(cloudReq);
            requestDBClient.updateInfraActiveRequests(foundRequest);
        } catch (Exception e) {
            logger.error("Error updating in flight request with Openstack Create Request", e);
        }
    }

    private Stack pollStackForCompletion(String cloudSiteId, String tenantId, String stackName, int timeoutMinutes,
            boolean backout, Heat heatClient, Stack heatStack, String canonicalName)
            throws MsoException, MsoOpenstackException {
        int createPollInterval =
                Integer.parseInt(this.environment.getProperty(createPollIntervalProp, CREATE_POLL_INTERVAL_DEFAULT));
        int pollTimeout = (timeoutMinutes * 60) + createPollInterval;
        int deletePollInterval = createPollInterval;
        int deletePollTimeout = pollTimeout;
        boolean createTimedOut = false;
        StringBuilder stackErrorStatusReason = new StringBuilder("");
        logger.debug("createPollInterval={}, pollTimeout={}", createPollInterval, pollTimeout);

        while (true) {
            try {
                heatStack = queryHeatStack(heatClient, canonicalName);
                statusHandler.updateStackStatus(heatStack, MDC.get(ONAPLogConstants.MDCs.REQUEST_ID));
                logger.debug("{} ({})", heatStack.getStackStatus(), canonicalName);
                try {
                    logger.debug("Current stack {}", this.getOutputsAsStringBuilder(heatStack).toString());
                } catch (Exception e) {
                    logger.debug("an error occurred trying to print out the current outputs of the stack", e);
                }

                if ("CREATE_IN_PROGRESS".equals(heatStack.getStackStatus())) {
                    if (pollTimeout <= 0) {
                        logger.error("{} Cloud site: {} Tenant: {} Stack: {} Stack status: {} {} Create stack timeout",
                                MessageEnum.RA_CREATE_STACK_TIMEOUT, cloudSiteId, tenantId, stackName,
                                heatStack.getStackStatus(), ErrorCode.AvailabilityError.getValue());
                        createTimedOut = true;
                        break;
                    }
                    sleep(createPollInterval * 1000L);
                    pollTimeout -= createPollInterval;
                    logger.debug("pollTimeout remaining: {}", pollTimeout);
                } else {
                    stackErrorStatusReason.append(
                            "Stack error (" + heatStack.getStackStatus() + "): " + heatStack.getStackStatusReason());
                    break;
                }
            } catch (MsoException me) {
                // Cannot query the stack status. Something is wrong.
                // Try to roll back the stack
                if (!backout) {
                    logger.warn("{} Exception in Create Stack, stack deletion suppressed {}",
                            MessageEnum.RA_CREATE_STACK_ERR, ErrorCode.BusinessProcesssError.getValue());
                } else {
                    try {
                        logger.debug(
                                "Create Stack error - unable to query for stack status - attempting to delete stack: {}"
                                        + " - This will likely fail and/or we won't be able to query to see if delete worked",
                                canonicalName);
                        OpenStackRequest<Void> request = heatClient.getStacks().deleteByName(canonicalName);
                        executeAndRecordOpenstackRequest(request);
                        boolean deleted = false;
                        while (!deleted) {
                            try {
                                heatStack = queryHeatStack(heatClient, canonicalName);
                                statusHandler.updateStackStatus(heatStack, MDC.get(ONAPLogConstants.MDCs.REQUEST_ID));
                                if (heatStack != null) {
                                    logger.debug(heatStack.getStackStatus());
                                    if ("DELETE_IN_PROGRESS".equals(heatStack.getStackStatus())) {
                                        if (deletePollTimeout <= 0) {
                                            logger.error(
                                                    "{} Cloud site: {} Tenant: {} Stack: {} Stack status: {} {} Rollback: DELETE stack timeout",
                                                    MessageEnum.RA_CREATE_STACK_TIMEOUT, cloudSiteId, tenantId,
                                                    stackName, heatStack.getStackStatus(),
                                                    ErrorCode.AvailabilityError.getValue());
                                            break;
                                        } else {
                                            sleep(deletePollInterval * 1000L);
                                            deletePollTimeout -= deletePollInterval;
                                        }
                                    } else if ("DELETE_COMPLETE".equals(heatStack.getStackStatus())) {
                                        logger.debug("DELETE_COMPLETE for {}", canonicalName);
                                        deleted = true;
                                        continue;
                                    } else {
                                        // got a status other than DELETE_IN_PROGRESS or DELETE_COMPLETE - so break and
                                        // evaluate
                                        break;
                                    }
                                } else {
                                    // assume if we can't find it - it's deleted
                                    logger.debug("heatStack returned null - assume the stack {} has been deleted",
                                            canonicalName);
                                    deleted = true;
                                    continue;
                                }

                            } catch (Exception e3) {
                                // Just log this one. We will report the original exception.
                                logger.error(EXCEPTION_ROLLING_BACK_STACK, MessageEnum.RA_CREATE_STACK_ERR,
                                        ErrorCode.BusinessProcesssError.getValue(), e3);
                            }
                        }
                    } catch (Exception e2) {
                        // Just log this one. We will report the original exception.
                        logger.error(EXCEPTION_ROLLING_BACK_STACK, MessageEnum.RA_CREATE_STACK_ERR,
                                ErrorCode.BusinessProcesssError.getValue(), e2);
                    }
                }

                // Propagate the original exception from Stack Query.
                me.addContext(CREATE_STACK);
                throw me;
            }
        }

        if (!"CREATE_COMPLETE".equals(heatStack.getStackStatus())) {
            logger.error("{} Create Stack error:  Polling complete with non-success status: {}, {} {} ",
                    MessageEnum.RA_CREATE_STACK_ERR, heatStack.getStackStatus(), heatStack.getStackStatusReason(),
                    ErrorCode.BusinessProcesssError.getValue());

            // Rollback the stack creation, since it is in an indeterminate state.
            if (!backout) {
                logger.warn(
                        "{} Create Stack errored, stack deletion suppressed {} Create Stack error, stack deletion suppressed",
                        MessageEnum.RA_CREATE_STACK_ERR, ErrorCode.BusinessProcesssError.getValue());
            } else {
                try {
                    logger.debug("Create Stack errored - attempting to DELETE stack: {}", canonicalName);
                    logger.debug("deletePollInterval={}, deletePollTimeout={}", deletePollInterval, deletePollTimeout);
                    OpenStackRequest<Void> request = heatClient.getStacks().deleteByName(canonicalName);
                    executeAndRecordOpenstackRequest(request);
                    boolean deleted = false;
                    while (!deleted) {
                        try {
                            heatStack = queryHeatStack(heatClient, canonicalName);
                            if (heatStack != null) {
                                logger.debug("{} ({})", heatStack.getStackStatus(), canonicalName);
                                if ("DELETE_IN_PROGRESS".equals(heatStack.getStackStatus())) {
                                    if (deletePollTimeout <= 0) {
                                        logger.error(
                                                "{} Cloud site: {} Tenant: {} Stack: {} Stack status: {} {} Rollback: DELETE stack timeout",
                                                MessageEnum.RA_CREATE_STACK_TIMEOUT, cloudSiteId, tenantId, stackName,
                                                heatStack.getStackStatus(), ErrorCode.AvailabilityError.getValue());
                                        break;
                                    } else {
                                        sleep(deletePollInterval * 1000L);
                                        deletePollTimeout -= deletePollInterval;
                                        logger.debug("deletePollTimeout remaining: {}", deletePollTimeout);
                                    }
                                } else if ("DELETE_COMPLETE".equals(heatStack.getStackStatus())) {
                                    logger.debug("DELETE_COMPLETE for {}", canonicalName);
                                    deleted = true;
                                    continue;
                                } else if ("DELETE_FAILED".equals(heatStack.getStackStatus())) {
                                    // Warn about this (?) - but still throw the original exception
                                    logger.warn(
                                            "{} Create Stack errored, stack deletion FAILED {} Create Stack error, stack deletion FAILED",
                                            MessageEnum.RA_CREATE_STACK_ERR,
                                            ErrorCode.BusinessProcesssError.getValue());
                                    logger.debug(
                                            "Stack deletion FAILED on a rollback of a create - {}, status={}, reason={}",
                                            canonicalName, heatStack.getStackStatus(),
                                            heatStack.getStackStatusReason());
                                    break;
                                } else {
                                    // got a status other than DELETE_IN_PROGRESS or DELETE_COMPLETE - so break and
                                    // evaluate
                                    break;
                                }
                            } else {
                                // assume if we can't find it - it's deleted
                                logger.debug("heatStack returned null - assume the stack {} has been deleted",
                                        canonicalName);
                                deleted = true;
                                continue;
                            }

                        } catch (MsoException me2) {
                            // We got an exception on the delete - don't throw this exception - throw the original -
                            // just log.
                            logger.debug("Exception thrown trying to delete {} on a create->rollback: {} ",
                                    canonicalName, me2.getContextMessage(), me2);
                            logger.warn("{} Create Stack errored, then stack deletion FAILED - exception thrown {} {}",
                                    MessageEnum.RA_CREATE_STACK_ERR, ErrorCode.BusinessProcesssError.getValue(),
                                    me2.getContextMessage());
                        }

                    } // end while !deleted
                    StringBuilder errorContextMessage;
                    if (createTimedOut) {
                        errorContextMessage = new StringBuilder("Stack Creation Timeout");
                    } else {
                        errorContextMessage = stackErrorStatusReason;
                    }
                    if (deleted) {
                        errorContextMessage.append(" - stack successfully deleted");
                    } else {
                        errorContextMessage.append(" - encountered an error trying to delete the stack");
                    }
                } catch (Exception e2) {
                    // shouldn't happen - but handle
                    logger.error(EXCEPTION_ROLLING_BACK_STACK, MessageEnum.RA_CREATE_STACK_ERR,
                            ErrorCode.BusinessProcesssError.getValue(), e2);
                }
            }
            MsoOpenstackException me = new MsoOpenstackException(0, "", stackErrorStatusReason.toString());
            me.addContext(CREATE_STACK);
            throw me;
        }
        return heatStack;
    }

    /**
     * Query for a single stack (by Name) in a tenant. This call will always return a StackInfo object. If the stack
     * does not exist, an "empty" StackInfo will be returned - containing only the stack name and a status of NOTFOUND.
     *
     * @param tenantId The Openstack ID of the tenant in which to query
     * @param cloudSiteId The cloud identifier (may be a region) in which to query
     * @param cloudOwner the cloud owner of the cloud site in which to query
     * @param stackName The name of the stack to query (may be simple or canonical)
     * @return A StackInfo object
     * @throws MsoOpenstackException Thrown if the Openstack API call returns an exception.
     */
    public StackInfo queryStack(String cloudSiteId, String cloudOwner, String tenantId, String stackName)
            throws MsoException {
        logger.debug("Query HEAT stack: {} in tenant {}", stackName, tenantId);

        // Obtain the cloud site information where we will create the stack
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        logger.debug(FOUND, cloudSite.toString());

        // Get a Heat client. They are cached between calls (keyed by tenantId:cloudId)
        Heat heatClient = null;
        try {
            heatClient = getHeatClient(cloudSite, tenantId);
            if (heatClient != null) {
                logger.debug(FOUND, heatClient.toString());
            }
        } catch (MsoTenantNotFound e) {
            // Tenant doesn't exist, so stack doesn't either
            logger.debug("Tenant with id " + tenantId + "not found.", e);
            return new StackInfo(stackName, HeatStatus.NOTFOUND);
        } catch (MsoException me) {
            // Got an Openstack error. Propagate it
            logger.error("{} {} Openstack Exception on Token request: ", MessageEnum.RA_CONNECTION_EXCEPTION,
                    ErrorCode.AvailabilityError.getValue(), me);
            me.addContext("QueryStack");
            throw me;
        }

        // Query the Stack.
        // An MsoException will propagate transparently to the caller.
        Stack heatStack = queryHeatStack(heatClient, stackName);

        if (heatStack == null) {
            // Stack does not exist. Return a StackInfo with status NOTFOUND
            return new StackInfo(stackName, HeatStatus.NOTFOUND);
        }

        return new StackInfoMapper(heatStack).map();
    }

    /**
     * Delete a stack (by Name/ID) in a tenant. If the stack is not found, it will be considered a successful deletion.
     * The return value is a StackInfo object which contains the current stack status.
     *
     * The client may choose to let the adapter poll Openstack for completion of the stack deletion, or may handle
     * polling itself via separate query calls. In either case, a StackInfo object will be returned. When polling is
     * enabled, a final status of NOTFOUND is expected. When not polling, a status of DELETING is expected.
     *
     * There is no rollback from a successful stack deletion. A deletion failure will also result in an undefined stack
     * state - the components may or may not have been all or partially deleted, so the resulting stack must be
     * considered invalid.
     *
     * @param tenantId The Openstack ID of the tenant in which to perform the delete
     * @param cloudOwner the cloud owner of the cloud site in which to delete the stack
     * @param cloudSiteId The cloud identifier (may be a region) from which to delete the stack.
     * @param stackName The name/id of the stack to delete. May be simple or canonical
     * @param pollForCompletion Indicator that polling should be handled in Java vs. in the client
     * @return A StackInfo object
     * @throws MsoOpenstackException Thrown if the Openstack API call returns an exception.
     * @throws MsoCloudSiteNotFound
     */
    public StackInfo deleteStack(String tenantId, String cloudOwner, String cloudSiteId, String stackName,
            boolean pollForCompletion) throws MsoException {
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        Heat heatClient = null;
        try {
            heatClient = getHeatClient(cloudSite, tenantId);
        } catch (MsoTenantNotFound e) {
            logger.debug("Tenant with id " + tenantId + "not found.", e);
            return new StackInfo(stackName, HeatStatus.NOTFOUND);
        } catch (MsoException me) {
            logger.error("{} {} Openstack Exception on Token request: ", MessageEnum.RA_CONNECTION_EXCEPTION,
                    ErrorCode.AvailabilityError.getValue(), me);
            me.addContext(DELETE_STACK);
            throw me;
        }

        // OK if stack not found, perform a query first
        Stack heatStack = queryHeatStack(heatClient, stackName);
        if (heatStack == null || "DELETE_COMPLETE".equals(heatStack.getStackStatus())) {
            // Not found. Return a StackInfo with status NOTFOUND
            return new StackInfo(stackName, HeatStatus.NOTFOUND);
        }

        // Use canonical name "<stack name>/<stack-id>" to delete.
        // Otherwise, deletion by name returns a 302 redirect.
        // NOTE: This is specific to the v1 Orchestration API.
        String canonicalName = heatStack.getStackName() + "/" + heatStack.getId();

        try {
            OpenStackRequest<Void> request = null;
            if (null != heatClient) {
                request = heatClient.getStacks().deleteByName(canonicalName);
            } else {
                logger.debug("Heat Client is NULL");
            }
            executeAndRecordOpenstackRequest(request);
        } catch (OpenStackResponseException e) {
            if (e.getStatus() == 404) {
                // Not found. We are OK with this. Return a StackInfo with status NOTFOUND
                return new StackInfo(stackName, HeatStatus.NOTFOUND);
            } else {
                // Convert the OpenStackResponseException to an MsoOpenstackException
                throw heatExceptionToMsoException(e, DELETE_STACK);
            }
        } catch (OpenStackConnectException e) {
            // Error connecting to Openstack instance. Convert to an MsoException
            throw heatExceptionToMsoException(e, DELETE_STACK);
        } catch (RuntimeException e) {
            // Catch-all
            throw runtimeExceptionToMsoException(e, DELETE_STACK);
        }

        // Requery the stack for current status.
        // It will probably still exist with "DELETE_IN_PROGRESS" status.
        heatStack = queryHeatStack(heatClient, canonicalName);
        statusHandler.updateStackStatus(heatStack, MDC.get(ONAPLogConstants.MDCs.REQUEST_ID));
        if (pollForCompletion) {
            int pollInterval = Integer
                    .parseInt(this.environment.getProperty(deletePollIntervalProp, "" + DELETE_POLL_INTERVAL_DEFAULT));
            int pollTimeout = Integer
                    .parseInt(this.environment.getProperty(deletePollTimeoutProp, "" + DELETE_POLL_INTERVAL_DEFAULT));
            statusHandler.updateStackStatus(heatStack, MDC.get(ONAPLogConstants.MDCs.REQUEST_ID));
            // When querying by canonical name, Openstack returns DELETE_COMPLETE status
            // instead of "404" (which would result from query by stack name).
            while (heatStack != null && !"DELETE_COMPLETE".equals(heatStack.getStackStatus())) {
                logger.debug("Stack status: {}", heatStack.getStackStatus());

                if ("DELETE_FAILED".equals(heatStack.getStackStatus())) {
                    // Throw a 'special case' of MsoOpenstackException to report the Heat status
                    String error = "Stack delete error (" + heatStack.getStackStatus() + "): "
                            + heatStack.getStackStatusReason();
                    MsoOpenstackException me = new MsoOpenstackException(0, "", error);
                    me.addContext(DELETE_STACK);

                    // Alarm this condition, stack deletion failed


                    throw me;
                }

                if (pollTimeout <= 0) {
                    logger.error("{} Cloud site: {} Tenant: {} Stack: {} Stack status: {} {} Delete Stack Timeout",
                            MessageEnum.RA_DELETE_STACK_TIMEOUT, cloudSiteId, tenantId, stackName,
                            heatStack.getStackStatus(), ErrorCode.AvailabilityError.getValue());

                    // Throw a 'special case' of MsoOpenstackException to report the Heat status
                    MsoOpenstackException me = new MsoOpenstackException(0, "", "Stack Deletion Timeout");
                    me.addContext(DELETE_STACK);

                    // Alarm this condition, stack deletion failed


                    throw me;
                }

                sleep(pollInterval * 1000L);

                pollTimeout -= pollInterval;
                logger.debug("pollTimeout remaining: {}", pollTimeout);

                heatStack = queryHeatStack(heatClient, canonicalName);
            }

            // The stack is gone when this point is reached
            return new StackInfo(stackName, HeatStatus.NOTFOUND);
        }
        // Return the current status (if not polling, the delete may still be in progress)
        StackInfo stackInfo = new StackInfoMapper(heatStack).map();
        stackInfo.setName(stackName);
        return stackInfo;
    }

    /**
     * Query for all stacks in a tenant site. This call will return a List of StackInfo objects, one for each deployed
     * stack.
     *
     * Note that this is limited to a single site. To ensure that a tenant is truly empty would require looping across
     * all tenant endpoints.
     *
     * @param tenantId The Openstack ID of the tenant to query
     * @param cloudSiteId The cloud identifier (may be a region) in which to query.
     * @return A List of StackInfo objects
     * @throws MsoOpenstackException Thrown if the Openstack API call returns an exception.
     * @throws MsoCloudSiteNotFound
     */
    public List<StackInfo> queryAllStacks(String tenantId, String cloudSiteId) throws MsoException {
        // Obtain the cloud site information where we will create the stack
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        // Get a Heat client. They are cached between calls (keyed by tenantId:cloudId)
        Heat heatClient = getHeatClient(cloudSite, tenantId);

        try {
            OpenStackRequest<Stacks> request = heatClient.getStacks().list();
            Stacks stacks = executeAndRecordOpenstackRequest(request);

            List<StackInfo> stackList = new ArrayList<>();

            // Not sure if returns an empty list or null if no stacks exist
            if (stacks != null) {
                for (Stack stack : stacks) {
                    stackList.add(new StackInfoMapper(stack).map());
                }
            }

            return stackList;
        } catch (OpenStackResponseException e) {
            if (e.getStatus() == 404) {
                // Not sure if this can happen, but return an empty list
                logger.debug("queryAllStacks - stack not found: ");
                return new ArrayList<>();
            } else {
                // Convert the OpenStackResponseException to an MsoOpenstackException
                throw heatExceptionToMsoException(e, QUERY_ALL_STACKS);
            }
        } catch (OpenStackConnectException e) {
            // Error connecting to Openstack instance. Convert to an MsoException
            throw heatExceptionToMsoException(e, QUERY_ALL_STACKS);
        } catch (RuntimeException e) {
            // Catch-all
            throw runtimeExceptionToMsoException(e, QUERY_ALL_STACKS);
        }
    }

    /**
     * Validate parameters to be passed to Heat template. This method performs three functions: 1. Apply default values
     * to parameters which have them defined 2. Report any required parameters that are missing. This will generate an
     * exception in the caller, since stack create/update operations would fail. 3. Report and remove any extraneous
     * parameters. This will allow clients to pass supersets of parameters and not get errors.
     *
     * These functions depend on the HeatTemplate definition from the MSO Catalog DB, along with the input parameter
     * Map. The output is an updated parameter map. If the parameters are invalid for the template, an
     * IllegalArgumentException is thrown.
     */
    public Map<String, Object> validateStackParams(Map<String, Object> inputParams, HeatTemplate heatTemplate) {
        // Check that required parameters have been supplied for this template type
        StringBuilder missingParams = null;
        List<String> paramList = new ArrayList<>();

        // TODO: Enhance DB to support defaults for Heat Template parameters

        for (HeatTemplateParam parm : heatTemplate.getParameters()) {
            if (parm.isRequired() && !inputParams.containsKey(parm.getParamName())) {
                if (missingParams == null) {
                    missingParams = new StringBuilder(parm.getParamName());
                } else {
                    missingParams.append("," + parm.getParamName());
                }
            }
            paramList.add(parm.getParamName());
        }
        if (missingParams != null) {
            // Problem - missing one or more required parameters
            String error = "Missing Required inputs for HEAT Template: " + missingParams;
            logger.error("{} for HEAT Template {} Missing Required inputs for HEAT Template: {}",
                    MessageEnum.RA_MISSING_PARAM, ErrorCode.SchemaError.getValue(), missingParams);
            throw new IllegalArgumentException(error);
        }

        // Remove any extraneous parameters (don't throw an error)
        Map<String, Object> updatedParams = new HashMap<>();
        List<String> extraParams = new ArrayList<>();

        for (Entry<String, Object> entry : inputParams.entrySet()) {
            if (!paramList.contains(entry.getKey())) {
                // This is not a valid parameter for this template
                extraParams.add(entry.getKey());
            } else {
                updatedParams.put(entry.getKey(), entry.getValue());
            }
        }

        if (!extraParams.isEmpty()) {
            logger.warn("{} Heat Stack ({}) extra input params received: {} {}", MessageEnum.RA_GENERAL_WARNING,
                    heatTemplate.getTemplateName(), extraParams, ErrorCode.DataError.getValue());
        }

        return updatedParams;
    }

    // ---------------------------------------------------------------
    // PRIVATE FUNCTIONS FOR USE WITHIN THIS CLASS

    /**
     * Get a Heat client for the Openstack Identity service. This requires a 'member'-level userId + password, which
     * will be retrieved from properties based on the specified cloud Id. The tenant in which to operate must also be
     * provided.
     * <p>
     * On successful authentication, the Heat object will be cached for the tenantID + cloudId so that it can be reused
     * without reauthenticating with Openstack every time.
     *
     * @return an authenticated Heat object
     */
    public Heat getHeatClient(CloudSite cloudSite, String tenantId) throws MsoException {
        String cloudId = cloudSite.getId();
        // For DCP/LCP, the region should be the cloudId.
        String region = cloudSite.getRegionId();

        // Obtain an MSO token for the tenant
        CloudIdentity cloudIdentity = cloudSite.getIdentityService();
        logger.debug(FOUND, cloudIdentity.toString());
        MsoTenantUtils tenantUtils =
                tenantUtilsFactory.getTenantUtilsByServerType(cloudIdentity.getIdentityServerType());
        String keystoneUrl = tenantUtils.getKeystoneUrl(cloudId, cloudIdentity);
        logger.debug("keystoneUrl={}", keystoneUrl);
        String heatUrl = null;
        String tokenId = null;
        try {
            if (ServerType.KEYSTONE.equals(cloudIdentity.getIdentityServerType())) {
                Keystone keystoneTenantClient = new Keystone(keystoneUrl);
                Access access = null;

                Authentication credentials = authenticationMethodFactory.getAuthenticationFor(cloudIdentity);

                OpenStackRequest<Access> request =
                        keystoneTenantClient.tokens().authenticate(credentials).withTenantId(tenantId);

                access = executeAndRecordOpenstackRequest(request);

                try {
                    logger.debug("access={}", access.toString());
                    heatUrl = KeystoneUtils.findEndpointURL(access.getServiceCatalog(), "orchestration", region,
                            "public");
                    logger.debug("heatUrl={}, region={}", heatUrl, region);
                } catch (RuntimeException e) {
                    // This comes back for not found (probably an incorrect region ID)
                    String error = "AIC did not match an orchestration service for: region=" + region + ",cloud="
                            + cloudIdentity.getIdentityUrl();
                    throw new MsoAdapterException(error, e);
                }
                tokenId = access.getToken().getId();
            } else if (ServerType.KEYSTONE_V3.equals(cloudIdentity.getIdentityServerType())) {
                try {
                    KeystoneAuthHolder holder = keystoneV3Authentication.getToken(cloudSite, tenantId, "orchestration");
                    tokenId = holder.getId();
                    heatUrl = holder.getServiceUrl();
                } catch (ServiceEndpointNotFoundException e) {
                    // This comes back for not found (probably an incorrect region ID)
                    String error = "cloud did not match an orchestration service for: region=" + region + ",cloud="
                            + cloudIdentity.getIdentityUrl();
                    throw new MsoAdapterException(error, e);
                }
            }
        } catch (OpenStackResponseException e) {
            if (e.getStatus() == 401) {
                // Authentication error.
                String error = "Authentication Failure: tenant=" + tenantId + ",cloud=" + cloudIdentity.getId();

                throw new MsoAdapterException(error);
            } else {
                throw keystoneErrorToMsoException(e, TOKEN_AUTH);
            }
        } catch (OpenStackConnectException e) {
            // Connection to Openstack failed
            MsoIOException me = new MsoIOException(e.getMessage(), e);
            me.addContext(TOKEN_AUTH);
            throw me;
        } catch (RuntimeException e) {
            // Catch-all
            throw runtimeExceptionToMsoException(e, TOKEN_AUTH);
        }
        Heat heatClient = new Heat(heatUrl);
        heatClient.token(tokenId);
        return heatClient;
    }

    /*
     * Query for a Heat Stack. This function is needed in several places, so a common method is useful. This method
     * takes an authenticated Heat Client (which internally identifies the cloud & tenant to search), and returns a
     * Stack object if found, Null if not found, or an MsoOpenstackException if the Openstack API call fails.
     *
     * The stack name may be a simple name or a canonical name ("{name}/{id}"). When simple name is used, Openstack
     * always returns a 302 redirect which results in a 2nd request (to the canonical name). Note that query by
     * canonical name for a deleted stack returns a Stack object with status "DELETE_COMPLETE" while query by simple
     * name for a deleted stack returns HTTP 404.
     *
     * @param heatClient an authenticated Heat client
     *
     * @param stackName the stack name to query
     *
     * @return a Stack object that describes the current stack or null if the requested stack doesn't exist.
     *
     * @throws MsoOpenstackException Thrown if the Openstack API call returns an exception
     */
    public Stack queryHeatStack(Heat heatClient, String stackName) throws MsoException {
        if (stackName == null) {
            return null;
        }
        try {
            OpenStackRequest<Stack> request = heatClient.getStacks().byName(stackName);
            return executeAndRecordOpenstackRequest(request);
        } catch (OpenStackResponseException e) {
            logger.error("Error in Query Stack", e);
            if (e.getStatus() == 404) {
                logger.debug("queryHeatStack - stack not found: {}", stackName);
                return null;
            } else {
                // Convert the OpenStackResponseException to an MsoOpenstackException
                throw heatExceptionToMsoException(e, "QueryStack");
            }
        } catch (OpenStackConnectException e) {
            // Connection to Openstack failed
            throw heatExceptionToMsoException(e, "QueryAllStack");
        }
    }


    public Map<String, Object> queryStackForOutputs(String cloudSiteId, String cloudOwner, String tenantId,
            String stackName) throws MsoException {
        logger.debug("MsoHeatUtils.queryStackForOutputs)");
        StackInfo heatStack = this.queryStack(cloudSiteId, cloudOwner, tenantId, stackName);
        if (heatStack == null || heatStack.getStatus() == HeatStatus.NOTFOUND) {
            return null;
        }
        return heatStack.getOutputs();
    }

    public void copyStringOutputsToInputs(Map<String, Object> inputs, Map<String, Object> otherStackOutputs,
            boolean overWrite) {
        if (inputs == null || otherStackOutputs == null)
            return;
        for (String key : otherStackOutputs.keySet()) {
            if (!inputs.containsKey(key)) {
                Object obj = otherStackOutputs.get(key);
                if (obj instanceof String) {
                    inputs.put(key, (String) otherStackOutputs.get(key));
                } else if (obj instanceof JsonNode) {
                    // This is a bit of mess - but I think it's the least impacting
                    // let's convert it BACK to a string - then it will get converted back later
                    try {
                        String str = this.convertNode((JsonNode) obj);
                        inputs.put(key, str);
                    } catch (Exception e) {
                        logger.debug("DANGER WILL ROBINSON: unable to convert value for JsonNode {} ", key, e);
                        // effect here is this value will not have been copied to the inputs - and therefore will error
                        // out downstream
                    }
                } else if (obj instanceof java.util.LinkedHashMap) {
                    logger.debug("LinkedHashMap - this is showing up as a LinkedHashMap instead of JsonNode");
                    try {
                        String str = JSON_MAPPER.writeValueAsString(obj);
                        inputs.put(key, str);
                    } catch (Exception e) {
                        logger.debug("DANGER WILL ROBINSON: unable to convert value for LinkedHashMap {} ", key, e);
                    }
                } else if (obj instanceof Integer) {
                    try {
                        String str = "" + obj;
                        inputs.put(key, str);
                    } catch (Exception e) {
                        logger.debug("DANGER WILL ROBINSON: unable to convert value for Integer {} ", key, e);
                    }
                } else {
                    try {
                        String str = obj.toString();
                        inputs.put(key, str);
                    } catch (Exception e) {
                        logger.debug("DANGER WILL ROBINSON: unable to convert value for Other {} ({}) ", key,
                                e.getMessage(), e);
                        // effect here is this value will not have been copied to the inputs - and therefore will error
                        // out downstream
                    }
                }
            }
        }
        return;
    }

    public StringBuilder requestToStringBuilder(CreateStackParam stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("Stack:\n");
        sb.append("\tStackName: " + stack.getStackName());
        sb.append("\tTemplateUrl: " + stack.getTemplateUrl());
        sb.append("\tTemplate: " + stack.getTemplate());
        sb.append("\tEnvironment: " + stack.getEnvironment());
        sb.append("\tTimeout: " + stack.getTimeoutMinutes());
        sb.append("\tParameters:\n");
        Map<String, Object> params = stack.getParameters();
        if (params == null || params.size() < 1) {
            sb.append("\nNONE");
        } else {
            for (String key : params.keySet()) {
                if (params.get(key) instanceof String) {
                    sb.append("\n").append(key).append("=").append((String) params.get(key));
                } else if (params.get(key) instanceof JsonNode) {
                    String jsonStringOut = this.convertNode((JsonNode) params.get(key));
                    sb.append("\n").append(key).append("=").append(jsonStringOut);
                } else if (params.get(key) instanceof Integer) {
                    String integerOut = "" + params.get(key);
                    sb.append("\n").append(key).append("=").append(integerOut);

                } else {
                    try {
                        String str = params.get(key).toString();
                        sb.append("\n").append(key).append("=").append(str);
                    } catch (Exception e) {
                        logger.debug("Exception :", e);
                    }
                }
            }
        }
        return sb;
    }

    private String convertNode(final JsonNode node) {
        try {
            final Object obj = JSON_MAPPER.treeToValue(node, Object.class);
            return JSON_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            logger.debug("Error converting json to string {} ", e.getMessage(), e);
        }
        return "[Error converting json to string]";
    }


    protected StringBuilder getOutputsAsStringBuilder(Stack heatStack) {
        // This should only be used as a utility to print out the stack outputs
        // to the log
        StringBuilder sb = new StringBuilder("");
        if (heatStack == null) {
            sb.append("(heatStack is null)");
            return sb;
        }
        List<Output> outputList = heatStack.getOutputs();
        if (outputList == null || outputList.isEmpty()) {
            sb.append("(outputs is empty)");
            return sb;
        }
        Map<String, Object> outputs = new HashMap<>();
        for (Output outputItem : outputList) {
            outputs.put(outputItem.getOutputKey(), outputItem.getOutputValue());
        }
        int counter = 0;
        sb.append("OUTPUTS:\n");
        for (String key : outputs.keySet()) {
            sb.append("outputs[").append(counter++).append("]: ").append(key).append("=");
            Object obj = outputs.get(key);
            if (obj instanceof String) {
                sb.append((String) obj).append(" (a string)");
            } else if (obj instanceof JsonNode) {
                sb.append(this.convertNode((JsonNode) obj)).append(" (a JsonNode)");
            } else if (obj instanceof java.util.LinkedHashMap) {
                try {
                    String str = JSON_MAPPER.writeValueAsString(obj);
                    sb.append(str).append(" (a java.util.LinkedHashMap)");
                } catch (Exception e) {
                    logger.debug("Exception :", e);
                    sb.append("(a LinkedHashMap value that would not convert nicely)");
                }
            } else if (obj instanceof Integer) {
                String str = "";
                try {
                    str = obj.toString() + " (an Integer)\n";
                } catch (Exception e) {
                    logger.debug("Exception :", e);
                    str = "(an Integer unable to call .toString() on)";
                }
                sb.append(str);
            } else if (obj instanceof ArrayList) {
                String str = "";
                try {
                    str = obj.toString() + " (an ArrayList)";
                } catch (Exception e) {
                    logger.debug("Exception :", e);
                    str = "(an ArrayList unable to call .toString() on?)";
                }
                sb.append(str);
            } else if (obj instanceof Boolean) {
                String str = "";
                try {
                    str = obj.toString() + " (a Boolean)";
                } catch (Exception e) {
                    logger.debug("Exception :", e);
                    str = "(an Boolean unable to call .toString() on?)";
                }
                sb.append(str);
            } else {
                String str = "";
                try {
                    str = obj.toString() + " (unknown Object type)";
                } catch (Exception e) {
                    logger.debug("Exception :", e);
                    str = "(a value unable to call .toString() on?)";
                }
                sb.append(str);
            }
            sb.append("\n");
        }
        sb.append("[END]");
        return sb;
    }


    public void copyBaseOutputsToInputs(Map<String, Object> inputs, Map<String, Object> otherStackOutputs,
            List<String> paramNames, Map<String, String> aliases) {
        if (inputs == null || otherStackOutputs == null)
            return;
        for (String key : otherStackOutputs.keySet()) {
            if (paramNames != null) {
                if (!paramNames.contains(key) && !aliases.containsKey(key)) {
                    logger.debug("\tParameter {} is NOT defined to be in the template - do not copy to inputs", key);
                    continue;
                }
                if (aliases.containsKey(key)) {
                    logger.debug("Found an alias! Will move {} to {}", key, aliases.get(key));
                    Object obj = otherStackOutputs.get(key);
                    key = aliases.get(key);
                    otherStackOutputs.put(key, obj);
                }
            }
            if (!inputs.containsKey(key)) {
                Object obj = otherStackOutputs.get(key);
                logger.debug("\t**Adding {} to inputs (.toString()={}", key, obj.toString());
                if (obj instanceof String) {
                    logger.debug("\t\t**A String");
                    inputs.put(key, obj);
                } else if (obj instanceof Integer) {
                    logger.debug("\t\t**An Integer");
                    inputs.put(key, obj);
                } else if (obj instanceof JsonNode) {
                    logger.debug("\t\t**A JsonNode");
                    inputs.put(key, obj);
                } else if (obj instanceof Boolean) {
                    logger.debug("\t\t**A Boolean");
                    inputs.put(key, obj);
                } else if (obj instanceof java.util.LinkedHashMap) {
                    logger.debug("\t\t**A java.util.LinkedHashMap **");
                    inputs.put(key, obj);
                } else if (obj instanceof java.util.ArrayList) {
                    logger.debug("\t\t**An ArrayList");
                    inputs.put(key, obj);
                } else {
                    logger.debug("\t\t**UNKNOWN OBJECT TYPE");
                    inputs.put(key, obj);
                }
            } else {
                logger.debug("key={} is already in the inputs - will not overwrite", key);
            }
        }
        return;
    }

    public List<String> convertCdlToArrayList(String cdl) {
        String cdl2 = cdl.trim();
        String cdl3;
        if (cdl2.startsWith("[") && cdl2.endsWith("]")) {
            cdl3 = cdl2.substring(1, cdl2.lastIndexOf("]"));
        } else {
            cdl3 = cdl2;
        }
        return new ArrayList<>(Arrays.asList(cdl3.split(",")));
    }

    /**
     * New with 1707 - this method will convert all the String *values* of the inputs to their "actual" object type
     * (based on the param type: in the db - which comes from the template): (heat variable type) -> java Object type
     * string -> String number -> Integer json -> marshal object to json comma_delimited_list -> ArrayList boolean ->
     * Boolean if any of the conversions should fail, we will default to adding it to the inputs as a string - see if
     * Openstack can handle it. Also, will remove any params that are extra. Any aliases will be converted to their
     * appropriate name (anyone use this feature?)
     * 
     * @param inputs - the Map<String, String> of the inputs received on the request
     * @param template the HeatTemplate object - this is so we can also verify if the param is valid for this template
     * @return HashMap<String, Object> of the inputs, cleaned and converted
     */
    public Map<String, Object> convertInputMap(Map<String, Object> inputs, HeatTemplate template) {
        HashMap<String, Object> newInputs = new HashMap<>();
        HashMap<String, HeatTemplateParam> params = new HashMap<>();
        HashMap<String, HeatTemplateParam> paramAliases = new HashMap<>();

        if (inputs == null) {
            logger.debug("convertInputMap - inputs is null - nothing to do here");
            return new HashMap<>();
        }

        logger.debug("convertInputMap in MsoHeatUtils called, with {} inputs, and template {}", inputs.size(),
                template.getArtifactUuid());
        try {
            logger.debug(template.toString());
            Set<HeatTemplateParam> paramSet = template.getParameters();
            logger.debug("paramSet has {} entries", paramSet.size());
        } catch (Exception e) {
            logger.debug("Exception occurred in convertInputMap {} :", e.getMessage(), e);
        }

        for (HeatTemplateParam htp : template.getParameters()) {
            logger.debug("Adding {}", htp.getParamName());
            params.put(htp.getParamName(), htp);
            if (htp.getParamAlias() != null && !"".equals(htp.getParamAlias())) {
                logger.debug("\tFound ALIAS {} -> {}", htp.getParamName(), htp.getParamAlias());
                paramAliases.put(htp.getParamAlias(), htp);
            }
        }
        logger.debug("Now iterate through the inputs...");
        for (String key : inputs.keySet()) {
            logger.debug("key={}", key);
            boolean alias = false;
            String realName = null;
            if (!params.containsKey(key)) {
                logger.debug("{} is not a parameter in the template! - check for an alias", key);
                // add check here for an alias
                if (!paramAliases.containsKey(key)) {
                    logger.debug("The parameter {} is in the inputs, but it's not a parameter for this template - omit",
                            key);
                    continue;
                } else {
                    alias = true;
                    realName = paramAliases.get(key).getParamName();
                    logger.debug("FOUND AN ALIAS! Will use {} in lieu of give key/alias {}", realName, key);
                }
            }
            String type = params.get(key).getParamType();
            if (type == null || "".equals(type)) {
                logger.debug("**PARAM_TYPE is null/empty for {}, will default to string", key);
                type = "string";
            }
            logger.debug("Parameter: {} is of type {}", key, type);
            if ("string".equalsIgnoreCase(type)) {
                // Easiest!
                String str = inputs.get(key) != null ? inputs.get(key).toString() : null;
                if (alias)
                    newInputs.put(realName, str);
                else
                    newInputs.put(key, str);
            } else if ("number".equalsIgnoreCase(type)) {
                String integerString = inputs.get(key) != null ? inputs.get(key).toString() : null;
                Integer anInteger = null;
                try {
                    anInteger = Integer.parseInt(integerString);
                } catch (Exception e) {
                    logger.debug("Unable to convert {} to an integer!!", integerString, e);
                    anInteger = null;
                }
                if (anInteger != null) {
                    if (alias)
                        newInputs.put(realName, anInteger);
                    else
                        newInputs.put(key, anInteger);
                } else {
                    if (alias)
                        newInputs.put(realName, integerString);
                    else
                        newInputs.put(key, integerString);
                }
            } else if ("json".equalsIgnoreCase(type)) {
                Object jsonObj = inputs.get(key);
                Object json;
                try {
                    if (jsonObj instanceof String) {
                        json = JSON_MAPPER.readTree(jsonObj.toString());
                    } else {
                        // will already marshal to json without intervention
                        json = jsonObj;
                    }
                } catch (IOException e) {
                    logger.error("failed to map to json, directly converting to string instead", e);
                    json = jsonObj.toString();
                }
                if (alias)
                    newInputs.put(realName, json);
                else
                    newInputs.put(key, json);
            } else if ("comma_delimited_list".equalsIgnoreCase(type)) {
                String commaSeparated = inputs.get(key) != null ? inputs.get(key).toString() : null;
                try {
                    List<String> anArrayList = this.convertCdlToArrayList(commaSeparated);
                    if (alias)
                        newInputs.put(realName, anArrayList);
                    else
                        newInputs.put(key, anArrayList);
                } catch (Exception e) {
                    logger.debug("Unable to convert {} to an ArrayList!!", commaSeparated, e);
                    if (alias)
                        newInputs.put(realName, commaSeparated);
                    else
                        newInputs.put(key, commaSeparated);
                }
            } else if ("boolean".equalsIgnoreCase(type)) {
                String booleanString = inputs.get(key) != null ? inputs.get(key).toString() : null;
                Boolean aBool = Boolean.valueOf(booleanString);
                if (alias)
                    newInputs.put(realName, aBool);
                else
                    newInputs.put(key, aBool);
            } else {
                // it's null or something undefined - just add it back as a String
                String str = inputs.get(key).toString();
                if (alias)
                    newInputs.put(realName, str);
                else
                    newInputs.put(key, str);
            }
        }
        return newInputs;
    }

    /*
     * This helpful method added for Valet
     */
    public String getCloudSiteKeystoneUrl(String cloudSiteId) throws MsoCloudSiteNotFound {
        String keystoneUrl = null;
        try {
            CloudSite cloudSite =
                    cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
            CloudIdentity cloudIdentity = cloudSite.getIdentityService();
            keystoneUrl = cloudIdentity.getIdentityUrl();
        } catch (Exception e) {
            throw new MsoCloudSiteNotFound(cloudSiteId);
        }
        if (keystoneUrl == null || keystoneUrl.isEmpty()) {
            throw new MsoCloudSiteNotFound(cloudSiteId);
        }
        return keystoneUrl;
    }


    /*******************************************************************************
     *
     * Methods (and associated utilities) to implement the VduPlugin interface
     *
     *******************************************************************************/

    /**
     * VduPlugin interface for instantiate function.
     *
     * Translate the VduPlugin parameters to the corresponding 'createStack' parameters, and then invoke the existing
     * function.
     */
    @Override
    public VduInstance instantiateVdu(CloudInfo cloudInfo, String instanceName, Map<String, Object> inputs,
            VduModelInfo vduModel, boolean rollbackOnFailure) throws VduException {
        String cloudSiteId = cloudInfo.getCloudSiteId();
        String cloudOwner = cloudInfo.getCloudOwner();
        String tenantId = cloudInfo.getTenantId();

        // Translate the VDU ModelInformation structure to that which is needed for
        // creating the Heat stack. Loop through the artifacts, looking specifically
        // for MAIN_TEMPLATE and ENVIRONMENT. Any other artifact will
        // be attached as a FILE.
        String heatTemplate = null;
        Map<String, Object> nestedTemplates = new HashMap<>();
        Map<String, Object> files = new HashMap<>();
        String heatEnvironment = null;

        for (VduArtifact vduArtifact : vduModel.getArtifacts()) {
            if (vduArtifact.getType() == ArtifactType.MAIN_TEMPLATE) {
                heatTemplate = new String(vduArtifact.getContent());
            } else if (vduArtifact.getType() == ArtifactType.NESTED_TEMPLATE) {
                nestedTemplates.put(vduArtifact.getName(), new String(vduArtifact.getContent()));
            } else if (vduArtifact.getType() == ArtifactType.ENVIRONMENT) {
                heatEnvironment = new String(vduArtifact.getContent());
            }
        }

        try {
            StackInfo stackInfo =
                    createStack(cloudSiteId, cloudOwner, tenantId, instanceName, vduModel, heatTemplate, inputs, true, // poll
                                                                                                                       // for
                                                                                                                       // completion
                            vduModel.getTimeoutMinutes(), heatEnvironment, nestedTemplates, files, rollbackOnFailure);

            // Populate a vduInstance from the StackInfo
            return stackInfoToVduInstance(stackInfo);
        } catch (Exception e) {
            throw new VduException("MsoHeatUtils (instantiateVDU): createStack Exception", e);
        }
    }


    /**
     * VduPlugin interface for query function.
     */
    @Override
    public VduInstance queryVdu(CloudInfo cloudInfo, String instanceId) throws VduException {
        String cloudSiteId = cloudInfo.getCloudSiteId();
        String cloudOwner = cloudInfo.getCloudOwner();
        String tenantId = cloudInfo.getTenantId();

        try {
            // Query the Cloudify Deployment object and populate a VduInstance
            StackInfo stackInfo = queryStack(cloudSiteId, cloudOwner, tenantId, instanceId);

            return stackInfoToVduInstance(stackInfo);
        } catch (Exception e) {
            throw new VduException("MsoHeatUtile (queryVdu): queryStack Exception ", e);
        }
    }


    /**
     * VduPlugin interface for delete function.
     */
    @Override
    public VduInstance deleteVdu(CloudInfo cloudInfo, String instanceId, int timeoutMinutes) throws VduException {
        String cloudSiteId = cloudInfo.getCloudSiteId();
        String cloudOwner = cloudInfo.getCloudOwner();
        String tenantId = cloudInfo.getTenantId();

        try {
            // Delete the Heat stack
            StackInfo stackInfo = deleteStack(tenantId, cloudOwner, cloudSiteId, instanceId, true);

            // Populate a VduInstance based on the deleted Cloudify Deployment object
            VduInstance vduInstance = stackInfoToVduInstance(stackInfo);

            // Override return state to DELETED (HeatUtils sets to NOTFOUND)
            vduInstance.getStatus().setState(VduStateType.DELETED);

            return vduInstance;
        } catch (Exception e) {
            throw new VduException("Delete VDU Exception", e);
        }
    }


    /**
     * VduPlugin interface for update function.
     *
     * Update is currently not supported in the MsoHeatUtils implementation of VduPlugin. Just return a VduException.
     *
     */
    @Override
    public VduInstance updateVdu(CloudInfo cloudInfo, String instanceId, Map<String, Object> inputs,
            VduModelInfo vduModel, boolean rollbackOnFailure) throws VduException {
        throw new VduException("MsoHeatUtils: updateVdu interface not supported");
    }


    /*
     * Convert the local DeploymentInfo object (Cloudify-specific) to a generic VduInstance object
     */
    protected VduInstance stackInfoToVduInstance(StackInfo stackInfo) {
        VduInstance vduInstance = new VduInstance();

        // The full canonical name as the instance UUID
        vduInstance.setVduInstanceId(stackInfo.getCanonicalName());
        vduInstance.setVduInstanceName(stackInfo.getName());

        // Copy inputs and outputs
        vduInstance.setInputs(stackInfo.getParameters());
        vduInstance.setOutputs(stackInfo.getOutputs());

        // Translate the status elements
        vduInstance.setStatus(stackStatusToVduStatus(stackInfo));

        return vduInstance;
    }

    private VduStatus stackStatusToVduStatus(StackInfo stackInfo) {
        VduStatus vduStatus = new VduStatus();

        // Map the status fields to more generic VduStatus.
        // There are lots of HeatStatus values, so this is a bit long...
        HeatStatus heatStatus = stackInfo.getStatus();
        String statusMessage = stackInfo.getStatusMessage();

        if (heatStatus == HeatStatus.INIT || heatStatus == HeatStatus.BUILDING) {
            vduStatus.setState(VduStateType.INSTANTIATING);
            vduStatus.setLastAction((new PluginAction("create", IN_PROGRESS, statusMessage)));
        } else if (heatStatus == HeatStatus.NOTFOUND) {
            vduStatus.setState(VduStateType.NOTFOUND);
        } else if (heatStatus == HeatStatus.CREATED) {
            vduStatus.setState(VduStateType.INSTANTIATED);
            vduStatus.setLastAction((new PluginAction("create", "complete", statusMessage)));
        } else if (heatStatus == HeatStatus.UPDATED) {
            vduStatus.setState(VduStateType.INSTANTIATED);
            vduStatus.setLastAction((new PluginAction("update", "complete", statusMessage)));
        } else if (heatStatus == HeatStatus.UPDATING) {
            vduStatus.setState(VduStateType.UPDATING);
            vduStatus.setLastAction((new PluginAction("update", IN_PROGRESS, statusMessage)));
        } else if (heatStatus == HeatStatus.DELETING) {
            vduStatus.setState(VduStateType.DELETING);
            vduStatus.setLastAction((new PluginAction("delete", IN_PROGRESS, statusMessage)));
        } else if (heatStatus == HeatStatus.FAILED) {
            vduStatus.setState(VduStateType.FAILED);
            vduStatus.setErrorMessage(stackInfo.getStatusMessage());
        } else {
            vduStatus.setState(VduStateType.UNKNOWN);
        }

        return vduStatus;
    }

    public Resources queryStackResources(String cloudSiteId, String tenantId, String stackName, int nestedDepth)
            throws MsoException {
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        Heat heatClient = getHeatClient(cloudSite, tenantId);
        OpenStackRequest<Resources> request =
                heatClient.getResources().listResources(stackName).queryParam("nested_depth", nestedDepth);
        return executeAndRecordOpenstackRequest(request);
    }

    public Events queryStackEvents(String cloudSiteId, String tenantId, String stackName, String stackId,
            int nestedDepth) throws MsoException {
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        Heat heatClient = getHeatClient(cloudSite, tenantId);
        OpenStackRequest<Events> request =
                heatClient.getEvents().listEvents(stackName, stackId).queryParam("nested_depth", nestedDepth);
        return executeAndRecordOpenstackRequest(request);
    }

    public Stacks queryStacks(String cloudSiteId, String tenantId, int limit, String marker)
            throws MsoCloudSiteNotFound, HeatClientException {
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        Heat heatClient;
        try {
            heatClient = getHeatClient(cloudSite, tenantId);
        } catch (MsoException e) {
            logger.error("Error Creating Heat Client", e);
            throw new HeatClientException("Error Creating Heat Client", e);
        }
        OpenStackRequest<Stacks> request =
                heatClient.getStacks().list().queryParam("limit", limit).queryParam("marker", marker);
        return executeAndRecordOpenstackRequest(request);
    }

    public <R> R executeHeatClientRequest(String url, String cloudSiteId, String tenantId, Class<R> returnType)
            throws MsoException {
        CloudSite cloudSite =
                cloudConfig.getCloudSite(cloudSiteId).orElseThrow(() -> new MsoCloudSiteNotFound(cloudSiteId));
        Heat heatClient = getHeatClient(cloudSite, tenantId);
        OpenStackRequest<R> request = heatClient.get(url, returnType);
        return executeAndRecordOpenstackRequest(request);
    }

    protected void sleep(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            logger.debug("Thread interrupted while sleeping", e);
            Thread.currentThread().interrupt();
        }
    }

}
