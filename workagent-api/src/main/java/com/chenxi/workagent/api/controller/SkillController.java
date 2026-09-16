package com.chenxi.workagent.api.controller;

import com.chenxi.workagent.infra.common.ApiResult;
import com.chenxi.workagent.infra.common.constant.SecurityConstants;
import com.chenxi.workagent.infra.entity.SkillDO;
import com.chenxi.workagent.service.skill.SkillService;
import com.chenxi.workagent.service.skill.dto.SkillDetailResponse;
import com.chenxi.workagent.service.skill.dto.SkillSummaryResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 技能市场接口（M3）：列表/详情/文件预览/导入/导出/删除。
 * 公共区仅管理员可导入与删除（服务层二次校验）。
 * @author 辰夕
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    /** 市场列表：scope=PUBLIC/USER/缺省全部，keyword 模糊匹配标识/描述/标签 */
    @GetMapping
    public ApiResult<List<SkillSummaryResponse>> list(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return ApiResult.ok(skillService.list(userId, scope, keyword));
    }

    @GetMapping("/{id}")
    public ApiResult<SkillDetailResponse> detail(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        return ApiResult.ok(skillService.detail(userId, id));
    }

    /** 包内文件内容预览（详情页文件树点击） */
    @GetMapping("/{id}/files/content")
    public ApiResult<Map<String, String>> fileContent(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id,
            @RequestParam("path") String path) {
        return ApiResult.ok(Map.of("path", path, "content", skillService.fileContent(userId, id, path)));
    }

    /** 导入技能 zip：scope=PUBLIC 需管理员 */
    @PostMapping("/import")
    public ApiResult<SkillSummaryResponse> importZip(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
            @RequestParam(value = "scope", defaultValue = SkillDO.SCOPE_USER) String scope,
            @RequestParam("file") MultipartFile file) {
        return ApiResult.ok(skillService.importZip(userId, scope, file));
    }

    /** 导出：返回 zip 预签名下载 URL */
    @GetMapping("/{id}/export")
    public ApiResult<Map<String, String>> export(
            @RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
            @PathVariable("id") Long id) {
        return ApiResult.ok(Map.of("url", skillService.exportUrl(userId, id)));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@RequestAttribute(SecurityConstants.ATTR_USER_ID) Long userId,
                                  @PathVariable("id") Long id) {
        skillService.delete(userId, id);
        return ApiResult.ok();
    }
}
