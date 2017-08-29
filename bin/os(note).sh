#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

export PATH=$PATH:/sbin

# sudo sysctl -w vm.extra_free_kbytes=2000000
# sudo sysctl -w vm.min_free_kbytes=1000000


#内核参数overcommit_memory ，内存分配策略：
#可选值：0、1、2。
#0， 表示内核将检查是否有足够的可用内存供应用进程使用；如果有足够的可用内存，内存申请允许；否则，内存申请失败，并把错误返回给应用进程。
#1， 表示内核允许分配所有的物理内存，而不管当前的内存状态如何。
#2， 表示内核允许分配超过所有物理内存和交换空间总和的内存
#什么是Overcommit和OOM：
#
#    Linux对大部分申请内存的请求都回复"yes"，以便能跑更多更大的程序。因为申请内存后，并不会马上使用内存。
#    这种技术叫做Overcommit。当linux发现内存不足时，会发生OOM killer(OOM=out-of-memory)。
#    它会选择杀死一些进程(用户态进程，不是内核线程)，以便释放内存。
#
#    当oom-killer发生时，linux会选择杀死哪些进程？
#    选择进程的函数是oom_badness函数(在mm/oom_kill.c中)，该函数会计算每个进程的点数(0~1000)。
#    点数越高，这个进程越有可能被杀死。每个进程的点数跟oom_score_adj有关，而且oom_score_adj可以被设置(-1000最低，1000最高)。
sudo sysctl -w vm.overcommit_memory=1


sudo sysctl -w vm.drop_caches=1


#内核参数zone_reclaim_mode：
#
#可选值0、1
#a、当某个节点可用内存不足时：
#1、如果为0的话，那么系统会倾向于从其他节点分配内存
#2、如果为1的话，那么系统会倾向于从本地节点回收Cache内存多数时候
#b、Cache对性能很重要，所以0是一个更好的选择。
sudo sysctl -w vm.zone_reclaim_mode=0


#单个进程可以用的VMA(虚拟内存区域)的数量
#Rmq 使用了大量的mmap内存映射文件，有可能会超出系统句柄限制
#因此可以通过修改vm.max_map_count，默认为65536
#同时需要关注一下参数>ulimit -a
#open files       (-n) 131072
sudo sysctl -w vm.max_map_count=655360


#所有全局系统进程的脏页数量达到系统总内存的多大比例后，就会触发pdflush/flush/kdmflush等后台回写进程运行。
#将vm.dirty_background_ratio设置为5-10，将vm.dirty_ratio设置为它的两倍左右，以确保能持续将脏数据刷新到磁盘，
#避免瞬间I/O写，产生严重等待（和MySQL中的innodb_max_dirty_pages_pct类似）

#这个参数指定了当文件系统缓存脏页数量达到系统内存
#百分之多少时（如5%）就会触发pdflush/flush/kdmflush等后台回写进程运行，
#将一定缓存的脏页异步地刷入外存；
sudo sysctl -w vm.dirty_background_ratio=50

#而这个参数则指定了当文件系统缓存脏页数量达到系统内存百分之多少时（如10%），系统不得不开始处
#理缓存脏页（因为此时脏页数量已经比较多，为了避免数据丢失需要将一定脏页刷入外存）；在此过程中很多
#应用进程可能会因为系统转而处理文件IO而阻塞。
sudo sysctl -w vm.dirty_ratio=50


sudo sysctl -w vm.dirty_writeback_centisecs=360000
sudo sysctl -w vm.page-cluster=3
sudo sysctl -w vm.swappiness=1

echo 'ulimit -n 655350' >> /etc/profile
echo '* hard nofile 655350' >> /etc/security/limits.conf

echo '* hard memlock      unlimited' >> /etc/security/limits.conf
echo '* soft memlock      unlimited' >> /etc/security/limits.conf


#系统I/O调度算法：deadline
DISK=`df -k | sort -n -r -k 2 | awk -F/ 'NR==1 {gsub(/[0-9].*/,"",$3); print $3}'`
[ "$DISK" = 'cciss' ] && DISK='cciss!c0d0'
echo 'deadline' > /sys/block/${DISK}/queue/scheduler


echo "---------------------------------------------------------------"


sysctl vm.extra_free_kbytes

#该文件表示强制Linux VM最低保留多少空闲内存（Kbytes）。
#当可用内存低于这个参数时，系统开始回收cache内存，以释放内存，直到可用内存大于这个值。
#cat  /proc/sys/vm/min_free_kbytes        centos6.4默认66M
#67584
sysctl vm.min_free_kbytes
sysctl vm.overcommit_memory
sysctl vm.drop_caches
sysctl vm.zone_reclaim_mode
sysctl vm.max_map_count
sysctl vm.dirty_background_ratio
sysctl vm.dirty_ratio
sysctl vm.dirty_writeback_centisecs
sysctl vm.page-cluster
sysctl vm.swappiness

su - admin -c 'ulimit -n'
cat /sys/block/$DISK/queue/scheduler

if [ -d ${HOME}/tmpfs ] ; then
    echo "tmpfs exist, do nothing."
else
    ln -s /dev/shm tmpfs
    echo "create tmpfs ok"
fi
