package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.service.memory.MemoryService;
import com.chenxi.workagent.service.memory.dto.MemoryResponse;
import com.chenxi.workagent.service.memory.dto.UpdateMemoryRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆中心接口（M4）：用户长期记忆的查看/编辑/删除/一键清空。
 * MEMORY.md 文件为真相源，表为 UI 镜像（服务层 read-repair 对账）。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/memories")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;

    /** 记忆列表（返回前与 MEMORY.md 对账，自动清理已被归纳合并的条目） */
    @GetMapping
    public ApiResult<List<MemoryResponse>> list(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId) {
        return ApiResult.ok(memoryService.list(userId));
    }

    /** 编辑单条记忆（先改文件成功再改表） */
    @PutMapping("/{id}")
    public ApiResult<Void> update(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id,
            @RequestBody UpdateMemoryRequest request) {
        memoryService.update(userId, id, request.content());
        return ApiResult.ok();
    }

    /** 删除单条记忆 */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        memoryService.delete(userId, id);
        return ApiResult.ok();
    }

    /** 一键清空全部记忆（含每日流水文件；会话聊天记录不受影响） */
    @DeleteMapping
    public ApiResult<Void> clearAll(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId) {
        memoryService.clearAll(userId);
        return ApiResult.ok();
    }
}
