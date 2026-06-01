# Polymarket 跟单系统需求分析

## 1. 系统概述

### 1.1 系统目标
实现一个 Polymarket 平台的跟单系统，允许用户自动复制指定交易者（Leader）的交易行为，实现自动化跟单交易。

### 1.2 核心功能
- **被跟单者管理**：添加、删除、查看被跟单者（Leader）
- **订单同步**：实时监控 Leader 的交易，自动复制其订单
- **跟单配置**：设置跟单比例、风险控制参数
- **订单执行**：自动创建和取消订单
- **跟单记录**：记录所有跟单操作和统计信息

### 1.3 前端要求
- **移动端适配**：前端网页必须适配移动端
  - 支持响应式设计（Responsive Design）
  - 移动端优先（Mobile First）设计原则
  - 支持触摸操作和手势
  - 断点设置：移动端 < 768px，平板 768px-1024px，桌面端 > 1024px
  - 使用响应式 UI 组件库（如 Ant Design Mobile）
  - 优化移动端性能和用户体验

## 2. 功能需求

### 2.1 账户管理（自己/跟单者）

#### 2.1.1 账户信息管理
- **功能**：管理自己的账户信息（支持多账户）
- **账户信息**：
  - `privateKey`: 私钥（必需，加密存储）
  - `walletAddress`: 钱包地址（从私钥推导，用于识别身份）
  - `apiKey`: Polymarket API Key（可选，如果 API 需要签名则从私钥生成）
  - `accountName`: 账户名称（可选，用于显示）
  - `isDefault`: 是否默认账户（用于跟单时自动选择）
- **业务规则**：
  - **支持多账户管理**（不再是单账户模式）
  - 私钥必须加密存储，不能明文保存
  - 钱包地址从私钥推导（前端使用 ethers.js 或 web3.js）
  - API Key 可以单独配置，或通过私钥签名生成
  - 钱包地址格式验证（0x 开头的 42 位地址）
  - 每个账户可以独立配置跟单参数

#### 2.1.2 通过私钥导入账户
- **功能**：通过私钥导入新账户
- **前端处理流程**：
  1. 用户输入私钥（支持助记词、私钥字符串、Keystore 文件）
  2. 前端使用 ethers.js 或 web3.js 从私钥推导地址
  3. 前端显示推导出的地址供用户确认
  4. 前端将私钥和地址发送到后端（私钥加密传输）
- **输入参数**：
  - `privateKey`: 私钥（必需，前端加密后传输）
  - `accountName`: 账户名称（可选）
  - `apiKey`: API Key（可选，如果 Polymarket API 需要）
  - `isDefault`: 是否设为默认账户（可选）
- **业务规则**：
  - 验证私钥格式（64 位十六进制字符串，可选 0x 前缀）
  - 后端验证私钥和地址的对应关系
  - 检查地址是否已存在（避免重复导入）
  - 私钥加密存储（使用 AES 加密）
  - 如果 API Key 未提供，尝试从私钥生成（如果 API 支持签名）

#### 2.1.3 更新账户信息
- **功能**：更新账户信息（不能修改私钥和地址）
- **输入参数**：
  - `accountId`: 账户ID（必需）
  - `accountName`: 账户名称（可选）
  - `apiKey`: API Key（可选）
  - `isDefault`: 是否设为默认账户（可选）
- **业务规则**：
  - 不能修改私钥和地址（如需修改，删除后重新导入）
  - 更新 API Key 时，需要验证有效性
  - 设置默认账户时，自动取消其他账户的默认状态

#### 2.1.4 删除账户
- **功能**：删除账户
- **输入参数**：
  - `accountId`: 账户ID（必需）
- **业务规则**：
  - 删除前检查是否有活跃订单
  - 如果有活跃订单，提示用户先取消订单
  - 删除账户相关的跟单配置
  - 保留历史订单记录（用于统计）

#### 2.1.5 查询账户列表
- **功能**：获取所有账户列表
- **返回数据**：
  - 账户列表（每个账户包含）：
    - 账户ID
    - 钱包地址（脱敏显示，只显示前6位和后4位）
    - 账户名称
    - 是否默认账户
    - API Key 状态（是否已配置）
    - 账户余额（通过 API 查询）
    - 账户统计信息（总订单数、总盈亏等）

#### 2.1.6 查询账户详情
- **功能**：获取指定账户的详细信息
- **输入参数**：
  - `accountId`: 账户ID（可选，不提供则返回默认账户）
- **返回数据**：
  - 钱包地址（脱敏显示）
  - 账户名称
  - 是否默认账户
  - API Key 状态（是否已配置，不返回实际 Key）
  - 账户余额（通过 API 查询）
  - 账户统计信息（总订单数、总盈亏等）

#### 2.1.7 账户余额查询
- **功能**：查询指定账户的余额和持仓
- **输入参数**：
  - `accountId`: 账户ID（可选，不提供则查询默认账户）
- **实现方式**：
  - 使用账户的 API Key 调用 Polymarket API 查询余额
  - 或通过订单记录计算持仓
- **返回数据**：
  - USDC 余额
  - 持仓列表（市场、方向、数量、价值）

#### 2.1.8 设置默认账户
- **功能**：设置默认账户（用于跟单时自动选择）
- **输入参数**：
  - `accountId`: 账户ID（必需）
- **业务规则**：
  - 设置新默认账户时，自动取消其他账户的默认状态
  - 至少需要有一个默认账户

#### 2.1.9 账户切换
- **功能**：在多个账户间切换（前端功能）
- **实现方式**：
  - 前端维护当前选中的账户ID
  - 所有操作基于当前选中的账户
  - 可以临时切换账户，不影响默认账户设置

#### 2.1.10 身份识别机制
- **功能**：系统如何区分"自己"和"Leader"
- **实现方式**：
  - 系统存储多个自己的账户（通过私钥导入）
  - 创建订单时，使用指定账户的 API Key 或私钥签名
  - 查询订单时，过滤掉 Leader 的订单
  - 跟单订单记录中标记为"自己的订单"和使用的账户ID
- **业务规则**：
  - Leader 地址不能与任何自己的账户地址相同
  - 添加 Leader 时，验证地址不是自己的任何账户地址
  - 跟单时可以指定使用哪个账户（默认使用默认账户）

### 2.2 被跟单者（Leader）管理

#### 2.2.1 添加被跟单者
- **功能**：添加新的被跟单者
- **输入参数**：
  - `leaderAddress`: 被跟单者的钱包地址（必需）
  - `leaderName`: 被跟单者名称（可选，用于显示）
  - `category`: 分类筛选（sports/crypto，可选，仅跟单该分类的交易）
- **业务规则**：
  - 验证地址格式
  - 检查是否已存在
  - 支持分类筛选
  - 验证 Leader 地址不能与自己的地址相同
  - **注意**：Leader 的启用状态由跟单关系管理，不再在 Leader 层面设置

