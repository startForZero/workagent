package com.chenxi.workagent.infra.storage;

import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MinIO 对象存储适配：上传/下载/预签名 URL。
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;
    private final WorkagentProperties properties;

    /**
     * 上传对象（bucket 不存在时自动创建）。
     *
     * @param bucket      目标 bucket
     * @param objectName  对象名（路径）
     * @param stream      输入流（调用方保证可用）
     * @param size        字节数
     * @param contentType 内容类型
     */
    public void put(String bucket, String objectName, InputStream stream, long size, String contentType) {
        try {
            ensureBucket(bucket);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            log.error("MinIO 上传失败: bucket={}, object={}", bucket, objectName, e);
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 下载对象为输入流（调用方负责关闭）。
     */
    public InputStream get(String bucket, String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            log.error("MinIO 下载失败: bucket={}, object={}", bucket, objectName, e);
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
    }

    /**
     * 生成预签名下载 URL（过期时间取 workagent.minio.presign-expire-minutes）。
     */
    public String presignGet(String bucket, String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(properties.getMinio().getPresignExpireMinutes(), TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            log.error("MinIO 预签名失败: bucket={}, object={}", bucket, objectName, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "生成下载链接失败");
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
