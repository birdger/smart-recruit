# SmartRecruit · 招聘管理端（Vue 3）

> SmartRecruit 企业级智能招聘平台的 HR 管理后台。**Vue 3.5 + TypeScript + Vite 6 + Element Plus + Pinia**，
> 覆盖工作台、职位、简历筛选、候选人、面试、题库、测评、Offer、合同、入职、人才库、数据分析、内推、官网配置、系统管理、AI 智能体监控共 18 个业务模块。

> ⚠️ 本仓库为技术展示用途。源码完整公开，但**不含任何后端地址、密钥与环境配置**，无法直接连上后端运行。

![Vue](https://img.shields.io/badge/Vue-3.5-42b883)
![TypeScript](https://img.shields.io/badge/TypeScript-5.5-3178c6)
![Vite](https://img.shields.io/badge/Vite-6.0-646cff)
![Element Plus](https://img.shields.io/badge/Element%20Plus-2.9-409eff)

---

## 一、功能模块

| 分类 | 页面 |
|---|---|
| 认证 | 登录（图形验证码）、注册、忘记密码 |
| 工作台 | 数据概览卡片、招聘漏斗、部门进度、最近动态、待办事项 |
| 招聘 | 职位管理、简历筛选、候选人中心 |
| 面试 | 面试日程（日/周视图）、AI 智能出题、面试题库、在线测评 |
| Offer | Offer 管理、合同管理（手写签名）、入职管理（六步流程） |
| 人才 | 人才库、数据分析（趋势/漏斗/AI 洞察） |
| 内推 | 内推计划、内推记录、排行榜 |
| 官网 | 官网配置、热门职位、社招、校招 |
| 系统 | 用户、角色、权限、部门、审计日志、系统设置 |
| AI | 智能体监控（任务队列 / 事件流 / Token 消耗 / 成功率） |

## 二、技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5 | 组合式 API + `<script setup>` |
| TypeScript | 5.5 | 全量类型定义（`src/types`） |
| Vite | 6.0 | 构建 / HMR / 代理 |
| Element Plus | 2.9 | UI 组件库 |
| Pinia | 2.2 | 状态管理（用户、权限、字典） |
| Vue Router | 4.5 | 路由 + 权限守卫 + 懒加载 |
| Axios | 1.7 | 请求封装：Token 注入、401 静默刷新 + 队列重放 |
| Chart.js | 4.4 | 图表封装 |
| mammoth | 1.12 | Word 简历 / 合同在线预览 |
| signature_pad | 5.1 | 合同手写签名画板 |

## 三、工程结构

```
src/
├── api/           # 按业务域拆分的接口封装（job / resume / interview / offer / talent / system / agent ...）
├── components/    # 通用组件（表格、分页、上传、图表、水印、签名板等）
├── composables/   # 组合式函数（分页、轮询、字典、权限判断）
├── constants/     # 枚举与常量（状态色、字典项）
├── directives/    # 自定义指令（如 v-permission 按钮级权限）
├── layouts/       # 主框架布局（侧边菜单 + 顶栏 + 标签页）
├── router/        # 路由表 + 守卫
├── stores/        # Pinia store
├── styles/        # 全局样式与主题变量
├── types/         # TS 类型定义
├── utils/         # 请求封装、格式化、校验
└── views/         # 18 个业务模块页面
```

## 四、关键实现

1. **Axios 拦截体系**：请求自动注入 `Authorization`；响应 401 时静默刷新 Token，刷新期间请求进队列，成功后批量重放；刷新失败清登录态并跳登录页。
2. **路由权限**：路由懒加载 + 守卫，未登录跳登录页、无权限拦截；菜单由后端权限点驱动渲染。
3. **按钮级权限**：`v-permission` 指令按权限点显隐，配合菜单/接口形成三级权限。
4. **AI 异步交互**：出题、筛选、简历解析、人才推荐均为"提交任务 → 轮询进度 → 展示结果"，配合骨架屏与进度条，避免页面假死。
5. **复合交互**：树形部门选择、动态表单（审批流节点）、手写签名板、Word 在线预览、水印组件。
6. **合规**：页面水印可配置（账号 + 姓名 + 时间），防止截图外泄。

## 五、本地运行

```bash
npm install
cp .env.example .env.development   # 填写后端网关地址
npm run dev                        # 默认 http://localhost:3000
npm run build                      # 产物 dist/
```

环境变量：

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 后端网关地址，如 `http://localhost:8080` |

> 后端源码见同账号下的 backend 仓库；数据库脚本与配置不随仓库提供，前端无法单独跑通完整链路。

## 六、公开范围

本仓库公开全部前端源码，但不包含：真实接口地址、后端配置、密钥、数据库脚本、AI 提示词。
用途仅限源码阅读与技术评审。

## License

**All Rights Reserved.** 仅授权个人学习与技术评审，禁止商用、二次分发与整体转售。详见 [LICENSE](./LICENSE)。