#### 2.2.2 删除被跟单者
- **功能**：删除被跟单者，停止跟单
- **输入参数**：
  - `leaderId`: 被跟单者ID
- **业务规则**：
  - 删除前取消所有相关的跟单订单
  - 保留历史跟单记录

#### 2.2.3 更新被跟单者
- **功能**：更新被跟单者信息
- **输入参数**：
  - `leaderId`: 被跟单者ID
  - `leaderName`: 名称（可选）
  - `category`: 分类筛选（可选）
- **业务规则**：
  - 不能修改地址
  - 启用状态由跟单关系管理，不在此处设置

#### 2.2.4 查询被跟单者列表
- **功能**：获取所有被跟单者列表
- **输入参数**：
  - `category`: 分类筛选（可选）
- **返回数据**：
  - 被跟单者列表
  - 每个 Leader 的统计信息（跟单订单数、盈亏等）
  - 每个 Leader 的跟单关系数量

### 2.3 跟单模板管理

#### 2.3.1 模板概念
- **功能**：创建和管理跟单模板，模板包含跟单参数配置
- **模板作用**：模板可以被多个钱包复用，实现配置的统一管理
- **模板与钱包关系**：多对多关系，一个钱包可以绑定多个模板，一个模板可以被多个钱包使用

#### 2.3.2 创建模板
- **功能**：创建新的跟单模板
- **输入参数**：
  - `templateName`: 模板名称（必需，用于标识模板）
    - 说明：模板的唯一标识名称，用于区分不同的跟单配置模板
    - 限制：模板名称必须唯一，不能与其他模板重名
  - `copyMode`: 跟单金额模式（"RATIO" 或 "FIXED"，默认 "RATIO"）
    - 说明：选择跟单金额的计算方式
    - `RATIO`: 比例模式，跟单金额 = Leader 订单金额 × (copyRatio / 100)
      - 适用场景：希望跟单金额随 Leader 订单大小按比例变化
      - 例如：Leader 买入 100 USDC，跟单比例 50%，则跟单金额为 50 USDC
    - `FIXED`: 固定金额模式，跟单金额 = fixedAmount（固定值）
      - 适用场景：希望无论 Leader 订单大小如何，跟单金额都固定不变
      - 例如：无论 Leader 买入多少，跟单金额始终为 10 USDC
  - `copyRatio`: 跟单比例（可选，百分比格式，10%-1000%，默认 100%，仅在 copyMode="RATIO" 时生效）
    - 说明：跟单比例表示跟单金额相对于 Leader 订单金额的百分比
    - 例如：100% 表示跟单金额 = Leader 订单金额 × 1.0（1:1 跟单）
    - 例如：50% 表示跟单金额 = Leader 订单金额 × 0.5（半仓跟单）
    - 例如：200% 表示跟单金额 = Leader 订单金额 × 2.0（双倍跟单）
    - 后端存储：百分比值除以 100 后存储为小数（如 100% 存储为 1.0）
  - `fixedAmount`: 固定跟单金额（可选，USDC，仅在 copyMode="FIXED" 时生效，必须 >= 1）
    - 说明：固定金额模式下，每次跟单的固定金额，不随 Leader 订单大小变化
    - 限制：必须 >= 1 USDC
    - 例如：设置为 10，则无论 Leader 买入多少，跟单金额始终为 10 USDC
  - `maxOrderSize`: 单笔订单最大金额（可选，USDC，仅在 copyMode="RATIO" 时生效）
    - 说明：比例模式下，限制单笔跟单订单的最大金额上限
    - 作用：防止跟单金额过大，控制风险
    - 例如：设置为 1000，即使计算出的跟单金额超过 1000，也会限制为 1000 USDC
  - `minOrderSize`: 单笔订单最小金额（可选，USDC，仅在 copyMode="RATIO" 时生效，必须 >= 1）
    - 说明：比例模式下，限制单笔跟单订单的最小金额下限
    - 作用：过滤掉金额过小的订单，避免频繁小额交易
    - 限制：如果填写，必须 >= 1 USDC
    - 例如：设置为 10，如果计算出的跟单金额小于 10，则跳过该订单
  - `maxDailyOrders`: 每日最大跟单订单数（可选，默认 100）
    - 说明：限制每日最多跟单的订单数量，用于风险控制
    - 作用：防止过度交易，控制每日交易频率
    - 例如：设置为 50，当日跟单订单数达到 50 后，停止跟单，次日重置
  - `priceTolerance`: 价格容忍度（可选，百分比，0-100，默认 5%）
    - 说明：允许跟单价格在 Leader 价格基础上的调整范围
    - 作用：在 Leader 价格 ± 容忍度范围内调整价格，提高成交率
    - 例如：设置为 5%，Leader 价格为 0.5，则跟单价格可在 0.475-0.525 范围内
  - `supportSell`: 跟单卖出（可选，默认 true）
    - 说明：是否跟单 Leader 的卖出订单
    - true: 跟单 Leader 的买入和卖出订单
    - false: 只跟单 Leader 的买入订单，忽略卖出订单
- **业务规则**：
  - 模板名称必须唯一
  - 模板创建后可以被多个钱包使用
  - 修改模板会影响所有使用该模板的跟单
  - **金额校验**（前后端都需要校验，校验失败时不允许创建/更新模板）：
    - `minOrderSize` 必须 >= 1（仅在 copyMode="RATIO" 时生效，如果填写了该字段）
      - 前端校验：表单提交前检查，如果填写了 `minOrderSize` 且值 < 1，显示错误提示并阻止提交
      - 后端校验：接收请求时检查，如果 `minOrderSize` < 1，返回错误响应，不允许创建模板
    - `fixedAmount` 必须 >= 1（仅在 copyMode="FIXED" 时生效，必填）
      - 前端校验：表单提交前检查，如果 `fixedAmount` < 1，显示错误提示并阻止提交
      - 后端校验：接收请求时检查，如果 `fixedAmount` < 1，返回错误响应，不允许创建模板
  - **固定金额模式限制**：
    - 固定金额模式下，不应用 `maxOrderSize` 和 `minOrderSize` 限制
    - 固定金额模式下，跟单金额始终等于 `fixedAmount`

#### 2.3.3 更新模板
- **功能**：修改现有模板的配置
- **输入参数**：
  - `templateId`: 模板ID（必需）
  - `templateName`: 模板名称（可选，用于修改模板名称）
  - 其他参数同创建模板（可选，只更新提供的字段）
- **业务规则**：
  - 可以修改模板名称，但新名称必须唯一，不能与其他模板重名
  - 修改模板会影响所有使用该模板的跟单
  - 建议在修改前提示用户影响范围
  - **金额校验**（前后端都需要校验）：
    - `minOrderSize` 必须 >= 1（仅在 copyMode="RATIO" 时生效）
    - `fixedAmount` 必须 >= 1（仅在 copyMode="FIXED" 时生效）
  - **固定金额模式限制**：
    - 固定金额模式下，不应用 `maxOrderSize` 和 `minOrderSize` 限制

