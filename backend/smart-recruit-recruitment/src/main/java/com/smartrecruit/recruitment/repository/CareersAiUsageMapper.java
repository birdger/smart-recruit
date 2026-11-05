package com.smartrecruit.recruitment.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartrecruit.recruitment.entity.CareersAiUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * {@code careers_ai_usage} 表的 Mapper 接口。
 */
@Mapper
public interface CareersAiUsageMapper extends BaseMapper<CareersAiUsage> {

    /**
     * 原子自增一次额度（仅当未达上限）。
     *
     * <p>返回受影响行数：1=预占成功，0=已达上限或记录不存在。</p>
     */
    @Update("""
            UPDATE careers_ai_usage
               SET used_count = used_count + 1
             WHERE user_id = #{userId}
               AND usage_date = #{usageDate}
               AND used_count < #{maxCount}
            """)
    int incrementIfBelow(@Param("userId") Long userId,
                         @Param("usageDate") LocalDate usageDate,
                         @Param("maxCount") int maxCount);

    /**
     * 首次创建当日额度记录并记 1 次。
     *
     * <p>{@code INSERT IGNORE} 在并发重复插入时返回 0。</p>
     */
    @Insert("""
            INSERT IGNORE INTO careers_ai_usage (user_id, usage_date, used_count)
            VALUES (#{userId}, #{usageDate}, 1)
            """)
    int insertInitial(@Param("userId") Long userId, @Param("usageDate") LocalDate usageDate);

    /**
     * 回滚一次预占（AI 调用失败时使用）。
     */
    @Update("""
            UPDATE careers_ai_usage
               SET used_count = used_count - 1
             WHERE user_id = #{userId}
               AND usage_date = #{usageDate}
               AND used_count > 0
            """)
    int release(@Param("userId") Long userId, @Param("usageDate") LocalDate usageDate);

    /**
     * 查询某日已使用次数。
     */
    @Select("""
            SELECT used_count FROM careers_ai_usage
             WHERE user_id = #{userId} AND usage_date = #{usageDate}
            """)
    Integer selectUsed(@Param("userId") Long userId, @Param("usageDate") LocalDate usageDate);
}
