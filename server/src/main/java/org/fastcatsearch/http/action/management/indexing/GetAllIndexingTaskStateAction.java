package org.fastcatsearch.http.action.management.indexing;

import java.io.Writer;
import java.util.List;
import java.util.Map.Entry;

import org.fastcatsearch.http.ActionAuthority;
import org.fastcatsearch.http.ActionMapping;
import org.fastcatsearch.http.action.ActionRequest;
import org.fastcatsearch.http.action.ActionResponse;
import org.fastcatsearch.http.action.AuthAction;
import org.fastcatsearch.job.state.IndexingTaskKey;
import org.fastcatsearch.job.state.IndexingTaskState;
import org.fastcatsearch.job.state.TaskKey;
import org.fastcatsearch.job.state.TaskState;
import org.fastcatsearch.job.state.TaskStateService;
import org.fastcatsearch.service.ServiceManager;
import org.fastcatsearch.util.ResponseWriter;

@ActionMapping(value = "/indexing/all-state", authority=ActionAuthority.Collections)
public class GetAllIndexingTaskStateAction extends AuthAction {

	@Override
	public void doAuthAction(ActionRequest request, ActionResponse response) throws Exception {
		
		TaskStateService taskStateService = ServiceManager.getInstance().getService(TaskStateService.class);
		
		List<Entry<TaskKey, TaskState>> taskEntryList = taskStateService.getTaskEntryList(IndexingTaskState.class);
		
		Writer writer = response.getWriter();
		ResponseWriter resultWriter = getDefaultResponseWriter(writer);
		resultWriter.object().key("indexingState").array();
		
		if(taskEntryList != null){
			for(Entry<TaskKey, TaskState> taskEntry : taskEntryList){
				IndexingTaskKey indexingTaskKey = (IndexingTaskKey) taskEntry.getKey();
				IndexingTaskState indexingTaskState = (IndexingTaskState) taskEntry.getValue();
				
				resultWriter.object()
				.key("collectionId").value(indexingTaskKey.collectionId())
				.key("indexingType").value(indexingTaskKey.indexingType())
				.key("isScheduled").value(indexingTaskState.isScheduled())
				.key("state").value(indexingTaskState.getState()) //색인, 전파등...
				.key("count").value(indexingTaskState.getDocumentCount())
				.key("startTime").value(indexingTaskState.getStartTime())
				.key("elapsed").value(indexingTaskState.getElapsedTime())
				.endObject();
			}
		}
		resultWriter.endArray().endObject();
		
		resultWriter.done();
		
		
	}

}
