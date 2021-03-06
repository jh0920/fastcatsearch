/*
 * Copyright 2013 Websquared, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fastcatsearch.ir.search.clause;

import org.fastcatsearch.ir.query.RankInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface OperatedClause {
	public static Logger logger = LoggerFactory.getLogger(OperatedClause.class);
	/**
	 * @param docInfo
	 * @return RankInfo를 올바로 읽었는지 여부. 
	 */
	public boolean next(RankInfo docInfo);
	
	
	public void close();
}
