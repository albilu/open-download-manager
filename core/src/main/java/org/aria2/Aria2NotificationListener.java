// Aria2NotificationListener.java
package org.aria2;

import org.aria2.Aria2Client.Aria2RpcError;

public interface Aria2NotificationListener {

    void onDownloadStart(String gid);

    void onDownloadPause(String gid);

    void onDownloadStop(String gid);

    void onDownloadComplete(String gid);

    void onDownloadError(String gid, Aria2RpcError error);

    void onBtDownloadComplete(String gid);

    //void onDownloadProgress(String gid, long numFiles, Map<String, Object> status);
}