#### 2.3.4 删除模板
- **功能**：删除模板
- **输入参数**：
  - `templateId`: 模板ID（必需）
- **业务规则**：
  - 删除前检查是否有跟单正在使用该模板
  - 如果有跟单使用，提示用户先解除绑定或删除跟单
  - 删除模板不会影响历史跟单记录

#### 2.3.5 复制模板
- **功能**：基于现有模板创建新模板
- **输入参数**：
  - `templateId`: 源模板ID（必需）
  - `templateName`: 新模板名称（必需）
  - 其他参数可选（覆盖源模板的配置）
- **业务规则**：
  - 新模板名称必须唯一
  - 复制后可以独立修改，不影响源模板

#### 2.3.6 查询模板列表
- **功能**：获取所有模板列表
- **返回数据**：
  - 模板列表（每个模板包含）：
    - 模板ID
    - 模板名称
    - 所有配置参数
    - 使用该模板的跟单数量
    - 创建时间
    - 更新时间

#### 2.3.7 查询模板详情
- **功能**：获取指定模板的详细信息
- **输入参数**：
  - `templateId`: 模板ID（必需）
- **返回数据**：
  - 模板的所有配置参数
  - 使用该模板的跟单列表

### 2.4 跟单配置管理（钱包-模板关联）

#### 2.4.1 创建跟单
- **功能**：为钱包绑定模板，创建跟单关系
- **输入参数**：
  - `accountId`: 钱包账户ID（必需）
  - `templateId`: 模板ID（必需）
  - `leaderId`: Leader ID（必需，指定跟单哪个 Leader）
  - `enabled`: 是否启用（默认 true）
- **业务规则**：
  - 一个钱包可以绑定多个模板（多对多关系）
  - 一个模板可以被多个钱包使用
  - 每个跟单关系对应一个 Leader
  - 创建后默认启用状态

#### 2.4.2 查询跟单列表
- **功能**：查询所有跟单关系
- **筛选条件**：
  - `accountId`: 按钱包筛选（可选）
  - `templateId`: 按模板筛选（可选）
  - `leaderId`: 按 Leader 筛选（可选）
  - `enabled`: 按启用状态筛选（可选）
- **返回数据**：
  - 跟单列表（每个跟单包含）：
    - 跟单ID
    - 钱包信息（账户ID、钱包地址、账户名称）
    - 模板信息（模板ID、模板名称）
    - Leader 信息（Leader ID、Leader 地址、Leader 名称）
    - 启用状态
    - 创建时间
    - 更新时间

#### 2.4.3 更新跟单状态
- **功能**：开启或停止跟单
- **输入参数**：
  - `copyTradingId`: 跟单ID（必需）
  - `enabled`: 启用状态（true/false）
- **业务规则**：
  - 停止跟单后，不再监控该 Leader 的交易
  - 已创建的跟单订单不受影响
  - 可以随时开启或停止

#### 2.4.4 删除跟单
- **功能**：删除跟单关系
- **输入参数**：
  - `copyTradingId`: 跟单ID（必需）
- **业务规则**：
  - 删除前自动停止跟单
  - 删除后不再监控该 Leader 的交易
  - 保留历史跟单记录

#### 2.4.5 查询钱包绑定的模板
- **功能**：查询指定钱包绑定的所有模板
- **输入参数**：
  - `accountId`: 钱包账户ID（必需）
- **返回数据**：
  - 模板列表（包含模板信息和对应的跟单状态）

### 2.5 订单同步与执行

#### 2.5.1 监控 Leader 交易
- **功能**：实时监控 Leader 的交易活动
- **实现方式**（优先级从高到低）：
  - **方式1（优先）**：使用 WebSocket 推送（RTDS API）
    - 如果 Polymarket RTDS API 支持订阅指定用户的交易，优先使用 WebSocket
    - WebSocket URL: `wss://ws-live-data.polymarket.com`
    - 订阅用户交易频道，实时接收交易推送
    - 优点：实时性强、延迟低、资源消耗少
  - **方式2（备选）**：定期轮询 CLOB API
    - 当 WebSocket 不可用或不支持时，使用轮询方式
    - 调用 CLOB API `/trades?user={leaderAddress}` 获取最新交易
    - 使用系统默认的轮询间隔配置
- **实现策略**：
  - 系统启动时尝试连接 WebSocket
  - 如果 WebSocket 连接成功且支持订阅用户交易，使用推送模式
  - 如果 WebSocket 不可用或不支持，自动降级到轮询模式
  - 支持运行时切换（WebSocket 断开时自动切换到轮询）
  - 使用系统默认的 WebSocket 重连和轮询配置
- **业务规则**：
  - 只监控已启用的 Leader
  - 根据分类筛选（如果设置了 category）
  - 去重处理（避免重复跟单同一笔交易）
  - WebSocket 模式下，每个 Leader 需要单独订阅
  - 轮询模式下，批量查询多个 Leader 的交易

#### 2.5.2 订单复制逻辑
- **触发条件**：
  - Leader 创建新订单（通过交易记录判断）
  - Leader 取消订单（需要监控订单状态变化）
- **复制流程**：
  1. 检测到 Leader 的新交易
  2. 查找所有启用状态的跟单关系（该 Leader 对应的跟单）
  3. 对每个跟单关系：
     a. 验证跟单状态（是否启用）
     b. 获取模板配置
     c. 验证风险控制（每日亏损、订单数限制等）
     d. 确定使用的账户（跟单关系中的账户）
     e. 计算跟单订单参数：
     - `market`: 与 Leader 相同
        - `side`: 与 Leader 相同（BUY/SELL），如果模板不支持卖出且 Leader 是卖出，则跳过
     - `price`: 根据价格容忍度调整（可选）
        - `size`: 根据模板配置计算（比例或固定金额）
     f. 使用账户的 API Key 或私钥签名创建订单
     g. 记录跟单记录（包含使用的账户ID、模板ID）

#### 2.5.3 价格调整策略
- **固定价格**：完全复制 Leader 的价格
- **市场价**：使用当前市场最优价格
- **价格容忍度**：在 Leader 价格 ± 容忍度范围内调整
- **默认策略**：固定价格（完全复制）

#### 2.5.4 订单大小计算
- **计算模式**（基于模板配置）：
  - **比例模式（copyMode = "RATIO"）**：
    ```
    跟单订单大小 = Leader 订单大小 × (copyRatio / 100)
    ```
    - 说明：`copyRatio` 为百分比值（如 100 表示 100%），需要除以 100 转换为小数进行计算
  - **固定金额模式（copyMode = "FIXED"）**：
    ```
    跟单订单大小 = fixedAmount（固定值，不随 Leader 订单大小变化）
    ```
