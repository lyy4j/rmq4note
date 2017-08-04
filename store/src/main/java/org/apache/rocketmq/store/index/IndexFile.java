/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.store.MappedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * 一下为IndexFile 索引文件的内存存储结构
 *
 *
 * [0，40) 字节，存放IndexHeader 内容；
 *
 * [40, 5000000 * 4(indexNum)），存放消息的哈希索引信息，
 * 例如，
 * 一条消息的槽位置为absSlotPos：|key.hashCode() % hashSlotNum|  * hashSlotSize(4) + IndexHeader.INDEX_HEADER_SIZE(40)
 *  对应的value，最后一次存放该位置的索引个数
 *
 * [5000000 * 4,n);该区域存放具体的索引内容，递增存放，每条索引的大小为20字节
 * 内容为 [消息的key的hash值(4字节),消息的物理位置(8字节),消息的存储时间以及索引文件创建的时间差（秒数,4字节,消息所在的绝对索引位置(4字节)]
 *
 *
 * 每个索引文件最多存放两千万条消息索引
 *
 *
 */
public class IndexFile {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static int hashSlotSize = 4;
    private static int indexSize = 20;
    private static int invalidIndex = 0;
    private final int hashSlotNum;
    private final int indexNum;
    private final MappedFile mappedFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final IndexHeader indexHeader;

    /**
     *
     * @param fileName userRootPath/index/YYMMDDhhmmss + mmm(毫秒)
     * @param hashSlotNum default value = 5000000
     * @param indexNum default value = 5000000 * 4
     *
     *  如果IndexFile  在IndexService.indexFileList.isEmpty()空时创建，则 endPhyOffset = endTimestamp = 0;
     *  否则， endPhyOffset = IndexService.indexFileList.last().endPhyOffset
     *         endTimestamp = IndexService.indexFileList.last().endTimestamp
     * @param endPhyOffset
     * @param endTimestamp
     * @throws IOException
     */
    public IndexFile(final String fileName, final int hashSlotNum, final int indexNum,
        final long endPhyOffset, final long endTimestamp) throws IOException {

        //fileTotalSize = 40 + (5,000,000[hashSlotNum] * 4[hashSlotSize]) + ((4 * 5,000,000)[indexNum] * 20[indexSize])
        //1G = 1024 * 1024 * 1024 = 1,073,741,824 (10亿级)
        int fileTotalSize =
            IndexHeader.INDEX_HEADER_SIZE + (hashSlotNum * hashSlotSize) + (indexNum * indexSize);
        this.mappedFile = new MappedFile(fileName, fileTotalSize);
        this.fileChannel = this.mappedFile.getFileChannel();
        this.mappedByteBuffer = this.mappedFile.getMappedByteBuffer();
        this.hashSlotNum = hashSlotNum;
        this.indexNum = indexNum;

        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        this.indexHeader = new IndexHeader(byteBuffer);

        if (endPhyOffset > 0) {
            this.indexHeader.setBeginPhyOffset(endPhyOffset);
            this.indexHeader.setEndPhyOffset(endPhyOffset);
        }

        if (endTimestamp > 0) {
            this.indexHeader.setBeginTimestamp(endTimestamp);
            this.indexHeader.setEndTimestamp(endTimestamp);
        }
    }

    public String getFileName() {
        return this.mappedFile.getFileName();
    }

    public void load() {
        this.indexHeader.load();
    }

    public void flush() {
        long beginTime = System.currentTimeMillis();
        if (this.mappedFile.hold()) {
            this.indexHeader.updateByteBuffer();
            this.mappedByteBuffer.force();
            this.mappedFile.release();
            log.info("flush index file eclipse time(ms) " + (System.currentTimeMillis() - beginTime));
        }
    }

    public boolean isWriteFull() {
        return this.indexHeader.getIndexCount() >= this.indexNum;
    }

    public boolean destroy(final long intervalForcibly) {
        return this.mappedFile.destroy(intervalForcibly);
    }

    /**
     *
     * @param key 指定的key: topic-uniqueKey
     * @param phyOffset 消息的持久化物理位移
     * @param storeTimestamp 消息的存储时间，即消息放进缓存的时间
     * @return
     */
    public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {
        if (this.indexHeader.getIndexCount() < this.indexNum) {

            int keyHash = indexKeyHashMethod(key);
            //hashSlotNum = 5000000
            //通过简单的hash算法，算出key所在的相对槽位置
            int slotPos = keyHash % this.hashSlotNum;
            //绝对槽位置 = 40 + slotPos(相对槽位置) * 4(hash槽大小)
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;

            try {

                // fileLock = this.fileChannel.lock(absSlotPos, hashSlotSize,
                // false);
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()) {
                    slotValue = invalidIndex;
                }

                long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();

                timeDiff = timeDiff / 1000;

                if (this.indexHeader.getBeginTimestamp() <= 0) {
                    timeDiff = 0;
                } else if (timeDiff > Integer.MAX_VALUE) {
                    timeDiff = Integer.MAX_VALUE;
                } else if (timeDiff < 0) {
                    timeDiff = 0;
                }

                //绝对索引位置 = 40(索引头大小) + 5000000(hashSlotNum) * 4(hashSlotSize) + IndexCount(当前已索引条数) * 20(索引大小)
                //this.indexHeader.getIndexCount() * indexSize 这里保证每条索引实体所存放的位置是递增的
                int absIndexPos =
                    IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                        + this.indexHeader.getIndexCount() * indexSize;

                // start absIndexPos:绝对索引位置
                // 索引消息的大小（20字节），4(1) + 4(2) + 8(3) + 4(4)
                //(1):消息的key的hash值，
                //(2):消息的物理位置
                //(3):消息的存储时间以及索引文件创建的时间差（秒数）
                //(4):在相同槽位置中，上一条索引消息所在的逻辑索引，这里是查询的关键，
                //这里详细梳理一下：
                //eg:
                //第一条索引消息的 的情况  ：
                //1st_index_msg, logicIndex = 1,根据key，散列到slot_2，则1st_index_msg.slotValue = 0(logicIndex)
                //
                //第二条索引消息的 的情况  ：
                //2nd_index_msg, logicIndex = 2,根据key，散列到slot_2，则2nd_index_msg.slotValue = 1(logicIndex)
                //
                //第三条索引消息的 的情况  ：
                //3rd_index_msg, logicIndex = 3,根据key，散列到slot_3，则3rd_index_msg.slotValue = 0(logicIndex)
                //
                //第四条索引消息的 的情况  ：
                //4th_index_msg, logicIndex = 4,根据key，散列到slot_3，则4rd_index_msg.slotValue = 3(logicIndex)
                //
                //第五条索引消息的 的情况  ：
                //5th_index_msg, logicIndex = 5,根据key，散列到slot_2，则5th_index_msg.slotValue = 2(logicIndex)
                //
                //这里就达到了一种类似链表的效果：
                //         _ _ _ _                                                               _ _ _ _
                //         |slot_2|  ------------------------------------------------------------|slot_3|
                //         ~~~~|~~~~                                                            ~~~~|~~~
                // |1st_index_msg,logicIndex=1,preLogicIndex=0|              |3rd_index_msg,logicIndex=3,preLogicIndex=0|
                //             |                                                       |
                // |2nd_index_msg,logicIndex=2,preLogicIndex=1|              |4th_index_msg,logicIndex=4,preLogicIndex=3|
                //             |
                // |5th_index_msg,logicIndex=5,preLogicIndex=2|
                //
                // 也就是说，当客户端需要通过制定的key来查询消息时，先通过散列算法，把key所在的slot槽值给算出
                // 例如，还是到absSlotPos = slot_2, 获取的值为 logicIndex = 5
                // 然后通过logicIndex 算出absIndexPos，即索引所在的绝对存储位置，分别获取phyOffset(具体消息的物理位置)，以及preLogicIndex
                // 然后在通过preLogicIndex 按照上述步骤找出具体的索引消息，直到preLogicIndex=0 为止。
                //
                //
                this.mappedByteBuffer.putInt(absIndexPos, keyHash);
                this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);
                this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);
                //absSlotPos:绝对槽位置
                this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());

                if (this.indexHeader.getIndexCount() <= 1) {
                    this.indexHeader.setBeginPhyOffset(phyOffset);
                    this.indexHeader.setBeginTimestamp(storeTimestamp);
                }

                //散列 槽 +1
                this.indexHeader.incHashSlotCount();
                //索引个数+1
                this.indexHeader.incIndexCount();
                //更新索引头文件的尾部 消息物理位置，
                this.indexHeader.setEndPhyOffset(phyOffset);
                //更新索引头文件的尾部 消息存储时间，
                this.indexHeader.setEndTimestamp(storeTimestamp);

                return true;
            } catch (Exception e) {
                log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            log.warn("Over index file capacity: index count = " + this.indexHeader.getIndexCount()
                + "; index max num = " + this.indexNum);
        }

        return false;
    }

    public int indexKeyHashMethod(final String key) {
        int keyHash = key.hashCode();
        int keyHashPositive = Math.abs(keyHash);
        if (keyHashPositive < 0)
            keyHashPositive = 0;
        return keyHashPositive;
    }

    public long getBeginTimestamp() {
        return this.indexHeader.getBeginTimestamp();
    }

    public long getEndTimestamp() {
        return this.indexHeader.getEndTimestamp();
    }

    public long getEndPhyOffset() {
        return this.indexHeader.getEndPhyOffset();
    }

    public boolean isTimeMatched(final long begin, final long end) {
        boolean result = begin < this.indexHeader.getBeginTimestamp() && end > this.indexHeader.getEndTimestamp();
        result = result || (begin >= this.indexHeader.getBeginTimestamp() && begin <= this.indexHeader.getEndTimestamp());
        result = result || (end >= this.indexHeader.getBeginTimestamp() && end <= this.indexHeader.getEndTimestamp());
        return result;
    }

    public void selectPhyOffset(final List<Long> phyOffsets, final String key, final int maxNum,
        final long begin, final long end, boolean lock) {
        if (this.mappedFile.hold()) {
            //获取 key 的绝对hash值
            int keyHash = indexKeyHashMethod(key);

            //通过keyHash 取模 计算消息所在的 槽
            int slotPos = keyHash % this.hashSlotNum;

            //计算绝对的 槽 位置 = 索引头大小(40) + slotPos * 4
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;
            try {
                if (lock) {
                    // fileLock = this.fileChannel.lock(absSlotPos,
                    // hashSlotSize, true);
                }

                //通过绝对的槽-位 得到 槽-值
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                // if (fileLock != null) {
                // fileLock.release();
                // fileLock = null;
                // }

                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()
                    || this.indexHeader.getIndexCount() <= 1) {
                    // TODO NOTFOUND
                } else {
                    for (int nextIndexToRead = slotValue;;) {
                        if (phyOffsets.size() >= maxNum) {
                            break;
                        }

                        //计算绝对的索引位置 = 索引头大小(40) + 5000000 * 4 + 20 * slotValue
                        int absIndexPos =
                            IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                                + nextIndexToRead * indexSize;

                        int keyHashRead = this.mappedByteBuffer.getInt(absIndexPos);
                        //读取物理offset (8字节)
                        long phyOffsetRead = this.mappedByteBuffer.getLong(absIndexPos + 4);

                        //读取时间差
                        long timeDiff = (long) this.mappedByteBuffer.getInt(absIndexPos + 4 + 8);
                        int prevIndexRead = this.mappedByteBuffer.getInt(absIndexPos + 4 + 8 + 4);

                        if (timeDiff < 0) {
                            break;
                        }

                        timeDiff *= 1000L;

                        long timeRead = this.indexHeader.getBeginTimestamp() + timeDiff;
                        boolean timeMatched = (timeRead >= begin) && (timeRead <= end);

                        if (keyHash == keyHashRead && timeMatched) {
                            phyOffsets.add(phyOffsetRead);
                        }

                        if (prevIndexRead <= invalidIndex
                            || prevIndexRead > this.indexHeader.getIndexCount()
                            || prevIndexRead == nextIndexToRead || timeRead < begin) {
                            break;
                        }

                        nextIndexToRead = prevIndexRead;
                    }
                }
            } catch (Exception e) {
                log.error("selectPhyOffset exception ", e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                this.mappedFile.release();
            }
        }
    }


    public static void main(String[] args) {
        System.out.println(40 + 4 * 5000000 + 20 * 4 * 5000000);
    }


}
