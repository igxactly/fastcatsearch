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

package org.fastcatsearch.job.indexing;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.fastcatsearch.cluster.ClusterUtils;
import org.fastcatsearch.cluster.Node;
import org.fastcatsearch.cluster.NodeJobResult;
import org.fastcatsearch.cluster.NodeService;
import org.fastcatsearch.common.io.Streamable;
import org.fastcatsearch.control.JobService;
import org.fastcatsearch.control.ResultFuture;
import org.fastcatsearch.db.mapper.IndexingResultMapper.ResultStatus;
import org.fastcatsearch.exception.FastcatSearchException;
import org.fastcatsearch.ir.IRService;
import org.fastcatsearch.ir.common.IndexingType;
import org.fastcatsearch.ir.config.CollectionContext;
import org.fastcatsearch.ir.config.CollectionIndexStatus.IndexStatus;
import org.fastcatsearch.ir.config.DataInfo.SegmentInfo;
import org.fastcatsearch.ir.search.CollectionHandler;
import org.fastcatsearch.job.cluster.NodeCollectionReloadJob;
import org.fastcatsearch.job.cluster.NodeDirectoryCleanJob;
import org.fastcatsearch.job.result.IndexingJobResult;
import org.fastcatsearch.job.state.IndexingTaskState;
import org.fastcatsearch.service.ServiceManager;
import org.fastcatsearch.transport.vo.StreamableThrowable;
import org.fastcatsearch.util.FilePaths;

/**
 * 특정 collection의 index node에서 수행되는 전파 및 적용 job.
 * 
 * index node가 아닌 노드에 전달되면 색인을 수행하지 않는다.
 *  
 * */
public class CollectionFullIndexingStepApplyJob extends IndexingJob {

	private static final long serialVersionUID = 7898036370433248984L;

	public CollectionFullIndexingStepApplyJob(){
	}
	
