# GitHub API 限流问题及替代方案

## 📊 GitHub API 限流情况

### REST API 限流规则
- **未认证请求**：每小时 60 次
- **认证请求（使用 Token）**：每小时 5,000 次
- **限流检测**：响应头 `X-RateLimit-Remaining` 显示剩余次数
- **限流重置**：响应头 `X-RateLimit-Reset` 显示重置时间（Unix 时间戳）

### GraphQL API 限流规则（基于点数）

#### 主要限流规则
- **未认证请求**：每小时 60 次（与 REST API 相同）
- **认证请求（使用 Token）**：
  - 个人用户/应用：5,000 点/小时
  - 组织拥有的应用：10,000 点/小时
- **每分钟点数限制**：2,000 点/分钟（仅限认证请求）

#### 点数计算规则
- **查询请求（Query）**：每个查询消耗 **1 点**
- **变更请求（Mutation）**：每个变更消耗 **5 点**
- **复杂度计算**：查询的复杂度会影响点数消耗（但基础查询通常为 1 点）

#### 次要限流规则
- **并发请求限制**：同时进行的请求不得超过 100 个
- **CPU 时间限制**：每 60 秒实际时间内，最大 CPU 时间为 90 秒（GraphQL API 为 60 秒）
- **内容创建限制**：
  - 每分钟不超过 80 个内容生成请求
  - 每小时不超过 500 个内容生成请求

#### 查询限制
- 必须在连接上提供 `first` 或 `last` 参数
- `first` 和 `last` 的值必须在 1 到 100 之间
- 单个调用请求的节点总数不能超过 500,000

#### 限流检测
GraphQL API 的限流信息在响应中返回：
```json
{
  "data": { ... },
  "extensions": {
    "rateLimit": {
      "limit": 5000,
      "remaining": 4998,
      "resetAt": "2024-12-07T15:00:00Z",
      "used": 2
    }
  }
}
```

### 当前使用场景（REST API）
- 获取 Issue 信息（获取 assignees）：每次请求 1 次
- 获取 Issue 评论列表：每次请求 1 次
- **总计**：每次获取公告列表需要 2 次 API 调用
- **缓存时间**：1 分钟（已实现）

### 限流风险分析

#### REST API
- **未认证**：60 次/小时 ÷ 2 次/请求 = 最多 30 次请求/小时
- **认证后**：5,000 次/小时 ÷ 2 次/请求 = 最多 2,500 次请求/小时
- **实际使用**：用户刷新 + 自动加载，可能触发限流

#### GraphQL API（如果迁移）
- **未认证**：60 次/小时（与 REST API 相同）
- **认证后**：
  - 查询消耗：1 个 GraphQL 查询 = 1 点（获取 Issue + Comments + Reactions）
  - 限流容量：5,000 点/小时 = 最多 5,000 次请求/小时
- **优势**：单次请求获取所有数据，请求次数减少 50%
- **实际使用**：认证后 5,000 次/小时足够使用

---

## 🔧 替代方案对比

### 方案 1：使用 GitHub Token 认证（推荐 ⭐⭐⭐⭐⭐）

**优点：**
- ✅ 实现简单，只需添加 Token
- ✅ 限流提升：60 → 5,000 次/小时（提升 83 倍）
- ✅ 无需额外服务
- ✅ 成本低（免费）

**缺点：**
- ❌ 需要用户提供 GitHub Token
- ❌ Token 需要存储（建议加密）

**实现方式：**
```kotlin
// 在拦截器中添加 Authorization 头
.header("Authorization", "token $githubToken")
```

**适用场景：** 推荐作为首选方案

---

### 方案 2：使用缓存机制（已实现 ⭐⭐⭐⭐）

**优点：**
- ✅ 已实现 1 分钟缓存
- ✅ 减少 API 调用次数
- ✅ 提升响应速度

**缺点：**
- ❌ 数据可能不是最新的
- ❌ 缓存时间需要平衡

