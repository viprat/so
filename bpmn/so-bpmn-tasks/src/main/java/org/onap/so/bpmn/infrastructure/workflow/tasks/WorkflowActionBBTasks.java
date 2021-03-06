/*-
 * ============LICENSE_START=======================================================
 * ONAP - SO
 * ================================================================================
 * Copyright (C) 2017 - 2018 AT&T Intellectual Property. All rights reserved.
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

package org.onap.so.bpmn.infrastructure.workflow.tasks;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.persistence.EntityNotFoundException;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.onap.aai.domain.yang.Vnfc;
import org.onap.so.bpmn.common.workflow.context.WorkflowCallbackResponse;
import org.onap.so.bpmn.common.workflow.context.WorkflowContextHolder;
import org.onap.so.bpmn.servicedecomposition.entities.BuildingBlock;
import org.onap.so.bpmn.servicedecomposition.entities.ConfigurationResourceKeys;
import org.onap.so.bpmn.servicedecomposition.entities.ExecuteBuildingBlock;
import org.onap.so.bpmn.servicedecomposition.entities.WorkflowResourceIds;
import org.onap.so.bpmn.servicedecomposition.tasks.BBInputSetupUtils;
import org.onap.so.client.aai.AAIObjectType;
import org.onap.so.client.exception.ExceptionBuilder;
import org.onap.so.db.catalog.beans.CvnfcConfigurationCustomization;
import org.onap.so.db.catalog.beans.VnfResourceCustomization;
import org.onap.so.db.catalog.client.CatalogDbClient;
import org.onap.so.db.request.beans.InfraActiveRequests;
import org.onap.so.db.request.client.RequestsDbClient;
import org.onap.so.serviceinstancebeans.RequestReferences;
import org.onap.so.serviceinstancebeans.ServiceInstancesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class WorkflowActionBBTasks {

    private static final String G_CURRENT_SEQUENCE = "gCurrentSequence";
    private static final String G_REQUEST_ID = "mso-request-id";
    private static final String G_ALACARTE = "aLaCarte";
    private static final String G_ACTION = "requestAction";
    private static final String RETRY_COUNT = "retryCount";
    private static final String FABRIC_CONFIGURATION = "FabricConfiguration";
    private static final String ASSIGN_FABRIC_CONFIGURATION_BB = "AssignFabricConfigurationBB";
    private static final String ACTIVATE_FABRIC_CONFIGURATION_BB = "ActivateFabricConfigurationBB";
    protected String maxRetries = "mso.rainyDay.maxRetries";
    private static final Logger logger = LoggerFactory.getLogger(WorkflowActionBBTasks.class);

    @Autowired
    private RequestsDbClient requestDbclient;
    @Autowired
    private WorkflowAction workflowAction;
    @Autowired
    private WorkflowActionBBFailure workflowActionBBFailure;
    @Autowired
    private Environment environment;
    @Autowired
    private BBInputSetupUtils bbInputSetupUtils;
    @Autowired
    private CatalogDbClient catalogDbClient;

    public void selectBB(DelegateExecution execution) {
        List<ExecuteBuildingBlock> flowsToExecute =
                (List<ExecuteBuildingBlock>) execution.getVariable("flowsToExecute");
        execution.setVariable("MacroRollback", false);
        int currentSequence = (int) execution.getVariable(G_CURRENT_SEQUENCE);
        ExecuteBuildingBlock ebb = flowsToExecute.get(currentSequence);

        if (ebb.getBuildingBlock().getBpmnFlowName().equals("ConfigAssignVnfBB")
                || ebb.getBuildingBlock().getBpmnFlowName().equals("ConfigDeployVnfBB")) {
            String serviceInstanceId = ebb.getWorkflowResourceIds().getServiceInstanceId();
            String vnfCustomizationUUID = ebb.getBuildingBlock().getKey();

            List<VnfResourceCustomization> vnfResourceCustomizations =
                    catalogDbClient.getVnfResourceCustomizationByModelUuid(serviceInstanceId);
            if (vnfResourceCustomizations != null && vnfResourceCustomizations.size() >= 1) {
                VnfResourceCustomization vrc = catalogDbClient.findVnfResourceCustomizationInList(vnfCustomizationUUID,
                        vnfResourceCustomizations);
                boolean skipConfigVNF = vrc.isSkipPostInstConf();
                if (skipConfigVNF) {
                    currentSequence++;
                    ebb = flowsToExecute.get(currentSequence);
                }
            }
        }

        boolean homing = (boolean) execution.getVariable("homing");
        boolean calledHoming = (boolean) execution.getVariable("calledHoming");
        if (homing && !calledHoming) {
            if (ebb.getBuildingBlock().getBpmnFlowName().equals("AssignVnfBB")) {
                ebb.setHoming(true);
                execution.setVariable("calledHoming", true);
            }
        } else {
            ebb.setHoming(false);
        }
        execution.setVariable("buildingBlock", ebb);
        currentSequence++;
        if (currentSequence >= flowsToExecute.size()) {
            execution.setVariable("completed", true);
        } else {
            execution.setVariable("completed", false);
        }
        execution.setVariable(G_CURRENT_SEQUENCE, currentSequence);
    }

    public void updateFlowStatistics(DelegateExecution execution) {
        try {
            int currentSequence = (int) execution.getVariable(G_CURRENT_SEQUENCE);
            if (currentSequence > 1) {
                InfraActiveRequests request = this.getUpdatedRequest(execution, currentSequence);
                requestDbclient.updateInfraActiveRequests(request);
            }
        } catch (Exception ex) {
            logger.warn(
                    "Bpmn Flow Statistics was unable to update Request Db with the new completion percentage. Competion percentage may be invalid.");
        }
    }

    protected InfraActiveRequests getUpdatedRequest(DelegateExecution execution, int currentSequence) {
        List<ExecuteBuildingBlock> flowsToExecute =
                (List<ExecuteBuildingBlock>) execution.getVariable("flowsToExecute");
        String requestId = (String) execution.getVariable(G_REQUEST_ID);
        InfraActiveRequests request = requestDbclient.getInfraActiveRequestbyRequestId(requestId);
        ExecuteBuildingBlock completedBB = flowsToExecute.get(currentSequence - 2);
        ExecuteBuildingBlock nextBB = flowsToExecute.get(currentSequence - 1);
        int completedBBs = currentSequence - 1;
        int totalBBs = flowsToExecute.size();
        int remainingBBs = totalBBs - completedBBs;
        String statusMessage = this.getStatusMessage(completedBB.getBuildingBlock().getBpmnFlowName(),
                nextBB.getBuildingBlock().getBpmnFlowName(), completedBBs, remainingBBs);
        Long percentProgress = this.getPercentProgress(completedBBs, totalBBs);
        request.setFlowStatus(statusMessage);
        request.setProgress(percentProgress);
        request.setLastModifiedBy("CamundaBPMN");
        return request;
    }

    protected Long getPercentProgress(int completedBBs, int totalBBs) {
        double ratio = (completedBBs / (totalBBs * 1.0));
        int percentProgress = (int) (ratio * 95);
        return new Long(percentProgress + 5);
    }

    protected String getStatusMessage(String completedBB, String nextBB, int completedBBs, int remainingBBs) {
        return "Execution of " + completedBB + " has completed successfully, next invoking " + nextBB
                + " (Execution Path progress: BBs completed = " + completedBBs + "; BBs remaining = " + remainingBBs
                + ").";
    }

    public void sendSyncAck(DelegateExecution execution) {
        final String requestId = (String) execution.getVariable(G_REQUEST_ID);
        final String resourceId = (String) execution.getVariable("resourceId");
        ServiceInstancesResponse serviceInstancesResponse = new ServiceInstancesResponse();
        RequestReferences requestRef = new RequestReferences();
        requestRef.setInstanceId(resourceId);
        requestRef.setRequestId(requestId);
        serviceInstancesResponse.setRequestReferences(requestRef);
        ObjectMapper mapper = new ObjectMapper();
        String jsonRequest = "";
        try {
            jsonRequest = mapper.writeValueAsString(serviceInstancesResponse);
        } catch (JsonProcessingException e) {
            workflowAction.buildAndThrowException(execution,
                    "Could not marshall ServiceInstancesRequest to Json string to respond to API Handler.", e);
        }
        WorkflowCallbackResponse callbackResponse = new WorkflowCallbackResponse();
        callbackResponse.setStatusCode(200);
        callbackResponse.setMessage("Success");
        callbackResponse.setResponse(jsonRequest);
        String processKey = execution.getProcessEngineServices().getRepositoryService()
                .getProcessDefinition(execution.getProcessDefinitionId()).getKey();
        WorkflowContextHolder.getInstance().processCallback(processKey, execution.getProcessInstanceId(), requestId,
                callbackResponse);
        logger.info("Successfully sent sync ack.");
        updateInstanceId(execution);
    }

    public void sendErrorSyncAck(DelegateExecution execution) {
        final String requestId = (String) execution.getVariable(G_REQUEST_ID);
        try {
            ExceptionBuilder exceptionBuilder = new ExceptionBuilder();
            String errorMsg = (String) execution.getVariable("WorkflowActionErrorMessage");
            if (errorMsg == null) {
                errorMsg = "WorkflowAction failed unexpectedly.";
            }
            String processKey = exceptionBuilder.getProcessKey(execution);
            String buildworkflowException =
                    "<aetgt:WorkflowException xmlns:aetgt=\"http://org.onap/so/workflow/schema/v1\"><aetgt:ErrorMessage>"
                            + errorMsg
                            + "</aetgt:ErrorMessage><aetgt:ErrorCode>7000</aetgt:ErrorCode></aetgt:WorkflowException>";
            WorkflowCallbackResponse callbackResponse = new WorkflowCallbackResponse();
            callbackResponse.setStatusCode(500);
            callbackResponse.setMessage("Fail");
            callbackResponse.setResponse(buildworkflowException);
            WorkflowContextHolder.getInstance().processCallback(processKey, execution.getProcessInstanceId(), requestId,
                    callbackResponse);
            execution.setVariable("sentSyncResponse", true);
        } catch (Exception ex) {
            logger.error(" Sending Sync Error Activity Failed. {}", ex.getMessage(), ex);
        }
    }

    public void updateRequestStatusToComplete(DelegateExecution execution) {
        try {
            final String requestId = (String) execution.getVariable(G_REQUEST_ID);
            InfraActiveRequests request = requestDbclient.getInfraActiveRequestbyRequestId(requestId);
            final String action = (String) execution.getVariable(G_ACTION);
            final boolean aLaCarte = (boolean) execution.getVariable(G_ALACARTE);
            final String resourceName = (String) execution.getVariable("resourceName");
            String macroAction = "";
            if (aLaCarte) {
                macroAction = "ALaCarte-" + resourceName + "-" + action + " request was executed correctly.";
            } else {
                macroAction = "Macro-" + resourceName + "-" + action + " request was executed correctly.";
            }
            execution.setVariable("finalStatusMessage", macroAction);
            Timestamp endTime = new Timestamp(System.currentTimeMillis());
            request.setEndTime(endTime);
            request.setFlowStatus("Successfully completed all Building Blocks");
            request.setStatusMessage(macroAction);
            request.setProgress(Long.valueOf(100));
            request.setRequestStatus("COMPLETE");
            request.setLastModifiedBy("CamundaBPMN");
            requestDbclient.updateInfraActiveRequests(request);
        } catch (Exception ex) {
            workflowAction.buildAndThrowException(execution, "Error Updating Request Database", ex);
        }
    }

    public void checkRetryStatus(DelegateExecution execution) {
        String handlingCode = (String) execution.getVariable("handlingCode");
        String requestId = (String) execution.getVariable(G_REQUEST_ID);
        String retryDuration = (String) execution.getVariable("RetryDuration");
        int retryCount = (int) execution.getVariable(RETRY_COUNT);
        int envMaxRetries;
        try {
            envMaxRetries = Integer.parseInt(this.environment.getProperty(maxRetries));
        } catch (Exception ex) {
            logger.error("Could not read maxRetries from config file. Setting max to 5 retries");
            envMaxRetries = 5;
        }
        int nextCount = retryCount + 1;
        if (handlingCode.equals("Retry")) {
            workflowActionBBFailure.updateRequestErrorStatusMessage(execution);
            try {
                InfraActiveRequests request = requestDbclient.getInfraActiveRequestbyRequestId(requestId);
                request.setRetryStatusMessage(
                        "Retry " + nextCount + "/" + envMaxRetries + " will be started in " + retryDuration);
                requestDbclient.updateInfraActiveRequests(request);
            } catch (Exception ex) {
                logger.warn("Failed to update Request Db Infra Active Requests with Retry Status", ex);
            }
            if (retryCount < envMaxRetries) {
                int currSequence = (int) execution.getVariable("gCurrentSequence");
                execution.setVariable("gCurrentSequence", currSequence - 1);
                execution.setVariable(RETRY_COUNT, nextCount);
            } else {
                workflowAction.buildAndThrowException(execution,
                        "Exceeded maximum retries. Ending flow with status Abort");
            }
        } else {
            execution.setVariable(RETRY_COUNT, 0);
        }
    }

    /**
     * Rollback will only handle Create/Activate/Assign Macro flows. Execute layer will rollback the flow its currently
     * working on.
     */
    public void rollbackExecutionPath(DelegateExecution execution) {
        if (!(boolean) execution.getVariable("isRollback")) {
            List<ExecuteBuildingBlock> flowsToExecute =
                    (List<ExecuteBuildingBlock>) execution.getVariable("flowsToExecute");
            List<ExecuteBuildingBlock> rollbackFlows = new ArrayList();
            int currentSequence = (int) execution.getVariable(G_CURRENT_SEQUENCE);
            int listSize = flowsToExecute.size();
            for (int i = listSize - 1; i >= 0; i--) {
                if (i > currentSequence - 1) {
                    flowsToExecute.remove(i);
                } else {
                    String flowName = flowsToExecute.get(i).getBuildingBlock().getBpmnFlowName();
                    if (flowName.contains("Assign")) {
                        flowName = "Unassign" + flowName.substring(6, flowName.length());
                    } else if (flowName.contains("Create")) {
                        flowName = "Delete" + flowName.substring(6, flowName.length());
                    } else if (flowName.contains("Activate")) {
                        flowName = "Deactivate" + flowName.substring(8, flowName.length());
                    } else {
                        continue;
                    }
                    flowsToExecute.get(i).getBuildingBlock().setBpmnFlowName(flowName);
                    rollbackFlows.add(flowsToExecute.get(i));
                }
            }

            String handlingCode = (String) execution.getVariable("handlingCode");
            List<ExecuteBuildingBlock> rollbackFlowsFiltered = new ArrayList<>();
            rollbackFlowsFiltered.addAll(rollbackFlows);
            if (handlingCode.equals("RollbackToAssigned") || handlingCode.equals("RollbackToCreated")) {
                for (int i = 0; i < rollbackFlows.size(); i++) {
                    if (rollbackFlows.get(i).getBuildingBlock().getBpmnFlowName().contains("Unassign")) {
                        rollbackFlowsFiltered.remove(rollbackFlows.get(i));
                    } else if (rollbackFlows.get(i).getBuildingBlock().getBpmnFlowName().contains("Delete")
                            && handlingCode.equals("RollbackToCreated")) {
                        rollbackFlowsFiltered.remove(rollbackFlows.get(i));
                    }
                }
            }

            workflowActionBBFailure.updateRequestErrorStatusMessage(execution);

            if (rollbackFlows.isEmpty())
                execution.setVariable("isRollbackNeeded", false);
            else
                execution.setVariable("isRollbackNeeded", true);
            execution.setVariable("flowsToExecute", rollbackFlowsFiltered);
            execution.setVariable("handlingCode", "PreformingRollback");
            execution.setVariable("isRollback", true);
            execution.setVariable("gCurrentSequence", 0);
            execution.setVariable(RETRY_COUNT, 0);
        } else {
            workflowAction.buildAndThrowException(execution,
                    "Rollback has already been called. Cannot rollback a request that is currently in the rollback state.");
        }
    }

    protected void updateInstanceId(DelegateExecution execution) {
        try {
            String requestId = (String) execution.getVariable(G_REQUEST_ID);
            String resourceId = (String) execution.getVariable("resourceId");
            WorkflowType resourceType = (WorkflowType) execution.getVariable("resourceType");
            InfraActiveRequests request = requestDbclient.getInfraActiveRequestbyRequestId(requestId);
            if (resourceType == WorkflowType.SERVICE) {
                request.setServiceInstanceId(resourceId);
            } else if (resourceType == WorkflowType.VNF) {
                request.setVnfId(resourceId);
            } else if (resourceType == WorkflowType.VFMODULE) {
                request.setVfModuleId(resourceId);
            } else if (resourceType == WorkflowType.VOLUMEGROUP) {
                request.setVolumeGroupId(resourceId);
            } else if (resourceType == WorkflowType.NETWORK) {
                request.setNetworkId(resourceId);
            } else if (resourceType == WorkflowType.CONFIGURATION) {
                request.setConfigurationId(resourceId);
            } else if (resourceType == WorkflowType.INSTANCE_GROUP) {
                request.setInstanceGroupId(resourceId);
            }
            requestDbclient.updateInfraActiveRequests(request);
        } catch (Exception ex) {
            workflowAction.buildAndThrowException(execution, "Failed to update Request db with instanceId");
        }
    }

    public void postProcessingExecuteBB(DelegateExecution execution) {
        List<ExecuteBuildingBlock> flowsToExecute =
                (List<ExecuteBuildingBlock>) execution.getVariable("flowsToExecute");
        String handlingCode = (String) execution.getVariable("handlingCode");
        final boolean aLaCarte = (boolean) execution.getVariable(G_ALACARTE);
        int currentSequence = (int) execution.getVariable(G_CURRENT_SEQUENCE);
        ExecuteBuildingBlock ebb = flowsToExecute.get(currentSequence - 1);
        String bbFlowName = ebb.getBuildingBlock().getBpmnFlowName();
        if (bbFlowName.equalsIgnoreCase("ActivateVfModuleBB") && aLaCarte && handlingCode.equalsIgnoreCase("Success")) {
            postProcessingExecuteBBActivateVfModule(execution, ebb, flowsToExecute);
        }
    }

    protected void postProcessingExecuteBBActivateVfModule(DelegateExecution execution, ExecuteBuildingBlock ebb,
            List<ExecuteBuildingBlock> flowsToExecute) {
        try {
            String serviceInstanceId = ebb.getWorkflowResourceIds().getServiceInstanceId();
            String vnfId = ebb.getWorkflowResourceIds().getVnfId();
            String vfModuleId = ebb.getResourceId();
            ebb.getWorkflowResourceIds().setVfModuleId(vfModuleId);
            String serviceModelUUID =
                    bbInputSetupUtils.getAAIServiceInstanceById(serviceInstanceId).getModelVersionId();
            String vnfCustomizationUUID = bbInputSetupUtils.getAAIGenericVnf(vnfId).getModelCustomizationId();
            String vfModuleCustomizationUUID =
                    bbInputSetupUtils.getAAIVfModule(vnfId, vfModuleId).getModelCustomizationId();
            List<Vnfc> vnfcs =
                    workflowAction.getRelatedResourcesInVfModule(vnfId, vfModuleId, Vnfc.class, AAIObjectType.VNFC);
            logger.debug("Vnfc Size: {}", vnfcs.size());
            for (Vnfc vnfc : vnfcs) {
                String modelCustomizationId = vnfc.getModelCustomizationId();
                logger.debug("Processing Vnfc: {}", modelCustomizationId);
                CvnfcConfigurationCustomization fabricConfig = catalogDbClient.getCvnfcCustomization(serviceModelUUID,
                        vnfCustomizationUUID, vfModuleCustomizationUUID, modelCustomizationId);
                if (fabricConfig != null && fabricConfig.getConfigurationResource() != null
                        && fabricConfig.getConfigurationResource().getToscaNodeType() != null
                        && fabricConfig.getConfigurationResource().getToscaNodeType().contains(FABRIC_CONFIGURATION)) {
                    String configurationId = UUID.randomUUID().toString();
                    ConfigurationResourceKeys configurationResourceKeys = new ConfigurationResourceKeys();
                    configurationResourceKeys.setCvnfcCustomizationUUID(modelCustomizationId);
                    configurationResourceKeys.setVfModuleCustomizationUUID(vfModuleCustomizationUUID);
                    configurationResourceKeys.setVnfResourceCustomizationUUID(vnfCustomizationUUID);
                    configurationResourceKeys.setVnfcName(vnfc.getVnfcName());
                    ExecuteBuildingBlock assignConfigBB = getExecuteBBForConfig(ASSIGN_FABRIC_CONFIGURATION_BB, ebb,
                            configurationId, configurationResourceKeys);
                    ExecuteBuildingBlock activateConfigBB = getExecuteBBForConfig(ACTIVATE_FABRIC_CONFIGURATION_BB, ebb,
                            configurationId, configurationResourceKeys);
                    flowsToExecute.add(assignConfigBB);
                    flowsToExecute.add(activateConfigBB);
                    flowsToExecute.stream()
                            .forEach(executeBB -> logger.info("Flows to Execute After Post Processing: {}",
                                    executeBB.getBuildingBlock().getBpmnFlowName()));
                    execution.setVariable("flowsToExecute", flowsToExecute);
                    execution.setVariable("completed", false);
                } else {
                    logger.debug("No cvnfcCustomization found for customizationId: " + modelCustomizationId);
                }
            }
        } catch (EntityNotFoundException e) {
            logger.debug(e.getMessage() + " Will not be running Fabric Config Building Blocks");
        } catch (Exception e) {
            String errorMessage = "Error occurred in post processing of Vf Module create";
            execution.setVariable("handlingCode", "RollbackToCreated");
            execution.setVariable("WorkflowActionErrorMessage", errorMessage);
            logger.error(errorMessage, e);
        }
    }

    protected ExecuteBuildingBlock getExecuteBBForConfig(String bbName, ExecuteBuildingBlock ebb,
            String configurationId, ConfigurationResourceKeys configurationResourceKeys) {
        ExecuteBuildingBlock configBB = new ExecuteBuildingBlock();
        BuildingBlock buildingBlock = new BuildingBlock();
        buildingBlock.setBpmnFlowName(bbName);
        buildingBlock.setMsoId(UUID.randomUUID().toString());
        configBB.setaLaCarte(ebb.isaLaCarte());
        configBB.setApiVersion(ebb.getApiVersion());
        configBB.setRequestAction(ebb.getRequestAction());
        configBB.setVnfType(ebb.getVnfType());
        configBB.setRequestId(ebb.getRequestId());
        configBB.setRequestDetails(ebb.getRequestDetails());
        configBB.setBuildingBlock(buildingBlock);
        WorkflowResourceIds workflowResourceIds = ebb.getWorkflowResourceIds();
        workflowResourceIds.setConfigurationId(configurationId);
        configBB.setWorkflowResourceIds(workflowResourceIds);
        configBB.setConfigurationResourceKeys(configurationResourceKeys);
        return configBB;
    }
}
