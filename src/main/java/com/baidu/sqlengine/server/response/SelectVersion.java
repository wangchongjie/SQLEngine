
package com.baidu.sqlengine.server.response;

import java.nio.ByteBuffer;

import com.baidu.sqlengine.backend.mysql.PacketUtil;
import com.baidu.sqlengine.constant.Fields;
import com.baidu.sqlengine.config.Versions;
import com.baidu.sqlengine.backend.mysql.protocol.EOFPacket;
import com.baidu.sqlengine.backend.mysql.protocol.FieldPacket;
import com.baidu.sqlengine.backend.mysql.protocol.ResultSetHeaderPacket;
import com.baidu.sqlengine.backend.mysql.protocol.RowDataPacket;
import com.baidu.sqlengine.server.ServerConnection;


public class SelectVersion {

    private static final int FIELD_COUNT = 1;
    private static final ResultSetHeaderPacket header = PacketUtil.getHeader(FIELD_COUNT);
    private static final FieldPacket[] fields = new FieldPacket[FIELD_COUNT];
    private static final EOFPacket eof = new EOFPacket();
    static {
        int i = 0;
        byte packetId = 0;
        header.packetId = ++packetId;
        fields[i] = PacketUtil.getField("VERSION()", Fields.FIELD_TYPE_VAR_STRING);
        fields[i++].packetId = ++packetId;
        eof.packetId = ++packetId;
    }

    public static void response(ServerConnection c) {
        ByteBuffer buffer = c.allocate();
        buffer = header.write(buffer, c,true);
        for (FieldPacket field : fields) {
            buffer = field.write(buffer, c,true);
        }
        buffer = eof.write(buffer, c,true);
        byte packetId = eof.packetId;
        RowDataPacket row = new RowDataPacket(FIELD_COUNT);
        row.add(Versions.SERVER_VERSION);
        row.packetId = ++packetId;
        buffer = row.write(buffer, c,true);
        EOFPacket lastEof = new EOFPacket();
        lastEof.packetId = ++packetId;
        buffer = lastEof.write(buffer, c,true);
        c.write(buffer);
    }

}