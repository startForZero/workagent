package com.chenxi.workagent.service.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chenxi.workagent.infra.common.constant.MinioConstants;
import com.chenxi.workagent.infra.common.enums.ErrorCode;
import com.chenxi.workagent.infra.common.exception.BizException;
import com.chenxi.workagent.infra.config.WorkagentProperties;
import com.chenxi.workagent.infra.entity.FileDO;
import com.chenxi.workagent.infra.mapper.FileMapper;
import com.chenxi.workagent.infra.storage.MinioStorageService;
import com.chenxi.workagent.service.file.dto.FileUploadResponse;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话附件服务：上传至 MinIO，发起 run 时落地到会话工作区。
 * @author 辰夕
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    /** 允许的文件扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "docx", "xlsx", "csv", "txt", "md", "png", "jpg", "jpeg", "gif", "webp");

    /** 工作区内附件目录名 */
    private static final String WORKSPACE_UPLOAD_DIR = "uploads";

    private final FileMapper fileMapper;
    private final MinioStorageService storageService;
    private final WorkagentProperties properties;

    public FileUploadResponse upload(Long userId, MultipartFile file) {
        if (file.getSize() > properties.getUpload().getMaxSizeMb() * 1024 * 1024) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE);
        }
        String filename = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String ext = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String objectName = MinioConstants.uploadObjectName(userId, fileId, filename);
        try (InputStream in = file.getInputStream()) {
            storageService.put(properties.getMinio().getBucketUploads(),
                    objectName, in, file.getSize(), file.getContentType());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取上传文件失败: {}", filename, e);
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        FileDO entity = new FileDO();
        entity.setFileId(fileId);
        entity.setUserId(userId);
        entity.setFilename(filename);
        entity.setContentType(file.getContentType());
        entity.setSize(file.getSize());
        entity.setOssPath(objectName);
        entity.setCreatedAt(LocalDateTime.now());
        fileMapper.insert(entity);
        return new FileUploadResponse(fileId, filename, file.getSize(), file.getContentType());
    }

    /**
     * 按 fileId 查询当前用户名下的文件（用于消息落库时记录附件名）。
     */
    public List<FileDO> findOwnedFiles(Long userId, List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        return fileMapper.selectList(new LambdaQueryWrapper<FileDO>()
                .eq(FileDO::getUserId, userId)
                .in(FileDO::getFileId, fileIds));
    }

    /**
     * 发起 run 前，将附件从 MinIO 落地到该会话工作区 uploads/ 目录。
     *
     * @return 落地后的相对路径清单（注入消息上下文）
     */
    public List<String> stageFilesForRun(Long userId, String sessionId, List<String> fileIds) {
        List<FileDO> files = findOwnedFiles(userId, fileIds);
        if (files.isEmpty()) {
            return List.of();
        }
        Path dir = Path.of(properties.getWorkspaceRoot(),
                String.valueOf(userId), sessionId, WORKSPACE_UPLOAD_DIR);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.error("创建工作区附件目录失败: {}", dir, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "工作区初始化失败");
        }
        return files.stream().map(f -> {
            Path target = dir.resolve(f.getFilename());
            try (InputStream in = storageService.get(
                    properties.getMinio().getBucketUploads(), f.getOssPath())) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                log.error("附件落地工作区失败: fileId={}", f.getFileId(), e);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "附件准备失败");
            }
            return WORKSPACE_UPLOAD_DIR + "/" + f.getFilename();
        }).toList();
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
