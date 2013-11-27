package org.fastcatsearch.http.action.management.logs;

import java.io.Writer;
import java.util.List;

import org.fastcatsearch.db.DBService;
import org.fastcatsearch.db.InternalDBModule.MapperSession;
import org.fastcatsearch.db.mapper.NotificationHistoryMapper;
import org.fastcatsearch.db.vo.NotificationVO;
import org.fastcatsearch.http.ActionAuthority;
import org.fastcatsearch.http.ActionMapping;
import org.fastcatsearch.http.action.ActionRequest;
import org.fastcatsearch.http.action.ActionResponse;
import org.fastcatsearch.http.action.AuthAction;
import org.fastcatsearch.util.ResponseWriter;

@ActionMapping(value="/management/logs/notification-history", authority=ActionAuthority.Logs)
public class GetNotificationHistoryAction extends AuthAction {
	
	@Override
	public void doAuthAction(ActionRequest request, ActionResponse response) throws Exception {
		
		PageDivider divider = new PageDivider(15,10);
		
		DBService dbService = DBService.getInstance();
		
		MapperSession<NotificationHistoryMapper> session = null;
		
		int pageNum = request.getIntParameter("pageNum",1);
		
		if(pageNum < 1) { pageNum = 1; }
		
		int rowStarts = 0;
		
		int rowFinish = 0;
		
		Writer writer = response.getWriter();
		ResponseWriter resultWriter = getDefaultResponseWriter(writer);
		
		try {
		
			session = dbService.getMapperSession(NotificationHistoryMapper.class);
			
			NotificationHistoryMapper mapper = session.getMapper();
			
			List<NotificationVO> entryList = null;
			
			divider.setTotal(mapper.getCount());
			
			if(pageNum > divider.totalPage()) {
				pageNum = divider.totalPage();
			}
			
			rowStarts = divider.rowStarts(pageNum);
			
			rowFinish = divider.rowFinish(pageNum);
			
			entryList = mapper.getEntryList(rowStarts, rowFinish);
			
			resultWriter.object().key("totalSize").value(divider.getTotalRecord())
				.key("pageNum").value(pageNum)
				.key("rowSize").value(divider.rowSize())
				.key("pageSize").value(divider.pageSize())
				.key("rowStarts").value(rowStarts)
				.key("rowFinish").value(rowFinish)
				.key("pageStarts").value(divider.pageStarts(pageNum))
				.key("pageFinish").value(divider.pageFinish(pageNum))
				.key("totalPage").value(divider.totalPage())
				.key("notifications").array();
			
			for(int inx=0; inx < entryList.size(); inx++) {
				
				NotificationVO entry = entryList.get(inx);
				
				resultWriter.object()
					.key("id").value(entry.id)
					.key("node").value(entry.node)
					.key("messageCode").value(entry.messageCode)
					.key("message").value(entry.message)
					.key("regtime").value(entry.regtime)
					.endObject();
				
			}
			resultWriter.endArray().endObject();
		
		} finally {
			
			if(session!=null) {
				session.closeSession();
			}
		}
		
		resultWriter.done();
	}
}