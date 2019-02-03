/*
 * Copyright ©2018 vbill.cn.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package cn.vbill.middleware.porter.task.load;

import cn.vbill.middleware.porter.common.cluster.ClusterProviderProxy;
import cn.vbill.middleware.porter.common.cluster.command.TaskPositionUploadCommand;
import cn.vbill.middleware.porter.common.cluster.data.DTaskStat;
import cn.vbill.middleware.porter.common.exception.TaskStopTriggerException;
import cn.vbill.middleware.porter.common.statistics.NodeLog;
import cn.vbill.middleware.porter.common.util.DefaultNamedThreadFactory;
import cn.vbill.middleware.porter.core.NodeContext;
import cn.vbill.middleware.porter.core.event.etl.ETLBucket;
import cn.vbill.middleware.porter.core.event.s.EventType;
import cn.vbill.middleware.porter.core.loader.DataLoader;
import cn.vbill.middleware.porter.core.loader.SubmitStatObject;
import cn.vbill.middleware.porter.core.task.AbstractStageJob;
import cn.vbill.middleware.porter.core.task.StageType;
import cn.vbill.middleware.porter.task.worker.TaskWork;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 完成SQL事件的最终执行，单线程执行,通过interrupt终止线程
 *
 * @author: zhangkewei[zhang_kw@suixingpay.com]
 * @date: 2017年12月24日 11:19
 * @version: V1.0
 * @review: zhangkewei[zhang_kw@suixingpay.com]/2017年12月24日 11:19
 */
