package com.baidu.sqlengine.merge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;import org.slf4j.LoggerFactory;

import com.baidu.sqlengine.SqlEngineServer;
import com.baidu.sqlengine.backend.mysql.BufferUtil;
import com.baidu.sqlengine.backend.mysql.MySQLMessage;
import com.baidu.sqlengine.backend.mysql.nio.handler.MultiNodeQueryHandler;
import com.baidu.sqlengine.backend.mysql.protocol.EOFPacket;
import com.baidu.sqlengine.backend.mysql.protocol.RowDataPacket;
import com.baidu.sqlengine.memory.SqlEngineMemory;
import com.baidu.sqlengine.memory.unsafe.memory.mm.DataNodeMemoryManager;
import com.baidu.sqlengine.memory.unsafe.memory.mm.MemoryManager;
import com.baidu.sqlengine.memory.unsafe.row.BufferHolder;
import com.baidu.sqlengine.memory.unsafe.row.StructType;
import com.baidu.sqlengine.memory.unsafe.row.UnsafeRow;
import com.baidu.sqlengine.memory.unsafe.row.UnsafeRowWriter;
import com.baidu.sqlengine.memory.unsafe.utils.SqlEnginePropertyConf;
import com.baidu.sqlengine.memory.unsafe.utils.sort.PrefixComparator;
import com.baidu.sqlengine.memory.unsafe.utils.sort.PrefixComparators;
import com.baidu.sqlengine.memory.unsafe.utils.sort.RowPrefixComputer;
import com.baidu.sqlengine.memory.unsafe.utils.sort.UnsafeExternalRowSorter;
import com.baidu.sqlengine.route.RouteResultSet;
import com.baidu.sqlengine.server.ServerConnection;
import com.baidu.sqlengine.util.StringUtil;

public class DataNodeMergeManager extends AbstractDataNodeMerge {

    private static Logger LOGGER = LoggerFactory.getLogger(DataNodeMergeManager.class);

    /**
     * key为datanode的分片节点名字
     * value为对应的排序器
     * 目前，没有使用！
     */
    private ConcurrentHashMap<String, UnsafeExternalRowSorter> unsafeRows =
            new ConcurrentHashMap<String,UnsafeExternalRowSorter>();
    /**
     * 全局sorter，排序器
     */
    private UnsafeExternalRowSorter globalSorter = null;
    /**
     * UnsafeRowGrouper
     */
    private UnsafeRowGrouper unsafeRowGrouper = null;

    /**
     * 全局merge，排序器
     */
    private UnsafeExternalRowSorter globalMergeResult = null;

    /**
     * sorter需要的上下文环境
     */
    private final SqlEngineMemory sqlEngineMemory;
    private final MemoryManager memoryManager;
    private final SqlEnginePropertyConf conf;
    /**
     * Limit N，M
     */
    private final  int limitStart;
    private final  int limitSize;


    public DataNodeMergeManager(MultiNodeQueryHandler handler, RouteResultSet rrs) {
        super(handler,rrs);
        this.sqlEngineMemory = SqlEngineServer.getInstance().getSqlEngineMemory();
        this.memoryManager = sqlEngineMemory.getResultMergeMemoryManager();
        this.conf = sqlEngineMemory.getConf();
        this.limitStart = rrs.getLimitStart();
        this.limitSize = rrs.getLimitSize();
    }


    public void onRowMetaData(Map<String, ColMeta> columToIndx, int fieldCount) throws IOException {

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("field metadata keys:" + columToIndx.keySet());
            LOGGER.debug("field metadata values:" + columToIndx.values());
        }

        OrderCol[] orderCols = null;
        StructType schema = null;
        UnsafeExternalRowSorter.PrefixComputer prefixComputer = null;
        PrefixComparator prefixComparator = null;

      
        DataNodeMemoryManager dataNodeMemoryManager = null;
        UnsafeExternalRowSorter sorter = null;

        int[] groupColumnIndexs = null;
        this.fieldCount = fieldCount;

        if (rrs.getGroupByCols() != null) {
            groupColumnIndexs = toColumnIndex(rrs.getGroupByCols(), columToIndx);
            if (LOGGER.isDebugEnabled()) {
                for (int i = 0; i <rrs.getGroupByCols().length ; i++) {
                    LOGGER.debug("groupColumnIndexs:" + rrs.getGroupByCols()[i]);
                }
            }
        }


