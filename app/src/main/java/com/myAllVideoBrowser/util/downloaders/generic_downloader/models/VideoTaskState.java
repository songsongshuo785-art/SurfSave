package com.myAllVideoBrowser.util.downloaders.generic_downloader.models;

public class VideoTaskState {
    public static final int DEFAULT = 0;       //默认状态
    public static final int PENDING = -1;      //下载排队
    public static final int PREPARE = 1;       //下载准备中
    public static final int START = 2;         //开始下载
    public static final int DOWNLOADING = 3;   //下载中
    public static final int PROXYREADY = 4;    //视频可以边下边播
    public static final int SUCCESS = 5;       //下载完成
    public static final int ERROR = 6;         //下载出错
    public static final int PAUSE = 7;         //下载暂停
    public static final int ENOSPC = 8;        //空间不足
    public static final int CANCELED = 9;      //下载取消
    public static final int PAUSING = 10;      //正在持久化暂停
    public static final int CANCELING = 11;    //正在停止并清理
    public static final int FINALIZING = 12;   //正在发布最终文件

}
