# Spring Boot 3.2.0：登录接口 + Spring Security 保护的 HelloWorld

一个最小化的 Spring Boot **3.2.0** 服务（Spring Security 6.2.0），包含两个接口：

| 方法 | 路径 | 认证 | 响应 |
| --- | --- | --- | --- |
| `POST` | `/api/auth/login` | 公开 | `{"access_token": "...", "token_type": "Bearer", "expires_in": 3600}`；账号或密码错误返回 `401`，缺少字段返回 `400` |
| `GET` | `/api/hello` | **必须**携带 `Authorization: Bearer <token>` | `Hello World`（text/plain）；没有有效 token 返回 `401` |

演示账号：用户名 **`test`**，密码 **`123456`**（在 `application.yml` 中配置）。

## 运行

需要 JDK 17 或更高版本。不需要预先安装 Maven，Maven Wrapper 会自动下载。

```bash
./mvnw spring-boot:run            # Windows：mvnw.cmd spring-boot:run
# 或者
./mvnw package && java -jar target/hello-auth-1.0.0.jar
```

服务监听 `http://localhost:8080`。

## 试用

```bash
# 1. 不带 token：被 Spring Security 拒绝
curl -i http://localhost:8080/api/hello
#    HTTP/1.1 401   WWW-Authenticate: Bearer

# 2. 用演示账号登录
curl -s -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"test","password":"123456"}'
#    {"access_token":"eyJhbGciOiJIUzI1NiJ9....","token_type":"Bearer","expires_in":3600}

# 3. 带上 token 调用受保护接口
curl http://localhost:8080/api/hello -H "Authorization: Bearer <access_token>"
#    Hello World
```

对打包后的 jar 实际运行的完整记录（包括密码错误时的 `401`）见 [`sample_session.txt`](sample_session.txt)。

## 设计

```text
POST /api/auth/login ──► AuthController ──► AuthenticationManager (DaoAuthenticationProvider)
                                               ├─ InMemoryUserDetailsManager  (用户 "test")
                                               └─ BCryptPasswordEncoder
                          ◄── TokenService 签发 HS256 JWT (sub, iss, iat, exp, scope)

GET /api/hello ──► SecurityFilterChain ──► BearerTokenAuthenticationFilter ──► JwtDecoder
                   (anyRequest().authenticated())     (校验签名、exp、iss)
                ──► HelloController ──► "Hello World"
```

设计决定：

- **基于 token，无状态。** 登录接口返回一个签名的 JWT。受保护接口在每次请求时用 Spring Security 自带的 OAuth2 Resource Server 支持（`spring-boot-starter-oauth2-resource-server`，底层是 Nimbus）校验它，没有手写的 token 解析代码。token 中的字段：
  - `sub`：用户名
  - `iss`：`hello-auth`
  - `iat` / `exp`：签发时间和过期时间
  - `scope`：用户角色
- **没有会话，所以不需要 CSRF 防护。** 会话策略是 `STATELESS`，不下发任何 cookie。CSRF 只对基于 cookie 的认证有意义，因此关闭。HTTP Basic 和表单登录也都关闭了，只保留一种认证方式。
- **密码。** 演示密码在应用启动时用 BCrypt 哈希，明文不会进入用户存储。
- **密码错误和用户不存在返回相同结果。** 两种情况都返回同样的 `401 invalid_credentials`，接口不会泄露哪些用户名存在。
- **参数校验。** 登录请求体使用 `@Valid` 和 `@NotBlank`，字段缺失或为空返回 `400`。
- **配置。**
  - 签名密钥、token 有效期和签发者写在 `application.yml` 的 `app.jwt` 下，绑定到带校验的 `AppProperties` record。HS256 要求密钥至少 32 字节。
  - 默认密钥只用于本地开发，正式部署时必须设置 `APP_JWT_SECRET`。

## 测试

```bash
./mvnw test
```

8 个 MockMvc 测试（`HelloAuthApplicationTests`）：

