package com.chenxi.workagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chenxi.workagent.infra.entity.UserModelDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author 辰夕
 */
@Mapper
public interface UserModelMapper extends BaseMapper<UserModelDO> {
}