	@Override
	public JobResult doRun() throws FastcatSearchException {
		
		prepare(IndexingType.FULL, "APPLY-INDEX");
		
		
		Throwable throwable = null;
		ResultStatus resultStatus = ResultStatus.RUNNING;
		Object result = null;
		long startTime = System.currentTimeMillis();
		try {
			IRService irService = ServiceManager.getInstance().getService(IRService.class);
			//find index node
			CollectionHandler collectionHandler = irService.collectionHandler(collectionId);
			CollectionContext collectionContext = irService.collectionContext(collectionId);
			
			String indexNodeId = collectionContext.collectionConfig().getIndexNode();
			NodeService nodeService = ServiceManager.getInstance().getService(NodeService.class);
			Node indexNode = nodeService.getNodeById(indexNodeId);
			
			if(!nodeService.isMyNode(indexNode)){
				//Pass job to index node
				//작업수행하지 않음.
				throw new RuntimeException("Invalid index node collection[" + collectionId + "] node[" + indexNodeId + "]");
			}

			if(!updateIndexingStatusStart()) {
				logger.error("Cannot start indexing job. {} : {}", collectionId, indexNodeId);
				resultStatus = ResultStatus.CANCEL;
				return new JobResult();
			}

			/*
			 * 색인파일 원격복사.
			 */
			indexingTaskState.setStep(IndexingTaskState.STEP_FILECOPY);
			
			SegmentInfo segmentInfo = collectionContext.dataInfo().getLastSegmentInfo();
			if (segmentInfo != null) {
				String segmentId = segmentInfo.getId();
				logger.debug("Transfer index data collection[{}] >> {}", collectionId, segmentInfo);

				FilePaths indexFilePaths = collectionContext.indexFilePaths();
				File indexDir = indexFilePaths.file();
				File segmentDir = indexFilePaths.file(segmentId);

				List<Node> nodeList = new ArrayList<Node>(nodeService.getNodeById(collectionContext.collectionConfig().getDataNodeList()));
				//색인노드가 data node에 추가되어있다면 제거한다.
				nodeList.remove(nodeService.getMyNode());
				
				// 색인전송할디렉토리를 먼저 비우도록 요청.segmentDir
				File relativeDataDir = environment.filePaths().relativise(indexDir);
				NodeDirectoryCleanJob cleanJob = new NodeDirectoryCleanJob(relativeDataDir);

				NodeJobResult[] nodeResultList = null;
				nodeResultList = ClusterUtils.sendJobToNodeList(cleanJob, nodeService, nodeList, false);
				
				//성공한 node만 전송.
				nodeList = new ArrayList<Node>();
				for (int i = 0; i < nodeResultList.length; i++) {
					NodeJobResult r = nodeResultList[i];
					logger.debug("node#{} >> {}", i, r);
					if (r.isSuccess()) {
						nodeList.add(r.node());
					}else{
						logger.warn("Do not send index file to {}", r.node());
					}
				}
				// 색인된 Segment 파일전송.
				TransferIndexFileMultiNodeJob transferJob = new TransferIndexFileMultiNodeJob(segmentDir, nodeList);
				ResultFuture resultFuture = JobService.getInstance().offer(transferJob);
				Object obj = resultFuture.take();
				if(resultFuture.isSuccess() && obj != null){
					nodeResultList = (NodeJobResult[]) obj;
				}else{
					
				}
				
				//성공한 node만 전송.
				nodeList = new ArrayList<Node>();
				for (int i = 0; i < nodeResultList.length; i++) {
					NodeJobResult r = nodeResultList[i];
					logger.debug("node#{} >> {}", i, r);
					if (r.isSuccess()) {
						nodeList.add(r.node());
					}else{
						logger.warn("Do not send index file to {}", r.node());
					}
				}
				
				
				if(stopRequested){
					throw new IndexingStopException();
				}
				
				/*
				 * 데이터노드에 컬렉션 리로드 요청.
				 */
				NodeCollectionReloadJob reloadJob = new NodeCollectionReloadJob(collectionContext);
				nodeResultList = ClusterUtils.sendJobToNodeList(reloadJob, nodeService, nodeList, false);
				for (int i = 0; i < nodeResultList.length; i++) {
					NodeJobResult r = nodeResultList[i];
					logger.debug("node#{} >> {}", i, r);
					if (r.isSuccess()) {
						logger.info("{} Collection reload OK.", r.node());
					}else{
						logger.warn("{} Collection reload Fail.", r.node());
					}
				}
				
//				if (!nodeResult) {
//					throw new FastcatSearchException("Node Collection Reload Failed!");
//				}
			}
			
			
			///이미 build-index에서 리로드 했으므로 리로드 스킵. 
			/*
			 * 데이터노드가 리로드 완료되었으면 인덱스노드도 리로드 시작.
			 * */
//			indexingTaskState.setStep(IndexingTaskState.STEP_FINALIZE);
//			
//			CollectionContextUtil.saveCollectionAfterIndexing(collectionContext);
//			CollectionHandler collectionHandler = irService.loadCollectionHandler(collectionContext);
//			Counter queryCounter = irService.queryCountModule().getQueryCounter(collectionId);
//			collectionHandler.setQueryCounter(queryCounter);
//			CollectionHandler oldCollectionHandler = irService.putCollectionHandler(collectionId, collectionHandler);
//			if (oldCollectionHandler != null) {
//				logger.info("## [{}] Close Previous Collection Handler", collectionContext.collectionId());
//				oldCollectionHandler.close();
//			}
			
			int duration = (int) (System.currentTimeMillis() - startTime);
			
			/*
			 * 캐시 클리어.
			 */
//			getJobExecutor().offer(new CacheServiceRestartJob());

			IndexStatus indexStatus = collectionContext.indexStatus().getFullIndexStatus();
			indexingLogger.info("[{}] Collection Full Indexing apply index Finished! {} time = {}", collectionId, indexStatus, duration);
			logger.info("== SegmentStatus ==");
			collectionHandler.printSegmentStatus();
			logger.info("===================");
			
			result = new IndexingJobResult(collectionId, indexStatus, duration);
			resultStatus = ResultStatus.SUCCESS;
			indexingTaskState.setStep(IndexingTaskState.STEP_END);
			return new JobResult(result);

		} catch (IndexingStopException e){
			if(stopRequested){
				resultStatus = ResultStatus.STOP;
			}else{
				resultStatus = ResultStatus.CANCEL;
			}
			result = new IndexingJobResult(collectionId, null, (int) (System.currentTimeMillis() - startTime), false);
			return new JobResult(result);
		} catch (Throwable e) {
			indexingLogger.error("[" + collectionId + "] Indexing", e);
			throwable = e;
			resultStatus = ResultStatus.FAIL;
			throw new FastcatSearchException("ERR-00500", throwable, collectionId); // 전체색인실패.
		} finally {
			Streamable streamableResult = null;
			if (throwable != null) {
				streamableResult = new StreamableThrowable(throwable);
			} else if (result instanceof Streamable) {
				streamableResult = (Streamable) result;
			}

			updateIndexingStatusFinish(resultStatus, streamableResult);
		}

	}

}