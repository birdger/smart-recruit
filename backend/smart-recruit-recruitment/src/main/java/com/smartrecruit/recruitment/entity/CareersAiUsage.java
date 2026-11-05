package com.smartrecruit.recruitment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 匹配每日额度使用量（课程演示）。
 *
 * <p>对应表 {@code careers_ai_usage}，按 {@code user_id + usage_date} 唯一。</p>
 */
@Data
@TableName("careers_ai_usage")
public class CareersAiUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 系统用户 ID */
    private Long userId;

    /** 使用日期（Asia/Shanghai） */
    private LocalDate usageDate;

    /** 当日已使用次数 */
    private Integer usedCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
