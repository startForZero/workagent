package com.chenxi.workagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chenxi.workagent.infra.entity.UserMemoryDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户长期记忆镜像表 Mapper。
 * @author 辰夕
 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemoryDO> {
}
