package com.smartrecruit.recruitment.dto.response;

import lombok.Data;

import java.util.List;

/**
 * Offer搭子 AI 求职匹配结果。
 *
 * 注意：matchScore 仅为 AI 参考评估，不代表实际招聘结果。
 */
@Data
public class AiMatchResultVO {

    /** 0-100 参考匹配分数 */
    private Integer score;

    /** 总体判断（一句话） */
    private String summary;

    /** 简历已有优势（数组） */
    private List<String> strengths;

    /** 岗位要求与现有材料的差距（数组） */
    private List<String> gaps;

    /** 可执行的优化建议（数组） */
    private List<String> suggestions;

    /** 使用的模型名称 */
    private String aiModel;

    /** 推理耗时毫秒 */
    private Long latencyMs;

    /** 免责声明 */
    private String disclaimer;

    /** 当前会员等级编码 */
    private String memberLevel;

    /** 当前会员等级名称 */
    private String memberLevelName;

    /** 每日 AI 匹配额度上限 */
    private Integer dailyLimit;

    /** 今日已使用次数（含本次成功调用） */
    private Integer usedToday;

    /** 今日剩余次数 */
    private Integer remainingQuota;
}
