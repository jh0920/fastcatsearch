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

package org.fastcatsearch.job;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.fastcatsearch.cluster.Node;
import org.fastcatsearch.cluster.NodeService;
import org.fastcatsearch.collector.SourceReaderFactory;
import org.fastcatsearch.common.Strings;
import org.fastcatsearch.common.io.StreamInput;
import org.fastcatsearch.common.io.StreamOutput;
import org.fastcatsearch.control.JobException;
import org.fastcatsearch.control.JobService;
import org.fastcatsearch.control.ResultFuture;
import org.fastcatsearch.data.DataService;
import org.fastcatsearch.data.DataStrategy;
import org.fastcatsearch.ir.common.IRFileName;
import org.fastcatsearch.ir.config.DataSourceSetting;
import org.fastcatsearch.ir.config.IRConfig;
import org.fastcatsearch.ir.config.IRSettings;
import org.fastcatsearch.ir.config.Schema;
import org.fastcatsearch.ir.search.CollectionHandler;
import org.fastcatsearch.ir.search.DataSequenceFile;
import org.fastcatsearch.ir.search.SegmentInfo;
import org.fastcatsearch.ir.source.SourceReader;
import org.fastcatsearch.ir.util.Formatter;
import org.fastcatsearch.job.Job.JobResult;
import org.fastcatsearch.job.result.IndexingJobResult;
import org.fastcatsearch.log.EventDBLogger;
import org.fastcatsearch.service.IRService;
import org.fastcatsearch.service.ServiceException;
import org.fastcatsearch.task.MakeIndexFileTask;
import org.fastcatsearch.transport.common.SendFileResultFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * 전체색인을 수행하여 색인파일을 생성하고,
 * 해당하는 data node에 색인파일을 복사한다.
 * 
 * */
public class NodeFullIndexJob extends StreamableJob {
	private static Logger indexingLogger = LoggerFactory.getLogger("INDEXING_LOG");

	String collectionId;

	public NodeFullIndexJob() {
	}

	public NodeFullIndexJob(String collectionId) {
		this.collectionId = collectionId;
	}

