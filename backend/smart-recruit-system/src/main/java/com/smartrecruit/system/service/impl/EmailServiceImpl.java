package com.smartrecruit.system.service.impl;

import com.smartrecruit.common.constant.ConfigKeys;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import com.smartrecruit.system.service.EmailService;
import com.smartrecruit.system.service.SysConfigService;

/**
 * 邮件发送服务。
 *
 * <p>提供统一的邮件发送能力，核心方法为 {@link #sendEmail(String, String, String, boolean)}。
 * 其他业务方法（验证码、密码重置、测评邀请等）均基于此核心方法构建。
 * 对外通过 {@code POST /api/v1/internal/email/send} 暴露通用发送接口。</p>
 *
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String defaultFrom;

    @Value("${smart-recruit.system-name:SmartRecruit}")
    private String defaultSystemName;

    private final SysConfigService sysConfigService;

    // ================================================================
    // 核心通用接口 — 供内部服务调用
    // ================================================================

    /**
     * 发送邮件（统一的底层方法）。
     *
     * @param to      收件人邮箱
     * @param subject 邮件主题
     * @param content 邮件内容
     * @param html    内容是否为 HTML 格式
     */
    public void sendEmail(String to, String subject, String content, boolean html) {
        String fromAddress = resolveFrom();
        if (fromAddress == null || fromAddress.isBlank()) {
            log.warn("================================================================");
            log.warn("SMTP 未配置 (spring.mail.username 为空)，邮件内容输出到控制台：");
            log.warn("收件人: {}", to);
            log.warn("主题: {}", subject);
            log.warn("格式: {}", html ? "HTML" : "纯文本");
            log.warn("--------------------------------------------------------------");
            log.warn("内容 (已截断): {}", content.substring(0, Math.min(content.length(), 500)));
            log.warn("================================================================");
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, resolveSenderName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content, html);
            mailSender.send(message);
            log.info("邮件发送成功: to={}, subject={}", to, subject);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("邮件发送失败: to={}, subject={}", to, subject, e);
            throw new RuntimeException("邮件发送失败，请稍后重试", e);
        }
    }

    /**
     * 发送邮箱验证码（注册场景）。
     *
     * @param to   收件人邮箱
     * @param code 6 位验证码
     */
    public void sendVerificationCode(String to, String code) {
        String name = resolveSystemName();
        sendEmail(to, "【" + name + "】邮箱验证码", buildVerificationCodeContent(to, code, name), true);
    }

    /**
     * 发送密码重置验证码。
     *
     * @param to   收件人邮箱
     * @param code 6 位验证码
     */
    public void sendPasswordResetCode(String to, String code) {
        String name = resolveSystemName();
        sendEmail(to, "【" + name + "】密码重置验证码", buildPasswordResetContent(to, code, name), true);
    }

    /**
     * 构建专业的 HTML 邮件内容。
     */
    private String buildVerificationCodeContent(String to, String code, String systemName) {
        // 邮箱地址脱敏显示
        String maskedEmail = maskEmail(to);

        return """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(systemName, maskedEmail, systemName, code, systemName);
    }

    /**
     * 构建密码重置 HTML 邮件内容。
     */
    private String buildPasswordResetContent(String to, String code, String systemName) {
        String maskedEmail = maskEmail(to);

        return """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(systemName, maskedEmail, systemName, code, systemName);
    }

    /**
     * 发送在线测评邀请邮件。
     *
     * @param to            候选人邮箱
     * @param candidateName 候选人姓名
     * @param jobTitle      应聘职位
     * @param typeLabel     测评类型（如"编程测试"）
     * @param assessmentUrl 测评链接（含 token）
     */
    public void sendAssessmentEmail(String to, String candidateName, String jobTitle,
                                     String typeLabel, String assessmentUrl) {
        String name = resolveSystemName();
        String subject = "【" + name + "】在线测评邀请 — " + jobTitle;
        sendEmail(to, subject,
                buildAssessmentInviteContent(to, candidateName, jobTitle, typeLabel,
                        assessmentUrl, name), true);
    }

    /**
     * 构建在线测评邀请 HTML 邮件内容。
     */
    private String buildAssessmentInviteContent(String to, String candidateName,
                                                 String jobTitle, String typeLabel,
                                                 String assessmentUrl, String systemName) {
        String maskedEmail = maskEmail(to);
        String typeIcon = "";

        return """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(
                systemName,                          // 头部标题
                candidateName,                       // 候选人姓名
                jobTitle,                            // 职位名
                jobTitle,                            // 测评卡片-职位
                typeIcon, typeLabel,                 // 测评卡片-类型
                maskedEmail,                         // 测评卡片-邮箱
                assessmentUrl,                       // CTA 按钮链接
                assessmentUrl,                       // 备选链接
                systemName                           // 底部
        );
    }

    /**
     * 邮箱地址脱敏。
     *
     * <p>示例：admin@smartrecruit.com → a***n@smartrecruit.com</p>
     */
    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf('@');
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.charAt(0) + "***" + local.charAt(local.length() - 1) + domain;
    }

    /**
     * 解析系统名称：优先取系统配置，缺失时回退到本地配置。
     */
    private String resolveSystemName() {
        String configured = sysConfigService.getString(ConfigKeys.SYSTEM_NAME, "");
        return configured.isBlank() ? defaultSystemName : configured;
    }

    /**
     * 解析发件人地址。
     *
     * <p>绝大多数 SMTP 服务商（163、QQ、企业邮等）要求 MAIL FROM 必须等于
     * 登录账号，否则返回 {@code 553 Mail from must equal authorized user}。
     * 因此发件地址固定使用 {@code spring.mail.username}（SMTP 登录账号），
     * 展示名称仍可通过系统配置 {@code email_sender_name} 自定义。</p>
     */
    private String resolveFrom() {
        return defaultFrom;
    }

    /**
     * 解析发件人显示名称：优先取系统配置。
     */
    private String resolveSenderName() {
        String configured = sysConfigService.getString(ConfigKeys.EMAIL_SENDER_NAME, "");
        return configured.isBlank() ? resolveSystemName() : configured;
    }
}
