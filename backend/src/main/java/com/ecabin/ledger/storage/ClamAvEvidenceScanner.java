package com.ecabin.ledger.storage;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** ClamAV INSTREAM scanner. Absence or error leaves uploads quarantined. */
@Component
public class ClamAvEvidenceScanner implements EvidenceScanner {
    @Value("${app.attachments.clamav-host:}") private String host;
    @Value("${app.attachments.clamav-port:3310}") private int port;
    @Value("${app.attachments.clamav-timeout-ms:5000}") private int timeout;
    @Override public Result scan(byte[] content) {
        if(host==null||host.isBlank()) return Result.UNAVAILABLE;
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress(host,port),timeout);socket.setSoTimeout(timeout);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.write("zINSTREAM".getBytes(StandardCharsets.US_ASCII));out.writeByte(0);
            int offset=0;while(offset<content.length){int size=Math.min(8192,content.length-offset);out.writeInt(size);out.write(content,offset,size);offset+=size;}out.writeInt(0);out.flush();
            byte[] response=new byte[4096];int read=socket.getInputStream().read(response);if(read<0)return Result.UNAVAILABLE;String value=new String(response,0,read,StandardCharsets.US_ASCII);
            if(value.contains("OK")) return Result.CLEAN;if(value.contains("FOUND")) return Result.REJECTED;return Result.UNAVAILABLE;
        } catch(Exception ex){return Result.UNAVAILABLE;}
    }
}
