/*
 * Copyright (c) 2013 Websquared, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors:
 *     swsong - initial API and implementation
 */

package org.fastcatsearch.ir;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.bind.JAXBException;

import org.fastcatsearch.cluster.NodeLoadBalancable;
import org.fastcatsearch.common.QueryCacheModule;
import org.fastcatsearch.control.JobService;
import org.fastcatsearch.env.Environment;
import org.fastcatsearch.exception.FastcatSearchException;
import org.fastcatsearch.ir.analysis.AnalyzerFactoryManager;
import org.fastcatsearch.ir.analysis.AnalyzerPoolManager;
import org.fastcatsearch.ir.common.IRException;
import org.fastcatsearch.ir.common.SettingException;
import org.fastcatsearch.ir.config.CollectionConfig;
import org.fastcatsearch.ir.config.CollectionContext;
import org.fastcatsearch.ir.config.CollectionsConfig;
import org.fastcatsearch.ir.config.CollectionsConfig.Collection;
import org.fastcatsearch.ir.config.IndexingScheduleConfig;
import org.fastcatsearch.ir.config.IndexingScheduleConfig.IndexingSchedule;
import org.fastcatsearch.ir.group.GroupResults;
import org.fastcatsearch.ir.group.GroupsData;
import org.fastcatsearch.ir.query.InternalSearchResult;
import org.fastcatsearch.ir.query.Result;
import org.fastcatsearch.ir.search.CollectionHandler;
import org.fastcatsearch.ir.settings.AnalyzerSetting;
import org.fastcatsearch.job.PriorityScheduledJob;
import org.fastcatsearch.job.ScheduledJobEntry;
import org.fastcatsearch.job.indexing.MasterCollectionAddIndexingJob;
import org.fastcatsearch.job.indexing.MasterCollectionFullIndexingJob;
import org.fastcatsearch.module.ModuleException;
import org.fastcatsearch.service.AbstractService;
import org.fastcatsearch.service.ServiceManager;
import org.fastcatsearch.settings.SettingFileNames;
import org.fastcatsearch.settings.Settings;
import org.fastcatsearch.util.CollectionContextUtil;
import org.fastcatsearch.util.FilePaths;
import org.fastcatsearch.util.JAXBConfigs;

public class IRService extends AbstractService {

	private Map<String, CollectionHandler> collectionHandlerMap;

	// TODO 캐시방식을 변경하자.
	private QueryCacheModule<String, Result> searchCache;
	private QueryCacheModule<String, InternalSearchResult> shardSearchCache;
	private QueryCacheModule<String, GroupResults> groupingCache;
	private QueryCacheModule<String, GroupsData> groupingDataCache;
	private QueryCacheModule<String, Result> documentCache;
	private CollectionsConfig collectionsConfig;
	private File collectionsRoot;

	private RealtimeQueryCountModule realtimeQueryStatisticsModule;

	private AnalyzerFactoryManager analyzerFactoryManager;
	
	private Set<String> dataNodeCollectionIdSet; //이 노드가 데이터노드인 컬렉션세트. 쿼리 count집계시 사용된다.
	
	public IRService(Environment environment, Settings settings, ServiceManager serviceManager) {
		super(environment, settings, serviceManager);
		realtimeQueryStatisticsModule = new RealtimeQueryCountModule(environment, settings);
	}

	public void setAnalyzerFactoryManager(AnalyzerProvider analyzerProvider){
		this.analyzerFactoryManager = analyzerProvider.getAnalyzerFactoryManager();
	}
	
	
	protected boolean doStart() throws FastcatSearchException {

		realtimeQueryStatisticsModule.load();
		
		collectionHandlerMap = new HashMap<String, CollectionHandler>();
		// collections 셋팅을 읽어온다.
		collectionsRoot = environment.filePaths().getCollectionsRoot().file();

		try {
			collectionsConfig = JAXBConfigs.readConfig(new File(collectionsRoot, SettingFileNames.collections), CollectionsConfig.class);
		} catch (JAXBException e) {
			logger.error("[ERROR] 컬렉션리스트 로딩실패. " + e.getMessage(), e);
		}

		dataNodeCollectionIdSet = new HashSet<String>();
		
		for (Collection collection : collectionsConfig.getCollectionList()) {
			try {
				String collectionId = collection.getId();
				realtimeQueryStatisticsModule.registerQueryCount(collectionId);
				
				CollectionContext collectionContext = null;
				CollectionHandler collectionHandler = null;
				logger.info("Load Collection [{}]", collectionId);
				try {
					collectionContext = loadCollectionContext(collection);
				} catch (SettingException e) {
					logger.error("컬렉션context 로드실패 " + collectionId, e);
					continue;
				}
				if (collectionContext == null) {
					continue;
				} else {
					collectionHandler = new CollectionHandler(collectionContext, analyzerFactoryManager);
					
					if(collectionContext.collectionConfig().getDataNodeList() != null 
						&& collectionContext.collectionConfig().getDataNodeList().contains(environment.myNodeId())){
						dataNodeCollectionIdSet.add(collectionId);
					}
				}

				collectionHandlerMap.put(collectionId, collectionHandler);
				collectionHandler.setQueryCounter(realtimeQueryStatisticsModule.getQueryCounter(collectionId));
				
				// active하지 않은 컬렉션은 map에 설정만 넣어두고 로드하지 않는다.
				collectionHandler.load();

			} catch (IRException e) {
				logger.error("[ERROR] " + e.getMessage(), e);
			} catch (SettingException e) {
				logger.error("[ERROR] " + e.getMessage(), e);
			} catch (Exception e) {
				logger.error("[ERROR] " + e.getMessage(), e);
			}
		}

		searchCache = new QueryCacheModule<String, Result>(environment, settings);
		shardSearchCache = new QueryCacheModule<String, InternalSearchResult>(environment, settings);
		groupingCache = new QueryCacheModule<String, GroupResults>(environment, settings);
		groupingDataCache = new QueryCacheModule<String, GroupsData>(environment, settings);
		documentCache = new QueryCacheModule<String, Result>(environment, settings);
		try {
			searchCache.load();
			shardSearchCache.load();
			groupingCache.load();
			groupingDataCache.load();
			documentCache.load();
		} catch (ModuleException e) {
			throw new FastcatSearchException("ERR-00320");
		}

		
		
		return true;
	}

	
	public CollectionHandler collectionHandler(String collectionId) {
		return collectionHandlerMap.get(collectionId);
	}

