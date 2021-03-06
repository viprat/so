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

package org.onap.so.apihandlerinfra;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.http.HttpStatus;
import org.onap.so.apihandler.common.ErrorNumbers;
import org.onap.so.apihandler.common.RequestClientParameter;
import org.onap.so.apihandlerinfra.exceptions.ApiException;
import org.onap.so.apihandlerinfra.exceptions.RecipeNotFoundException;
import org.onap.so.apihandlerinfra.exceptions.RequestDbFailureException;
import org.onap.so.apihandlerinfra.exceptions.ValidateException;
import org.onap.so.apihandlerinfra.logging.ErrorLoggerInfo;
import org.onap.so.db.catalog.beans.Workflow;
import org.onap.so.db.catalog.client.CatalogDbClient;
import org.onap.so.db.request.beans.InfraActiveRequests;
import org.onap.so.db.request.client.RequestsDbClient;
import org.onap.so.exceptions.ValidationException;
import org.onap.so.logger.ErrorCode;
import org.onap.so.logger.MessageEnum;
import org.onap.so.serviceinstancebeans.ModelType;
import org.onap.so.serviceinstancebeans.RequestReferences;
import org.onap.so.serviceinstancebeans.ServiceInstancesRequest;
import org.onap.so.serviceinstancebeans.ServiceInstancesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.HashMap;

@Component
@Path("/onap/so/infra/instanceManagement")
@Api(value = "/onap/so/infra/instanceManagement", description = "Infrastructure API Requests for Instance Management")
public class InstanceManagement {

    private static Logger logger = LoggerFactory.getLogger(InstanceManagement.class);
    private static String uriPrefix = "/instanceManagement/";
    private static final String SAVE_TO_DB = "save instance to db";

    @Autowired
    private RequestsDbClient infraActiveRequestsClient;

    @Autowired
    private CatalogDbClient catalogDbClient;

    @Autowired
    private MsoRequest msoRequest;

    @Autowired
    private RequestHandlerUtils requestHandlerUtils;

    @POST
    @Path("/{version:[vV][1]}/serviceInstances/{serviceInstanceId}/vnfs/{vnfInstanceId}/workflows/{workflowUuid}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Execute custom workflow", response = Response.class)
    @Transactional
    public Response executeCustomWorkflow(String request, @PathParam("version") String version,
            @PathParam("serviceInstanceId") String serviceInstanceId, @PathParam("vnfInstanceId") String vnfInstanceId,
            @PathParam("workflowUuid") String workflowUuid, @Context ContainerRequestContext requestContext)
            throws ApiException {
        String requestId = requestHandlerUtils.getRequestId(requestContext);
        HashMap<String, String> instanceIdMap = new HashMap<>();
        instanceIdMap.put("serviceInstanceId", serviceInstanceId);
        instanceIdMap.put("vnfInstanceId", vnfInstanceId);
        instanceIdMap.put("workflowUuid", workflowUuid);
        return processCustomWorkflowRequest(request, Action.inPlaceSoftwareUpdate, instanceIdMap, version, requestId,
                requestContext);
    }