- **限制检查**（仅在比例模式下生效）：
  - 比例模式下，不能超过模板配置的 `maxOrderSize`
  - 比例模式下，不能低于模板配置的 `minOrderSize`
  - 如果超出限制，调整到边界值
- **业务规则**：
  - 使用跟单关系绑定的模板配置
  - **固定金额模式**：无论 Leader 订单大小如何，跟单金额都固定为 `fixedAmount`，不应用 `maxOrderSize` 和 `minOrderSize` 限制
  - **比例模式**：跟单金额随 Leader 订单大小按比例变化，需要检查 `maxOrderSize` 和 `minOrderSize` 限制

#### 2.5.5 订单取消同步
- **功能**：当 Leader 取消订单时，同步取消对应的跟单订单
- **实现方式**：
  - 监控 Leader 的活跃订单列表
  - 检测到订单消失或状态变为 cancelled
  - 查找对应的跟单订单并取消

### 2.6 风险控制

#### 2.6.1 每日订单数限制
- **功能**：限制每日跟单订单数量
- **规则**：
  - 当日跟单订单数 >= `maxDailyOrders` 时，停止跟单
  - 次日自动重置

#### 2.6.2 单笔订单金额限制（仅比例模式）
- **功能**：限制单笔跟单订单的最大和最小金额（仅在比例模式下生效）
- **规则**：
  - 比例模式下，订单金额必须在 `minOrderSize`（>= 1）和 `maxOrderSize` 之间
  - 固定金额模式下，不应用此限制
  - 超出范围时，调整到边界值或跳过

#### 2.6.3 市场状态检查
- **功能**：在跟单前检查市场状态
- **检查项**：
  - 市场是否活跃（active）
  - 市场是否已关闭（closed）
  - 订单簿是否有足够流动性
- **规则**：
  - 市场不活跃或已关闭时，跳过跟单

### 2.6 自己的订单管理

#### 2.6.1 查询自己的所有订单
- **功能**：查询自己在 Polymarket 上的所有订单（包括跟单订单和手动订单）
- **实现方式**：
  - 调用 CLOB API `/orders/active` 获取活跃订单
  - 从数据库查询跟单订单记录
  - 合并显示
- **筛选条件**：
  - `marketId`: 按市场筛选
  - `status`: 按订单状态筛选（active, filled, cancelled）
  - `side`: 按方向筛选（BUY/SELL）
  - `category`: 按分类筛选
  - `isCopyOrder`: 是否只显示跟单订单（true/false）
- **返回数据**：
  - 订单列表（包括订单ID、市场、方向、价格、数量、状态等）
  - 区分跟单订单和手动订单

#### 2.6.2 查询自己的持仓
- **功能**：查询自己的当前持仓
- **实现方式**：
  - 通过订单记录计算持仓（买入-卖出）
  - 或调用 API 查询持仓（如果支持）
- **返回数据**：
  - 持仓列表（市场、方向、数量、平均成本、当前价值、盈亏）

#### 2.6.3 手动创建订单
- **功能**：手动创建订单（非跟单订单）
- **输入参数**：
  - `marketId`: 市场ID
  - `side`: 方向（BUY/SELL）
  - `price`: 价格
  - `size`: 数量
- **业务规则**：
  - 使用自己的 API Key 创建订单
  - 验证订单参数
  - 记录为手动订单（非跟单订单）

#### 2.6.4 手动取消订单
- **功能**：手动取消自己的订单
- **输入参数**：
  - `orderId`: 订单ID
- **业务规则**：
  - 只能取消自己的订单
  - 如果是跟单订单，记录取消原因
  - 调用 CLOB API 取消订单

### 2.7 跟单记录与统计

#### 2.5.1 跟单记录
- **记录内容**：
  - 跟单订单ID
  - Leader 订单ID/交易ID
  - Leader 地址
  - 市场ID
  - 订单方向（BUY/SELL）
  - 价格
  - 数量
  - 跟单比例
  - 创建时间
  - 订单状态
  - 盈亏（订单平仓后计算）

#### 2.5.2 统计信息
- **全局统计**：
  - 总跟单订单数
  - 总盈亏
  - 胜率
  - 平均盈亏
  - 最大单笔盈利/亏损
- **按 Leader 统计**：
  - 每个 Leader 的跟单订单数
  - 每个 Leader 的盈亏
  - 每个 Leader 的胜率
- **按分类统计**：
  - sports 分类的跟单统计
  - crypto 分类的跟单统计
- **时间维度统计**：
  - 今日统计
  - 本周统计
  - 本月统计
  - 历史统计

### 2.8 订单管理（跟单订单）

#### 2.6.1 查询跟单订单
- **功能**：查询所有跟单订单
- **筛选条件**：
  - `leaderId`: 按 Leader 筛选
  - `marketId`: 按市场筛选
  - `status`: 按订单状态筛选（active, filled, cancelled）
  - `category`: 按分类筛选
  - `startTime`: 开始时间
  - `endTime`: 结束时间
- **分页支持**：
  - `page`: 页码
  - `limit`: 每页数量

#### 2.6.2 手动取消跟单订单
- **功能**：手动取消指定的跟单订单
- **输入参数**：
  - `copyOrderId`: 跟单订单ID
- **业务规则**：
  - 只能取消自己的跟单订单
  - 记录取消原因

## 3. 数据模型

### 3.1 Account（账户信息）
```kotlin
@Entity
@Table(name = "wallet_accounts")
data class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "private_key", nullable = false, length = 500)
    val privateKey: String,  // 私钥（加密存储）
    
    @Column(name = "wallet_address", unique = true, nullable = false, length = 42)
    val walletAddress: String,  // 钱包地址（从私钥推导）
    
    @Column(name = "api_key", length = 200)
    val apiKey: String? = null,  // Polymarket API Key（可选，加密存储）
    
    @Column(name = "account_name", length = 100)
    val accountName: String? = null,
    
    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,  // 是否默认账户
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
```

## 4. 数据模型（原有）

### 4.1 Leader（被跟单者）
```kotlin
@Entity
@Table(name = "copy_trading_leaders")
data class Leader(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "leader_address", unique = true, nullable = false, length = 42)
    val leaderAddress: String,  // 钱包地址
    
    @Column(name = "leader_name", length = 100)
    val leaderName: String? = null,
    
    @Column(name = "category", length = 20)
    val category: String? = null,  // sports 或 crypto，null 表示不筛选
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
```