	public CollectionContext collectionContext(String collectionId) {
		CollectionHandler h = collectionHandler(collectionId);
		if (h != null) {
			return h.collectionContext();
		} else {
			return null;
		}
	}

	public List<Collection> getCollectionList() {
		return collectionsConfig.getCollectionList();
	}

	public CollectionHandler createCollection(String collectionId, CollectionConfig collectionConfig) throws IRException, SettingException {

		if (collectionsConfig.contains(collectionId)) {
			// 이미 컬렉션 존재.
			throw new SettingException("Collection id already exists. " + collectionId);
		}

		try {
			FilePaths collectionFilePaths = environment.filePaths().collectionFilePaths(collectionId);
			collectionFilePaths.file().mkdirs();

			CollectionContext collectionContext = CollectionContextUtil.create(collectionConfig, collectionFilePaths);

			
			collectionsConfig.addCollection(collectionId);
			JAXBConfigs.writeConfig(new File(collectionsRoot, SettingFileNames.collections), collectionsConfig, CollectionsConfig.class);
			CollectionHandler collectionHandler = new CollectionHandler(collectionContext, analyzerFactoryManager);
			collectionHandlerMap.put(collectionId, collectionHandler);

			realtimeQueryStatisticsModule.registerQueryCount(collectionId);
			return collectionHandler;
		} catch (IRException e) {
			throw e;
		} catch (Exception e) {
			logger.error("Error while create collection", e);
			throw new SettingException(e);
		}
	}

	public CollectionContext loadCollectionContext(Collection collection) throws SettingException {
		FilePaths collectionFilePaths = environment.filePaths().collectionFilePaths(collection.getId());
		if (!collectionFilePaths.file().exists()) {
			// 디렉토리가 존재하지 않으면.
			logger.error("[{}]컬렉션 디렉토리가 존재하지 않습니다.", collection);
			return null;
		}
		return CollectionContextUtil.load(collection, collectionFilePaths);
	}

	public CollectionHandler removeCollectionHandler(String collectionId) {
		realtimeQueryStatisticsModule.removeQueryCount(collectionId);
		return collectionHandlerMap.remove(collectionId);
	}

	public CollectionHandler putCollectionHandler(String collectionId, CollectionHandler collectionHandler) {
		return collectionHandlerMap.put(collectionId, collectionHandler);
	}

	public CollectionHandler loadCollectionHandler(CollectionContext collectionContext) throws IRException, SettingException {
		return new CollectionHandler(collectionContext, analyzerFactoryManager).load();
	}

	protected boolean doStop() throws FastcatSearchException {
		realtimeQueryStatisticsModule.unload();

		Iterator<Entry<String, CollectionHandler>> iter = collectionHandlerMap.entrySet().iterator();
		while (iter.hasNext()) {
			Entry<String, CollectionHandler> entry = iter.next();
			try {
				CollectionHandler collectionHandler = entry.getValue();
				if (collectionHandler != null) {
					collectionHandler.close();
				}
				logger.info("Shutdown Collection [{}]", entry.getKey());
			} catch (IOException e) {
				logger.error("[ERROR] " + e.getMessage(), e);
				throw new FastcatSearchException("IRService 종료중 에러발생.", e);
			}
		}
		searchCache.unload();
		shardSearchCache.unload();
		groupingCache.unload();
		groupingDataCache.unload();
		documentCache.unload();

		collectionHandlerMap.clear();
		return true;
	}

	@Override
	protected boolean doClose() throws FastcatSearchException {
		collectionHandlerMap = null;
		realtimeQueryStatisticsModule = null;
		return true;
	}

	public QueryCacheModule<String, Result> searchCache() {
		return searchCache;
	}

