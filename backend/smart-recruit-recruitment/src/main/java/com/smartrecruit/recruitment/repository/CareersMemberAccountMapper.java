package com.smartrecruit.recruitment.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartrecruit.recruitment.entity.CareersMemberAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * {@code careers_member_account} 表的 Mapper 接口。
 */
@Mapper
public interface CareersMemberAccountMapper extends BaseMapper<CareersMemberAccount> {

    /**
     * 写入或更新会员记录（按 {@code user_id} 唯一键）。
     *
     * <p>每次模拟开通均从当前时间重新计算有效期，可重复演示。</p>
     */
    @Insert("""
            INSERT INTO careers_member_account (user_id, level, status, start_time, expire_time)
            VALUES (#{userId}, #{level}, 'ACTIVE', #{startTime}, #{expireTime}) AS new
            ON DUPLICATE KEY UPDATE level = new.level,
                                    status = 'ACTIVE',
                                    start_time = new.start_time,
                                    expire_time = new.expire_time
            """)
    int upsertMembership(@Param("userId") Long userId,
                         @Param("level") String level,
                         @Param("startTime") LocalDateTime startTime,
                         @Param("expireTime") LocalDateTime expireTime);
}
