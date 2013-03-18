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

package org.fastcatsearch.collector;

import java.util.List;
import java.util.Properties;

import org.fastcatsearch.control.JobException;
import org.fastcatsearch.ir.common.IRException;
import org.fastcatsearch.ir.config.DataSourceSetting;
import org.fastcatsearch.ir.config.IRSettings;
import org.fastcatsearch.ir.config.Schema;
import org.fastcatsearch.ir.document.Document;
import org.fastcatsearch.ir.source.SourceReader;
import org.fastcatsearch.log.EventDBLogger;



public class MultiSourceReader extends SourceReader{
	
	private int pos;
	private SourceReader sourceReader;
	
	private Schema schema;
	private List<DataSourceSetting> settingList;
	private boolean isFull;
	
	public MultiSourceReader(Schema schema, List<DataSourceSetting> settingList, boolean isFull) throws IRException {
		this.schema = schema;
		this.settingList = settingList;
		this.isFull = isFull;
		//제일 첫 소스리더를 로딩한다.
		if(!createSourceReader(schema, settingList.get(pos), isFull)){
			throw new IRException("소스리더 생성중 에러발생!");
		}
		pos++;
	}

	protected boolean createSourceReader(Schema schema, DataSourceSetting dsSetting, boolean isFull) throws IRException{
		String sourceType = dsSetting.sourceType;
		
		if(sourceType.equalsIgnoreCase("FILE")){
			sourceReader = (SourceReader) IRSettings.classLoader.loadObject(dsSetting.fileDocParser, new Class[]{Schema.class, DataSourceSetting.class, Boolean.class}, new Object[]{schema, dsSetting, isFull});
			logger.debug("Loading sourceReader : {}, {}", dsSetting.fileDocParser, sourceReader);
			if(sourceReader == null){
				logger.error("소스리더를 로드하지 못했습니다. 해당 클래스가 클래스패스에 없거나 생성자 시그너처가 일치하는지 확인이 필요합니다. sourceType={}", sourceType);
				return false;
			}
		}else if(sourceType.equalsIgnoreCase("DB")){
			sourceReader = new DBReader(schema, dsSetting, isFull);
			return true;
		}else if(sourceType.equalsIgnoreCase("WEB")){
			//웹페이지 리더
			sourceReader = new WebPageSourceReader(schema, dsSetting, isFull);
			return true;
		}else if(sourceType.equalsIgnoreCase("CUSTOM")){
			sourceReader = (SourceReader) IRSettings.classLoader.loadObject(dsSetting.customReaderClass, new Class[]{Schema.class, DataSourceSetting.class, Boolean.class, Properties.class}, new Object[]{schema, dsSetting, isFull});
			logger.debug("Loading sourceReader : {}, {}", dsSetting.customReaderClass, sourceReader);
			if(sourceReader == null){
				logger.error("소스리더를 로드하지 못했습니다. 해당 클래스가 클래스패스에 없거나 생성자 시그너처가 일치하는지 확인이 필요합니다. sourceType={}", sourceType);
				return false;
			}
		}else{
			EventDBLogger.error(EventDBLogger.CATE_INDEX, "수집대상 소스타입을 알수 없습니다.sourceType={}", sourceType);
		}
		return false;
	}
	
	@Override
	public boolean hasNext() throws IRException {
		if(sourceReader.hasNext()){
			return true;
		}else{
			if(pos < settingList.size()){
				//먼저 기존의 reader를 닫는다.
				sourceReader.close();
				//다른 reader를 생성한다.
				if(!createSourceReader(schema, settingList.get(pos), isFull)){
					return false;
				}
				pos++;
				if(sourceReader.hasNext()){
					return true;
				}else{
					while (!sourceReader.hasNext()) {
						if(pos < settingList.size()){
							//먼저 기존의 reader를 닫는다.
							sourceReader.close();
							//다른 reader를 생성한다.
							if(!createSourceReader(schema, settingList.get(pos), isFull)){
								return false;
							}
							pos++;
						}else{
							return false;
						}
					}
					return true;
				}
			}else{
				//더이상의 다른 reader가 없다.
				return false;
			}
		}
	}

	@Override
	public Document next() throws IRException {
		return sourceReader.next();
	}

	@Override
	public void close() throws IRException {
		sourceReader.close();
	}
	
	
}