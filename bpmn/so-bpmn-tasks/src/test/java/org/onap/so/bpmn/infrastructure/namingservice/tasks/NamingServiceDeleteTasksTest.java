/*-
 * ============LICENSE_START=======================================================
 * ONAP - SO
 * ================================================================================
 * Copyright (C) 2017 AT&T Intellectual Property. All rights reserved.
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

package org.onap.so.bpmn.infrastructure.namingservice.tasks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.onap.so.bpmn.BaseTaskTest;
import org.onap.so.bpmn.servicedecomposition.bbobjects.InstanceGroup;
import org.onap.so.bpmn.servicedecomposition.entities.ResourceKey;
import org.onap.so.client.exception.BBObjectNotFoundException;

public class NamingServiceDeleteTasksTest extends BaseTaskTest {
    @InjectMocks
    private NamingServiceDeleteTasks namingServiceDeleteTasks = new NamingServiceDeleteTasks();

    private InstanceGroup instanceGroup;

    @Before
    public void before() throws BBObjectNotFoundException {
        instanceGroup = setInstanceGroup();
        when(extractPojosForBB.extractByKey(any(), ArgumentMatchers.eq(ResourceKey.INSTANCE_GROUP_ID)))
                .thenReturn(instanceGroup);
    }

    @Test
    public void deleteInstanceGroupTest() throws Exception {

        doReturn("").when(namingServiceResources).deleteInstanceGroupName(instanceGroup);

        namingServiceDeleteTasks.deleteInstanceGroupName(execution);
        verify(namingServiceResources, times(1)).deleteInstanceGroupName(instanceGroup);
    }

    @Test
    public void deleteInstanceGroupExceptionTest() throws Exception {
        expectedException.expect(BBObjectNotFoundException.class);
        lookupKeyMap.put(ResourceKey.INSTANCE_GROUP_ID, "notfound");
        doThrow(BBObjectNotFoundException.class).when(extractPojosForBB).extractByKey(any(),
                ArgumentMatchers.eq(ResourceKey.INSTANCE_GROUP_ID));
        doReturn("").when(namingServiceResources).deleteInstanceGroupName(instanceGroup);
        namingServiceDeleteTasks.deleteInstanceGroupName(execution);
        verify(namingServiceResources, times(1)).deleteInstanceGroupName(instanceGroup);
    }

}