public class LoadJob extends AbstractStageJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoadJob.class);

    private final DataLoader dataLoder;
    private final TaskWork work;
    //最新的消费进度差值
    private volatile long newestPositionDiffer = 0;
    //最近一次数据库插入开始执行时间
    private volatile Calendar currentLoadStartTime;

    private final ScheduledExecutorService positionCheckService;

    public LoadJob(TaskWork work, long positionCheckInterval, long alarmPositionCount) {
        super(work.getBasicThreadName(), 50L);
        this.dataLoder = work.getDataLoader();
        this.work = work;
        //消费进度告警
        if (positionCheckInterval > 0) {
            positionCheckService = Executors.newSingleThreadScheduledExecutor(
                    new DefaultNamedThreadFactory(work.getBasicThreadName() + "-positionConsumedCheck"));
            positionCheckService.scheduleAtFixedRate(() -> {
                String taskId = work.getTaskId();
                String swimlaneId = work.getDataConsumer().getSwimlaneId();
                //当前进度差值超过告警线
                if (newestPositionDiffer >= alarmPositionCount) {
                    NodeLog noticeMsg = new NodeLog(NodeLog.LogType.TASK_WARNING, taskId, swimlaneId,
                            "未消费消息堆积:" + newestPositionDiffer + "条,告警阀值:" + alarmPositionCount);
                    noticeMsg.setTitle("【关注】" + taskId + "-" + swimlaneId + "消息堆积" + newestPositionDiffer + "条");
                    NodeLog.upload(noticeMsg, work.getReceivers());
                }
                //目标端数据库事务提交等待等原因造成的load等待
                Calendar tmpCurrLoadStartTime = currentLoadStartTime;
                long currentLoadTime = null != tmpCurrLoadStartTime ? tmpCurrLoadStartTime.getTime().getTime() : -1;
                if (currentLoadTime > 0) {
                    long minutesDiff = TimeUnit.MILLISECONDS.toMinutes(Calendar.getInstance().getTime().getTime() - currentLoadTime);
                    if (minutesDiff > 5) { //事务提交等待超过5分钟
                        NodeLog noticeMsg = new NodeLog(NodeLog.LogType.TASK_WARNING, taskId, swimlaneId,
                                "目标端提交事务等待" + minutesDiff + "分钟,告警阀值:5分钟");
                        noticeMsg.setTitle("【告警】" + taskId + "-" + swimlaneId + "目标端提交事务等待" + minutesDiff + "分钟");
                        NodeLog.upload(noticeMsg, work.getReceivers());
                    }
                }
            }, positionCheckInterval, positionCheckInterval, TimeUnit.SECONDS);
        } else {
            positionCheckService = null;
        }
    }

    @Override
    protected void doStop() {
        try {
            if (null != positionCheckService) {
                positionCheckService.shutdownNow();
            }
            dataLoder.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.warn("关闭LoadJob失败", e);
        }
    }

    @Override
    protected void doStart() throws Exception {
        dataLoder.startup();
    }

    @Override
    protected void loopLogic() throws InterruptedException {
        //只要队列有消息，持续读取
        ETLBucket bucket = null;
        do {
            //确保任务出错停止后不执行do{}逻辑
            if (work.triggerStopped()) {
                break;
            }
            //正常逻辑
            try {
                bucket = work.waitEvent(StageType.TRANSFORM);
                //异常
                if (null != bucket && null != bucket.getException()) {
                    throw new TaskStopTriggerException(bucket.getException());
                }

                //没有异常
                if (null != bucket && null == bucket.getException()) {
                    //记录当前时间
                    currentLoadStartTime = Calendar.getInstance();
                    //执行载入逻辑
                    Pair<Boolean, List<SubmitStatObject>> loadResult = dataLoder.load(bucket);
                    //逻辑执行失败
                    if (!loadResult.getLeft()) {
                        throw new TaskStopTriggerException("批次" + bucket.getSequence() + "Load失败!");
                    }
                    LOGGER.info("尝试提交消费同步点到集群策略:{}", bucket.getPosition().render());
                    //提交批次消费同步点
                    if (null != bucket.getPosition()) {
                        newestPositionDiffer = work.getDataConsumer().commitPosition(bucket.getPosition());
                        if (bucket.getPosition().checksum()) {
                            LOGGER.info("提交消费同步点:{},消息堆积:{}", bucket.getPosition().render(), newestPositionDiffer);
                            ClusterProviderProxy.INSTANCE.broadcast(new TaskPositionUploadCommand(work.getTaskId(),
                                    work.getDataConsumer().getSwimlaneId(), bucket.getPosition().render()));
                            LOGGER.info("结束提交消费同步点:{},消息堆积:{}", bucket.getPosition().render(), newestPositionDiffer);
                        }

                        NodeContext.INSTANCE.flushConsumeProcess(
                                work.getTaskId() + "-" + work.getDataConsumer().getSwimlaneId(),
                                newestPositionDiffer + "");
                    }
                    currentLoadStartTime = null;
                    //更新消费统计数据
                    loadResult.getRight().forEach(o -> updateStat(o));
                    //标记数据已清除
                    loadResult.getRight().clear();
                    bucket.markUnUsed();
                }
            } catch (TaskStopTriggerException stopException) {
                LOGGER.error("Load ETLRow error", stopException);
                stopException.printStackTrace();
                work.stopAndAlarm(stopException.getMessage());
                /**
                 * 立即停止目标端载入逻辑,理论上存在任务停止线程和当前载入线程同时执行的情况
                 */
                break;
            } catch (InterruptedException e) {
                throw e;
            } catch (Throwable e) {
                e.printStackTrace();
                NodeLog.upload(NodeLog.LogType.TASK_LOG, work.getTaskId(), work.getDataConsumer().getSwimlaneId(),
                        "Load ETLRow error" + e.getMessage());
                LOGGER.error("Load ETLRow error!", e);
            }
        } while (null != bucket && !work.triggerStopped() && getWorkingStat()); //数据不为空并且当前任务没有触发停止告警
    }

    @Override
    public ETLBucket output() throws Exception {
        throw new Exception("unsupported Method");
    }

    @Override
    public boolean isPrevPoolEmpty() {
        return work.isPoolEmpty(StageType.TRANSFORM);
    }

    @Override
    public boolean stopWaiting() {
        return work.getDataConsumer().isAutoCommitPosition();
    }


    /**
     * 更新任务状态
     * For a prepared statement batch, it is not possible to know the number of rows affected in the database
     * by each individual statement in the batch.Therefore, all array elements have a value of -2.
     * According to the JDBC 2.0 specification, a value of -2 indicates that the operation was successful
     * but the number of rows affected is unknown.
     *
     * @param object
     */
    private void updateStat(SubmitStatObject object) {
        int affect = object.getAffect();
        boolean hit = affect > 0 || affect == -2;
        EventType eventType = object.getType();
        //虽然每个状态值的变更都有stat对象锁，但在最外层加对象锁避免了多次请求的问题（锁可重入），同时保证状态各字段变更一致性
        DTaskStat stat = work.getDTaskStat(object.getSchema(), object.getTable());
        switch (eventType.getIndex()) {
            case EventType.DELETE_INDEX:
                if (hit) {
                    stat.incrementDeleteRow();
                } else {
                    stat.incrementErrorDeleteRow();
                }
                break;
            case EventType.UPDATE_INDEX:
                if (hit) {
                    stat.incrementUpdateRow();
                } else {
                    stat.incrementErrorUpdateRow();
                }
                break;
            case EventType.INSERT_INDEX:
                if (hit) {
                    stat.incrementInsertRow();
                } else {
                    stat.incrementErrorInsertRow();
                }
                break;
            case EventType.TRUNCATE_INDEX:
                if (hit) {
                    stat.incrementDeleteRow();
                } else {
                    stat.incrementErrorDeleteRow();
                }
                break;
            default:
                break;
        }

        //更新最后执行消息事件的产生时间，用于计算从消息产生到加载如路时间、计算数据同步检查时间
        if (null != object.getOpTime()) {
            stat.setLastLoadedDataTime(object.getOpTime());
        }

        stat.setLastLoadedSystemTime(new Date());
        if (null != object.getPosition()) {
            stat.setProgress(object.getPosition().render());
        }

        //打印当前消息所在点位，方便问题查找
        if (!hit) {
            LOGGER.error("{}.{} {} {}", object.getSchema(), object.getTable(), object.getType().getCode(), object.getPosition().render());
        }
    }
}
