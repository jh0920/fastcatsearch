package org.fastcatsearch.job.internal;

import java.io.IOException;

import org.fastcatsearch.common.io.Streamable;
import org.fastcatsearch.exception.FastcatSearchException;
import org.fastcatsearch.ir.IRService;
import org.fastcatsearch.ir.io.DataInput;
import org.fastcatsearch.ir.io.DataOutput;
import org.fastcatsearch.ir.query.InternalSearchResult;
import org.fastcatsearch.ir.query.Query;
import org.fastcatsearch.ir.search.CollectionHandler;
import org.fastcatsearch.job.Job;
import org.fastcatsearch.query.QueryMap;
import org.fastcatsearch.query.QueryParseException;
import org.fastcatsearch.query.QueryParser;
import org.fastcatsearch.service.ServiceManager;
import org.fastcatsearch.transport.vo.StreamableInternalSearchResult;

public class InternalSearchJob extends Job implements Streamable {
	private QueryMap queryMap;
	private boolean forMerging;
	
	public InternalSearchJob(){}
	
	public InternalSearchJob(QueryMap queryMap){
		this.queryMap = queryMap;
	}
	
	public InternalSearchJob(QueryMap queryMap, boolean forMerging){
		this.queryMap = queryMap;
		this.forMerging = forMerging;
	}
	
	@Override
	public JobResult doRun() throws FastcatSearchException {
		
		Query q = null;
		try {
			q = QueryParser.getInstance().parseQuery(queryMap);
		} catch (QueryParseException e) {
			throw new FastcatSearchException("ERR-01000", e.getMessage());
		}
		
		String collectionId = queryMap.collectionId();
		
		try {
			InternalSearchResult result = null;
			
			IRService irService = ServiceManager.getInstance().getService(IRService.class);
			
			//Not Exist in Cache
			if(result == null){
				CollectionHandler collectionHandler = irService.collectionHandler(collectionId);
				if(collectionHandler == null){
					throw new FastcatSearchException("ERR-00520", collectionId);
				}
				
				result = collectionHandler.searcher().searchInternal(collectionId, q, forMerging);
			}

			return new JobResult(new StreamableInternalSearchResult(result));
			
		} catch (FastcatSearchException e){
			throw e;
		} catch(Exception e){
			logger.error("", e);
			throw new FastcatSearchException("ERR-00552", e, collectionId);
		}
		
	}
	@Override
	public void readFrom(DataInput input) throws IOException {
		this.queryMap = new QueryMap();
		queryMap.readFrom(input);
		this.forMerging = input.readBoolean();
	}
	@Override
	public void writeTo(DataOutput output) throws IOException {
		queryMap.writeTo(output);
		output.writeBoolean(forMerging);
	}
}