**优化建议：**
- 可以延长缓存时间到 5-10 分钟（公告更新频率低）
- 实现多级缓存（内存 + Redis）

**适用场景：** 配合其他方案使用

---

### 方案 3：使用 GraphQL API（推荐 ⭐⭐⭐⭐）

**优点：**
- ✅ 单次请求获取所有数据（Issue + Comments + Reactions）
- ✅ 减少请求次数：2 次 → 1 次
- ✅ 可以精确控制返回字段
- ✅ 限流更宽松：5,000 点/小时（查询消耗 1 点/次）
- ✅ 认证后限流充足（5,000 次/小时）

**缺点：**
- ❌ 需要学习 GraphQL 语法
- ❌ 需要修改现有代码
- ❌ 需要处理 GraphQL 响应格式

**限流对比：**
- REST API（认证）：5,000 次/小时 ÷ 2 次/请求 = 2,500 次完整请求/小时
- GraphQL API（认证）：5,000 点/小时 ÷ 1 点/请求 = 5,000 次完整请求/小时
- **提升**：GraphQL 比 REST 多 100% 的请求容量

**实现方式：**
```graphql
query {
  repository(owner: "WrBug", name: "PolyHermes") {
    issue(number: 1) {
      assignees(first: 10) {
        nodes {
          login
        }
      }
      comments(first: 100) {
        nodes {
          id
          body
          createdAt
          updatedAt
          issue {
            id
          }
          author {
            login
            avatarUrl
          }
          reactions(first: 100) {
            totalCount
            nodes {
              content
            }
          }
        }
      }
    }
  }
}
```

**响应格式：**
```json
{
  "data": {
    "repository": {
      "issue": {
        "assignees": {
          "nodes": [
            { "login": "WrBug" }
          ]
        },
        "comments": {
          "nodes": [
            {
              "id": "123",
              "body": "...",
              "reactions": {
                "totalCount": 10,
                "nodes": [
                  { "content": "THUMBS_UP" },
                  { "content": "HEART" }
                ]
              }
            }
          ]
        }
      }
    }
  },
  "extensions": {
    "rateLimit": {
      "limit": 5000,
      "remaining": 4999,
      "resetAt": "2024-12-07T15:00:00Z"
    }
  }
}
```

**适用场景：** 适合需要优化请求次数和限流容量的场景

---

### 方案 4：自建代理服务 ⭐⭐⭐

**优点：**
- ✅ 可以添加额外缓存层（5-30 分钟）
- ✅ 可以聚合多个请求
- ✅ 可以添加限流保护
- ✅ 可以 Token 轮换（多个 Token 共享限流）
- ✅ 免费额度充足（Cloudflare Workers 100,000 次/天）

**缺点：**
- ❌ 需要额外部署服务
- ❌ 增加系统复杂度
- ❌ 需要维护

**实现方式：**
```
用户请求 → 自建代理（Cloudflare Workers/Vercel） → GitHub API
         ← 缓存响应（5-30分钟） ←
```

**可选平台：**
- **Cloudflare Workers**：免费 100,000 次/天
- **Vercel Edge Functions**：免费额度充足
- **Netlify Functions**：免费额度充足
- **自建 Node.js 服务**：完全控制

**适用场景：** 需要更高可用性和更长缓存时间的场景

---

### 方案 4.1：第三方 GitHub API 代理服务 ❌

**结论：没有可用的第三方服务**

**原因：**
1. ❌ **数据源限制**：公告数据在 GitHub Issue，无法迁移到其他平台
2. ❌ **认证问题**：GitHub API 需要 Token，第三方服务无法安全共享用户 Token
3. ❌ **服务缺失**：没有公开的、稳定的第三方 GitHub API 代理服务
4. ❌ **商业限制**：GitHub 不允许第三方服务代理其 API（违反 ToS）

