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

public class WeightedOperatedClause implements OperatedClause {
	private OperatedClause mainClause;
	private OperatedClause wieghtClause;
	private boolean hasNext1 = true;
	private boolean hasNext2 = true;

	private RankInfo docInfo1 = new RankInfo();
	private RankInfo docInfo2 = new RankInfo();
	
	public WeightedOperatedClause(OperatedClause mainClause, OperatedClause wieghtClause) {
		this.mainClause = mainClause;
		this.wieghtClause = wieghtClause;
		
		hasNext2 = wieghtClause.next(docInfo2);
	}

	public boolean next(RankInfo docInfo) {
		
		hasNext1 = mainClause.next(docInfo1);
		
		if (hasNext1) {
			int doc1 = docInfo1.docNo();
			int doc2 = docInfo2.docNo();
			float score1 = docInfo1.score();
			float score2 = docInfo2.score();
			while (hasNext1 && hasNext2 && (doc1 != doc2)) {
				while (hasNext1 && (doc1 < doc2)) {
					docInfo.init(doc1, score1);
					return true;
				}
				while (hasNext2 && (doc1 > doc2)) {
					hasNext2 = wieghtClause.next(docInfo2);
					doc2 = docInfo2.docNo();
					score2 = docInfo2.score();
				}
			}

			if (hasNext1 && hasNext2 && (doc1 == doc2)) {
				docInfo.init(doc1, score1 + score2);
				hasNext2 = wieghtClause.next(docInfo2);
				return true;
			}
			if (hasNext1){
				docInfo.init(doc1, score1);
				return true;
			}
			return false;
		}

		// mainClause가 끝나면 더이상 없는것이다.
		return false;
	}

	@Override
	public String toString() {
		return "[" + getClass().getSimpleName() + "]" + (mainClause != null ? mainClause.toString() : "null") + " / " + (wieghtClause != null ? wieghtClause.toString() : "null");
	}

	@Override
	public void close() {
		if(mainClause != null){
			mainClause.close();
		}
		if(wieghtClause != null){
			wieghtClause.close();
		}		
	}

}
