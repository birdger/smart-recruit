package com.smartrecruit.offer.service.impl;

import com.smartrecruit.common.dto.ApiResponse;
import com.smartrecruit.common.constant.ConfigKeys;
import com.smartrecruit.offer.entity.Offer;
import com.smartrecruit.offer.entity.Contract;
import com.smartrecruit.offer.service.RemoteConfigService;
import com.smartrecruit.offer.feign.SystemClient;
import com.smartrecruit.offer.dto.remote.EmailRequest;
import com.smartrecruit.common.util.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import com.smartrecruit.offer.service.OfferMailService;

/**
 * Offer 邮件服务，负责构建并发送 Offer 录用通知书邮件。
 *
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OfferMailServiceImpl implements OfferMailService {

    private final SystemClient systemClient;
    private final RemoteConfigService remoteConfigService;

    @Value("${smart-recruit.frontend-url}")
    private String frontendUrl;

    /**
     * 发送 Offer 录用通知书给候选人。
     *
     * @param offer         Offer 实体
     * @param approvalFlow  审批流程描述（如"审批已通过"）
     */
    public void sendOfferLetter(Offer offer, String approvalFlow) {
        if (offer.getCandidateEmail() == null || offer.getCandidateEmail().isBlank()) {
            log.warn("候选人邮箱为空，无法发送 Offer 邮件: offerId={}, candidateName={}",
                    offer.getId(), offer.getCandidateName());
            return;
        }

        String confirmUrl = offer.getConfirmToken() != null
                ? frontendUrl + "/offer-confirm/" + offer.getConfirmToken()
                : null;
        String subject = "【" + systemName() + "】录用通知书 — " + offer.getPositionTitle();
        String html = buildOfferLetterHtml(offer, approvalFlow, confirmUrl);

        try {
            EmailRequest request =
                    new EmailRequest(offer.getCandidateEmail(), subject, html, true);

            ApiResponse<Void> response = systemClient.sendEmail(request);
            if (response.ok()) {
                log.info("Offer 邮件已发送: offerId={}, candidateEmail={}, offerNo={}",
                        offer.getId(), offer.getCandidateEmail(), offer.getOfferNo());
            } else {
                log.error("Offer 邮件发送失败: offerId={}, code={}, message={}",
                        offer.getId(), response.code(), response.message());
            }
        } catch (Exception e) {
            log.error("Offer 邮件发送异常: offerId={}, candidateEmail={}",
                    offer.getId(), offer.getCandidateEmail(), e);
        }
    }

    /**
     * 发送录用合同到候选人邮箱（附签署链接）。
     */
    @Override
    public void sendContractForSigning(Contract contract) {
        if (contract.getCandidateEmail() == null || contract.getCandidateEmail().isBlank()) {
            log.warn("候选人邮箱为空，无法发送合同邮件: contractNo={}, candidateName={}",
                    contract.getContractNo(), contract.getCandidateName());
            return;
        }
        if (contract.getSignToken() == null || contract.getSignToken().isBlank()) {
            log.warn("合同签署 Token 为空，无法发送签署邮件: contractNo={}", contract.getContractNo());
            return;
        }

        String signUrl = frontendUrl + "/contract-sign/" + contract.getSignToken();
        String subject = "【" + systemName() + "】录用合同待签署 — "
                + (contract.getJobTitle() != null ? contract.getJobTitle() : "录用合同");
        String html = buildContractSigningHtml(contract, signUrl);

        try {
            EmailRequest request =
                    new EmailRequest(contract.getCandidateEmail(), subject, html, true);
            ApiResponse<Void> response = systemClient.sendEmail(request);
            if (response.ok()) {
                log.info("合同邮件已发送: contractNo={}, candidateEmail={}",
                        contract.getContractNo(), contract.getCandidateEmail());
            } else {
                log.error("合同邮件发送失败: contractNo={}, code={}, message={}",
                        contract.getContractNo(), response.code(), response.message());
            }
        } catch (Exception e) {
            log.error("合同邮件发送异常: contractNo={}, candidateEmail={}",
                    contract.getContractNo(), contract.getCandidateEmail(), e);
        }
    }

    /** 构建合同签署通知 HTML 邮件。 */
    private String buildContractSigningHtml(Contract contract, String signUrl) {
        String totalPackage = contract.getTotalPackage() != null
                ? String.format("%,.0f", contract.getTotalPackage()) + " 元" : "面议";
        String validPeriod = (contract.getValidFrom() != null
                ? DateUtils.formatCnDate(contract.getValidFrom()) : "—")
                + " 至 " + (contract.getValidUntil() != null
                ? DateUtils.formatCnDate(contract.getValidUntil()) : "—");

        return """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(
                systemName(),
                contract.getCandidateName(),
                systemName(),
                contract.getContractNo(),
                contract.getJobTitle() != null ? contract.getJobTitle() : "—",
                contract.getDepartmentName() != null ? contract.getDepartmentName() : "—",
                validPeriod,
                totalPackage,
                contract.getOfferNo() != null ? contract.getOfferNo() : "—",
                signUrl,
                signUrl,
                systemName());
    }

    /**
     * 构建录用通知书 HTML 邮件。
     */
    private String buildOfferLetterHtml(Offer offer, String approvalFlow, String confirmUrl) {
        String salaryInfo = extractSalaryInfo(offer);
        String onboardDate = offer.getExpectedOnboardDate() != null
                ? DateUtils.formatCnDate(offer.getExpectedOnboardDate()) : "另行通知";
        String validDate = offer.getValidUntil() != null
                ? DateUtils.formatCnDate(offer.getValidUntil()) : "—";

        String actionSection = confirmUrl != null
                ? """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(escapeHtml(confirmUrl), escapeHtml(confirmUrl), validDate)
                : "";

        return """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(
                escapeHtml(systemName()),
                escapeHtml(offer.getCandidateName()),
                escapeHtml(systemName()),
                buildInfoRows(offer, onboardDate, validDate),
                approvalFlow,
                actionSection,
                escapeHtml(systemName())
        );
    }

    private String buildInfoRows(Offer offer, String onboardDate, String validDate) {
        StringBuilder sb = new StringBuilder();
        sb.append(infoRow("Offer 编号", offer.getOfferNo()));
        sb.append(infoRow("录用职位", offer.getPositionTitle()));
        sb.append(infoRow("所属部门", offer.getDepartmentName()));
        if (offer.getLevel() != null && !offer.getLevel().isBlank()) {
            sb.append(infoRow("录用职级", offer.getLevel()));
        }
        sb.append(infoRow("入职日期", onboardDate));
        sb.append(infoRow("Offer 有效期至", validDate));
        sb.append(infoRow("薪酬方案", extractSalaryInfo(offer)));
        return sb.toString();
    }

    private String infoRow(String label, String value) {
        return """
                <tr>
                    <td style="padding:8px 0;font-size:14px;color:#666666;width:120px;vertical-align:top;">%s</td>
                    <td style="padding:8px 0;font-size:14px;color:#222222;font-weight:500;">%s</td>
                </tr>
                """.formatted(label, value != null ? value : "—");
    }

    @SuppressWarnings("unchecked")
    private String extractSalaryInfo(Offer offer) {
        if (!(offer.getSalaryStructure() instanceof Map<?, ?> map)) {
            return "详见正式 Offer 文件";
        }
        StringBuilder sb = new StringBuilder();
        Object base = map.get("baseSalary");
        if (base instanceof Number) {
            sb.append("月薪 ¥").append(String.format("%,.0f", ((Number) base).doubleValue()));
        }
        Object bonus = map.get("bonusMonths");
        if (bonus instanceof Number) {
            if (!sb.isEmpty()) sb.append("，");
            sb.append("奖金 ").append(((Number) bonus).intValue()).append(" 个月");
        }
        Object stock = map.get("stockOptions");
        if (stock instanceof Number && ((Number) stock).doubleValue() > 0) {
            if (!sb.isEmpty()) sb.append("，");
            BigDecimal s = BigDecimal.valueOf(((Number) stock).doubleValue());
            sb.append("期权 ").append(s.toPlainString()).append(" 股");
        }
        Object total = map.get("total");
        if (total instanceof Number) {
            double t = ((Number) total).doubleValue();
            sb.append("（年薪总包 ¥").append(String.format("%,.0f", t / 10000)).append(" 万）");
        }
        return sb.isEmpty() ? "详见正式 Offer 文件" : sb.toString();
    }

    // ---- 审批流程通知邮件 ----

    /**
     * 发送审批请求邮件给审批人。
     */
    public void sendApprovalRequestEmail(Offer offer, String approverEmail, String approverName,
                                         String nodeName, int level, int totalLevels) {
        String offerDetailUrl = frontendUrl + "/offers/" + offer.getId() + "/approval-review";
        String subject = "【" + systemName() + "】待审批 Offer — " + offer.getCandidateName()
                + "(" + offer.getDepartmentName() + ") " + offer.getOfferNo() + "（第" + level + "级审批）";
        String html = buildNotificationHtml(
                "审批请求",
                offer,
                """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(escapeHtml(approverName), level, totalLevels, escapeHtml(nodeName)),
                """
                <a href="%s" style="display:inline-block;padding:12px 32px;background-color:#1a73e8;color:#ffffff;
                text-decoration:none;border-radius:6px;font-size:15px;font-weight:600;margin-top:20px;">前往审批</a>
                """.formatted(escapeHtml(offerDetailUrl))
        );
        sendNotificationEmail(approverEmail, subject, html);
    }

    /**
     * 发送审批进度通知给 Offer 创建人（某一级通过，但非最后一级）。
     */
    public void sendApprovalProgressToCreator(Offer offer, String creatorEmail,
                                              String approverName, String nodeName, int level, int totalLevels) {
        String subject = "【" + systemName() + "】Offer 审批进度 — " + offer.getCandidateName()
                + "(" + offer.getDepartmentName() + ") " + offer.getOfferNo() + "（第" + level + "级已通过）";
        String html = buildNotificationHtml(
                "审批进度通知",
                offer,
                """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(escapeHtml(approverName), level, totalLevels, escapeHtml(nodeName)),
                ""
        );
        sendNotificationEmail(creatorEmail, subject, html);
    }

    /**
     * 发送全部审批完成通知给 Offer 创建人。
     */
    public void sendApprovalCompleteToCreator(Offer offer, String creatorEmail) {
        String subject = "【" + systemName() + "】Offer 审批全部通过 — " + offer.getCandidateName()
                + "(" + offer.getDepartmentName() + ") " + offer.getOfferNo();
        String html = buildNotificationHtml(
                "审批完成",
                offer,
                """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """,
                ""
        );
        sendNotificationEmail(creatorEmail, subject, html);
    }

    /**
     * 发送审批驳回通知给 Offer 创建人。
     */
    public void sendOfferRejectedToCreator(Offer offer, String creatorEmail,
                                           String approverName, String role, String comment) {
        String subject = "【" + systemName() + "】Offer 审批被驳回 — " + offer.getCandidateName()
                + "(" + offer.getDepartmentName() + ") " + offer.getOfferNo();
        String commentHtml = (comment != null && !comment.isBlank())
                ? """
                <p style="margin:8px 0 0;font-size:13px;color:#666;line-height:1.6;">
                    <strong>审批意见：</strong>%s
                </p>
                """.formatted(escapeHtml(comment))
                : "";
        String html = buildNotificationHtml(
                "审批驳回",
                offer,
                """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(escapeHtml(approverName), escapeHtml(role != null ? role : "审批人"), commentHtml),
                ""
        );
        sendNotificationEmail(creatorEmail, subject, html);
    }

    // ---- 通知邮件私有辅助 ----

    /**
     * 构建通知邮件的通用 HTML 骨架。
     */
    private String buildNotificationHtml(String title, Offer offer, String bodyHtml, String actionButton) {
        return """
            【提示词正文已脱敏】
            此处原为完整的业务提示词（评估维度、评分规则、输出 JSON Schema 约束等）。
            公开版本仅保留工程结构，提示词内容不对外提供。
            """.formatted(
                escapeHtml(title),
                buildCompactOfferInfoHtml(offer),
                bodyHtml,
                actionButton,
                escapeHtml(systemName())
        );
    }

    /**
     * 构建紧凑的 Offer 摘要信息（用于通知邮件）。
     */
    private String buildCompactOfferInfoHtml(Offer offer) {
        return """
                <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f8fafc;border:1px solid #e8edf2;border-radius:8px;">
                    <tr>
                        <td style="padding:14px 20px;">
                            <table width="100%%" cellpadding="0" cellspacing="0">
                                %s
                                %s
                                %s
                                %s
                                %s
                            </table>
                        </td>
                    </tr>
                </table>
                """.formatted(
                compactInfoRow("Offer 编号", offer.getOfferNo()),
                compactInfoRow("候选人", offer.getCandidateName()),
                compactInfoRow("录用职位", offer.getPositionTitle()),
                compactInfoRow("所属部门", offer.getDepartmentName()),
                compactInfoRow("薪酬方案", extractSalaryInfo(offer))
        );
    }

    private String compactInfoRow(String label, String value) {
        return """
                <tr>
                    <td style="padding:4px 0;font-size:13px;color:#888;width:80px;white-space:nowrap;">%s</td>
                    <td style="padding:4px 0;font-size:13px;color:#333;font-weight:500;">%s</td>
                </tr>
                """.formatted(label, value != null ? value : "—");
    }

    /**
     * 统一发送通知邮件（best-effort，失败不抛异常）。
     */
    private void sendNotificationEmail(String to, String subject, String html) {
        if (to == null || to.isBlank()) {
            log.warn("收件人邮箱为空，跳过邮件发送: subject={}", subject);
            return;
        }
        try {
            EmailRequest request = new EmailRequest(to, subject, html, true);

            ApiResponse<Void> response = systemClient.sendEmail(request);
            if (response.ok()) {
                log.info("通知邮件已发送: to={}, subject={}", to, subject);
            } else {
                log.error("通知邮件发送失败: to={}, code={}, message={}", to, response.code(), response.message());
            }
        } catch (Exception e) {
            log.warn("通知邮件发送异常: to={}, subject={}", to, subject, e);
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * 系统名称：优先读取系统配置，缺失时回退默认值。
     */
    private String systemName() {
        return remoteConfigService.getString(ConfigKeys.SYSTEM_NAME, "SmartRecruit");
    }
}
