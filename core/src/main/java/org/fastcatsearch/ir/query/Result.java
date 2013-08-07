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

package org.fastcatsearch.ir.query;

import org.fastcatsearch.ir.group.GroupResults;
import org.fastcatsearch.ir.group.GroupResult;

public class Result {

	private int count;
	private int totalCount;
//	private int fieldCount;
	private String[] fieldNameList;
	private Row[] rows;
	private GroupResults groupResults;
	private int start;
	
	//원문조회기능에서 필요.
	private int docCount;
	private int deletedDocCount;
	private int segmentCount;
	
	public Result(){}
//	public Result(Row[] data, GroupResults groupResults, String[] fieldNameList, int count, int totalCount, int start){
//		this(data, groupResults, fieldNameList, count, totalCount, start);
//	}
	public Result(Row[] data, GroupResults groupResults, String[] fieldNameList, int count, int totalCount, int start){
		this.rows = data;
		this.groupResults = groupResults;
//		this.fieldCount = fieldCount;
		this.fieldNameList = fieldNameList;
		this.count = count;
		this.totalCount = totalCount;
		this.start = start;
	}
	
	public int getTotalCount(){
		return totalCount;
	}
	
	public int getCount(){
		return count;
	}
	
	public int getFieldCount(){
		return fieldNameList.length;
	}
	
	public Row[] getData(){
		return rows;
	}
	
	public String[] getFieldNameList(){
		return fieldNameList;
	}
	
	public void setGroupResult(GroupResults groupResult){
		this.groupResults = groupResult;
	}
	
	public GroupResults getGroupResult(){
		return groupResults;
	}
	
	public int getStart(){
		return start;
	}
	
	public String toString(){
		if(groupResults != null){
			return "[Result]count = "+count+", totalCount = "+totalCount+", fieldCount = "+fieldNameList.length+", groupResult.length = "+groupResults.groupSize();
		}else{
			return "[Result]count = "+count+", totalCount = "+totalCount+", fieldCount = "+fieldNameList.length;
		}
	}
	
	public int getDocCount() {
		return docCount;
	}
	public void setDocCount(int docCount) {
		this.docCount = docCount;
	}
	public int getDeletedDocCount() {
		return deletedDocCount;
	}
	public void setDeletedDocCount(int deletedDocCount) {
		this.deletedDocCount = deletedDocCount;
	}
	public int getSegmentCount() {
		return segmentCount;
	}
	public void setSegmentCount(int segmentCount) {
		this.segmentCount = segmentCount;
	}
	
}
