package com.chenxi.workagent.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chenxi.workagent.infra.entity.MessageDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * wa_message Mapper。
 * @author 辰夕
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageDO> {
}