### 4.2 CopyTradingTemplate（跟单模板）
```kotlin
@Entity
@Table(name = "copy_trading_templates")
data class CopyTradingTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "template_name", unique = true, nullable = false, length = 100)
    val templateName: String,  // 模板名称
    
    @Column(name = "copy_mode", nullable = false, length = 10)
    val copyMode: String = "RATIO",  // "RATIO" 或 "FIXED"
    
    @Column(name = "copy_ratio", nullable = false, precision = 10, scale = 2)
    val copyRatio: BigDecimal = BigDecimal.ONE,  // 仅在 copyMode="RATIO" 时生效
    
    @Column(name = "fixed_amount", precision = 20, scale = 8)
    val fixedAmount: BigDecimal? = null,  // 仅在 copyMode="FIXED" 时生效，必须 >= 1
    
    @Column(name = "max_order_size", precision = 20, scale = 8)
    val maxOrderSize: BigDecimal? = null,  // 仅在 copyMode="RATIO" 时生效
    
    @Column(name = "min_order_size", precision = 20, scale = 8)
    val minOrderSize: BigDecimal? = null,  // 仅在 copyMode="RATIO" 时生效，必须 >= 1
    
    @Column(name = "max_daily_orders", nullable = false)
    val maxDailyOrders: Int = 100,
    
    @Column(name = "price_tolerance", nullable = false, precision = 5, scale = 2)
    val priceTolerance: BigDecimal = "5".toSafeBigDecimal(),  // 百分比
    
    @Column(name = "support_sell", nullable = false)
    val supportSell: Boolean = true,  // 跟单卖出
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
```

### 4.3 CopyTrading（跟单关系，钱包-模板关联）
```kotlin
@Entity
@Table(name = "copy_trading")
data class CopyTrading(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,  // 钱包账户ID
    
    @Column(name = "template_id", nullable = false)
    val templateId: Long,  // 模板ID
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,  // Leader ID
    
    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,  // 是否启用
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis(),
    
    // 唯一约束：同一个钱包、模板、Leader 的组合只能有一条记录
    @UniqueConstraint(columnNames = ["account_id", "template_id", "leader_id"])
)
```

### 4.4 CopyOrder（跟单订单）
```kotlin
@Entity
@Table(name = "copy_orders")
data class CopyOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,  // 使用的账户ID
    
    @Column(name = "template_id", nullable = false)
    val templateId: Long,  // 使用的模板ID
    
    @Column(name = "copy_trading_id", nullable = false)
    val copyTradingId: Long,  // 跟单关系ID
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,
    
    @Column(name = "leader_address", nullable = false, length = 42)
    val leaderAddress: String,
    
    @Column(name = "leader_trade_id", length = 100)
    val leaderTradeId: String? = null,  // Leader 的交易ID
    
    @Column(name = "leader_order_id", length = 100)
    val leaderOrderId: String? = null,  // Leader 的订单ID（如果有）
    
    @Column(name = "market_id", nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "category", nullable = false, length = 20)
    val category: String,  // sports 或 crypto
    
    @Column(name = "side", nullable = false, length = 10)
    val side: String,  // BUY 或 SELL
    
    @Column(name = "price", nullable = false, precision = 20, scale = 8)
    val price: BigDecimal,
    
    @Column(name = "size", nullable = false, precision = 20, scale = 8)
    val size: BigDecimal,
    
    @Column(name = "copy_ratio", nullable = false, precision = 10, scale = 2)
    val copyRatio: BigDecimal,
    
    @Column(name = "order_id", length = 100)
    var orderId: String? = null,  // Polymarket 订单ID
    
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "pending",  // pending, created, filled, cancelled, failed
    
    @Column(name = "filled_size", nullable = false, precision = 20, scale = 8)
    var filledSize: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "pnl", precision = 20, scale = 8)
    var pnl: BigDecimal? = null,  // 盈亏（订单平仓后计算）
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
```

### 4.5 DailyStatistics（每日统计）
```kotlin
@Entity
@Table(name = "copy_trading_daily_stats")
data class DailyStatistics(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "date", nullable = false, unique = true)
    val date: String,  // YYYY-MM-DD 格式
    
    @Column(name = "total_orders", nullable = false)
    val totalOrders: Int = 0,
    
    @Column(name = "total_pnl", nullable = false, precision = 20, scale = 8)
    val totalPnl: BigDecimal = BigDecimal.ZERO,
    
    @Column(name = "win_count", nullable = false)
    val winCount: Int = 0,
    
    @Column(name = "loss_count", nullable = false)
    val lossCount: Int = 0,
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
```

## 5. API 接口设计

### 5.1 账户管理接口

#### 5.1.1 通过私钥导入账户
- **接口**: `POST /api/copy-trading/accounts/import`
- **请求体**:
```json
{
  "privateKey": "encrypted_private_key",  // 前端加密后的私钥
  "walletAddress": "0x...",  // 前端从私钥推导的地址（用于验证）
  "accountName": "Account 1",
  "apiKey": "your_api_key",  // 可选
  "isDefault": false
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "id": 1,
    "walletAddress": "0x1234...5678",
    "accountName": "Account 1",
    "isDefault": false,
    "apiKeyConfigured": true
  },
  "msg": ""
}
```
- **前端处理**：
  - 使用 ethers.js: `new ethers.Wallet(privateKey).address`
  - 或使用 web3.js: `web3.eth.accounts.privateKeyToAccount(privateKey).address`
  - 私钥加密后传输（使用 HTTPS + 前端加密）

#### 5.1.2 更新账户信息
- **接口**: `POST /api/copy-trading/accounts/update`
- **请求体**:
```json
{
  "accountId": 1,
  "accountName": "Updated Name",
  "apiKey": "new_api_key",
  "isDefault": true
}
```
- **注意**：不能修改私钥和地址

#### 5.1.3 删除账户
- **接口**: `POST /api/copy-trading/accounts/delete`
- **请求体**:
```json
{
  "accountId": 1
}
```
- **业务规则**：
  - 如果账户有活跃订单，返回错误提示
  - 如果删除的是默认账户，需要先设置其他账户为默认

#### 5.1.4 查询账户列表
- **接口**: `POST /api/copy-trading/accounts/list`
- **响应**:
```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "id": 1,
        "walletAddress": "0x1234...5678",
        "accountName": "Account 1",
        "isDefault": true,
        "apiKeyConfigured": true,
        "balance": "1000.5",
        "totalOrders": 100,
        "totalPnl": "50.5"
      },
      {
        "id": 2,
        "walletAddress": "0xabcd...efgh",
        "accountName": "Account 2",
        "isDefault": false,
        "apiKeyConfigured": true,
        "balance": "500.0",
        "totalOrders": 50,
        "totalPnl": "20.0"
      }
    ],
    "total": 2
  },
  "msg": ""
}
```

#### 5.1.5 查询账户详情
- **接口**: `POST /api/copy-trading/accounts/detail`
- **请求体**:
```json
{
  "accountId": 1
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "id": 1,
    "walletAddress": "0x1234...5678",
    "accountName": "Account 1",
    "isDefault": true,
    "apiKeyConfigured": true,
    "balance": "1000.5",
    "totalOrders": 100,
    "totalPnl": "50.5"
  },
  "msg": ""
}
```

