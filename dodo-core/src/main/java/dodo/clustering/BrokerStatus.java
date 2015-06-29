/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package dodo.clustering;

import dodo.scheduler.WorkerStatus;
import dodo.client.TaskStatusView;
import dodo.client.WorkerStatusView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replicated status of the broker. Each broker, leader or follower, contains a
 * copy of this status. The status is replicated to follower by reading the
 * StatusChangesLog
 *
 * @author enrico.olivelli
 */
public class BrokerStatus {

    private static final Logger LOGGER = Logger.getLogger(BrokerStatus.class.getName());

    private final Map<Long, Task> tasks = new HashMap<>();
    private final AtomicLong nextTaskId = new AtomicLong(0);
    private final Map<String, WorkerStatus> workers = new HashMap<>();
    private final AtomicLong newTaskId = new AtomicLong();
    private long maxTaskId = -1;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final StatusChangesLog log;
    private LogSequenceNumber lastLogSequenceNumber;
    private final AtomicInteger checkpointsCount = new AtomicInteger();
    private final SlotsManager slotsManager = new SlotsManager();

    public WorkerStatus getWorkerStatus(String workerId) {
        return workers.get(workerId);
    }

    public BrokerStatus(StatusChangesLog log) {
        this.log = log;
    }

    public List<WorkerStatusView> getAllWorkers() {
        List<WorkerStatusView> result = new ArrayList<>();
        lock.readLock().lock();
        try {
            workers.values().stream().forEach((k) -> {
                result.add(createWorkerStatusView(k));
            });
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    public List<TaskStatusView> getAllTasks() {
        List<TaskStatusView> result = new ArrayList<>();
        lock.readLock().lock();
        try {
            tasks.values().stream().forEach((k) -> {
                result.add(createTaskStatusView(k));
            });
        } finally {
            lock.readLock().unlock();
        }
        return result;
    }

    private TaskStatusView createTaskStatusView(Task task) {
        if (task == null) {
            return null;
        }
        TaskStatusView s = new TaskStatusView();
        s.setCreatedTimestamp(task.getCreatedTimestamp());
        s.setUserid(task.getUserId());
        s.setWorkerId(task.getWorkerId());
        s.setStatus(task.getStatus());
        s.setTaskId(task.getTaskId());
        s.setParameter(task.getParameter());
        s.setType(task.getType());
        s.setResult(task.getResult());
        s.setAttempts(task.getAttempts());
        s.setMaxattempts(task.getMaxattempts());
        s.setExecutionDeadline(task.getExecutionDeadline());
        return s;
    }

    private WorkerStatusView createWorkerStatusView(WorkerStatus k) {
        WorkerStatusView res = new WorkerStatusView();
        res.setId(k.getWorkerId());
        res.setLocation(k.getWorkerLocation());
        res.setLastConnectionTs(k.getLastConnectionTs());
        String s;
        switch (k.getStatus()) {
            case WorkerStatus.STATUS_CONNECTED:
                s = "CONNECTED";
                break;
            case WorkerStatus.STATUS_DEAD:
                s = "DEAD";
                break;
            case WorkerStatus.STATUS_DISCONNECTED:
                s = "DISCONNECTED";
                break;
            default:
                s = "?" + k.getStatus();
        }
        res.setProcessId(k.getProcessId());
        res.setStatus(s);
        return res;
    }

    public Collection<WorkerStatus> getWorkersAtBoot() {
        return workers.values();
    }

    public Collection<Task> getTasksAtBoot() {
        return tasks.values();
    }

    public long nextTaskId() {
        return nextTaskId.incrementAndGet();
    }

    public int getCheckpointsCount() {
        return checkpointsCount.get();
    }

    public void checkpoint() throws LogNotAvailableException {
        checkpointsCount.incrementAndGet();
        LOGGER.info("checkpoint");
        BrokerStatusSnapshot snapshot;
        lock.readLock().lock();
        try {
            snapshot = createSnapshot();
        } finally {
            lock.readLock().unlock();
        }
        this.log.checkpoint(snapshot);
    }

    private BrokerStatusSnapshot createSnapshot() {
        BrokerStatusSnapshot snap = new BrokerStatusSnapshot(maxTaskId, lastLogSequenceNumber);
        for (Task task : tasks.values()) {
            snap.tasks.add(task.cloneForSnapshot());
        }
        for (WorkerStatus status : workers.values()) {
            snap.workers.add(status.cloneForSnapshot());
        }
        return snap;
    }

    public void close() {
        try {
            this.log.close();
        } catch (LogNotAvailableException sorry) {
            LOGGER.log(Level.SEVERE, "Error while closing transaction log", sorry);
        }

    }

    public List<Long> purgeFinishedTasksAndSignalExpiredTasks(int finishedTasksRetention, int maxExpiredPerCycle) {
        long now = System.currentTimeMillis();
        long finished_deadline = now - finishedTasksRetention;

        List<Long> expired = new ArrayList<>();
        int expiredcount = 0;
        this.lock.writeLock().lock();
        try {
            // tasks are only purged from memry, not from logs
            // in case of broker restart it may re-appear
            for (Iterator<Map.Entry<Long, Task>> it = tasks.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Long, Task> taskEntry = it.next();
                Task t = taskEntry.getValue();
                switch (t.getStatus()) {
                    case Task.STATUS_WAITING:
                        if (expiredcount < maxExpiredPerCycle) {
                            long taskdeadline = t.getExecutionDeadline();
                            if (taskdeadline > 0 && taskdeadline < now) {
                                expired.add(t.getTaskId());
                                expiredcount++;
                                LOGGER.log(Level.INFO, "task {0}, created at {1}, expired, deadline {2}", new Object[]{t.getTaskId(), new java.util.Date(t.getCreatedTimestamp()), new java.util.Date(taskdeadline)});
                            }
                        }
                        break;
                    case Task.STATUS_ERROR:
                    case Task.STATUS_FINISHED:
                        if (t.getCreatedTimestamp() < finished_deadline) {
                            LOGGER.log(Level.INFO, "purging finished task {0}, created at {1}", new Object[]{t.getTaskId(), new java.util.Date(t.getCreatedTimestamp())});
                            it.remove();
                        }
                        break;
                }
            }
        } finally {
            this.lock.writeLock().unlock();
        }
        return expired;
    }

    public void followTheLeader() {
        try {
            while (!log.isLeader() && !log.isClosed()) {
                log.followTheLeader(this.lastLogSequenceNumber,
                        (logSeqNumber, edit) -> {
                            applyEdit(logSeqNumber, edit);
                        });
            }
        } catch (LogNotAvailableException err) {
            throw new RuntimeException(err);
        }
    }

    public static final class ModificationResult {

        public final LogSequenceNumber sequenceNumber;
        public final long newTaskId;

        public ModificationResult(LogSequenceNumber sequenceNumber, long newTaskId) {
            this.sequenceNumber = sequenceNumber;
            this.newTaskId = newTaskId;
        }

    }

    public ModificationResult applyModification(StatusEdit edit) throws LogNotAvailableException {
        LOGGER.log(Level.FINEST, "applyModification {0}", edit);
        if (edit.editType == StatusEdit.TYPE_ADD_TASK && edit.slot != null) {
            if (slotsManager.assignSlot(edit.slot)) {
                try {
                    LogSequenceNumber num = log.logStatusEdit(edit); // ? out of the lock ?        
                    return applyEdit(num, edit);
                } catch (LogNotAvailableException releaseSlot) {
                    slotsManager.releaseSlot(edit.slot);
                    throw releaseSlot;
                }
            } else {
                // slot already assigned
                return new ModificationResult(null, 0);
            }
        } else {
            LogSequenceNumber num = log.logStatusEdit(edit); // ? out of the lock ?        
            return applyEdit(num, edit);
        }
    }

    /**
     * Apply the modification to the status, this operation cannot fail, a
     * failure MUST lead to the death of the JVM because it will not be
     * recoverable as the broker will go out of synch
     *
     * @param edit
     */
    private ModificationResult applyEdit(LogSequenceNumber num, StatusEdit edit) {
        LOGGER.log(Level.FINEST, "applyEdit {0}", edit);

        lock.writeLock().lock();
        try {
            lastLogSequenceNumber = num;
            switch (edit.editType) {
                case StatusEdit.TYPE_ASSIGN_TASK_TO_WORKER: {
                    long taskId = edit.taskId;
                    String workerId = edit.workerId;
                    Task task = tasks.get(taskId);
                    task.setStatus(Task.STATUS_RUNNING);
                    task.setWorkerId(workerId);
                    task.setAttempts(edit.attempt);
                    return new ModificationResult(num, -1);
                }
                case StatusEdit.TYPE_TASK_STATUS_CHANGE: {
                    long taskId = edit.taskId;
                    String workerId = edit.workerId;
                    Task task = tasks.get(taskId);
                    if (task == null) {
                        throw new IllegalStateException();
                    }
                    if (workerId != null && !task.getWorkerId().equals(workerId)) {
                        throw new IllegalStateException("task " + taskId + ", bad workerid " + workerId + ", expected " + task.getWorkerId());
                    }
                    task.setStatus(edit.taskStatus);
                    task.setResult(edit.result);
                    if (task.getSlot() != null) {
                        switch (edit.taskStatus) {
                            case Task.STATUS_FINISHED:
                            case Task.STATUS_ERROR: {
                                slotsManager.releaseSlot(task.getSlot());
                            }
                        }
                    }
                    return new ModificationResult(num, -1);
                }
                case StatusEdit.TYPE_ADD_TASK: {
                    Task task = new Task();
                    task.setTaskId(edit.taskId);
                    if (maxTaskId < edit.taskId) {
                        maxTaskId = edit.taskId;
                    }
                    task.setCreatedTimestamp(System.currentTimeMillis());
                    task.setParameter(edit.parameter);
                    task.setType(edit.taskType);
                    task.setUserId(edit.userid);
                    task.setStatus(Task.STATUS_WAITING);
                    task.setMaxattempts(edit.maxattempts);
                    task.setExecutionDeadline(edit.executionDeadline);
                    task.setSlot(edit.slot);
                    tasks.put(edit.taskId, task);
                    if (edit.slot != null) {
                        // we need this, for log-replay on recovery and on followers
                        slotsManager.assignSlot(edit.slot);
                    }
                    return new ModificationResult(num, edit.taskId);
                }
                case StatusEdit.TYPE_WORKER_CONNECTED: {
                    WorkerStatus node = workers.get(edit.workerId);
                    if (node == null) {
                        node = new WorkerStatus();
                        node.setWorkerId(edit.workerId);

                        workers.put(edit.workerId, node);
                    }
                    node.setStatus(WorkerStatus.STATUS_CONNECTED);
                    node.setWorkerLocation(edit.workerLocation);
                    node.setProcessId(edit.workerProcessId);
                    node.setLastConnectionTs(edit.timestamp);
                    return new ModificationResult(num, -1);
                }
                case StatusEdit.TYPE_WORKER_DISCONNECTED: {
                    WorkerStatus node = workers.get(edit.workerId);
                    if (node == null) {
                        node = new WorkerStatus();
                        node.setWorkerId(edit.workerId);
                        workers.put(edit.workerId, node);
                    }
                    node.setStatus(WorkerStatus.STATUS_DISCONNECTED);
                    return new ModificationResult(num, -1);
                }
                case StatusEdit.TYPE_WORKER_DIED: {
                    WorkerStatus node = workers.get(edit.workerId);
                    if (node == null) {
                        node = new WorkerStatus();
                        node.setWorkerId(edit.workerId);
                        workers.put(edit.workerId, node);
                    }
                    node.setStatus(WorkerStatus.STATUS_DEAD);
                    return new ModificationResult(num, -1);
                }
                default:
                    throw new IllegalArgumentException();

            }
        } finally {
            lock.writeLock().unlock();
        }

    }

    public void recover() {

        lock.writeLock().lock();
        try {
            BrokerStatusSnapshot snapshot = log.loadBrokerStatusSnapshot();
            this.maxTaskId = snapshot.getMaxTaskId();
            this.newTaskId.set(maxTaskId + 1);
            for (Task task : snapshot.getTasks()) {
                this.tasks.put(task.getTaskId(), task);
            }
            for (WorkerStatus worker : snapshot.getWorkers()) {
                this.workers.put(worker.getWorkerId(), worker);
            }
            log.recovery(snapshot.getActualLogSequenceNumber(),
                    (logSeqNumber, edit) -> {
                        applyEdit(logSeqNumber, edit);
                    });
            newTaskId.set(maxTaskId + 1);
        } catch (LogNotAvailableException err) {
            throw new RuntimeException(err);
        } finally {
            lock.writeLock().unlock();
        }

    }

    public void startWriting() {
        lock.writeLock().lock();
        try {
            log.startWriting();
        } catch (LogNotAvailableException err) {
            throw new RuntimeException(err);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Task getTask(long taskId) {
        lock.readLock().lock();
        try {
            return tasks.get(taskId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public TaskStatusView getTaskStatus(long taskId) {
        Task task;
        lock.readLock().lock();
        try {
            task = tasks.get(taskId);
        } finally {
            lock.readLock().unlock();
        }

        TaskStatusView s = createTaskStatusView(task);
        return s;
    }

}
