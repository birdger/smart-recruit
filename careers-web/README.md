# SmartRecruit · 招聘官网 / 候选人门户（Vue 3）

> SmartRecruit 智能招聘平台面向**候选人**的公开端：职位浏览与投递、校园/社会招聘专区、内推入口、
> 在线测评作答、Offer 查看与**合同电子签署**。**Vue 3.5 + TypeScript + Vite 6**，与 HR 管理端共用一套后端网关。

> ⚠️ 本仓库为技术展示用途，不含真实后端地址与配置。

---

## 一、功能模块

| 模块 | 说明 |
|---|---|
| 首页 | Hero 区、统计数据、文化价值观、热门职位（内容由后台「官网配置」驱动） |
| 职位列表 / 详情 | 关键词与条件筛选、分页、职位职责与要求、一键投递 |
| 社会招聘 / 校园招聘 | 按招聘类型分区的独立列表页 |
| 投递 | 简历上传（PDF/Word）、表单校验、投递状态查询 |
| 内推入口 | 通过内推令牌链接进入，自动关联推荐人 |
| 在线测评 | 免登录（令牌鉴权）作答、倒计时、提交自动评分 |
| 合同签署 | 免登录签署页 + 手写签名画板，签署后合同生效 |
| 个人中心 | 我的投递、我的 Offer、资料完善 |

## 二、技术栈

Vue 3.5 · TypeScript 5.5 · Vite 6 · Vue Router 4 · Pinia 2 · Axios 1.7 · signature_pad 5.1

## 三、工程结构

```
src/
├── api/          # 公开接口（careers / auth / config）
├── components/   # 职位卡片、筛选栏、上传、签名板等
├── router/       # 公开路由（首页、职位、投递、签署、测评）
├── stores/       # 站点配置、用户态
├── styles/       # 主题与响应式样式
├── types/        # 类型定义
├── utils/        # 请求封装与工具
└── views/        # Home / JobList / JobDetail / Social / Campus / Referral / Auth / 合同签署 ...
```

## 四、关键实现

1. **免登录公开页**：测评与合同签署使用**令牌鉴权**（URL 携带一次性 token），无需注册即可完成，后台可追溯。
2. **移动端优先**：响应式布局，候选人基本在手机上看职位，卡片式列表 + 吸底投递按钮。
3. **官网内容可配置**：Hero、统计、价值观、导航、页脚均由后台配置下发，前端零改版运营。
4. **电子签署**：`signature_pad` 手写画板 → 导出图片加密上传 → 双签（候选人 / HR）→ 状态机流转到"生效中"。
5. **请求封装**：统一错误提示、加载态、上传进度。

## 五、本地运行

```bash
npm install
cp .env.example .env.development   # 填写后端网关地址
npm run dev                        # 默认 http://localhost:3001
npm run build
```

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 后端网关地址，如 `http://localhost:8080` |

## License

**All Rights Reserved.** 仅授权个人学习与技术评审，禁止商用、二次分发与整体转售。详见 [LICENSE](./LICENSE)。