	public QueryCacheModule<String, GroupResults> groupingCache() {
		return groupingCache;
	}

	public QueryCacheModule<String, Result> documentCache() {
		return documentCache;
	}

	public void registerLoadBanlancer(NodeLoadBalancable nodeLoadBalancable) {
		// 차후 검색시 로드밸런싱에 대비하여 먼저 collectionId로 node들을 등록해놓는다.
		for (Collection collection : getCollectionList()) {
			String collectionId = collection.getId();
			CollectionHandler collectionHandler = collectionHandlerMap.get(collectionId);
			if (collectionHandler == null) {
				continue;
			}
			List<String> dataNodeIdList = collectionHandler.collectionContext().collectionConfig().getDataNodeList();
			nodeLoadBalancable.updateLoadBalance(collectionId, dataNodeIdList);

		}
	}

	public RealtimeQueryCountModule queryCountModule() {
		return realtimeQueryStatisticsModule;
	}
	
	private SimpleDateFormat simpleDateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	private static String IndexingSchduleKey = "INDEXING-SCHEDULE-";
	
	public boolean reloadSchedule(String collectionId) {
		CollectionContext collectionContext = collectionContext(collectionId);
		if (collectionContext == null) {
			return false;
		}
		
		String scheduleKey = IndexingSchduleKey + collectionId;
		JobService.getInstance().cancelSchedule(scheduleKey);
		
		IndexingScheduleConfig indexingScheduleConfig = collectionContext(collectionId).indexingScheduleConfig();
		IndexingSchedule fullIndexingSchedule = indexingScheduleConfig.getFullIndexingSchedule();
		IndexingSchedule addIndexingSchedule = indexingScheduleConfig.getAddIndexingSchedule();
		
		List<ScheduledJobEntry> scheduledEntryList = new ArrayList<ScheduledJobEntry>();
		
		if (fullIndexingSchedule != null) {
			MasterCollectionFullIndexingJob job = new MasterCollectionFullIndexingJob();
			job.setArgs(collectionId);

			if (fullIndexingSchedule.isActive()) {
				String startTime = fullIndexingSchedule.getStart();
				int periodInSecond = fullIndexingSchedule.getPeriodInSecond();

				try {
					logger.debug("Load full indexing schdule {} : {}: {}", collectionId, startTime, periodInSecond);
					if(periodInSecond >= 0){
						//실행이 보장되는 스케쥴 작업으로 생성.
						scheduledEntryList.add(new ScheduledJobEntry(job, simpleDateFormat.parse(startTime), periodInSecond, true));
					}
				} catch (ParseException e) {
					logger.error("[{}] Full Indexing schedule time parse error : {}", collectionId, startTime);
					return false;
				}
			}
		}
		
		if (addIndexingSchedule != null) {
			MasterCollectionAddIndexingJob job = new MasterCollectionAddIndexingJob();
			job.setArgs(collectionId);

			if (addIndexingSchedule.isActive()) {
				String startTime = addIndexingSchedule.getStart();
				int periodInSecond = addIndexingSchedule.getPeriodInSecond();

				try {
					logger.debug("Load add indexing schdule {} : {}: {}", collectionId, startTime, periodInSecond);
					if(periodInSecond >= 0){
						scheduledEntryList.add(new ScheduledJobEntry(job, simpleDateFormat.parse(startTime), periodInSecond, false));
					}
				} catch (ParseException e) {
					logger.error("[{}] Add Indexing schedule time parse error : {}", collectionId, startTime);
					return false;
				}

			}
		}
		if(scheduledEntryList.size() > 0){
			PriorityScheduledJob scheduledJob = new PriorityScheduledJob(scheduleKey, scheduledEntryList);
			JobService.getInstance().schedule(scheduledJob, true);
		}else{
			logger.info("Collection {} has no indexing schedule.", collectionId);
		}
		return true;
	}

	public void reloadAllSchedule() {
		// 색인 스케쥴등록.
		for (CollectionsConfig.Collection collection : getCollectionList()) {
			String collectionId = collection.getId();
			reloadSchedule(collectionId);
		}
	}

	public Set<String> getDataNodeCollectionIdS(){
		return dataNodeCollectionIdSet;
	}
	// 모든 컬렉션들의 검색노드들을 모아서 리턴한다.
	public List<String> getSearchNodeList() {
		Set<String> searchNodeSet = new HashSet<String>();
		for (CollectionHandler collectionHandler : collectionHandlerMap.values()) {
			List<String> searchNodeList = collectionHandler.collectionContext().collectionConfig().getSearchNodeList();
			if (searchNodeList != null) {
				for (String searchNodeId : searchNodeList) {
					searchNodeSet.add(searchNodeId);
				}
			}
		}
		return new ArrayList<String>(searchNodeSet);
	}

	public AnalyzerPoolManager createAnalyzerPoolManager(List<AnalyzerSetting> analyzerSettingList) {
		AnalyzerPoolManager analyzerPoolManager = new AnalyzerPoolManager();
		analyzerPoolManager.register(analyzerSettingList, analyzerFactoryManager);
		return analyzerPoolManager;
	}
}
