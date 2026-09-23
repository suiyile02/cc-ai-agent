package com.ai.config.props;

import lombok.Data;

/** 上传文件存储({@code app.storage.*}), 从 {@code AppProperties.Storage} 迁出。 */
@Data
public class StorageProps {

    /**
     * 上传文件本地存储根目录。
     *
     * <p><b>多实例部署必须换成共享存储</b>(NFS/OSS/MinIO)：文件落在本机磁盘, 实例 A 上传的文档
     * 实例 B 读不到, 解析与下载都会 404。见 roadmap E 系列。
     */
    private String path = "./data/files";
}