#### 5.1.6 查询账户余额
- **接口**: `POST /api/copy-trading/accounts/balance`
- **请求体**:
```json
{
  "accountId": 1
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "usdcBalance": "1000.5",
    "positions": [
      {
        "marketId": "0x...",
        "side": "YES",
        "quantity": "100",
        "avgPrice": "0.5",
        "currentValue": "50",
        "pnl": "0"
      }
    ]
  },
  "msg": ""
}
```

#### 5.1.7 设置默认账户
- **接口**: `POST /api/copy-trading/accounts/set-default`
- **请求体**:
```json
{
  "accountId": 1
}
```

### 5.2 Leader 管理接口（原有）

#### 5.2.1 添加被跟单者
- **接口**: `POST /api/copy-trading/leaders/add`
- **请求体**:
```json
{
  "leaderAddress": "0x...",
  "leaderName": "Trader A",
  "category": "sports"
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "id": 1,
    "leaderAddress": "0x...",
    "leaderName": "Trader A",
    "category": "sports",
    "createdAt": 1234567890000,
    "updatedAt": 1234567890000
  },
  "msg": ""
}
```
- **说明**：
  - 账户和模板的关联通过跟单管理接口创建
  - 启用状态由跟单关系管理

#### 5.2.2 删除被跟单者
- **接口**: `POST /api/copy-trading/leaders/delete`
- **请求体**:
```json
{
  "leaderId": 1
}
```

#### 5.2.3 更新被跟单者
- **接口**: `POST /api/copy-trading/leaders/update`
- **请求体**:
```json
{
  "leaderId": 1,
  "leaderName": "Trader A Updated",
  "category": "crypto"
}
```
- **说明**：
  - 只能更新名称和分类
  - 账户和模板关联通过跟单管理接口管理

#### 5.2.4 查询被跟单者列表
- **接口**: `POST /api/copy-trading/leaders/list`
- **请求体**:
```json
{
  "category": "sports"
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "id": 1,
        "leaderAddress": "0x...",
        "leaderName": "Trader A",
        "category": "sports",
        "copyTradingCount": 3,
        "totalOrders": 10,
        "totalPnl": "50.5",
        "createdAt": 1234567890000,
        "updatedAt": 1234567890000
      }
    ],
    "total": 1
  },
  "msg": ""
}
```

### 5.3 跟单模板管理接口（子菜单：跟单模板）

#### 5.3.1 创建模板
- **接口**: `POST /api/copy-trading/templates/create`
- **请求体**:
```json
{
  "templateName": "保守型模板",
  "copyMode": "RATIO",
  "copyRatio": "0.5",  // 后端存储为小数（前端输入 50% 转换为 0.5）
  "fixedAmount": null,
  "maxOrderSize": "1000",
  "minOrderSize": "1",
  "maxDailyOrders": 100,
  "priceTolerance": "5",
  "supportSell": true
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "id": 1,
    "templateName": "保守型模板",
    "copyMode": "RATIO",
    "copyRatio": "0.5",  // 后端存储为小数（50% 存储为 0.5）
    "fixedAmount": null,
    "maxOrderSize": "1000",
    "minOrderSize": "1",
    "maxDailyOrders": 100,
    "priceTolerance": "5",
    "supportSell": true,
    "createdAt": 1234567890000,
    "updatedAt": 1234567890000
  },
  "msg": ""
}
```

#### 5.3.2 更新模板
- **接口**: `POST /api/copy-trading/templates/update`
- **请求体**:
```json
{
  "templateId": 1,
  "templateName": "新模板名称",  // 可选，用于修改模板名称
  "copyRatio": "0.6",  // 后端存储为小数（前端输入 60% 转换为 0.6 存储）
  "maxOrderSize": "2000"
}
```
- **注意**：可以修改模板名称，但新名称必须唯一，不能与其他模板重名

#### 5.3.3 删除模板
- **接口**: `POST /api/copy-trading/templates/delete`
- **请求体**:
```json
{
  "templateId": 1
}
```

#### 5.3.4 复制模板
- **接口**: `POST /api/copy-trading/templates/copy`
- **请求体**:
```json
{
  "templateId": 1,
  "templateName": "保守型模板-副本",
  "copyRatio": "0.7"  // 后端存储为小数（前端输入 70% 转换为 0.7 存储）
}
```

#### 5.3.5 查询模板列表
- **接口**: `POST /api/copy-trading/templates/list`
- **响应**:
```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "id": 1,
        "templateName": "保守型模板",
  "copyMode": "RATIO",
        "copyRatio": "0.5",  // 后端存储为小数（前端显示为 50%）  // 后端存储为小数（50% 存储为 0.5）
        "useCount": 3,
        "createdAt": 1234567890000,
        "updatedAt": 1234567890000
      }
    ],
    "total": 1
  },
  "msg": ""
}
```

#### 5.3.6 查询模板详情
- **接口**: `POST /api/copy-trading/templates/detail`
- **请求体**:
```json
{
  "templateId": 1
}
```

### 5.4 跟单配置管理接口（子菜单：跟单配置）

#### 5.4.1 创建跟单
- **接口**: `POST /api/copy-trading/create`
- **请求体**:
```json
{
  "accountId": 1,
  "templateId": 1,
  "leaderId": 1,
  "enabled": true
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "id": 1,
    "accountId": 1,
    "accountName": "Account 1",
    "walletAddress": "0x1234...5678",
    "templateId": 1,
    "templateName": "保守型模板",
    "leaderId": 1,
    "leaderName": "Trader A",
    "leaderAddress": "0x...",
    "enabled": true,
    "createdAt": 1234567890000
  },
  "msg": ""
}
```

#### 5.4.2 查询跟单列表
- **接口**: `POST /api/copy-trading/list`
- **请求体**:
```json
{
  "accountId": 1,
  "templateId": 1,
  "leaderId": 1,
  "enabled": true
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "id": 1,
        "accountId": 1,
        "accountName": "Account 1",
        "walletAddress": "0x1234...5678",
        "templateId": 1,
        "templateName": "保守型模板",
        "leaderId": 1,
        "leaderName": "Trader A",
        "leaderAddress": "0x...",
        "enabled": true,
        "createdAt": 1234567890000,
        "updatedAt": 1234567890000
      }
    ],
    "total": 1
  },
  "msg": ""
}
```

#### 5.4.3 更新跟单状态
- **接口**: `POST /api/copy-trading/update-status`
- **请求体**:
```json
{
  "copyTradingId": 1,
  "enabled": false
}
```

#### 5.4.4 删除跟单
- **接口**: `POST /api/copy-trading/delete`
- **请求体**:
```json
{
  "copyTradingId": 1
}
```