**为什么不可行：**
- 数据在 GitHub，必须调用 GitHub API
- Token 是个人凭证，不能共享给第三方
- 没有公开的代理服务（违反 GitHub ToS）

**可行的替代思路：**
- ✅ **自建代理服务**（方案 4）：使用 Cloudflare Workers 等平台
- ✅ **使用 GitHub Token**（方案 1）：直接认证，限流提升 83 倍
- ✅ **使用 GraphQL API**（方案 3）：减少请求次数，提升限流容量

---

### 方案 5：使用 GitHub Webhook（不适用）❌

**说明：**
- Webhook 是事件驱动的，不适合主动获取数据
- 公告功能需要主动查询，不适合 Webhook

**适用场景：** 不适用于当前需求

---

### 方案 6：使用其他代码托管平台 API（不适用）❌

**说明：**
- GitLab、Bitbucket、Gitee 等不包含 GitHub 的 Issue 数据
- 公告数据在 GitHub，无法迁移到其他平台
- 这些平台的 API 无法访问 GitHub 的数据

**适用场景：** 不适用于当前需求（数据在 GitHub）

---

## 🎯 推荐方案组合

### 方案 A：Token + 缓存（推荐）⭐⭐⭐⭐⭐

**组合：**
1. 使用 GitHub Token 认证（提升限流到 5,000/小时）
2. 保持 1-5 分钟缓存
3. 添加限流检测和错误处理

**优点：**
- 实现简单
- 限流充足（5,000/小时足够使用）
- 响应快速（缓存）

**实现成本：** 低

---

### 方案 B：GraphQL + Token + 缓存 ⭐⭐⭐⭐⭐

**组合：**
1. 使用 GraphQL API（减少请求次数，提升限流容量）
2. 使用 GitHub Token 认证
3. 保持缓存机制

**优点：**
- 请求次数最少（1 次/请求）
- 限流容量最大（5,000 次/小时，比 REST 多 100%）
- 数据获取更高效（单次请求获取所有数据）
- 可以精确控制返回字段

**实现成本：** 中等（需要学习 GraphQL）

**限流对比：**
- REST API：2,500 次完整请求/小时
- GraphQL API：5,000 次完整请求/小时
- **提升**：100% 的请求容量提升

---

### 方案 C：自建代理服务 + 缓存 ⭐⭐⭐

**组合：**
1. 使用 Cloudflare Workers / Vercel Edge Functions 自建代理
2. 在代理层添加缓存（5-30 分钟）
3. 聚合请求（可选）
4. Token 轮换（可选，多个 Token 共享限流）

**优点：**
- 可以添加更长的缓存时间（5-30 分钟）
- 可以聚合多个请求
- 可以添加限流保护
- 可以 Token 轮换（多个 Token 共享限流容量）

**实现成本：** 中等（需要部署，但平台提供免费额度）

**实现示例（Cloudflare Workers）：**
```javascript
// cloudflare-worker.js
export default {
  async fetch(request) {
    const cacheKey = request.url;
    const cache = caches.default;
    
    // 检查缓存（5 分钟）
    let response = await cache.match(cacheKey);
    if (response) {
      return response;
    }
    
    // 转发到 GitHub API
    const githubResponse = await fetch(request, {
      headers: {
        'Authorization': `Bearer ${GITHUB_TOKEN}`,
        'Accept': 'application/vnd.github+json'
      }
    });
    
    // 缓存响应（5 分钟）
    response = new Response(githubResponse.body, githubResponse);
    response.headers.set('Cache-Control', 'public, max-age=300');
    await cache.put(cacheKey, response.clone());
    
    return response;
  }
}
```

---

## 📝 实现建议

### 短期方案（立即实施）
1. **添加 GitHub Token 支持**
   - 在配置文件中添加 `github.token` 配置项
   - 在拦截器中添加 Authorization 头
   - 限流从 60 → 5,000/小时

2. **优化缓存时间**
   - 将缓存时间从 1 分钟延长到 5-10 分钟
   - 公告更新频率低，5-10 分钟足够

