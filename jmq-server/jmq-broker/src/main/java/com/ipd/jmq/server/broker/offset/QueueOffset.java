package com.ipd.jmq.server.broker.offset;


import com.ipd.jmq.server.broker.sequence.Sequence;
import com.ipd.jmq.server.broker.sequence.SequenceSet;
import com.ipd.jmq.server.store.ConsumeQueue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 队列偏移量
 */
public class QueueOffset implements Cloneable {
    // 主题
    private transient String topic;
    // 主题
    private transient int queueId;
    // 消费者偏移量
    private ConcurrentMap<String, UnSequenceOffset> offsets = new ConcurrentHashMap<String, UnSequenceOffset>();

    public QueueOffset() {

    }

    public QueueOffset(String topic, int queueId) {
        this.topic = topic;
        this.queueId = queueId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getQueueId() {
        return queueId;
    }

    public void setQueueId(int queueId) {
        this.queueId = queueId;
    }

    public ConcurrentMap<String, UnSequenceOffset> getOffsets() {
        return offsets;
    }

    public void setOffsets(ConcurrentMap<String, UnSequenceOffset> offsets) {
        this.offsets = offsets;
    }

    /**
     * 获取偏移量
     *
     * @param consumer 消费者
     */
    public UnSequenceOffset getOffset(final String consumer) {
        return offsets.get(consumer);
    }

    /**
     * 获取偏移量，不存在则创建
     *
     * @param consumer 消费者
     * @return 偏移量
     */
    public UnSequenceOffset getAndCreateOffset(final String consumer) {
        UnSequenceOffset offset = offsets.get(consumer);
        if (offset == null) {
            offset = new UnSequenceOffset(topic, queueId, consumer);
            UnSequenceOffset old = offsets.putIfAbsent(consumer, offset);
            if (old != null) {
                offset = old;
            }
        }
        return offset;
    }

    /**
     * 应答位置
     *
     * @param consumer 消费者
     * @param offset   位置
     */
    public void acknowledge(String consumer, long offset) {
        synchronized (this) {
            UnSequenceOffset target = getAndCreateOffset(consumer);
            SequenceSet acks = target.getAcks();
            acks.add(offset);
            //正常逻辑，第一个sequence应该包含旧的ackOffset
            Sequence headSequence = acks.getHead();
            if(target.getAckOffset().get() >target.getSubscribeOffset().get() || headSequence.contains(target.getSubscribeOffset().get()+ConsumeQueue.CQ_RECORD_SIZE)) {
                //只有第一个序列从订阅位置开始才表示第一段确认已经返回，否则不能设置ack值,否则会跳过第一个区间
                //兼容旧数据：ackOffset位置大于subscribeOffset(如果已经有值说明)已经开始消费过了
                if (headSequence.contains(target.getAckOffset().get() + ConsumeQueue.CQ_RECORD_SIZE)) {
                    target.compareGreaterAndSet(target.getAckOffset(), headSequence.getLast());
                } else if (headSequence.getFirst() > target.getAckOffset().get()) {
                    target.compareGreaterAndSet(target.getAckOffset(), headSequence.getLast());
                }
            }
        }
    }

    /**
     * 重置应答位置
     *
     * @param consumer 消费者
     * @param offset   应答位置
     */
    public void resetAckOffset(String consumer, long offset) {
        UnSequenceOffset target = getAndCreateOffset(consumer);
        synchronized (this) {
            long subOffset = target.getSubscribeOffset().get();
            long firstAck = subOffset+ConsumeQueue.CQ_RECORD_SIZE;
            SequenceSet ss = new SequenceSet();
            Sequence s = null;
            if(offset < ConsumeQueue.CQ_RECORD_SIZE) {
                //初始化位置
                target.getSubscribeOffset().set(0);
                s = null;
            }else if(firstAck > offset){
                target.getSubscribeOffset().set(offset-ConsumeQueue.CQ_RECORD_SIZE);
                s = new Sequence(offset);
            }else{
                if(firstAck == offset) {
                    s = new Sequence(firstAck);
                }else{
                    s = new Sequence(firstAck,offset);
                }

            }
            if(s != null) {
                ss.addFirst(s);
            }
            target.setAcks(ss);
            target.resetAckOffset(offset);
        }
    }

    /**
     * 更新偏移量
     *
     * @param queueOffset 队列偏移量
     */
    public void updateOffset(QueueOffset queueOffset) {
        if (queueOffset == null) {
            return;
        }
        synchronized (this) {
            for (Map.Entry<String, UnSequenceOffset> entry : queueOffset.offsets.entrySet()) {
                Offset src = entry.getValue();
                Offset target = getAndCreateOffset(entry.getKey());
                target.updateOffset(src);
            }
        }
    }

    @Override
    public QueueOffset clone() throws CloneNotSupportedException {
        QueueOffset copyOfQueueOffset = (QueueOffset) super.clone();
        synchronized (this) {
            for (Map.Entry<String, UnSequenceOffset> entry : copyOfQueueOffset.offsets.entrySet()) {
                copyOfQueueOffset.offsets.put(entry.getKey(), entry.getValue().clone());
            }
        }
        return copyOfQueueOffset;
    }
}