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

package org.fastcatsearch.control;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import org.fastcatsearch.cluster.NodeService;
import org.fastcatsearch.common.ThreadPoolFactory;
import org.fastcatsearch.env.Environment;
import org.fastcatsearch.ir.common.SettingException;
import org.fastcatsearch.job.FullIndexJob;
import org.fastcatsearch.job.IncIndexJob;
import org.fastcatsearch.job.IndexingJob;
import org.fastcatsearch.job.Job;
import org.fastcatsearch.service.AbstractService;
import org.fastcatsearch.service.ServiceException;
import org.fastcatsearch.service.ServiceManager;
import org.fastcatsearch.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author sangwook.song
 * 
 */

public class JobService extends AbstractService implements JobExecutor {
	private static Logger logger = LoggerFactory.getLogger(JobService.class);
	private static Logger indexingLogger = LoggerFactory.getLogger("INDEXING_LOG");

	private BlockingQueue<Job> jobQueue;
	private Map<Long, ResultFuture> resultFutureMap;
	private Map<Long, Job> runningJobList;
	private AtomicLong jobIdIncrement;

	private ThreadPoolExecutor jobExecutor;
	private ThreadPoolExecutor searchJobExecutor;
	private ThreadPoolExecutor otherJobExecutor;
	private ScheduledThreadPoolExecutor scheduledJobExecutor;
	private ScheduledThreadPoolExecutor otherScheduledJobExecutor;

	private JobConsumer worker;
	private JobScheduler jobScheduler;
	private IndexingMutex indexingMutex;
	private boolean useJobScheduler;
	private int executorMaxPoolSize;

	private static JobService instance;

	private JobHandler jobHandler;
	
	public static JobService getInstance() {
		return instance;
	}

	public void asSingleton() {
		instance = this;
	}

	public JobService(Environment environment, Settings settings, ServiceManager serviceManager) {
		super(environment, settings, serviceManager);
	}

	public JobHandler jobHandler(){
		return jobHandler;
	}
	protected boolean doStart() throws ServiceException {
		jobIdIncrement = new AtomicLong();
		resultFutureMap = new ConcurrentHashMap<Long, ResultFuture>();
		runningJobList = new ConcurrentHashMap<Long, Job>();
		jobQueue = new LinkedBlockingQueue<Job>();
		indexingMutex = new IndexingMutex();
		jobScheduler = new JobScheduler();

		executorMaxPoolSize = settings.getInt("pool.max");

		int indexJobMaxSize = 100;
		int searchJobMaxSize = 100;
		int otherJobMaxSize = 100;

		jobExecutor = ThreadPoolFactory.newCachedThreadPool("IndexJobExecutor", executorMaxPoolSize);
		// searchJobExecutor =
		// ThreadPoolFactory.newUnlimitedCachedDaemonThreadPool("SearchJobExecutor");
		// otherJobExecutor =
		// ThreadPoolFactory.newUnlimitedCachedDaemonThreadPool("OtherJobExecutor");
		scheduledJobExecutor = ThreadPoolFactory.newScheduledThreadPool("ScheduledIndexJobExecutor");
		// otherScheduledJobExecutor =
		// ThreadPoolFactory.newScheduledThreadPool("OtherScheduledJobExecutor");

		worker = new JobConsumer();
		worker.start();
		if (useJobScheduler) {
			jobScheduler.start();
		}

		jobHandler = new JobHandler(serviceManager.getService(NodeService.class));
		
		return true;
	}

	protected boolean doStop() {
		logger.debug(getClass().getName() + " stop requested.");
		worker.interrupt();
		resultFutureMap.clear();
		jobQueue.clear();
		runningJobList.clear();
		jobExecutor.shutdownNow();
		if (useJobScheduler) {
			jobScheduler.stop();
		}
		return true;
	}

	protected boolean doClose() {
		return true;
	}

	public int runningJobSize() {
		return runningJobList.size();
	}

	public int inQueueJobSize() {
		return jobQueue.size();
	}

	public void setUseJobScheduler(boolean useJobScheduler) {
		this.useJobScheduler = useJobScheduler;
	}

	public Collection<Job> getRunningJobs() {
		return runningJobList.values();
	}

	public Collection<String> getIndexingList() {
		return indexingMutex.getIndexingList();
	}

	public void setSchedule(String key, String jobClassName, String args, Timestamp startTime, int period, boolean isYN)
			throws SettingException {
		if (!useJobScheduler) {
			return;
		}

		jobScheduler.setSchedule(key, jobClassName, args, startTime, period, isYN);
	}

	public boolean reloadSchedules() throws SettingException {
		if (!useJobScheduler) {
			return false;
		}

		return jobScheduler.reload();
	}

	public boolean toggleIndexingSchedule(String collection, String type, boolean isActive) {
		if (!useJobScheduler) {
			return false;
		}

		return jobScheduler.reloadIndexingSchedule(collection, type, isActive);
	}

	public ThreadPoolExecutor getJobExecutor() {
		return jobExecutor;
	}