3. **添加限流检测**
   - 检查响应头 `X-RateLimit-Remaining`
   - 当剩余次数 < 10 时，延长缓存时间
   - 当触发限流时，返回缓存数据

### 中期方案（可选）
1. **迁移到 GraphQL API**
   - 学习 GraphQL 语法
   - 重写 API 调用
   - 减少请求次数

2. **实现多级缓存**
   - 内存缓存（快速）
   - Redis 缓存（持久化）

### 长期方案（如需要）
1. **自建代理服务**
   - 使用 Cloudflare Workers / Vercel Edge Functions
   - 添加更长的缓存时间（5-30 分钟）
   - 聚合多个请求
   - Token 轮换（多个 Token 共享限流）

---

## 🔍 限流检测实现

### 响应头说明
- `X-RateLimit-Limit`: 总限制次数
- `X-RateLimit-Remaining`: 剩余次数
- `X-RateLimit-Used`: 已使用次数
- `X-RateLimit-Reset`: 重置时间（Unix 时间戳）

### 错误处理
当触发限流时，GitHub API 返回：
- HTTP 403 Forbidden
- 响应头 `X-RateLimit-Remaining: 0`
- 响应体包含限流信息

---

## 💡 总结

### 第三方 API 服务情况

**结论：没有可用的第三方 GitHub API 代理服务**

**原因：**
1. ❌ 数据源限制：公告数据在 GitHub，无法迁移
2. ❌ 认证问题：GitHub API 需要 Token，第三方无法安全共享
3. ❌ 服务缺失：没有公开的、稳定的第三方代理服务

**可行的替代方案：**
- ✅ **自建代理服务**：使用 Cloudflare Workers / Vercel 等平台
- ✅ **使用 GitHub Token**：直接认证，限流提升 83 倍
- ✅ **使用 GraphQL API**：减少请求次数，提升限流容量

---

### 限流对比表

| 方案 | API 类型 | 认证 | 请求次数 | 限流容量 | 完整请求数/小时 | 实现难度 |
|------|---------|------|---------|---------|----------------|---------|
| 当前 | REST | 否 | 2 次/请求 | 60 次/小时 | 30 次 | - |
| REST + Token | REST | 是 | 2 次/请求 | 5,000 次/小时 | 2,500 次 | ⭐ 简单 |
| GraphQL | GraphQL | 否 | 1 次/请求 | 60 次/小时 | 60 次 | ⭐⭐ 中等 |
| GraphQL + Token | GraphQL | 是 | 1 次/请求 | 5,000 点/小时 | 5,000 次 | ⭐⭐ 中等 |
| 自建代理 + Token | REST/GraphQL | 是 | 1-2 次/请求 | 5,000+ 次/小时 | 5,000+ 次 | ⭐⭐⭐ 中等 |

### 推荐方案

**最佳方案：** 方案 A（Token + 缓存）⭐⭐⭐⭐⭐
- 实现简单（只需添加 Token）
- 效果显著（限流提升 83 倍：60 → 5,000/小时）
- 成本低
- 适合当前需求
- **限流容量**：2,500 次完整请求/小时

**优化方案：** 方案 B（GraphQL + Token + 缓存）⭐⭐⭐⭐⭐
- 请求次数最少（1 次/请求）
- 限流容量最大（5,000 次/小时，比 REST 多 100%）
- 数据获取更高效（单次请求获取所有数据）
- 适合长期优化
- **限流容量**：5,000 次完整请求/小时

**备选方案：** 方案 C（代理服务）⭐⭐⭐
- 适合需要更高可用性的场景
- 需要额外部署和维护

### 建议实施顺序

1. **立即实施**：方案 A（Token + 缓存）
   - 快速解决限流问题
   - 实现成本低

2. **中期优化**：方案 B（GraphQL + Token + 缓存）
   - 进一步提升限流容量
   - 优化请求效率