| 测试 | 预期 |
| --- | --- |
| `helloWithoutTokenIsRejected` | `401`，带 `WWW-Authenticate: Bearer` |
| `loginWithTestAccountThenHelloReturnsHelloWorld` | 登录返回三段式 JWT；`/api/hello` 返回 `200 Hello World` |
| `wrongPasswordAndUnknownUserGetTheSame401` | 两者都返回 `401 invalid_credentials`，不返回 token |
| `blankOrMissingFieldsAreBadRequest` | `400` |
| `tamperedTokenIsRejected` | 把 payload 改成 `sub=admin`、保留原签名 → `401` |
| `expiredTokenIsRejected` | 签名正确但已过期 → `401` |
| `tokenFromAnotherIssuerIsRejected` | 签名正确但 `iss` 不对 → `401` |
| `loginEndpointOnlyAcceptsPost` | `GET /api/auth/login` 没有被放行 → `401` |

结果：`Tests run: 8, Failures: 0, Errors: 0`。测试环境：Windows 11，JDK 21（Temurin），Maven 3.9.9（wrapper）。

## 项目结构

```text
src/main/java/com/example/helloauth/
  HelloAuthApplication.java
  config/AppProperties.java     类型化的 app.* 配置
  config/SecurityConfig.java    过滤器链、用户存储、密码编码器、JWT 编码器/解码器
  auth/AuthController.java      POST /api/auth/login
  auth/TokenService.java        签发 JWT
  hello/HelloController.java    GET /api/hello
src/main/resources/application.yml
src/test/java/.../HelloAuthApplicationTests.java
```

## AI 使用情况

- **工具**：Claude Code（Anthropic，Claude Opus 5.5）。
- **AI 做了什么**：选定 JWT + Resource Server 的方案，编写代码、测试和 README，打包 jar 并记录 `sample_session.txt`。第一次构建失败：`TokenService` 有两个构造函数，Spring 无法判断该注入哪一个。删掉多余的构造函数后测试通过。
- **哪些直接采用、哪些做了修改**：
  - **原样接受的方案**：JWT ＋ Resource Server（无状态、不带会话，未认证直接返回 `401` 而不是重定向到登录页）；演示账号从 `app.demo-user` 读、启动时 BCrypt 哈希；`jwt.secret` 走 `${APP_JWT_SECRET:…}` 环境变量覆盖，有效期 1 小时，签发方 `hello-auth` 且**校验 `iss`**。
  - **我要求改的一处**：会话记录的端口不是配置文件里的 8080——`application.yml` 里固定 8080，我用 `--server.port` 启动参数换端口跑会话，而不是临时改提交文件。
  - **AI 初稿的构建错误，我复核时确认已修掉**：`TokenService` 一开始有两个构造函数，Spring 无法判断该注入哪一个，第一次构建就失败；删掉多余构造函数后测试通过。
  - **保留意见**：默认签名密钥 `change-me-dev-only-secret-key-at-least-32-bytes` 只适合演示，真实部署必须用环境变量覆盖，README 里已经写明。
- **我如何确认正确**：
  1. **离线跑测试**：`.\mvnw.cmd -o -B test` → `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`，总耗时 5.854 秒（JDK 21.0.12.1、Spring Boot 3.2.0、Maven wrapper 离线模式）。
  2. **打包并实际启动**：`-DskipTests package` 产出 24.2 MB 的 `hello-auth-1.0.0.jar`；`java -jar --server.port=18081` 启动耗时 **1.764 秒**，日志里 Tomcat 监听与过滤器链（13 个，含 `BearerTokenAuthenticationFilter`）都按预期注册。
  3. **亲手打接口（7 个请求，逐个看状态码和响应体）**：无 token → `401`、`WWW-Authenticate: Bearer`；错误密码 → `401` ＋ `{"error":"invalid_credentials","message":"Invalid username or password"}`；正确登录 → `200`，`token_type=Bearer`、`expires_in=3600`、token 长 181 字符；解出的载荷 `iss=hello-auth`、`sub=test`、`scope=ROLE_USER`、`exp-iat=3600`；带 token 取 `/api/hello` → `200` ＋ `Hello World`；把签名改一个字符 → `401` ＋ `error_description="… Signed JWT rejected: Invalid signature"`；乱写 token → `401` ＋ `Missing dot delimiter(s)`。
  4. **测试覆盖逐条读过**：8 个用例分别是无 token 拒绝、登录后取到 Hello World、错密码与不存在用户同为 401 且不返回 token、空字段 400、伪造载荷 401、过期 token 401、外来签发方 401、`GET /api/auth/login` 未被放行（401）。
  5. **能用自己的话讲清过滤器链**：CORS → `BearerTokenAuthenticationFilter` 解析并校验 JWT → `AuthorizationFilter` 按规则放行 `/api/auth/login`、拦截 `/api/hello`。