#### 5.4.5 查询钱包绑定的模板
- **接口**: `POST /api/copy-trading/account-templates`
- **请求体**:
```json
{
  "accountId": 1
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "templateId": 1,
        "templateName": "保守型模板",
        "copyTradingId": 1,
        "leaderId": 1,
        "leaderName": "Trader A",
        "enabled": true
      }
    ],
    "total": 1
  },
  "msg": ""
}
```

### 5.4 自己的订单管理接口

#### 5.4.1 查询自己的所有订单
- **接口**: `POST /api/copy-trading/my-orders/list`
- **请求体**:
```json
{
  "marketId": "0x...",
  "status": "active",
  "side": "BUY",
  "category": "sports",
  "isCopyOrder": false,
  "page": 1,
  "limit": 20
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "orderId": "order_123",
        "marketId": "0x...",
        "category": "sports",
        "side": "BUY",
        "price": "0.5",
        "size": "100",
        "filled": "50",
        "status": "active",
        "isCopyOrder": true,
        "leaderName": "Trader A",
        "createdAt": 1234567890000
      }
    ],
    "total": 100,
    "page": 1,
    "limit": 20
  },
  "msg": ""
}
```

#### 5.4.2 查询自己的持仓
- **接口**: `POST /api/copy-trading/my-orders/positions`
- **响应**:
```json
{
  "code": 0,
  "data": {
    "positions": [
      {
        "marketId": "0x...",
        "marketTitle": "Market Title",
        "category": "sports",
        "side": "YES",
        "quantity": "100",
        "avgPrice": "0.5",
        "currentPrice": "0.6",
        "currentValue": "60",
        "cost": "50",
        "pnl": "10",
        "pnlPercent": "20"
      }
    ]
  },
  "msg": ""
}
```

#### 5.4.3 手动创建订单
- **接口**: `POST /api/copy-trading/my-orders/create`
- **请求体**:
```json
{
  "marketId": "0x...",
  "side": "BUY",
  "price": "0.5",
  "size": "100"
}
```

#### 5.4.4 手动取消订单
- **接口**: `POST /api/copy-trading/my-orders/cancel`
- **请求体**:
```json
{
  "orderId": "order_123"
}
```

### 5.5 跟单订单管理接口

#### 5.5.1 查询跟单订单
- **接口**: `POST /api/copy-trading/orders/list`
- **请求体**:
```json
{
  "leaderId": 1,
  "marketId": "0x...",
  "status": "active",
  "category": "sports",
  "startTime": 1234567890000,
  "endTime": 1234567890000,
  "page": 1,
  "limit": 20
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "list": [
      {
        "id": 1,
        "leaderId": 1,
        "leaderAddress": "0x...",
        "leaderName": "Trader A",
        "marketId": "0x...",
        "category": "sports",
        "side": "BUY",
        "price": "0.5",
        "size": "100",
        "copyRatio": "1.0",
        "orderId": "order_123",
        "status": "filled",
        "filledSize": "100",
        "pnl": "10.5",
        "createdAt": 1234567890000
      }
    ],
    "total": 100,
    "page": 1,
    "limit": 20
  },
  "msg": ""
}
```

#### 5.5.2 取消跟单订单
- **接口**: `POST /api/copy-trading/orders/cancel`
- **请求体**:
```json
{
  "copyOrderId": 1
}
```

### 5.6 统计接口

#### 5.6.1 获取全局统计
- **接口**: `POST /api/copy-trading/statistics/global`
- **请求体**:
```json
{
  "startTime": 1234567890000,
  "endTime": 1234567890000
}
```
- **响应**:
```json
{
  "code": 0,
  "data": {
    "totalOrders": 100,
    "totalPnl": "500.5",
    "winRate": "60.5",
    "avgPnl": "5.005",
    "maxProfit": "50.0",
    "maxLoss": "-30.0"
  },
  "msg": ""
}
```

#### 5.6.2 获取 Leader 统计
- **接口**: `POST /api/copy-trading/statistics/leader`
- **请求体**:
```json
{
  "leaderId": 1,
  "startTime": 1234567890000,
  "endTime": 1234567890000
}
```

#### 5.6.3 获取分类统计
- **接口**: `POST /api/copy-trading/statistics/category`
- **请求体**:
```json
{
  "category": "sports",
  "startTime": 1234567890000,
  "endTime": 1234567890000
}
```

## 6. 技术实现要点

### 6.0 前端私钥处理（重要）

#### 6.0.1 私钥导入方式
前端支持多种私钥导入方式：
1. **私钥字符串**：直接输入 64 位十六进制私钥（可选 0x 前缀）
2. **助记词（Mnemonic）**：12 或 24 个单词的助记词
3. **Keystore 文件**：JSON 格式的加密钱包文件（需要密码）

#### 6.0.2 地址推导实现
前端使用以下库从私钥推导地址：

**使用 ethers.js（推荐）**：
```typescript
import { ethers } from 'ethers';

// 从私钥创建钱包
const wallet = new ethers.Wallet(privateKey);
const address = wallet.address;

// 从助记词创建钱包
const walletFromMnemonic = ethers.Wallet.fromMnemonic(mnemonic);
const addressFromMnemonic = walletFromMnemonic.address;
```

**使用 web3.js**：
```typescript
import Web3 from 'web3';

const web3 = new Web3();
const account = web3.eth.accounts.privateKeyToAccount(privateKey);
const address = account.address;
```

#### 6.0.3 私钥安全传输
- **前端加密**：私钥在传输前使用 AES 加密（可选，因为 HTTPS 已加密）
- **HTTPS 传输**：必须使用 HTTPS 协议
- **后端验证**：后端验证私钥和地址的对应关系

#### 6.0.4 前端存储策略
- **不保存明文**：前端不将私钥明文保存到 localStorage 或 sessionStorage
- **内存处理**：私钥只在内存中处理，用完即清除
- **用户选择**：如果用户需要记住私钥，可以保存加密后的私钥（需要密码）

### 6.1 账户管理实现
- **私钥存储**：
  - 私钥必须加密存储（使用 AES-256 加密）
  - 加密密钥存储在环境变量或配置文件中
  - 私钥解密只在需要签名时进行，用完即清除内存
- **私钥验证**：
  - 后端验证私钥和地址的对应关系
  - 使用以太坊库验证私钥格式
- **多账户支持**：
  - 系统支持多个账户同时存在
  - 每个账户可以独立配置
  - 跟单时可以指定使用哪个账户
- **身份识别**：
  - 系统从数据库加载所有账户信息
  - 创建订单时使用指定账户的 API Key 或私钥签名
  - 查询订单时过滤自己的所有账户地址
- **前端私钥处理**：
  - 前端使用 ethers.js 或 web3.js 从私钥推导地址
  - 私钥在传输前加密（使用 HTTPS + 前端加密）
  - 前端不保存私钥明文（除非用户明确要求）