	@Override
	public JobResult doRun() throws JobException, ServiceException {

		IndexingJobResult indexingJobResult = null;
		try {
			
			long startTime = System.currentTimeMillis();
			IRConfig irconfig = IRSettings.getConfig(true);
			int DATA_SEQUENCE_CYCLE = irconfig.getInt("data.sequence.cycle");

			String collectionHomeDir = IRSettings.getCollectionHome(collectionId);
			Schema workSchema = IRSettings.getWorkSchema(collectionId, true, false);
			if (workSchema == null)
				workSchema = IRSettings.getSchema(collectionId, false);

			if (workSchema.getFieldSize() == 0) {
				indexingLogger.error("[" + collectionId + "] Full Indexing Canceled. Schema field is empty. time = "
						+ Strings.getHumanReadableTimeInterval(System.currentTimeMillis() - startTime));
				throw new JobException("[" + collectionId + "] Full Indexing Canceled. Schema field is empty. time = "
						+ Strings.getHumanReadableTimeInterval(System.currentTimeMillis() - startTime));
			}

			// 주키가 없으면 색인실패
			if (workSchema.getIndexID() == -1) {
				EventDBLogger.error(EventDBLogger.CATE_INDEX, "컬렉션 스키마에 주키가 없음.");
				throw new JobException("컬렉션 스키마에 주키(Primary Key)를 설정해야합니다.");
			}
			DataSequenceFile dataSequenceFile = new DataSequenceFile(collectionHomeDir, -1); // read
																								// sequence
			int newDataSequence = (dataSequenceFile.getSequence() + 1) % DATA_SEQUENCE_CYCLE;

			logger.debug("dataSequence=" + newDataSequence + ", DATA_SEQUENCE_CYCLE=" + DATA_SEQUENCE_CYCLE);

			File collectionDataDir = new File(IRSettings.getCollectionDataPath(collectionId, newDataSequence));
			FileUtils.deleteDirectory(collectionDataDir);

			// Make new CollectionHandler
			// this handler's schema or other setting can be different from
			// working segment handler's one.

			int segmentNumber = 0;

			DataSourceSetting dsSetting = IRSettings.getDatasource(collectionId, true);
			SourceReader sourceReader = SourceReaderFactory.createSourceReader(collectionId, workSchema, dsSetting, true);

			if (sourceReader == null) {
				EventDBLogger.error(EventDBLogger.CATE_INDEX, "데이터수집기를 생성할 수 없습니다.");
				throw new JobException("데이터 수집기 생성중 에러발생. sourceType = " + dsSetting.sourceType);
			}

			File segmentDir = new File(IRSettings.getSegmentPath(collectionId, newDataSequence, segmentNumber));
			indexingLogger.info("Segment Dir = " + segmentDir.getAbsolutePath());

			/*
			 * 색인파일 생성.
			 */
			MakeIndexFileTask makeIndexFileTask = new MakeIndexFileTask();
			int dupCount = 0;
			try{
				dupCount = makeIndexFileTask.makeIndex(collectionId, collectionHomeDir, workSchema, collectionDataDir, dsSetting, sourceReader, segmentDir);
			}finally{
				try{
					sourceReader.close();
				}catch(Exception e){
					logger.error("Error while close source reader! "+e.getMessage(),e);
				}
			}
			CollectionHandler newHandler = new CollectionHandler(collectionId, newDataSequence);
			int[] updateAndDeleteSize = newHandler.addSegment(segmentNumber, null); //collection.info 파일저장.
			newHandler.saveDataSequenceFile(); //data.sequence 파일저장.
			
			
			/*
			 * 색인파일 원격복사.
			 */
			DataStrategy dataStrategy = DataService.getInstance().getCollectionDataStrategy(collectionId);
			List<Node> nodeList = dataStrategy.dataNodes();
			if (nodeList == null || nodeList.size() == 0) {
				throw new JobException("색인파일을 복사할 노드가 정의되어있지 않습니다.");
			}


			//
			//TODO 색인전송할디렉토리를 먼저 비우도록 요청.segmentDir
			//
			Collection<File> files = FileUtils.listFiles(segmentDir, null, true);
			//add collection.info 파일
			File collectionInfoFile = new File(collectionDataDir, IRFileName.collectionInfoFile);
			files.add(collectionInfoFile);
			int totalFileCount = files.size();

			//TODO 순차적전송이라서 여러노드전송시 속도가 느림.해결요망.  
			for (int i = 0; i < nodeList.size(); i++) {
				Node node = nodeList.get(i);
				Iterator<File> fileIterator = files.iterator();
				int fileCount = 1;
				while (fileIterator.hasNext()) {
					File sourceFile = fileIterator.next();
					File targetFile = environment.filePaths().getRelativePathFile(sourceFile);
					logger.debug("sourceFile >> {}", sourceFile.getPath());
					logger.debug("targetFile >> {}", targetFile.getPath());
					logger.info("[{} / {}]파일 {} 전송시작! ", new Object[] { fileCount, totalFileCount, sourceFile.getPath() });
					SendFileResultFuture sendFileResultFuture = NodeService.getInstance().sendFile(node, sourceFile, targetFile);
					Object result = sendFileResultFuture.take();
					if (sendFileResultFuture.isSuccess()) {
						logger.info("[{} / {}]파일 {} 전송완료!", new Object[] { fileCount, totalFileCount, sourceFile.getPath() });
					} else {
						throw new JobException("파일전송에 실패했습니다.");
					}
					fileCount++;
				}

			}

			/*
			 * 데이터노드에 컬렉션 리로드 요청.
			 */
			NodeCollectionReloadJob reloadJob = new NodeCollectionReloadJob(startTime, collectionId, newDataSequence, segmentNumber);
			List<ResultFuture> resultFutureList = new ArrayList<ResultFuture>(nodeList.size());
			for (int i = 0; i < nodeList.size(); i++) {
				Node node = nodeList.get(i);
				ResultFuture resultFuture = NodeService.getInstance().sendRequest(node, reloadJob);
				resultFutureList.add(resultFuture);
			}
			for (int i = 0; i < resultFutureList.size(); i++) {
				Node node = nodeList.get(i);
				ResultFuture resultFuture = resultFutureList.get(i);
				Object obj = resultFuture.take();
				if(!resultFuture.isSuccess()){
					logger.debug("리로드 결과 : {}", obj);
					throw new JobException("컬렉션 리로드 실패. collection="+collectionId+", "+node);
				}
			}
			
			/*
			 * 데이터노드가 리로드 완료되었으면 인덱스노드도 리로드 시작.
			 * */
			IRService irService = IRService.getInstance();
			CollectionHandler oldCollectionHandler = irService.putCollectionHandler(collectionId, newHandler);
			if(oldCollectionHandler != null){
				logger.info("## Close Previous Collection Handler");
				oldCollectionHandler.close();
			}
			
			SegmentInfo si = newHandler.getLastSegmentInfo();
			logger.info(si.toString());
			int docSize = si.getDocCount();
			
			/*
			 * indextime 파일 업데이트.
			 */
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			String startDt = sdf.format(startTime);
			String endDt = sdf.format(new Date());
			int duration = (int) (System.currentTimeMillis() - startTime);
			String durationStr = Formatter.getFormatTime(duration);
			IRSettings.storeIndextime(collectionId, "FULL", startDt, endDt, durationStr, docSize);
			
			/*
			 * 5초후에 캐시 클리어.
			 */
			getJobExecutor().offer(new CacheServiceRestartJob(5000));
			
			int updateSize = updateAndDeleteSize[0];
			int deleteSize = updateAndDeleteSize[1] + dupCount;
			return new JobResult(new IndexingJobResult(collectionId, segmentDir, docSize, updateSize, deleteSize, duration));
			
		} catch (Exception e) {
			EventDBLogger.error(EventDBLogger.CATE_INDEX, "전체색인에러", EventDBLogger.getStackTrace(e));
			indexingLogger.error("[" + collectionId + "] Indexing error = " + e.getMessage(), e);
			throw new JobException(e);
		}

//		return new JobResult(indexingJobResult);
	}

	@Override
	public void readFrom(StreamInput input) throws IOException {
		collectionId = input.readString();
	}

	@Override
	public void writeTo(StreamOutput output) throws IOException {
		output.writeString(collectionId);
	}

}
