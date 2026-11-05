package com.smartrecruit.recruitment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartrecruit.common.exception.ValidationException;
import com.smartrecruit.recruitment.dto.response.MembershipActivateVO;
import com.smartrecruit.recruitment.dto.response.MembershipPlanVO;
import com.smartrecruit.recruitment.dto.response.MembershipVO;
import com.smartrecruit.recruitment.dto.response.PlanFeatureVO;
import com.smartrecruit.recruitment.entity.CareersMemberAccount;
import com.smartrecruit.recruitment.enums.MembershipLevel;
import com.smartrecruit.recruitment.repository.CareersAiUsageMapper;
import com.smartrecruit.recruitment.repository.CareersMemberAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 会员中心与 AI 匹配额度服务（课程演示）。
 *
 * <p>所有会员等级、有效期与额度均以服务端数据为准；客户端只展示服务端返回结果。
 * 额度按 {@code Asia/Shanghai} 自然日重置，开通会员不产生真实支付。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipService {

    /** 额度重置时区（本地自然日） */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ORDER_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CareersMemberAccountMapper accountMapper;
    private final CareersAiUsageMapper usageMapper;

    // ──────────────────────── 查询 ────────────────────────

    /**
     * 查询当前用户的会员信息与今日额度。
     */
    public MembershipVO getMembership(Long userId) {
        LocalDateTime now = LocalDateTime.now(ZONE).withNano(0);
        CareersMemberAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<CareersMemberAccount>()
                        .eq(CareersMemberAccount::getUserId, userId)
                        .last("LIMIT 1"));

        MembershipLevel level = MembershipLevel.FREE;
        String status = "FREE";
        LocalDateTime startTime = null;
        LocalDateTime expireTime = null;

        if (account != null && account.getLevel() != null) {
            MembershipLevel stored = MembershipLevel.of(account.getLevel());
            if (stored != MembershipLevel.FREE) {
                startTime = account.getStartTime();
                expireTime = account.getExpireTime();
                if (expireTime != null && expireTime.isAfter(now)) {
                    level = stored;
                    status = "ACTIVE";
                } else {
                    // 已过期：按免费用户对待，但仍展示历史有效期
                    status = "EXPIRED";
                }
            }
        }

        LocalDate today = LocalDate.now(ZONE);
        int used = usedToday(userId, today);
        int limit = level.getDailyLimit();

        MembershipVO vo = new MembershipVO();
        vo.setLevel(level.name());
        vo.setLevelName(level.getDisplayName());
        vo.setStatus(status);
        vo.setActive(level != MembershipLevel.FREE);
        vo.setStartTime(startTime == null ? null : startTime.format(DATE_TIME_FMT));
        vo.setExpireTime(expireTime == null ? null : expireTime.format(DATE_TIME_FMT));
        vo.setDaysRemaining(expireTime == null || level == MembershipLevel.FREE
                ? null
                : Math.max(0, ChronoUnit.DAYS.between(now.toLocalDate(), expireTime.toLocalDate())));
        vo.setDailyLimit(limit);
        vo.setUsedToday(used);
        vo.setRemaining(Math.max(0, limit - used));
        vo.setUsageDate(today.toString());
        vo.setResetAt(today.plusDays(1).atStartOfDay().format(DATE_TIME_FMT));
        vo.setPerks(perksOf(level));
        return vo;
    }

    /**
     * 查询三档会员套餐（额度与演示价格均由服务端统一配置）。
     */
    public List<MembershipPlanVO> listPlans() {
        List<MembershipPlanVO> plans = new ArrayList<>();
        for (MembershipLevel level : MembershipLevel.values()) {
            MembershipPlanVO plan = new MembershipPlanVO();
            plan.setCode(level.name());
            plan.setName(level.getDisplayName());
            plan.setPrice(level.getDemoPrice());
            plan.setPriceNote(level == MembershipLevel.FREE
                    ? "永久免费"
                    : "演示价格 ¥" + level.getDemoPrice() + "/月");
            plan.setDurationDays(level.getDurationDays());
            plan.setDailyLimit(level.getDailyLimit());
            plan.setRecommended(level.isRecommended());
            plan.setFeatures(featuresOf(level));
            plans.add(plan);
        }
        return plans;
    }

    // ──────────────────────── 模拟开通 ────────────────────────

    /**
     * 模拟开通会员：更新服务端会员状态，不涉及真实支付。
     */
    public MembershipActivateVO activate(Long userId, String planCode) {
        MembershipLevel level = MembershipLevel.of(planCode);
        if (level == MembershipLevel.FREE) {
            throw new ValidationException("planCode", "仅支持开通普通会员或高级会员");
        }

        LocalDateTime now = LocalDateTime.now(ZONE).withNano(0);
        LocalDateTime expire = now.plusDays(level.getDurationDays());
        accountMapper.upsertMembership(userId, level.name(), now, expire);
        log.info("会员模拟开通: userId={}, level={}, expire={}", userId, level.name(), expire);

        MembershipActivateVO vo = new MembershipActivateVO();
        vo.setOrderNo("DEMO" + now.format(ORDER_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        vo.setPlanCode(level.name());
        vo.setPlanName(level.getDisplayName());
        vo.setAmount(level.getDemoPrice());
        vo.setDemo(true);
        vo.setPaid(false);
        vo.setCreatedAt(now.format(DATE_TIME_FMT));
        vo.setMembership(getMembership(userId));
        return vo;
    }

    // ──────────────────────── 额度预占 / 回滚 ────────────────────────

    /**
     * 原子预占一次 AI 匹配额度。
     *
     * @return 预占成功返回额度所属日期；额度已用尽返回 {@code null}
     */
    public LocalDate reserveAiQuota(Long userId) {
        LocalDate today = LocalDate.now(ZONE);
        int limit = getMembership(userId).getDailyLimit();

        // 记录已存在：仅当未达上限时原子自增
        if (usageMapper.incrementIfBelow(userId, today, limit) > 0) {
            return today;
        }
        // 记录不存在：首次插入并记 1 次（并发重复插入时返回 0）
        if (usageMapper.insertInitial(userId, today) > 0) {
            return today;
        }
        // 记录刚被并发创建：再尝试一次自增
        return usageMapper.incrementIfBelow(userId, today, limit) > 0 ? today : null;
    }

    /**
     * 回滚一次预占（AI 调用失败时调用，避免错误扣减）。
     */
    public void releaseAiQuota(Long userId, LocalDate usageDate) {
        if (usageDate == null) {
            return;
        }
        int affected = usageMapper.release(userId, usageDate);
        if (affected > 0) {
            log.info("AI 额度已回滚: userId={}, date={}", userId, usageDate);
        }
    }

    // ──────────────────────── 内部方法 ────────────────────────

    private int usedToday(Long userId, LocalDate date) {
        Integer used = usageMapper.selectUsed(userId, date);
        return used == null ? 0 : used;
    }

    private List<String> perksOf(MembershipLevel level) {
        List<String> perks = new ArrayList<>();
        for (PlanFeatureVO feature : featuresOf(level)) {
            perks.add(Boolean.TRUE.equals(feature.getPlanning())
                    ? feature.getText() + "（规划中）"
                    : feature.getText());
        }
        return perks;
    }

    private List<PlanFeatureVO> featuresOf(MembershipLevel level) {
        List<PlanFeatureVO> features = new ArrayList<>();
        switch (level) {
            case FREE -> {
                features.add(feature("基础求职工作台", false));
                features.add(feature("简历管理入口", false));
                features.add(feature("每日 " + level.getDailyLimit() + " 次 AI 岗位匹配", false));
                features.add(feature("基础岗位收藏与投递记录", false));
            }
            case PRO -> {
                features.add(feature("每日 " + level.getDailyLimit() + " 次 AI 岗位匹配", false));
                features.add(feature("AI 简历优化", true));
                features.add(feature("求职行动计划", true));
                features.add(feature("包含免费用户全部权益", false));
            }
            case PREMIUM -> {
                features.add(feature("每日 " + level.getDailyLimit() + " 次 AI 岗位匹配", false));
                features.add(feature("AI 面试模拟", true));
                features.add(feature("完整求职分析与规划", true));
                features.add(feature("包含普通会员全部权益", false));
            }
        }
        return features;
    }

    private PlanFeatureVO feature(String text, boolean planning) {
        PlanFeatureVO vo = new PlanFeatureVO();
        vo.setText(text);
        vo.setPlanning(planning);
        return vo;
    }
}
