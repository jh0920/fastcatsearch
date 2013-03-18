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

package org.fastcatsearch.servlet;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.fastcatsearch.control.JobController;
import org.fastcatsearch.control.JobResult;
import org.fastcatsearch.ir.config.FieldSetting;
import org.fastcatsearch.ir.config.IRSettings;
import org.fastcatsearch.ir.config.Schema;
import org.fastcatsearch.ir.config.SettingException;
import org.fastcatsearch.ir.field.ScoreField;
import org.fastcatsearch.ir.group.GroupEntry;
import org.fastcatsearch.ir.group.GroupResult;
import org.fastcatsearch.ir.io.AsciiCharTrie;
import org.fastcatsearch.ir.query.Result;
import org.fastcatsearch.ir.query.Row;
import org.fastcatsearch.ir.util.Formatter;
import org.fastcatsearch.job.DocumentSearchJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class DocumentSearchServlet extends HttpServlet {
	
	private static final long serialVersionUID = 963640595944747847L;
	private static Logger logger = LoggerFactory.getLogger(DocumentSearchServlet.class);
	//tab(9), cr(10), LF(13) 제외
	private static String CONTROL_CHAR_REGEXP = "["+(char)0+" "+(char)1+" "+(char)2+" "+(char)3+" "+(char)4+" "+(char)5+" "
		+(char)6+" "+(char)7+" "+(char)8+" "+(char)11+" "+(char)12+" "+(char)14+" "+(char)15+" "+(char)16+" "+(char)17+" "
		+(char)18+" "+(char)19+" "+(char)20+" "+(char)21+" "+(char)22+" "+(char)23+" "+(char)24+" "+(char)25+" "+(char)26+" "
		+(char)27+" "+(char)28+" "+(char)29+" "+(char)30+" "+(char)31+"]";
	public static final int JSON_TYPE = 0;
	public static final int XML_TYPE = 1;
	
	private int RESULT_TYPE = JSON_TYPE;
	
	public void init(){
		String type = getServletConfig().getInitParameter("result_format");
		if(type != null){
			if(type.equalsIgnoreCase("json")){
				RESULT_TYPE = JSON_TYPE;
			}else if(type.equalsIgnoreCase("xml")){
				RESULT_TYPE = XML_TYPE;
			}
		}
	}
	
	public DocumentSearchServlet() {
		this(JSON_TYPE);
	}
	
    public DocumentSearchServlet(int resultType){
    	RESULT_TYPE = resultType;
    }
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    	Enumeration enumeration = request.getParameterNames();
    	String timeoutStr = request.getParameter("timeout");
    	String isAdmin = request.getParameter("admin");
    	String collectionName = request.getParameter("cn");
    	String requestCharset = request.getParameter("requestCharset");
    	String responseCharset = request.getParameter("responseCharset");
    	StringBuffer sb = new StringBuffer();
    	while(enumeration.hasMoreElements()){
    		String key = (String) enumeration.nextElement();
    		String value = request.getParameter(key);
    		sb.append(key);
    		sb.append("=");
    		sb.append(value);
    		sb.append("&");
    	}
    	doReal(sb.toString(), timeoutStr, isAdmin, collectionName, response, requestCharset, responseCharset);
    }
    
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    	String queryString = request.getQueryString();
    	String timeoutStr = request.getParameter("timeout");
    	String isAdmin = request.getParameter("admin");
    	String collectionName = request.getParameter("cn");
    	String requestCharset = request.getParameter("requestCharset");
    	String responseCharset = request.getParameter("responseCharset");
    	doReal(queryString, timeoutStr, isAdmin, collectionName, response, requestCharset, responseCharset);
    	
    }
    
    private void doReal(String queryString, String timeoutStr, String isAdmin, String collectionName, HttpServletResponse response, String requestCharset, String responseCharset) throws ServletException, IOException {
    	if(requestCharset == null)
    		requestCharset = "UTF-8";
    	
    	if(responseCharset == null)
    		responseCharset = "UTF-8";
    	
    	logger.debug("requestCharset = "+requestCharset);
    	logger.debug("responseCharset = "+responseCharset);
    	queryString = URLDecoder.decode(queryString, requestCharset);
    	logger.debug("queryString = "+queryString);
    	
    	//TODO 디폴트 시간을 셋팅으로 빼자.
    	int timeout = 5;
    	if(timeoutStr != null)
    		timeout = Integer.parseInt(timeoutStr);
    	logger.debug("timeout = "+timeout+" s");
    	
		response.setCharacterEncoding(responseCharset);
    	response.setStatus(HttpServletResponse.SC_OK);
    	
    	if(RESULT_TYPE == JSON_TYPE){
    		response.setContentType("application/json; charset="+responseCharset);
    	}else if(RESULT_TYPE == XML_TYPE){
    		response.setContentType("text/xml; charset="+responseCharset);
    	}
    	
    	PrintWriter w = response.getWriter();
    	BufferedWriter writer = new BufferedWriter(w);
    	
    	long searchTime = 0;
    	long st = System.currentTimeMillis();
    	
    	DocumentSearchJob job = new DocumentSearchJob();
    	job.setArgs(new String[]{queryString});
    	
    	Result result = null;
    	
		JobResult jobResult = JobController.getInstance().offer(job);
		Object obj = jobResult.poll(timeout);
		searchTime = (System.currentTimeMillis() - st);
		if(jobResult.isSuccess()){
			result = (Result)obj;
		}else{
			String errorMsg = (String)obj;
			
			if(RESULT_TYPE == JSON_TYPE){
				if(errorMsg != null){
					errorMsg = errorMsg.replaceAll(CONTROL_CHAR_REGEXP, " ").replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"").replaceAll("\t", "\\\\t").replaceAll("\r\n", "\\\\n").replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\n");
				}
				writer.write("{");
				writer.newLine();
	    		writer.write("\t\"status\": \"1\",");
	    		writer.newLine();
	    		writer.write("\t\"time\": \""+Formatter.getFormatTime(searchTime)+"\",");
	    		writer.newLine();
	    		writer.write("\t\"error_msg\": \""+errorMsg+"\"");
	    		writer.newLine();
	    		writer.write("}");
			}else if(RESULT_TYPE == XML_TYPE){
				if(errorMsg != null){
					errorMsg = errorMsg.replaceAll(CONTROL_CHAR_REGEXP, " ").replaceAll("&", "&amp;").replaceAll("\'", "&apos;").replaceAll("\"", "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
				}
				writer.write("<fastcat>");
				writer.newLine();
	    		writer.write("\t<status>1</status>");
	    		writer.newLine();
	    		writer.write("\t<time>"+Formatter.getFormatTime(searchTime)+"</time>");
	    		writer.newLine();
	    		writer.write("\t<error_msg>"+errorMsg+"</error_msg>");
	    		writer.newLine();
	    		writer.write("</fastcat>");
			}
			
			writer.close();
    		return;
		}
		
    	//SUCCESS
		String logStr = searchTime+", "+result.getCount()+", "+result.getTotalCount()+", "+result.getFieldCount();
		if(result.getGroupResult() != null){
			String grStr = ", [";
			GroupResult[] gr = result.getGroupResult();
			for (int i = 0; i < gr.length; i++) {
				if(i > 0)
					grStr += ", ";
				grStr += gr[i].size();
			}
			grStr += "]";
			logStr += grStr;
		}
		
		if(RESULT_TYPE == JSON_TYPE){
			//JSON
			int fieldCount = result.getFieldCount();
			writer.write("{");
			writer.newLine();
			writer.write("\t\"status\": \"0\",");
			writer.newLine();
			writer.write("\t\"time\": \""+Formatter.getFormatTime(searchTime)+"\",");
			writer.newLine();
			writer.write("\t\"total_count\": \""+result.getTotalCount()+"\",");
			writer.newLine();
			writer.write("\t\"count\": \""+result.getCount()+"\",");
			writer.newLine();
			writer.write("\t\"field_count\": \""+fieldCount+"\",");
			writer.newLine();
			
			writer.write("\t\"seg_count\": \""+result.getSegmentCount()+"\",");
			writer.newLine();
			writer.write("\t\"doc_count\": \""+result.getDocCount()+"\",");
			writer.newLine();
			writer.write("\t\"del_count\": \""+result.getDeletedDocCount()+"\",");
			writer.newLine();
			
			writer.write("\t\"fieldname_list\": [");
			writer.newLine();
			writer.write("\t\t");
			
			String[] fieldNames = result.getFieldNameList();
			
			if(result.getCount() == 0){
				writer.write("{\"name\": \"_no_\"}");
			}else{
	    		writer.write("{\"name\": \"_no_\"}, ");
	    		for (int i = 0; i < fieldNames.length; i++) {
	    			writer.write("{\"name\": ");
	    			writer.write("\""+fieldNames[i]+"\"}");
	    			if(i < fieldNames.length - 1)
						writer.write(",");
				}
	    		writer.write("");
			}
	    	
			writer.write("\t],");
			
			if(isAdmin != null && isAdmin.equalsIgnoreCase("true")){
				if(result.getCount() == 0){
					writer.write("\t\"colmodel_list\": [");
	        		writer.write("\t\t{\"name\": \"_no_\", \"index\": \"_no_\", \"width\": \"100%\", \"sorttype\": \"int\", \"align\": \"center\"}");
	        		writer.write("\t],");
				}else{
	    			writer.write("\t\"colmodel_list\": [");
	        		writer.write("\t\t");
	        		AsciiCharTrie fieldnames = null;
	        		List<FieldSetting> fieldSettingList = null;
	        		try {
	        			Schema schema = IRSettings.getSchema(collectionName, false);
	        			fieldnames = schema.fieldnames;
	        			fieldSettingList = schema.getFieldSettingList();
					} catch (SettingException e) {
						
					}
					
					
					//write _no_
					if(fieldNames.length > 0){
						writer.write("{\"name\": \"_no_\", \"index\": \"_no_\", \"width\": \"20\", \"sorttype\": \"int\", \"align\": \"center\"}");
					}
	        		for (int i = 0; i < fieldNames.length; i++) {
	        			int idx = fieldnames.get(fieldNames[i]);
	        			if(idx < 0){
	        				if(fieldNames[i].equalsIgnoreCase(ScoreField.fieldName)){
	        					writer.write(",");
	        					writer.write("\t\t{\"name\": \"");
	                			writer.write(fieldNames[i]);
	                			writer.write("\", \"index\": \"");
	                			writer.write(fieldNames[i]);
	                			writer.write("\", \"width\": ");
	        					writer.write("\"20\", \"sorttype\": \"int\", \"align\": \"right\"}");
	        				}else{
	        					//Unknown Field
	        					writer.write(",");
	        					writer.write("\t\t{\"name\": \"");
	                			writer.write(fieldNames[i]);
	                			writer.write("\", \"index\": \"");
	                			writer.write(fieldNames[i]);
	                			writer.write("\", \"width\": ");
	        					writer.write("\"20\"}");
	        				}
	        			}else{
	        				writer.write(",");
	            			writer.write("\t\t{\"name\": \"");
	            			writer.write(fieldNames[i]);
	            			writer.write("\", \"index\": \"");
	            			writer.write(fieldNames[i]);
	            			writer.write("\", \"width\": ");
	        			
	            			FieldSetting fs = fieldSettingList.get(idx);
	            			if(fs.type == FieldSetting.Type.Int || fs.type == FieldSetting.Type.Long || fs.type == FieldSetting.Type.Float || fs.type == FieldSetting.Type.Double || fs.type == FieldSetting.Type.DateTime){
	            				writer.write("\"20\", \"sorttype\": \"int\", \"align\": \"right\"}");
	            			}else{
	            				if(fs.size > 0 && fs.size < 50){
	            					writer.write("\"");
	            					writer.write((fs.size * 2)+"");
	            					writer.write("\", ");
	            				}else{
	            					writer.write("\"100\", ");
	            				}
	            				writer.write("\"sorttype\": \"text\"}");
	            			}
	            			
	        			}
	        			
	//            			if(i < fieldNames.length - 1)
	//            				writer.write(",");
	        			
					}
	        		writer.write("");
	        		writer.write("\t],");
				}
			}
			
			
			writer.write("\t\"result\":");
			writer.write("\t["); //array
			//data
			Row[] rows = result.getData();
			int start = result.getMetadata().start();
			
			if(rows.length == 0){
				writer.write("\t\t{\"_no_\": \"No result found!\"}");
				writer.write("\t]");
			}else{
	    		for (int i = 0; i < rows.length; i++) {
	    			writer.write("\t\t{");
					Row row = rows[i];
					writer.write("\t\t\"_no_\": \""+row.getRowTag()+"\",");
					for(int k = 0; k < fieldCount; k++) {
						char[] f = row.get(k);
						String fdata = new String(f).trim();
						//For Json validity
						//1. replace single back-slash quote with double back-slash
						//2. replace double quote with character '\"' 
						//4. replace linefeed with character '\n' 
	//							fdata = fdata.replaceAll("\r\n", "\\\\n").replaceAll("\n", "").replaceAll("\r", "").replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"").replaceAll("\t", "\\\\t");
						fdata = fdata.replaceAll(CONTROL_CHAR_REGEXP, " ").replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"").replaceAll("\t", "").replaceAll("\r\n", "").replaceAll("\n", "").replaceAll("\r", "").replaceAll("\\s{2}", "");
						writer.write("\t\t\""+fieldNames[k]+"\": \""+fdata+"\"");
	//						writer.write("\t\t\""+fieldNames[k]+"\": \""+URLEncoder.encode(fdata, "utf-8")+"\"");
						if(k < fieldCount - 1)
							writer.write(",");
						else
							writer.write("");
					}
					writer.write(",");
					//for delete set
					if (row.isDeleted()) {
						writer.write("\t\t\"_delete_\": \"true\"");
					}else{
						writer.write("\t\t\"_delete_\": \"false\"");
					}
					
					writer.write("\t\t}");
					if(i < rows.length - 1)
						writer.write(",");
					else
						writer.write("");
	    		}
	    		
	    		writer.write("\t],");
	    		
	    		//group
	    		writer.write("\t\"group_result\":");
	    		GroupResult[] groupResultList = result.getGroupResult();
	    		if(groupResultList == null){
	    			writer.write(" \"null\"");
	    		}else{
	    			writer.write("");
	        		writer.write("\t[");
	        		for (int i = 0; i < groupResultList.length; i++) {
	        			writer.write("\t\t[");
						GroupResult groupResult = groupResultList[i];
						int size = groupResult.size();
						for (int k = 0; k < size; k++) {
							GroupEntry e = groupResult.getEntry(k);
							String keyData = e.key.getKeyString();
							keyData = keyData.replaceAll(CONTROL_CHAR_REGEXP, " ").replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"").replaceAll("\t", "\\\\t").replaceAll("\r\n", "\\\\n").replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\n");
							
							writer.write("\t\t{\"_no_\": \"");
							writer.write((k+1)+"");
							writer.write("\", \"key\": \"");
							writer.write(keyData);
							writer.write("\", \"freq\": \"");
							writer.write(e.count()+"");
							writer.write("\"}");
							if(k < size - 1)
								writer.write(",");
							else
								writer.write("");
							
						}
						writer.write("\t\t]");
						if(i < groupResultList.length - 1)
							writer.write(",");
						else
							writer.write("");
						
					}
	        		
	        		writer.write("\t]");
	    		}//for
			}//if else
			writer.write("}");
		}else if(RESULT_TYPE == XML_TYPE){
			//XML
			//this does not support admin test, have no column meta data
			
			int fieldCount = result.getFieldCount();
			writer.write("<fastcat>");
			writer.newLine();
			writer.write("\t<status>0</status>");
			writer.newLine();
			writer.write("\t<time>");
			writer.write(Formatter.getFormatTime(searchTime));
			writer.write("</time>");
			writer.newLine();
			writer.write("\t<total_count>");
			writer.write(result.getTotalCount()+"");
			writer.write("</total_count>");
			writer.newLine();
			writer.write("\t<count>");
			writer.write(result.getCount()+"");
			writer.write("</count>");
			writer.newLine();
			writer.write("\t<field_count>");
			writer.write(fieldCount+"");
			writer.write("</field_count>");
			writer.newLine();
			
			String[] fieldNames = result.getFieldNameList();
			
			if(result.getCount() == 0){
				writer.write("\t<fieldname_list>");
				writer.newLine();
				writer.write("\t\t<name>_no_</name>");
				writer.newLine();
				writer.write("\t</fieldname_list>");
				writer.newLine();
			}else{
				writer.write("\t<fieldname_list>");
				writer.newLine();
	    		writer.write("\t\t<name>_no_</name>");
	    		writer.newLine();
				writer.write("\t</fieldname_list>");
				writer.newLine();
	    		for (int i = 0; i < fieldNames.length; i++) {
	    			writer.write("\t<fieldname_list>");
					writer.newLine();
	    			writer.write("\t\t<name>");
	    			writer.write(fieldNames[i]);
	    			writer.write("</name>");
	    			writer.newLine();
	    			writer.write("\t</fieldname_list>");
					writer.newLine();
				}
			}
	    	
			
			//data
			Row[] rows = result.getData();
			int start = result.getMetadata().start();
			
			if(rows.length == 0){
				writer.write("\t<result>");
				writer.newLine();
				writer.write("\t\t<_no_>No result found!</_no_>");
				writer.newLine();
				writer.write("\t</result>");
				writer.newLine();
			}else{
	    		for (int i = 0; i < rows.length; i++) {
					Row row = rows[i];
					writer.write("\t<result>");
					writer.newLine();
					writer.write("\t\t<_no_>");
					writer.write((start + i)+"");
					writer.write("</_no_>");
					writer.newLine();
					for(int k = 0; k < fieldCount; k++) {
						char[] f = row.get(k);
						String fdata = new String(f);
						//XML validity
						//replace linefeed with character '\n' 
//						fdata = fdata.replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"").replaceAll("\t", "\\\\t").replaceAll("\r\n", "\\\\n").replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\n");
//						fdata = fdata.replaceAll("\t", "\\\\t").replaceAll("\r\n", "\\\\n").replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\n");
						fdata = fdata.replaceAll(CONTROL_CHAR_REGEXP, " ").replaceAll("&", "&amp;").replaceAll("\'", "&apos;").replaceAll("\"", "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
						writer.write("\t\t<");
						writer.write(fieldNames[k]);
						writer.write(">");
						writer.write(fdata);
						writer.write("</");
						writer.write(fieldNames[k]);
						writer.write(">");
						writer.newLine();
					}
					writer.write("\t</result>");
					writer.newLine();
	    		}
	    		
	    		//group
	    		GroupResult[] groupResultList = result.getGroupResult();
	    		if(groupResultList == null){
	    			writer.write("\t<group_result />");
	    			writer.newLine();
	    		}else{
	    			writer.write("\t<group_result>");
	    			writer.newLine();
	        		for (int i = 0; i < groupResultList.length; i++) {
	        			writer.write("\t\t<group_list>");
	        			writer.newLine();
						GroupResult groupResult = groupResultList[i];
						int size = groupResult.size();
						for (int k = 0; k < size; k++) {
							writer.write("\t\t\t<group_item>");
							writer.newLine();
							GroupEntry e = groupResult.getEntry(k);
							String keyData = e.key.getKeyString();
							keyData = keyData.replaceAll(CONTROL_CHAR_REGEXP, " ").replaceAll("&", "&amp;").replaceAll("\'", "&apos;").replaceAll("\"", "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
							
							writer.write("\t\t\t\t<_no_>");
							writer.write((k+1)+"");
							writer.write("</_no_>");
							writer.newLine();
							writer.write("\t\t\t\t<key>");
							writer.write(keyData);
							writer.write("</key>");
							writer.newLine();
							writer.write("\t\t\t\t<freq>");
							writer.write(e.count()+"");
							writer.write("</freq>");
							writer.newLine();
							writer.write("\t\t\t</group_item>");
							writer.newLine();
							
						}
						writer.write("\t\t</group_list>");
						writer.newLine();
					}
	        		writer.write("\t</group_result>");
	    			writer.newLine();
	    			
	    		}//for
			}//if else
			writer.write("</fastcat>");
		}
    	

    	writer.close();
    }
}