        if (rrs.getHavingCols() != null) {
            ColMeta colMeta = columToIndx.get(rrs.getHavingCols().getLeft()
                    .toUpperCase());

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("getHavingCols:" + rrs.getHavingCols().toString());
            }
            if (colMeta != null) {
                rrs.getHavingCols().setColMeta(colMeta);
            }
        }

        if (rrs.isHasAggrColumn()) {
            List<MergeCol> mergCols = new LinkedList<MergeCol>();
            Map<String, Integer> mergeColsMap = rrs.getMergeCols();

            if (mergeColsMap != null) {
            	if (LOGGER.isDebugEnabled()) {
                	LOGGER.debug("isHasAggrColumn:" + mergeColsMap.toString());
            	}
                for (Map.Entry<String, Integer> mergEntry : mergeColsMap
                        .entrySet()) {
                    String colName = mergEntry.getKey().toUpperCase();
                    int type = mergEntry.getValue();
                    if (MergeCol.MERGE_AVG == type) {
                        ColMeta sumColMeta = columToIndx.get(colName + "SUM");
                        ColMeta countColMeta = columToIndx.get(colName
                                + "COUNT");
                        if (sumColMeta != null && countColMeta != null) {
                            ColMeta colMeta = new ColMeta(sumColMeta.colIndex,
                                    countColMeta.colIndex,
                                    sumColMeta.getColType());
                            mergCols.add(new MergeCol(colMeta, mergEntry
                                    .getValue()));
                        }
                    } else {
                        ColMeta colMeta = columToIndx.get(colName);
                        mergCols.add(new MergeCol(colMeta, mergEntry.getValue()));
                    }
                }
            }

            // add no alias merg column
            for (Map.Entry<String, ColMeta> fieldEntry : columToIndx.entrySet()) {
                String colName = fieldEntry.getKey();
                int result = MergeCol.tryParseAggCol(colName);
                if (result != MergeCol.MERGE_UNSUPPORT
                        && result != MergeCol.MERGE_NOMERGE) {
                    mergCols.add(new MergeCol(fieldEntry.getValue(), result));
                }
            }

            /**
             * Group操作
             */
            unsafeRowGrouper = new UnsafeRowGrouper(columToIndx,rrs.getGroupByCols(),
                    mergCols.toArray(new MergeCol[mergCols.size()]),
                    rrs.getHavingCols());
        }


        if (rrs.getOrderByCols() != null) {
            LinkedHashMap<String, Integer> orders = rrs.getOrderByCols();
            orderCols = new OrderCol[orders.size()];
            int i = 0;
            for (Map.Entry<String, Integer> entry : orders.entrySet()) {
                String key = StringUtil.removeBackquote(entry.getKey()
                        .toUpperCase());
                ColMeta colMeta = columToIndx.get(key);
                if (colMeta == null) {
                    throw new IllegalArgumentException(
                            "all columns in order by clause should be in the selected column list!"
                                    + entry.getKey());
                }
                orderCols[i++] = new OrderCol(colMeta, entry.getValue());
            }

            /**
             * 构造全局排序器
             */
            schema = new StructType(columToIndx,fieldCount);
            schema.setOrderCols(orderCols);

            prefixComputer = new RowPrefixComputer(schema);

            if(orderCols.length>0 && orderCols[0].getOrderType() == OrderCol.COL_ORDER_TYPE_ASC){
                prefixComparator = PrefixComparators.LONG;
            }else {
                prefixComparator = PrefixComparators.LONG_DESC;
            }

            dataNodeMemoryManager =
                    new DataNodeMemoryManager(memoryManager,Thread.currentThread().getId());

            /**
             * 默认排序，只是将数据连续存储到内存中即可。
             */
            globalSorter = new UnsafeExternalRowSorter(
                    dataNodeMemoryManager,
                    sqlEngineMemory,
                    schema,
                    prefixComparator, prefixComputer,
                    conf.getSizeAsBytes("sqlEngine.buffer.pageSize","1m"),
                    false/**是否使用基数排序*/,
                    true/**排序*/);
        }


        if(conf.getBoolean("sqlEngine.stream.output.result",false)
                && globalSorter == null
                && unsafeRowGrouper == null){
                setStreamOutputResult(true);
        }else {

            /**
             * 1.schema 
             */

             schema = new StructType(columToIndx,fieldCount);
             schema.setOrderCols(orderCols);

            /**
             * 2 .PrefixComputer
             */
             prefixComputer = new RowPrefixComputer(schema);

            /**
             * 3 .PrefixComparator 默认是ASC，可以选择DESC
             */

            prefixComparator = PrefixComparators.LONG;


            dataNodeMemoryManager = new DataNodeMemoryManager(memoryManager,
                            Thread.currentThread().getId());

            globalMergeResult = new UnsafeExternalRowSorter(
                    dataNodeMemoryManager,
                    sqlEngineMemory,
                    schema,
                    prefixComparator,
                    prefixComputer,
                    conf.getSizeAsBytes("sqlEngine.buffer.pageSize", "1m"),
                    false,/**是否使用基数排序*/
                    false/**不排序*/);
        }
    }

    @Override
    public List<RowDataPacket> getResults(byte[] eof) {
        return null;
    }

    private UnsafeRow unsafeRow = null;
    private BufferHolder bufferHolder = null;
    private UnsafeRowWriter unsafeRowWriter = null;
    private  int Index = 0;

    @Override
    public void run() {

        if (!running.compareAndSet(false, true)) {
            return;
        }

        boolean nulpack = false;

        try {
            for (; ; ) {
                final PackWraper pack = packs.poll();

                if (pack == null) {
                    nulpack = true;
                    break;
                }
                if (pack == END_FLAG_PACK) {
                    /**
                     * 最后一个节点datenode发送了row eof packet说明了整个
                     * 分片数据全部接收完成，进而将结果集全部发给你SqlEngine 客户端
                     */
                    final int warningCount = 0;
                    final EOFPacket eofp = new EOFPacket();
                    final ByteBuffer eof = ByteBuffer.allocate(9);
                    BufferUtil.writeUB3(eof, eofp.calcPacketSize());
                    eof.put(eofp.packetId);
                    eof.put(eofp.fieldCount);
                    BufferUtil.writeUB2(eof,warningCount);
                    BufferUtil.writeUB2(eof,eofp.status);
                    final ServerConnection source = multiQueryHandler.getSession().getSource();
                    final byte[] array = eof.array();


                    Iterator<UnsafeRow> iters = null;


                    if (unsafeRowGrouper != null){
                        /**
                         * group by里面需要排序情况
                         */
                        if (globalSorter != null){
                            iters = unsafeRowGrouper.getResult(globalSorter);
                        }else {
                            iters = unsafeRowGrouper.getResult(globalMergeResult);
                        }

                    }else if(globalSorter != null){

                        iters = globalSorter.sort();

                    }else if (!isStreamOutputResult){

                        iters = globalMergeResult.sort();

                    }

                    if(iters != null)
                        multiQueryHandler.outputMergeResult(source,array,iters);


                    if(unsafeRowGrouper!=null){
                        unsafeRowGrouper.free();
                        unsafeRowGrouper = null;
                    }

                    if(globalSorter != null){
                        globalSorter.cleanupResources();
                        globalSorter = null;
                    }

                    if (globalMergeResult != null){
                        globalMergeResult.cleanupResources();
                        globalMergeResult = null;
                    }

                    break;
                }

                unsafeRow = new UnsafeRow(fieldCount);
                bufferHolder = new BufferHolder(unsafeRow,0);
                unsafeRowWriter = new UnsafeRowWriter(bufferHolder,fieldCount);
                bufferHolder.reset();

                /**
                 *构造一行row，将对应的col填充.
                 */
                MySQLMessage mm = new MySQLMessage(pack.rowData);
                mm.readUB3();
                mm.read();

                for (int i = 0; i < fieldCount; i++) {
                    byte[] colValue = mm.readBytesWithLength();
                    if (colValue != null)
                    	unsafeRowWriter.write(i,colValue);
                    else
                        unsafeRow.setNullAt(i);
                }

                unsafeRow.setTotalSize(bufferHolder.totalSize());

                if(unsafeRowGrouper != null){
                    unsafeRowGrouper.addRow(unsafeRow);
                }else if (globalSorter != null){
                    globalSorter.insertRow(unsafeRow);
                }else {
                    globalMergeResult.insertRow(unsafeRow);
                }

                unsafeRow = null;
                bufferHolder = null;
                unsafeRowWriter = null;
            }

        } catch (final Exception e) {
            multiQueryHandler.handleDataProcessException(e);
        } finally {
            running.set(false);
            if (nulpack && !packs.isEmpty()) {
                this.run();
            }
        }
    }

    /**
     * 释放DataNodeMergeManager所申请的资源
     */
    public void clear() {

        unsafeRows.clear();

        if(unsafeRowGrouper!=null){
            unsafeRowGrouper.free();
            unsafeRowGrouper = null;
        }

        if(globalSorter != null){
            globalSorter.cleanupResources();
            globalSorter = null;
        }

        if (globalMergeResult != null){
            globalMergeResult.cleanupResources();
            globalMergeResult = null;
        }
    }
}