	public ResultFuture offer(Job job) {
		job.setEnvironment(environment);
		job.setJobExecutor(this);

		if (job instanceof IndexingJob) {
			if (indexingMutex.isLocked(job)) {
				indexingLogger.info("The collection [" + job.getStringArgs(0) + "] has already started an indexing job.");
				return null;
			}
		}

		long myJobId = jobIdIncrement.getAndIncrement();
		logger.debug("### OFFER Job-{}", myJobId);

		if (job instanceof IndexingJob) {
			indexingMutex.access(myJobId, job);
		}
		
		if (job.isNoResult()) {
			job.setId(myJobId);
			jobQueue.offer(job);
			return null;
		} else {
			ResultFuture resultFuture = new ResultFuture(myJobId, resultFutureMap);
			resultFutureMap.put(myJobId, resultFuture);
			job.setId(myJobId);
			jobQueue.offer(job);
			return resultFuture;
		}
	}

	public void result(Job job, Object result, boolean isSuccess) {
		long jobId = job.getId();
		ResultFuture resultFuture = resultFutureMap.remove(jobId);
		runningJobList.remove(jobId);
		if (logger.isDebugEnabled()) {
			logger.debug("### ResultFuture = {} / map={} / job={} / result={} / success= {}", new Object[] { resultFuture,
					resultFutureMap.size(), job.getClass().getSimpleName(), result, isSuccess });
		}

		//
		// FIXME 색인서버와 DB정보 입력서버(마스터)는 다를수 있으므로, JobService에서 DB에 직접입력하지 않는다.
		// 호출한 Job에서 수행.
		//
		if (job instanceof IndexingJob) {
			indexingMutex.release(jobId);
			// DBService dbHandler = DBService.getInstance();
			String collection = job.getStringArgs(0);
			logger.debug("job={}, colletion={}", job, collection);

			String indexingType = "-";
			if (job instanceof FullIndexJob) {
				indexingType = "F";
				// 전체색인시는 증분색인 정보를 클리어해준다.
				// dbHandler.IndexingResult.delete(collection, "I");
			} else if (job instanceof IncIndexJob) {
				indexingType = "I";
			}
			// int status = isSuccess ? IndexingResult.STATUS_SUCCESS :
			// IndexingResult.STATUS_FAIL;
			// if(result instanceof IndexingResult){
			// IndexingResult jobResultIndex = (IndexingResult)result;
			// dbHandler.IndexingResult.updateOrInsert(collection, indexingType,
			// status, jobResultIndex.docSize, jobResultIndex.updateSize,
			// jobResultIndex.deleteSize, job.isScheduled(), new Timestamp(st),
			// new Timestamp(et), (int)(et-st));
			// dbHandler.IndexingHistory.insert(collection, indexingType,
			// isSuccess, jobResultIndex.docSize, jobResultIndex.updateSize,
			// jobResultIndex.deleteSize, job.isScheduled(), new Timestamp(st),
			// new Timestamp(et), (int)(et-st));
			// }else{
			// dbHandler.IndexingResult.updateOrInsert(collection, indexingType,
			// status, 0, 0, 0, job.isScheduled(), new Timestamp(st), new
			// Timestamp(et), (int)(et-st));
			// dbHandler.IndexingHistory.insert(collection, indexingType,
			// isSuccess, 0, 0, 0, job.isScheduled(), new Timestamp(st), new
			// Timestamp(et), (int)(et-st));
			// }
			// dbHandler.commit();
		}
		// }

		if (resultFuture != null) {
			resultFuture.put(result, isSuccess);
		} else {
			// 시간초과로 ResultFutuer.poll에서 미리제거된 경우.
			logger.debug("result arrived but future object is already removed due to timeout. result={}", result);
		}

	}

	/*
	 * 이 쓰레드는 절대로 죽어서는 아니되오.
	 */
	class JobConsumer extends Thread {
		// 2013-4-5 exception발생시 worker가 죽어서 더이상 작업할당을 못하는 상황발생.
		// throw를 catch하도록 수정.

		public JobConsumer() {
			super("JobConsumerThread");
		}

		public void run() {
			try {
				while (!Thread.interrupted()) {
					Job job = null;
					try {
						job = jobQueue.take();
						// logger.info("Execute = " + job);
						// logger.info("runningJobList[{}], jobQueue[{}] ",
						// runningJobList.size(), jobQueue.size());
						runningJobList.put(job.getId(), job);
						jobExecutor.execute(job);

					} catch (InterruptedException e) {
						throw e;

					} catch (RejectedExecutionException e) {
						// jobExecutor rejecthandler가 abortpolicy의 경우
						// RejectedExecutionException을 던지게 되어있다.
						logger.error("처리허용량을 초과하여 작업이 거부되었습니다. max.pool = {}, job={}", executorMaxPoolSize, job);
						result(job, new ExecutorMaxCapacityExceedException("처리허용량을 초과하여 작업이 거부되었습니다. max.pool ="
								+ executorMaxPoolSize), false);

					} catch (Throwable e) {
						// 나머지 jobExecutor 의 에러를 처리한다.
						logger.error("", e);
						result(job, null, false);
					}
				}
			} catch (InterruptedException e) {
				logger.debug(this.getClass().getName() + " is interrupted.");
			}

		}
	}

}