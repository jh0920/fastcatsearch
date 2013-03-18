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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import org.fastcatsearch.control.JobController;
import org.fastcatsearch.control.JobException;


public class MonitorJobExecutorJob extends Job{

	public MonitorJobExecutorJob(){ }
	
	@Override
	public Map<String, String> run0() throws JobException {
		
		ThreadPoolExecutor executor = JobController.getInstance().getJobExecutor();
		Map<String, String> result = new HashMap<String, String>();
		
		int d = executor.getActiveCount();
		result.put("ActiveCount", Integer.toString(d));
		
		d = executor.getPoolSize();
		result.put("PoolSize", Integer.toString(d));
		
		d = executor.getMaximumPoolSize();
		result.put("MaximumPoolSize", Integer.toString(d));
		
		long l = executor.getCompletedTaskCount();
		result.put("CompletedTaskCount", Long.toString(l));
		
		return result;
	}
	
}