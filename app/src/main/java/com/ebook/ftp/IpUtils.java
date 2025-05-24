package com.ebook.ftp;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;

public class IpUtils {
    public static String getLocalIpAddress(){
        try{
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces){
                List<InetAddress> addr = Collections.list(intf.getInetAddresses());
                for (InetAddress inetAddress : addr){
                   if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address){
                       return inetAddress.getHostAddress();
                   }
                }
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        return  null;
    }
}
