package org.fastcatsearch.ir.index;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.fastcatsearch.ir.analysis.AnalyzerPoolManager;
import org.fastcatsearch.ir.common.IRException;
import org.fastcatsearch.ir.common.IndexFileNames;
import org.fastcatsearch.ir.config.IndexConfig;
import org.fastcatsearch.ir.config.DataInfo.RevisionInfo;
import org.fastcatsearch.ir.document.Document;
import org.fastcatsearch.ir.document.PrimaryKeyIndexesWriter;
import org.fastcatsearch.ir.settings.Schema;
import org.fastcatsearch.ir.util.Formatter;

/**
 * 문서를 제외한 색인파일만 기록한다.
 * 
 * */
public class SegmentIndexWriter implements IndexWritable {
	protected int count;
	protected long startTime;

	protected SelectedIndexList selectedIndexList;// 색인필드 선택사항.
	protected PrimaryKeyIndexesWriter primaryKeyIndexesWriter;
	protected SearchIndexesWriter searchIndexesWriter;
	protected FieldIndexesWriter fieldIndexesWriter;
	protected GroupIndexesWriter groupIndexesWriter;

	protected String segmentId;
	protected File targetDir;
	protected RevisionInfo revisionInfo;

	public SegmentIndexWriter(Schema schema, File targetDir, RevisionInfo revisionInfo, IndexConfig indexConfig, AnalyzerPoolManager analyzerPoolManager,
			SelectedIndexList selectedIndexList) throws IRException {
		init(schema, targetDir, revisionInfo, indexConfig, analyzerPoolManager, selectedIndexList);
	}

	public void init(Schema schema, File targetDir, RevisionInfo revisionInfo, IndexConfig indexConfig, AnalyzerPoolManager analyzerPoolManager, SelectedIndexList selectedIndexList)
			throws IRException {
		try {
			this.segmentId = targetDir.getName();
			this.targetDir = targetDir;
			this.revisionInfo = revisionInfo;

			// make a default 0 revision directory
			IndexFileNames.getRevisionDir(targetDir, revisionInfo.getId()).mkdirs();

			if (selectedIndexList == null) {
				primaryKeyIndexesWriter = new PrimaryKeyIndexesWriter(schema, targetDir, revisionInfo, indexConfig);
				searchIndexesWriter = new SearchIndexesWriter(schema, targetDir, revisionInfo, indexConfig, analyzerPoolManager);
				fieldIndexesWriter = new FieldIndexesWriter(schema, targetDir, revisionInfo);
				groupIndexesWriter = new GroupIndexesWriter(schema, targetDir, revisionInfo, indexConfig);
			} else {
				if (selectedIndexList.isPrimaryKeyIndexSelected()) {
					primaryKeyIndexesWriter = new PrimaryKeyIndexesWriter(schema, targetDir, revisionInfo, indexConfig);
				}

				List<String> searchIndexList = selectedIndexList.getSearchIndexList();
				searchIndexesWriter = new SearchIndexesWriter(schema, targetDir, revisionInfo, indexConfig, analyzerPoolManager, searchIndexList);

				List<String> fieldIndexList = selectedIndexList.getFieldIndexList();
				fieldIndexesWriter = new FieldIndexesWriter(schema, targetDir, revisionInfo, fieldIndexList);
				
				List<String> groupIndexList = selectedIndexList.getGroupIndexList();
				groupIndexesWriter = new GroupIndexesWriter(schema, targetDir, revisionInfo, indexConfig, groupIndexList);

			}
		} catch (IOException e) {
			// writer생성시 에러가 발생하면(ex 토크나이저 발견못함) writer들이 안 닫힌채로 색인이 끝나서 다음번 색인시
			// 파일들을 삭제못하게 되므로 close해준다.
			try {
				closeWriter();
			} catch (Exception ignore) {
				// ignore
			}
			throw new IRException(e);
		}

	}

	public int getDocumentCount() {
		return count;
	}

	/**
	 * 색인후 내부 문서번호를 리턴한다.
	 */
	public int addDocument(Document document) throws IRException, IOException {
		return addDocument(document, count);
	}
	
	protected int addDocument(Document document, int docNo) throws IRException, IOException {
		// logger.debug("doc >> {}", document);
		if (primaryKeyIndexesWriter != null) {
			primaryKeyIndexesWriter.write(document, docNo);
		}
		if (searchIndexesWriter != null) {
			searchIndexesWriter.write(document, docNo);
		}
		if (fieldIndexesWriter != null) {
			fieldIndexesWriter.write(document);
		}
		if (groupIndexesWriter != null) {
			groupIndexesWriter.write(document);
		}

		count++;
		return docNo;
	}

	private void closeWriter() throws Exception {
		boolean errorOccured = false;
		Exception exception = null;

		try {
			if (primaryKeyIndexesWriter != null) {
				primaryKeyIndexesWriter.close();
			}
		} catch (Exception e) {
			logger.error("PK색인에러", e);
			exception = e;
			errorOccured = true;
		}
		try {
			if (searchIndexesWriter != null) {
				searchIndexesWriter.close();
			}
		} catch (Exception e) {
			logger.error("검색필드 색인에러", e);
			exception = e;
			errorOccured = true;
		}
		try {
			if (fieldIndexesWriter != null) {
				fieldIndexesWriter.close();
			}
		} catch (Exception e) {
			logger.error("필드색인필드 색인에러", e);
			exception = e;
			errorOccured = true;
		}
		try {
			if (groupIndexesWriter != null) {
				groupIndexesWriter.close();
			}
		} catch (Exception e) {
			logger.error("그룹색인필드 색인에러", e);
			exception = e;
			errorOccured = true;
		}

		if (errorOccured) {
			throw exception;
		}
	}

	public void close() throws IOException, IRException {
		try {
			closeWriter();

			// 여기서는 동일 수집문서내 pk중복만 처리하고 삭제문서갯수는 알수 없다.
			// 삭제문서는 DataSourceReader에서 알수 있으므로, 이 writer를 호출하는 class에서 처리한다.
			revisionInfo.setDocumentCount(count);
			int updateCount = 0;
			int insertCount = 0;
			if(primaryKeyIndexesWriter != null){
				updateCount = primaryKeyIndexesWriter.getUpdateDocCount();
				insertCount = count - updateCount;
			}else{
				insertCount = count;
			}
			revisionInfo.setInsertCount(insertCount);
			revisionInfo.setUpdateCount(updateCount);
			revisionInfo.setCreateTime(Formatter.formatDate());

			logger.info("Segment [{}] Indexed, elapsed = {}, mem = {}, {}", segmentId, Formatter.getFormatTime(System.currentTimeMillis() - startTime),
					Formatter.getFormatSize(Runtime.getRuntime().totalMemory()), revisionInfo);

		} catch (Exception e) {
			File revisionDir = IndexFileNames.getRevisionDir(targetDir, revisionInfo.getId());
			FileUtils.deleteDirectory(revisionDir);
			throw new IRException(e);
		}

	}
	
	public void getIndexWriteInfo(IndexWriteInfoList list) {
		fieldIndexesWriter.getIndexWriteInfo(list);
		groupIndexesWriter.getIndexWriteInfo(list);
	}

}