    private Response processCustomWorkflowRequest(String requestJSON, Actions action,
            HashMap<String, String> instanceIdMap, String version, String requestId,
            ContainerRequestContext requestContext) throws ApiException {
        String serviceInstanceId = null;
        if (instanceIdMap != null) {
            serviceInstanceId = instanceIdMap.get("serviceInstanceId");
        }
        Boolean aLaCarte = true;
        long startTime = System.currentTimeMillis();
        ServiceInstancesRequest sir = null;
        String apiVersion = version.substring(1);

        String requestUri = requestHandlerUtils.getRequestUri(requestContext, uriPrefix);

        sir = requestHandlerUtils.convertJsonToServiceInstanceRequest(requestJSON, action, requestId, requestUri);
        String requestScope = requestHandlerUtils.deriveRequestScope(action, sir, requestUri);
        InfraActiveRequests currentActiveReq =
                msoRequest.createRequestObject(sir, action, requestId, Status.IN_PROGRESS, requestJSON, requestScope);

        try {
            requestHandlerUtils.validateHeaders(requestContext);
        } catch (ValidationException e) {
            logger.error("Exception occurred", e);
            ErrorLoggerInfo errorLoggerInfo =
                    new ErrorLoggerInfo.Builder(MessageEnum.APIH_VALIDATION_ERROR, ErrorCode.SchemaError)
                            .errorSource(Constants.MSO_PROP_APIHANDLER_INFRA).build();
            ValidateException validateException =
                    new ValidateException.Builder(e.getMessage(), HttpStatus.SC_BAD_REQUEST,
                            ErrorNumbers.SVC_BAD_PARAMETER).cause(e).errorInfo(errorLoggerInfo).build();
            requestHandlerUtils.updateStatus(currentActiveReq, Status.FAILED, validateException.getMessage());
            throw validateException;
        }

        requestHandlerUtils.parseRequest(sir, instanceIdMap, action, version, requestJSON, aLaCarte, requestId,
                currentActiveReq);
        requestHandlerUtils.setInstanceId(currentActiveReq, requestScope, null, instanceIdMap);

        int requestVersion = Integer.parseInt(version.substring(1));
        String vnfType = msoRequest.getVnfType(sir, requestScope, action, requestVersion);

        if (requestScope.equalsIgnoreCase(ModelType.vnf.name()) && vnfType != null) {
            currentActiveReq.setVnfType(vnfType);
        }

        InfraActiveRequests dup = null;
        boolean inProgress = false;

        dup = requestHandlerUtils.duplicateCheck(action, instanceIdMap, null, requestScope, currentActiveReq);

        if (dup != null) {
            inProgress = requestHandlerUtils.camundaHistoryCheck(dup, currentActiveReq);
        }

        if (dup != null && inProgress) {
            requestHandlerUtils.buildErrorOnDuplicateRecord(currentActiveReq, action, instanceIdMap, null, requestScope,
                    dup);
        }
        ServiceInstancesResponse serviceResponse = new ServiceInstancesResponse();

        RequestReferences referencesResponse = new RequestReferences();

        referencesResponse.setRequestId(requestId);

        serviceResponse.setRequestReferences(referencesResponse);
        Boolean isBaseVfModule = false;

        String workflowUuid = null;
        if (instanceIdMap != null) {
            workflowUuid = instanceIdMap.get("workflowUuid");
        }

        RecipeLookupResult recipeLookupResult = getInstanceManagementWorkflowRecipe(currentActiveReq, workflowUuid);

        String serviceInstanceType = requestHandlerUtils.getServiceType(requestScope, sir, true);

        serviceInstanceId = requestHandlerUtils.setServiceInstanceId(requestScope, sir);
        String vnfId = "";

        if (sir.getVnfInstanceId() != null) {
            vnfId = sir.getVnfInstanceId();
        }

        try {
            infraActiveRequestsClient.save(currentActiveReq);
        } catch (Exception e) {
            ErrorLoggerInfo errorLoggerInfo =
                    new ErrorLoggerInfo.Builder(MessageEnum.APIH_DB_ACCESS_EXC, ErrorCode.DataError)
                            .errorSource(Constants.MSO_PROP_APIHANDLER_INFRA).build();
            throw new RequestDbFailureException.Builder(SAVE_TO_DB, e.toString(), HttpStatus.SC_INTERNAL_SERVER_ERROR,
                    ErrorNumbers.SVC_DETAILED_SERVICE_ERROR).cause(e).errorInfo(errorLoggerInfo).build();
        }

        RequestClientParameter requestClientParameter = null;
        try {
            requestClientParameter = new RequestClientParameter.Builder().setRequestId(requestId)
                    .setBaseVfModule(isBaseVfModule).setRecipeTimeout(recipeLookupResult.getRecipeTimeout())
                    .setRequestAction(action.toString()).setServiceInstanceId(serviceInstanceId).setVnfId(vnfId)
                    .setServiceType(serviceInstanceType).setVnfType(vnfType)
                    .setRequestDetails(requestHandlerUtils.mapJSONtoMSOStyle(requestJSON, sir, aLaCarte, action))
                    .setApiVersion(apiVersion).setALaCarte(aLaCarte).setRequestUri(requestUri).build();
        } catch (IOException e) {
            ErrorLoggerInfo errorLoggerInfo =
                    new ErrorLoggerInfo.Builder(MessageEnum.APIH_BPEL_RESPONSE_ERROR, ErrorCode.SchemaError)
                            .errorSource(Constants.MSO_PROP_APIHANDLER_INFRA).build();
            throw new ValidateException.Builder("Unable to generate RequestClientParamter object" + e.getMessage(),
                    HttpStatus.SC_INTERNAL_SERVER_ERROR, ErrorNumbers.SVC_BAD_PARAMETER).errorInfo(errorLoggerInfo)
                            .build();
        }
        return requestHandlerUtils.postBPELRequest(currentActiveReq, requestClientParameter,
                recipeLookupResult.getOrchestrationURI(), requestScope);
    }

    private RecipeLookupResult getInstanceManagementWorkflowRecipe(InfraActiveRequests currentActiveReq,
            String workflowUuid) throws ApiException {
        RecipeLookupResult recipeLookupResult = null;

        try {
            recipeLookupResult = getCustomWorkflowUri(workflowUuid);
        } catch (IOException e) {
            ErrorLoggerInfo errorLoggerInfo =
                    new ErrorLoggerInfo.Builder(MessageEnum.APIH_REQUEST_VALIDATION_ERROR, ErrorCode.SchemaError)
                            .errorSource(Constants.MSO_PROP_APIHANDLER_INFRA).build();
            ValidateException validateException =
                    new ValidateException.Builder(e.getMessage(), HttpStatus.SC_BAD_REQUEST,
                            ErrorNumbers.SVC_BAD_PARAMETER).cause(e).errorInfo(errorLoggerInfo).build();
            requestHandlerUtils.updateStatus(currentActiveReq, Status.FAILED, validateException.getMessage());
            throw validateException;
        }

        if (recipeLookupResult == null) {
            ErrorLoggerInfo errorLoggerInfo =
                    new ErrorLoggerInfo.Builder(MessageEnum.APIH_DB_ACCESS_EXC, ErrorCode.DataError)
                            .errorSource(Constants.MSO_PROP_APIHANDLER_INFRA).build();
            RecipeNotFoundException recipeNotFoundExceptionException =
                    new RecipeNotFoundException.Builder("Recipe could not be retrieved from catalog DB.",
                            HttpStatus.SC_NOT_FOUND, ErrorNumbers.SVC_GENERAL_SERVICE_ERROR).errorInfo(errorLoggerInfo)
                                    .build();
            requestHandlerUtils.updateStatus(currentActiveReq, Status.FAILED,
                    recipeNotFoundExceptionException.getMessage());
            throw recipeNotFoundExceptionException;
        }

        return recipeLookupResult;
    }

    private RecipeLookupResult getCustomWorkflowUri(String workflowUuid) throws IOException {

        String recipeUri = null;
        Workflow workflow = catalogDbClient.findWorkflowByArtifactUUID(workflowUuid);
        if (workflow == null) {
            return null;
        } else {
            String workflowName = workflow.getArtifactName();
            recipeUri = "/mso/async/services/" + workflowName;
        }
        return new RecipeLookupResult(recipeUri, 180);
    }
}