### 6.2 订单监控实现

#### 6.2.1 WebSocket 推送模式（优先）
- **连接方式**：
  - 使用 WebSocket 客户端连接到 `wss://ws-live-data.polymarket.com`
  - 为每个 Leader 订阅用户交易频道
  - 订阅消息格式：
    ```json
    {
      "type": "subscribe",
      "channel": "user",
      "user": "leader_address",
      "apiKey": "your_api_key"  // 如果需要认证
    }
    ```
- **消息处理**：
  - 监听 WebSocket 消息，接收交易推送
  - 解析交易数据，提取 `side`（BUY/SELL）、`market`、`price`、`size` 等信息
  - 实时触发跟单逻辑
- **连接管理**：
  - 实现自动重连机制
  - 连接断开时自动降级到轮询模式
  - 支持动态添加/移除 Leader 订阅

#### 6.2.2 轮询模式（备选）
- **实现方式**：
  - 使用定时任务（ScheduledExecutorService 或 Spring @Scheduled）
  - 定期调用 CLOB API `/trades?user={leaderAddress}` 获取最新交易
  - 记录上次查询的时间戳，只处理新交易
- **优化策略**：
  - 批量查询多个 Leader 的交易（如果 API 支持）
  - 根据 Leader 活跃度调整轮询频率
  - 使用缓存减少重复查询

#### 6.2.3 模式切换
- **自动切换**：
  - 系统启动时优先尝试 WebSocket 连接
  - WebSocket 连接成功且可用时，使用推送模式
  - WebSocket 不可用或断开时，自动切换到轮询模式
  - 使用系统默认的 WebSocket 重连和轮询配置

#### 6.2.4 去重机制
- **实现方式**：
  - 使用 Redis 或数据库记录已处理的交易ID
  - 记录格式：`leader_id + trade_id` 作为唯一键
  - 避免重复跟单同一笔交易
- **清理策略**：
  - 定期清理过期的已处理记录（如 24 小时前）
  - 使用 TTL 自动过期机制

### 6.3 订单执行流程
1. 检测到 Leader 新交易
2. 验证配置和风险控制
3. 计算跟单订单参数
4. 调用 CLOB API 创建订单
5. 保存跟单记录
6. 更新统计信息

### 6.4 订单状态同步
- 定期查询活跃订单状态
- 检测订单状态变化（filled, cancelled）
- 更新跟单记录状态
- 计算盈亏（订单平仓后）

### 6.5 错误处理
- API 调用失败重试机制
- 订单创建失败记录日志
- 异常情况告警（可选）

### 6.6 性能优化
- 批量查询多个 Leader 的交易
- 使用缓存减少 API 调用
- 异步处理订单创建

## 7. 数据库设计

### 7.1 表结构
- `wallet_accounts`: 钱包账户表
- `copy_trading_leaders`: 被跟单者表
- `copy_trading_templates`: 跟单模板表
- `copy_trading`: 跟单关系表（钱包-模板关联，多对多）
- `copy_orders`: 跟单订单表
- `copy_trading_daily_stats`: 每日统计表
- `copy_trading_processed_trades`: 已处理交易表（用于去重）

### 7.2 索引设计
- `wallet_accounts.wallet_address`: UNIQUE 索引
- `copy_trading_leaders.leader_address`: UNIQUE 索引
- `copy_trading_templates.template_name`: UNIQUE 索引
- `copy_trading.account_id + template_id + leader_id`: 联合唯一索引
- `copy_trading.account_id`: 索引
- `copy_trading.template_id`: 索引
- `copy_trading.leader_id`: 索引
- `copy_orders.copy_trading_id`: 索引
- `copy_orders.leader_id`: 索引
- `copy_orders.market_id`: 索引
- `copy_orders.created_at`: 索引
- `copy_trading_processed_trades.leader_address + trade_id`: 联合唯一索引

## 8. 安全考虑

### 8.1 API 认证
- 所有接口需要 API Key 认证
- 验证用户权限

### 8.2 风险控制
- 严格的金额限制
- 每日亏损限制
- 订单数量限制

### 8.3 数据验证
- 验证 Leader 地址格式
- 验证订单参数
- 验证配置参数范围

### 8.4 私钥和 API Key 安全
- **私钥安全**：
  - 私钥必须加密存储，不能明文保存
  - 使用 AES-256 加密算法
  - 加密密钥存储在环境变量或配置文件中
  - 私钥解密只在需要签名时进行，用完即清除内存
  - 前端传输私钥时使用 HTTPS + 前端加密
- **API Key 安全**：
  - API Key 加密存储（如果提供）
  - 使用 AES 加密算法
  - 加密密钥存储在环境变量或配置文件中
- **访问控制**：
  - 私钥只能通过导入接口设置，不能修改
  - 查询接口不返回私钥和完整的 API Key
  - 更新 API Key 时需要验证旧 Key
- **前端安全**：
  - 前端使用 ethers.js 或 web3.js 处理私钥
  - 私钥在内存中处理，不保存到 localStorage（除非用户明确要求）
  - 支持助记词导入（前端转换为私钥）

## 9. 后续扩展功能（可选）

### 9.1 高级功能
- 智能跟单（根据 Leader 历史表现筛选）
- 反向跟单（反向操作 Leader 的订单）
- 部分跟单（只跟单特定市场或条件）

### 9.2 分析功能
- Leader 表现分析
- 市场分析
- 盈亏分析报告

### 9.3 通知功能
- 跟单订单通知
- 风险告警通知
- 每日统计报告

## 10. 开发优先级

### Phase 1: 核心功能（MVP）
1. **账户管理**（新增）
   - 通过私钥导入账户（前端从私钥推导地址）
   - 多账户管理（增删改查）
   - 默认账户设置
   - 账户信息查询
   - API Key 管理（可选）
2. Leader 管理（增删改查）
3. **跟单模板管理**（新增）
   - 创建、更新、删除、复制模板
   - 查询模板列表和详情
4. **跟单管理**（新增）
   - 创建跟单（钱包-模板关联）
   - 查询跟单列表
   - 开启/停止跟单
   - 删除跟单
   - 查询钱包绑定的模板
5. 订单监控和同步（基础版本）
6. 跟单订单记录（包含账户ID、模板ID）
7. **前端移动端适配**（必须）
   - 响应式布局设计
   - 移动端 UI 组件适配
   - 触摸操作优化
   - 移动端性能优化

### Phase 2: 完善功能
1. 自己的订单管理
   - 查询所有订单
   - 查询持仓
   - 手动创建/取消订单
2. 风险控制完善
3. 统计功能
4. 错误处理和重试
5. **前端移动端优化**
   - 移动端交互优化
   - 手势操作支持
   - 离线功能支持（可选）

### Phase 3: 优化和扩展
1. 性能优化
2. 高级功能
3. 分析和报告
4. 通知功能

