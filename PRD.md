# spring-scim-server: 完整產品需求文件 (PRD)

**Production-ready SCIM 2.0 Server for Spring Boot — seamlessly receive user provisioning from Microsoft Entra ID**

> **文件版本**: 1.2  
> **更新日期**: 2026-01-08  
> **狀態**: Draft

---

## 1. 專案概述

### 1.1 目標

spring-scim-server 是一個開源專案，提供生產級 SCIM 2.0 Server 實作，使 Spring Boot 應用程式能夠無縫接收來自 Microsoft Entra ID 的使用者佈建（User Provisioning），並透過 CloudEvents 規格發布事件，最終同步至下游系統。

### 1.2 核心資料流

```
┌──────────────────┐      SCIM 2.0       ┌─────────────────────┐
│                  │    (HTTPS/REST)     │                     │
│ Microsoft        │ ──────────────────► │  spring-scim-server │
│ Entra ID         │   POST/PATCH/DELETE │  (本專案)            │
│                  │                     │                     │
└──────────────────┘                     └──────────┬──────────┘
                                                    │
                                         CloudEvents (Binary Mode)
                                                    │
                                                    ▼
                                         ┌─────────────────────┐
                                         │   GCP Pub/Sub       │
                                         │   (Message Queue)   │
                                         └──────────┬──────────┘
                                                    │
                                                    ▼
                                         ┌─────────────────────┐
                                         │ Spring Auth Server  │
                                         │ (User Table)        │
                                         └─────────────────────┘
```

### 1.3 技術棧

| 類別 | 技術 | 版本 |
|------|------|------|
| **Runtime** | Java | 25 |
| **Framework** | Spring Boot | 4.0.1 |
| **SCIM SDK** | UnboundID SCIM 2 SDK | 5.0.0 |
| **Event Format** | CloudEvents Specification | 1.0.2 |
| **Event Builder** | Spring Cloud Function `CloudEventMessageBuilder` | 內建 |
| **Messaging** | Spring Cloud Stream | 5.0.x |
| **Message Broker** | GCP Pub/Sub | - |
| **Database** | PostgreSQL | 16 |
| **Deployment** | GCP (Cloud Run / GKE) | - |

### 1.4 SCIM 資源類型與 Schema 支援

#### 資源類型 (Resource Types)

| 資源類型 | 端點 | 支援狀態 | 說明 |
|---------|------|---------|------|
| **User** | `/scim/v2/Users` | ✅ v1.0 | 核心用戶資源 |
| **Group** | `/scim/v2/Groups` | 🔮 Future | 群組與成員關係 |

#### Schema 支援

| Schema | URI | 支援狀態 | 說明 |
|--------|-----|---------|------|
| **Core User** | `urn:ietf:params:scim:schemas:core:2.0:User` | ✅ v1.0 | 基本用戶屬性 |
| **Enterprise User** | `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User` | ✅ v1.0 | 組織、部門、主管等 |
| **Custom Extension** | `urn:ietf:params:scim:schemas:extension:{Name}:2.0:User` | 🔮 Future | 自訂擴展屬性 |
| **Core Group** | `urn:ietf:params:scim:schemas:core:2.0:Group` | 🔮 Future | 群組與成員 |

#### Discovery 端點

| 端點 | 說明 | 用途 |
|------|------|------|
| `/scim/v2/ServiceProviderConfig` | 服務配置 | Entra ID Test Connection |
| `/scim/v2/Schemas` | Schema 定義 | 屬性發現 |
| `/scim/v2/ResourceTypes` | 資源類型 | 支援的資源列表 |

> **注意事項**：
> - Entra ID **不支援巢狀群組**（nested groups），僅同步直接成員
> - **沒有 Organization 資源**：組織資訊透過 Enterprise User Extension 傳遞
> - Custom Extension 的複雜/多值屬性僅 Gallery Apps 支援

### 1.5 設計原則

1. **關注點分離**：SCIM Server 獨立於 Authorization Server
2. **事件驅動**：透過 CloudEvents + Pub/Sub 解耦上下游系統
3. **MQ 無關性**：透過 Spring Cloud Stream 抽象，可替換底層 Message Broker
4. **Entra ID 優先**：針對 Microsoft Entra ID 的 SCIM 實作進行優化
5. **漸進式擴展**：v1.0 專注 User，未來版本加入 Group 支援

---

## 2. 技術挑戰與解決方案

### 2.1 Jackson 3 vs Jackson 2 相容性

#### 問題描述

Spring Boot 4.0 預設使用 **Jackson 3**（package: `tools.jackson`），而 UnboundID SCIM 2 SDK 5.0.0 依賴 **Jackson 2**（package: `com.fasterxml.jackson`）。兩者為 breaking change，無法直接共存。

| 項目 | Jackson 2.x | Jackson 3.x |
|------|-------------|-------------|
| Package | `com.fasterxml.jackson` | `tools.jackson` |
| 核心類別 | `ObjectMapper` | `JsonMapper` (Builder pattern) |
| 異常類型 | `JsonProcessingException` (checked) | `JacksonException` (unchecked) |
| Java 版本 | 8+ | 17+ |

#### 解決方案：SCIM 端點自訂 MessageConverter

為 `application/scim+json` media type 配置獨立的 Jackson 2 HttpMessageConverter，與 Spring Boot 4 預設的 Jackson 3 隔離：

```java
@Configuration
public class ScimJacksonConfig {

    /**
     * 建立專用於 SCIM 的 Jackson 2 ObjectMapper
     * 使用 UnboundID SDK 提供的預配置 ObjectMapper
     */
    @Bean("jackson2ScimObjectMapper")
    public com.fasterxml.jackson.databind.ObjectMapper jackson2ScimObjectMapper() {
        // SDK 提供的預配置 ObjectMapper，已處理 SCIM 特殊序列化需求
        com.fasterxml.jackson.databind.ObjectMapper mapper = 
            com.unboundid.scim2.common.utils.JsonUtils.createObjectMapper();
        
        // Entra ID 相容性：忽略未知欄位
        mapper.configure(
            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, 
            false);
        
        return mapper;
    }

    /**
     * 註冊 SCIM 專用的 HttpMessageConverter
     * 僅處理 application/scim+json media type
     */
    @Bean
    public WebMvcConfigurer scimWebMvcConfigurer(
            @Qualifier("jackson2ScimObjectMapper") 
            com.fasterxml.jackson.databind.ObjectMapper jackson2Mapper) {
        
        return new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                // 建立 SCIM 專用的 Jackson 2 Converter
                MappingJackson2HttpMessageConverter scimConverter = 
                    new MappingJackson2HttpMessageConverter(jackson2Mapper);
                
                // 僅處理 SCIM media type
                scimConverter.setSupportedMediaTypes(List.of(
                    new MediaType("application", "scim+json"),
                    MediaType.APPLICATION_JSON  // 備援
                ));
                
                // 插入到最高優先順序，確保 SCIM 請求優先使用此 converter
                converters.add(0, scimConverter);
            }
        };
    }
}
```

#### Gradle 依賴配置

```groovy
dependencies {
    // UnboundID SCIM SDK（帶入 Jackson 2）
    implementation 'com.unboundid.product.scim2:scim2-sdk-server:5.0.0'
    
    // 明確引入 Jackson 2（確保版本一致）
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2'
}
```

#### 驗證方式

```java
@SpringBootTest
class ScimJacksonConfigTest {

    @Autowired
    @Qualifier("jackson2ScimObjectMapper")
    private com.fasterxml.jackson.databind.ObjectMapper jackson2Mapper;

    @Test
    void shouldUseJackson2ForScimResources() {
        assertThat(jackson2Mapper.getClass().getPackageName())
            .startsWith("com.fasterxml.jackson");
    }

    @Test
    void shouldSerializeUserResource() throws Exception {
        UserResource user = new UserResource();
        user.setUserName("test@example.com");
        
        String json = jackson2Mapper.writeValueAsString(user);
        
        assertThat(json).contains("userName");
    }
}
```

### 2.2 GCP Pub/Sub 整合

#### 依賴配置

```groovy
ext {
    set('springCloudGcpVersion', "7.4.2")
}

dependencies {
    // Spring Cloud Stream
    implementation 'org.springframework.cloud:spring-cloud-stream'
    
    // GCP Pub/Sub Binder
    implementation "com.google.cloud:spring-cloud-gcp-pubsub-stream-binder:${springCloudGcpVersion}"
    
    // Spring Cloud Function（內建 CloudEventMessageBuilder）
    implementation 'org.springframework.cloud:spring-cloud-function-context'
}
```

#### application.yaml 配置

```yaml
spring:
  cloud:
    gcp:
      project-id: ${GCP_PROJECT_ID}
      pubsub:
        publisher:
          enable-message-ordering: true
        subscriber:
          parallel-pull-count: 4
          flow-control:
            max-outstanding-element-count: 1000
    
    stream:
      default-binder: pubsub
      bindings:
        # 發送 SCIM 事件
        scimEventOut-out-0:
          destination: scim-user-events
          content-type: application/json
        
      gcp:
        pubsub:
          default:
            consumer:
              auto-create-resources: true
              ack-mode: MANUAL
```

---

## 3. UnboundID SCIM 2 SDK 使用指南

### 3.1 Maven/Gradle 依賴

**版本**: 5.0.0（發布日期：2025 年 12 月 15 日）

```groovy
dependencies {
    // SCIM Server 核心（自動傳遞 scim2-sdk-common 及 Jackson 2）
    implementation 'com.unboundid.product.scim2:scim2-sdk-server:5.0.0'
}
```

**自動傳遞依賴**（不需額外引入）：

| Artifact | 用途 |
|----------|------|
| `scim2-sdk-common` | SCIM 核心類別 |
| `jackson-databind` (2.18+) | JSON 序列化 |
| `jackson-jakarta-rs-json-provider` | JAX-RS JSON 支援 |
| `jakarta.ws.rs-api` | Jakarta EE API |

**參考來源**：
- GitHub: https://github.com/pingidentity/scim2
- Releases: https://github.com/pingidentity/scim2/releases
- Maven Central: https://central.sonatype.com/namespace/com.unboundid.product.scim2

### 3.2 核心類別

#### 資源類別（`com.unboundid.scim2.common.types`）

| 類別 | 說明 |
|------|------|
| `UserResource` | SCIM User POJO，對應 `urn:ietf:params:scim:schemas:core:2.0:User` |
| `GroupResource` | SCIM Group POJO |
| `GenericScimResource` | 彈性 JSON 資源，適合動態 schema |
| `EnterpriseUserExtension` | 企業用戶擴展屬性 |

#### 訊息類別（`com.unboundid.scim2.common.messages`）

| 類別 | 說明 |
|------|------|
| `PatchRequest` | SCIM PATCH 操作容器 |
| `PatchOperation` | 單一 patch 操作（add/remove/replace） |
| `ListResponse<T>` | 分頁列表回應包裝器 |
| `ErrorResponse` | SCIM 錯誤回應 |

### 3.3 SCIM Filter 解析

```java
import com.unboundid.scim2.common.filters.Filter;

// 從字串解析
Filter filter = Filter.fromString("userName eq \"john@example.com\"");

// 程式化建立
Filter eqFilter = Filter.eq("userName", "john");
Filter andFilter = Filter.and(
    Filter.eq("active", true),
    Filter.pr("emails")  // present
);

// 複合屬性 filter
Filter emailFilter = Filter.complex("emails", 
    Filter.eq("type", "work"));
```

### 3.4 Entra ID 相容性設定

UnboundID SDK 已內建處理多項 Entra ID 非標準行為：

| 問題 | SDK 處理 | 額外設定 |
|------|---------|---------|
| 大寫 PATCH op（`"Add"` vs `"add"`） | ✅ 自動處理 (v2.3.8+) | 無需 |
| 未知 JSON 欄位 | ⚠️ 需設定 | `IGNORE_UNKNOWN_FIELDS = true` |
| Remove 操作帶 value | ✅ 自動處理 (v4.1.0+) | 無需 |

**必要設定**：

```java
// 在應用程式啟動時設定
@PostConstruct
public void configureScimSdk() {
    // 忽略 Entra ID 發送的未知欄位
    BaseScimResource.IGNORE_UNKNOWN_FIELDS = true;
}
```

---

## 4. Microsoft Entra ID SCIM 配置

### 4.1 建立 Enterprise Application

1. 登入 **Microsoft Entra admin center** (https://entra.microsoft.com)
2. 導航至：**Identity → Applications → Enterprise applications**
3. 點擊 **+ New application → Create your own application**
4. 選擇 **"Integrate any other application you don't find in the gallery (Non-gallery)"**
5. 輸入名稱（例如：`spring-scim-server`），建立應用程式

### 4.2 配置 Provisioning

1. 進入應用程式管理頁面，選擇 **Provisioning**
2. 點擊 **Get started**
3. **Provisioning Mode**: 選擇 **Automatic**
4. 填入 **Admin Credentials**：

| 欄位 | 值 | 說明 |
|------|-----|------|
| **Tenant URL** | `https://your-domain.com/scim/v2?aadOptscim062020` | 加上 feature flag |
| **Secret Token** | `<your-bearer-token>` | 長效 Bearer Token |

5. 點擊 **Test Connection** 驗證
6. **Save** 儲存設定

**`?aadOptscim062020` Feature Flag 效果**：
- `active` 使用 boolean `false` 而非 string `"False"`
- 移除群組成員使用標準 SCIM path filter

### 4.3 Attribute Mapping

#### Core User 屬性

| Microsoft Entra Attribute | SCIM Attribute | 類型 | 說明 |
|---------------------------|----------------|------|------|
| `objectId` | `externalId` | String | **關鍵**：跨系統身份連結 |
| `userPrincipalName` | `userName` | String | 唯一識別符 |
| `givenName` | `name.givenName` | String | 名 |
| `surname` | `name.familyName` | String | 姓 |
| `displayName` | `displayName` | String | 顯示名稱 |
| `mail` | `emails[type eq "work"].value` | Complex | 工作郵件 |
| `otherMails` | `emails[type eq "other"].value` | Complex | 其他郵件 |
| `telephoneNumber` | `phoneNumbers[type eq "work"].value` | Complex | 工作電話 |
| `mobile` | `phoneNumbers[type eq "mobile"].value` | Complex | 手機 |
| `streetAddress` | `addresses[type eq "work"].streetAddress` | Complex | 街道地址 |
| `city` | `addresses[type eq "work"].locality` | Complex | 城市 |
| `postalCode` | `addresses[type eq "work"].postalCode` | Complex | 郵遞區號 |
| `country` | `addresses[type eq "work"].country` | Complex | 國家 |
| `preferredLanguage` | `preferredLanguage` | String | 偏好語言 |
| `Switch([IsSoftDeleted]...)` | `active` | Boolean | 啟用狀態 |

#### Enterprise User Extension 屬性

| Microsoft Entra Attribute | SCIM Attribute | 類型 | 說明 |
|---------------------------|----------------|------|------|
| `employeeId` | `employeeNumber` | String | 員工編號 |
| `costCenter` | `costCenter` | String | 成本中心 |
| `companyName` | `organization` | String | 組織/公司名稱 |
| `division` | `division` | String | 事業群 |
| `department` | `department` | String | 部門 |
| `jobTitle` | `title` | String | 職稱（Core User） |
| `manager` | `manager` | Complex | 主管（含 value, $ref, displayName） |

> **Enterprise User Extension Schema URI**: `urn:ietf:params:scim:schemas:extension:enterprise:2.0:User`

#### Roles 屬性（應用程式角色）

| Microsoft Entra Attribute | SCIM Attribute | 說明 |
|---------------------------|----------------|------|
| `appRoleAssignments` | `roles` | 應用程式角色指派 |

**Roles JSON 格式**：
```json
{
  "roles": [{
    "primary": true,
    "type": "WindowsAzureActiveDirectoryRole",
    "value": "Admin"
  }]
}
```

### 4.4 Provisioning 行為

| 行為 | 說明 |
|------|------|
| **Initial Cycle** | 首次同步，掃描所有使用者，可能耗時數小時 |
| **Incremental Cycle** | 約 **40 分鐘**一次（2025-2026 仍維持） |
| **Soft Delete** | 發送 `PATCH {"active": false}` |
| **Hard Delete** | Soft delete 後 **30 天**發送 DELETE |

### 4.5 已知限制

- **僅支援 PATCH**：更新操作不使用 PUT
- **Filter 運算子限制**：僅支援 `eq` 和 `and`
- **不支援巢狀群組**：僅同步直接成員
- **NULL 值無法佈建**

**參考來源**：
- Tutorial: https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups
- How Provisioning Works: https://learn.microsoft.com/en-us/entra/identity/app-provisioning/how-provisioning-works
- Compatibility Issues: https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-config-problem-scim-compatibility

---

## 5. CloudEvents 事件設計

### 5.1 CloudEvents 規範（1.0.2）

**必要屬性**：

| 屬性 | 類型 | 說明 |
|------|------|------|
| `specversion` | String | 固定為 `"1.0"` |
| `id` | String | 事件唯一識別碼（UUID） |
| `source` | URI-reference | 事件來源（例如 `/scim/v2/Users`） |
| `type` | String | 事件類型（reverse-DNS 格式） |

**選用屬性**：

| 屬性 | 用途 |
|------|------|
| `time` | 事件發生時間（ISO 8601） |
| `subject` | 事件主體（Resource ID） |
| `datacontenttype` | 資料格式（`application/json`） |

### 5.2 Event Type 定義

```
io.github.samzhu.scim.user.created.v1
io.github.samzhu.scim.user.updated.v1
io.github.samzhu.scim.user.patched.v1
io.github.samzhu.scim.user.deactivated.v1
io.github.samzhu.scim.user.deleted.v1
```

### 5.3 Event Data Schema

```java
/**
 * SCIM User Provisioning Event Data
 */
public record ScimUserEventData(
    String operation,           // CREATE, UPDATE, PATCH, DELETE
    String resourceId,          // SCIM Resource ID (UUID)
    String externalId,          // Entra Object ID
    String userName,
    String displayName,
    String email,
    Boolean active,
    Instant timestamp,
    
    // Enterprise User Extension
    EnterpriseUserData enterpriseUser,
    
    // For PATCH events - changed attributes
    List<PatchOperationData> patchOperations,
    
    // Resource metadata
    ScimMetaData meta
) {}

public record EnterpriseUserData(
    String employeeNumber,
    String costCenter,
    String organization,        // 組織/公司
    String division,            // 事業群
    String department,          // 部門
    String title,               // 職稱
    String managerId,           // Manager's externalId
    String managerDisplayName
) {}

public record PatchOperationData(
    String op,      // add, remove, replace
    String path,    // e.g., "active", "name.givenName"
    Object value
) {}

public record ScimMetaData(
    String resourceType,        // "User"
    Instant created,
    Instant lastModified,
    String version              // ETag, e.g., "W/\"1\""
) {}
```

### 5.4 CloudEvent 完整範例

```json
{
  "specversion": "1.0",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "source": "/scim/v2/Users",
  "type": "io.github.samzhu.scim.user.created.v1",
  "subject": "user-12345",
  "time": "2026-01-08T10:30:00.000Z",
  "datacontenttype": "application/json",
  "data": {
    "operation": "CREATE",
    "resourceId": "550e8400-e29b-41d4-a716-446655440000",
    "externalId": "00000000-0000-0000-0000-000000000001",
    "userName": "john.doe@example.com",
    "displayName": "John Doe",
    "email": "john.doe@example.com",
    "active": true,
    "timestamp": "2026-01-08T10:30:00.000Z",
    "enterpriseUser": {
      "employeeNumber": "EMP001",
      "costCenter": "CC-4130",
      "organization": "Cathay Financial Holdings",
      "division": "Technology",
      "department": "Engineering",
      "title": "Senior Developer",
      "managerId": "00000000-0000-0000-0000-000000000002",
      "managerDisplayName": "Jane Smith"
    },
    "meta": {
      "resourceType": "User",
      "created": "2026-01-08T10:30:00.000Z",
      "lastModified": "2026-01-08T10:30:00.000Z",
      "version": "W/\"1\""
    }
  }
}
```

**PATCH Event 範例**（使用者停用）：

```json
{
  "specversion": "1.0",
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "source": "/scim/v2/Users",
  "type": "io.github.samzhu.scim.user.deactivated.v1",
  "subject": "user-12345",
  "time": "2026-01-08T11:00:00.000Z",
  "datacontenttype": "application/json",
  "data": {
    "operation": "PATCH",
    "resourceId": "550e8400-e29b-41d4-a716-446655440000",
    "externalId": "00000000-0000-0000-0000-000000000001",
    "userName": "john.doe@example.com",
    "active": false,
    "timestamp": "2026-01-08T11:00:00.000Z",
    "patchOperations": [
      {
        "op": "replace",
        "path": "active",
        "value": false
      }
    ],
    "meta": {
      "resourceType": "User",
      "lastModified": "2026-01-08T11:00:00.000Z",
      "version": "W/\"2\""
    }
  }
}
```

### 5.5 Event Publisher 實作

```java
import org.springframework.cloud.function.cloudevent.CloudEventMessageBuilder;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScimEventPublisher {

    private final StreamBridge streamBridge;

    private static final String BINDING_NAME = "scimEventOut-out-0";
    private static final URI EVENT_SOURCE = URI.create("/scim/v2/Users");

    /**
     * 發布 SCIM User Created 事件
     */
    public void publishUserCreated(ScimUserEventData eventData) {
        publishEvent("io.github.samzhu.scim.user.created.v1", eventData);
    }

    /**
     * 發布 SCIM User Updated 事件
     */
    public void publishUserUpdated(ScimUserEventData eventData) {
        publishEvent("io.github.samzhu.scim.user.updated.v1", eventData);
    }

    /**
     * 發布 SCIM User Patched 事件
     */
    public void publishUserPatched(ScimUserEventData eventData) {
        publishEvent("io.github.samzhu.scim.user.patched.v1", eventData);
    }

    /**
     * 發布 SCIM User Deactivated 事件
     */
    public void publishUserDeactivated(ScimUserEventData eventData) {
        publishEvent("io.github.samzhu.scim.user.deactivated.v1", eventData);
    }

    /**
     * 發布 SCIM User Deleted 事件
     */
    public void publishUserDeleted(ScimUserEventData eventData) {
        publishEvent("io.github.samzhu.scim.user.deleted.v1", eventData);
    }

    /**
     * 使用 CloudEventMessageBuilder 建立並發送 CloudEvent
     * Spring Cloud Stream 會自動序列化 POJO 為 JSON
     */
    private void publishEvent(String eventType, ScimUserEventData eventData) {
        String eventId = UUID.randomUUID().toString();
        OffsetDateTime eventTime = OffsetDateTime.now(ZoneOffset.UTC);
        String subject = eventData.resourceId();

        // 使用 CloudEventMessageBuilder 建立訊息
        // Spring Cloud Stream 會自動將 POJO 序列化為 JSON
        Message<ScimUserEventData> message = CloudEventMessageBuilder
            .withData(eventData)
            .setId(eventId)
            .setType(eventType)
            .setSource(EVENT_SOURCE)
            .setTime(eventTime)
            .setSubject(subject)
            .setDataContentType("application/json")
            .build();

        boolean sent = streamBridge.send(BINDING_NAME, message);
        
        if (sent) {
            log.info("Published CloudEvent: type={}, id={}, subject={}", 
                eventType, eventId, subject);
        } else {
            log.error("Failed to publish CloudEvent: type={}, id={}", 
                eventType, eventId);
        }
    }
}
```

**參考來源**：
- Spring Cloud Function CloudEvents: https://docs.spring.io/spring-cloud-function/reference/spring-cloud-function/cloud-events.html
- CloudEvents Specification: https://github.com/cloudevents/spec

---

## 6. 安全性設計

### 6.1 認證架構

SCIM API 採用 **Bearer Token** 認證，由 Microsoft Entra ID 在每個請求附帶：

```
Authorization: Bearer <token>
```

#### 認證方案比較

| 方案 | Token 管理 | 安全性 | 複雜度 | 推薦 |
|------|-----------|--------|--------|------|
| **OAuth2 Resource Server (JWT)** | Entra ID 自動管理 | 高（有過期時間） | 簡單配置 | ✅ 推薦 |
| Long-lived Token | 需自行管理 | 中（無過期） | 自訂 Filter | 備選 |

---

### 6.2 方案一：OAuth2 Resource Server（推薦）

Entra ID 自動簽發 JWT，Spring Security 自動驗證簽章，**不需要管理 token**。

#### Gradle 依賴

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
}
```

#### application.yaml

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://login.microsoftonline.com/${ENTRA_TENANT_ID}/v2.0
          audiences: ${SCIM_APP_CLIENT_ID}
```

#### Spring Security 配置

```java
@Configuration
@EnableWebSecurity
public class ScimSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain scimSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/scim/**")
            .authorizeHttpRequests(authorize -> authorize
                // Discovery 端點允許匿名存取（用於 Test Connection）
                .requestMatchers("/scim/v2/ServiceProviderConfig").permitAll()
                .requestMatchers("/scim/v2/Schemas").permitAll()
                .requestMatchers("/scim/v2/ResourceTypes").permitAll()
                // 其他 SCIM 端點需認證
                .requestMatchers("/scim/**").authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        
        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("roles");
        converter.setAuthorityPrefix("ROLE_");
        
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(converter);
        return jwtConverter;
    }
}
```

#### Entra ID 配置步驟

1. **App Registration**：在 Entra ID 建立 App Registration
2. **Expose an API**：設定 Application ID URI（例如 `api://scim-server`）
3. **Add a scope**：新增 scope（例如 `Provisioning.ReadWrite`）
4. **Enterprise App Provisioning**：
   - Tenant URL: `https://your-domain.com/scim/v2`
   - Authentication: 選擇 **OAuth 2.0 Client Credentials**
   - Token Endpoint: `https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token`
   - Client ID / Client Secret: Enterprise App 的憑證

#### 運作流程

```
1. Entra ID Provisioning Service 向 Entra ID 請求 JWT
2. Entra ID 簽發 JWT（包含 aud, iss, exp 等 claims）
3. Provisioning Service 在 SCIM 請求附帶 JWT
4. Spring Security 自動驗證：
   - 簽章有效性（用 Entra ID 公鑰）
   - Token 是否過期
   - Audience 是否正確
5. 驗證通過 → 處理 SCIM 請求
```

---

### 6.3 方案二：Long-lived Token（備選）

自行生成固定 token，手動配置到 Entra ID，適合快速驗證或簡單部署。

#### Gradle 依賴

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-security'
}
```

#### application.yaml

```yaml
scim:
  security:
    bearer-token: ${SCIM_BEARER_TOKEN}  # 需自行生成並管理
```

#### 自訂 Bearer Token Filter

```java
@Component
public class ScimBearerTokenFilter extends OncePerRequestFilter {

    @Value("${scim.security.bearer-token}")
    private String expectedToken;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        
        // 跳過 Discovery 端點
        String path = request.getRequestURI();
        if (path.endsWith("/ServiceProviderConfig") || 
            path.endsWith("/Schemas") || 
            path.endsWith("/ResourceTypes")) {
            chain.doFilter(request, response);
            return;
        }
        
        String authHeader = request.getHeader("Authorization");
        
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }
        
        String token = authHeader.substring(7);
        
        // 使用時間恆定比較防止 timing attack
        if (!MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                expectedToken.getBytes(StandardCharsets.UTF_8))) {
            
            log.warn("SCIM authentication failed from IP: {}", 
                request.getRemoteAddr());
            sendUnauthorized(response, "Invalid bearer token");
            return;
        }
        
        // 設定認證資訊
        SecurityContextHolder.getContext().setAuthentication(
            new PreAuthenticatedAuthenticationToken("scim-client", null, 
                List.of(new SimpleGrantedAuthority("ROLE_SCIM_CLIENT"))));
        
        chain.doFilter(request, response);
    }
    
    private void sendUnauthorized(HttpServletResponse response, String message) 
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/scim+json");
        response.getWriter().write("""
            {
              "schemas": ["urn:ietf:params:scim:api:messages:2.0:Error"],
              "status": "401",
              "detail": "%s"
            }
            """.formatted(message));
    }
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/scim/");
    }
}
```

#### Spring Security 配置

```java
@Configuration
@EnableWebSecurity
public class ScimSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain scimSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/scim/**")
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/scim/v2/ServiceProviderConfig").permitAll()
                .requestMatchers("/scim/v2/Schemas").permitAll()
                .requestMatchers("/scim/v2/ResourceTypes").permitAll()
                .requestMatchers("/scim/**").authenticated()
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        
        return http.build();
    }
}
```

#### Entra ID 配置

1. **Enterprise App Provisioning**：
   - Tenant URL: `https://your-domain.com/scim/v2?aadOptscim062020`
   - Secret Token: 貼上你生成的 token

#### Token 生成建議

```bash
# 使用 openssl 生成安全的隨機 token
openssl rand -base64 48
```

---

### 6.4 方案選擇建議

| 場景 | 推薦方案 |
|------|---------|
| **生產環境** | OAuth2 Resource Server（自動管理、有過期機制） |
| **快速 POC** | Long-lived Token（設定簡單） |
| **合規要求高** | OAuth2 Resource Server（審計更完整） |
| **內網部署** | 皆可 |
```

### 6.5 TLS 配置

> **注意**：本專案部署於 GCP，TLS termination 由 GCP Load Balancer 或 Cloud Run 處理，應用程式本身不需處理 TLS 憑證。

**GCP 提供的 TLS 保障**：
- Cloud Run：自動提供 managed TLS 憑證
- GKE + Ingress：透過 Google-managed certificates
- 符合 Entra ID 要求的 TLS 1.2+

**驗證方式**：確認 Entra ID Provisioning 的 Tenant URL 使用 `https://` scheme。

### 6.6 Rate Limiting

> **v1.0 不實作**：Entra ID 是唯一可信的 Client，且有固定同步週期（增量約 40 分鐘），無需限流。GCP 基礎設施（Cloud Armor、Load Balancer）已提供 DDoS 防護。

**未來考量**（若開放多租戶或其他 IdP）：

| 層級 | 建議限制 |
|------|---------|
| Per-Tenant | 1000 req/min |
| Per-IP | 100 req/min |

---

---

## 7. Audit Logging

### 7.1 必要記錄欄位

| 欄位 | 說明 | 合規框架 |
|------|------|---------|
| `timestamp` | ISO 8601 格式時間戳 | PCI-DSS 10.4 |
| `traceId` | 分散式追蹤 ID | - |
| `sourceIp` | 請求來源 IP | SOC2, PCI-DSS |
| `method` | HTTP Method | All |
| `path` | 請求路徑 | All |
| `operation` | SCIM 操作類型 | All |
| `resourceId` | 被操作資源 ID | GDPR |
| `outcome` | 成功/失敗 | All |
| `statusCode` | HTTP 狀態碼 | All |
| `duration` | 處理時間 (ms) | - |

### 7.2 Log Format

```json
{
  "timestamp": "2026-01-08T10:30:00.000Z",
  "level": "INFO",
  "logger": "scim.audit",
  "traceId": "0af7651916cd43dd8448eb211c80319c",
  "spanId": "b7ad6b7169203331",
  "message": "SCIM operation completed",
  "context": {
    "sourceIp": "40.126.x.x",
    "method": "POST",
    "path": "/scim/v2/Users",
    "operation": "CREATE",
    "resourceId": "user-12345",
    "externalId": "00000000-0000-0000-0000-000000000001",
    "outcome": "SUCCESS",
    "statusCode": 201,
    "duration": 45
  }
}
```

### 7.3 合規要求

| 框架 | 要求 |
|------|------|
| **PCI-DSS 4.0** | 保留 12 個月，最近 3 個月可立即存取 |
| **GDPR** | PII 使用 pseudonymization，定義明確保留期限 |
| **SOC2** | 記錄所有存取，日誌防竄改 |

---

## 8. API 規格

### 8.1 必須實作的端點

#### User 資源端點

| 端點 | Method | 說明 | Entra ID 使用 |
|------|--------|------|--------------|
| `/scim/v2/Users` | POST | 建立使用者 | ✅ |
| `/scim/v2/Users/{id}` | GET | 取得使用者 | ✅ |
| `/scim/v2/Users/{id}` | PATCH | 更新使用者 | ✅ |
| `/scim/v2/Users/{id}` | DELETE | 刪除使用者 | ✅ |
| `/scim/v2/Users` | GET | 搜尋使用者（支援 filter） | ✅ |

#### Discovery 端點（必須實作）

| 端點 | Method | 說明 | Entra ID 使用 |
|------|--------|------|--------------|
| `/scim/v2/ServiceProviderConfig` | GET | 服務配置 | ✅ Test Connection |
| `/scim/v2/Schemas` | GET | Schema 定義 | ✅ 屬性發現 |
| `/scim/v2/ResourceTypes` | GET | 資源類型 | ✅ |

### 8.2 Discovery 端點回應範例

#### ServiceProviderConfig

```json
{
  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"],
  "documentationUri": "https://github.com/samzhu/spring-scim-server",
  "patch": {
    "supported": true
  },
  "bulk": {
    "supported": false,
    "maxOperations": 0,
    "maxPayloadSize": 0
  },
  "filter": {
    "supported": true,
    "maxResults": 200
  },
  "changePassword": {
    "supported": false
  },
  "sort": {
    "supported": false
  },
  "etag": {
    "supported": true
  },
  "authenticationSchemes": [{
    "type": "oauthbearertoken",
    "name": "OAuth Bearer Token",
    "description": "Authentication scheme using OAuth 2.0 Bearer Token"
  }]
}
```

#### Schemas

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
  "totalResults": 2,
  "Resources": [
    {
      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Schema"],
      "id": "urn:ietf:params:scim:schemas:core:2.0:User",
      "name": "User",
      "description": "User Account",
      "attributes": [...]
    },
    {
      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Schema"],
      "id": "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User",
      "name": "EnterpriseUser",
      "description": "Enterprise User Extension",
      "attributes": [...]
    }
  ]
}
```

#### ResourceTypes

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
  "totalResults": 1,
  "Resources": [{
    "schemas": ["urn:ietf:params:scim:schemas:core:2.0:ResourceType"],
    "id": "User",
    "name": "User",
    "endpoint": "/Users",
    "description": "User Account",
    "schema": "urn:ietf:params:scim:schemas:core:2.0:User",
    "schemaExtensions": [{
      "schema": "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User",
      "required": false
    }]
  }]
}
```

### 8.3 不需實作的端點

| 端點 | 原因 |
|------|------|
| `PUT /scim/v2/Users/{id}` | Entra ID 僅使用 PATCH |
| `/scim/v2/Bulk` | Entra ID 不使用（未來可能支援） |
| `/scim/v2/Groups` | v1.0 不支援 Group 同步 |

### 8.4 錯誤回應格式

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:Error"],
  "scimType": "uniqueness",
  "detail": "User with userName 'john@example.com' already exists",
  "status": "409"
}
```

**scimType 值**：

| scimType | HTTP Status | 說明 |
|----------|-------------|------|
| `uniqueness` | 409 | 唯一性衝突 |
| `mutability` | 400 | 嘗試修改不可變屬性 |
| `invalidSyntax` | 400 | 請求格式錯誤 |
| `invalidFilter` | 400 | Filter 語法錯誤 |
| `invalidValue` | 400 | 屬性值無效 |
| `noTarget` | 404 | 資源不存在 |

---

## 9. 資料模型

### 9.1 資料庫 Schema（PostgreSQL）

```sql
-- ======================
-- SCIM Users Table
-- ======================
CREATE TABLE scim_users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id     VARCHAR(255) UNIQUE,  -- Entra Object ID
    user_name       VARCHAR(255) NOT NULL UNIQUE,
    
    -- Core User: Name
    given_name      VARCHAR(255),
    family_name     VARCHAR(255),
    display_name    VARCHAR(255),
    
    -- Core User: Contact
    primary_email   VARCHAR(255),
    phone_work      VARCHAR(50),
    phone_mobile    VARCHAR(50),
    
    -- Core User: Address
    street_address  VARCHAR(500),
    city            VARCHAR(255),
    postal_code     VARCHAR(50),
    country         VARCHAR(100),
    
    -- Core User: Preferences
    preferred_language VARCHAR(50),
    locale          VARCHAR(50),
    timezone        VARCHAR(100),
    
    -- Core User: Status
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    
    -- Enterprise User Extension
    employee_number VARCHAR(255),
    cost_center     VARCHAR(255),
    organization    VARCHAR(255),         -- 組織/公司
    division        VARCHAR(255),         -- 事業群
    department      VARCHAR(255),         -- 部門
    title           VARCHAR(255),         -- 職稱
    manager_id      UUID REFERENCES scim_users(id),
    
    -- Metadata
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version         BIGINT NOT NULL DEFAULT 0
);

-- Indexes
CREATE INDEX idx_scim_users_user_name ON scim_users(user_name);
CREATE INDEX idx_scim_users_external_id ON scim_users(external_id);
CREATE INDEX idx_scim_users_active ON scim_users(active);
CREATE INDEX idx_scim_users_department ON scim_users(department);
CREATE INDEX idx_scim_users_organization ON scim_users(organization);
CREATE INDEX idx_scim_users_manager_id ON scim_users(manager_id);

-- ======================
-- SCIM User Emails Table (Multi-valued)
-- ======================
CREATE TABLE scim_user_emails (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES scim_users(id) ON DELETE CASCADE,
    value           VARCHAR(255) NOT NULL,
    type            VARCHAR(50),          -- work, home, other
    is_primary      BOOLEAN DEFAULT FALSE,
    
    UNIQUE(user_id, value)
);

CREATE INDEX idx_scim_user_emails_user_id ON scim_user_emails(user_id);

-- ======================
-- SCIM User Phone Numbers Table (Multi-valued)
-- ======================
CREATE TABLE scim_user_phones (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES scim_users(id) ON DELETE CASCADE,
    value           VARCHAR(50) NOT NULL,
    type            VARCHAR(50),          -- work, mobile, fax, other
    is_primary      BOOLEAN DEFAULT FALSE,
    
    UNIQUE(user_id, value, type)
);

CREATE INDEX idx_scim_user_phones_user_id ON scim_user_phones(user_id);
```

### 9.2 JPA Entity

```java
@Entity
@Table(name = "scim_users")
public class ScimUserEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "external_id", unique = true)
    private String externalId;
    
    @Column(name = "user_name", nullable = false, unique = true)
    private String userName;
    
    // Core User: Name
    @Column(name = "given_name")
    private String givenName;
    
    @Column(name = "family_name")
    private String familyName;
    
    @Column(name = "display_name")
    private String displayName;
    
    // Core User: Contact
    @Column(name = "primary_email")
    private String primaryEmail;
    
    @Column(name = "phone_work")
    private String phoneWork;
    
    @Column(name = "phone_mobile")
    private String phoneMobile;
    
    // Core User: Address
    @Column(name = "street_address")
    private String streetAddress;
    
    @Column(name = "city")
    private String city;
    
    @Column(name = "postal_code")
    private String postalCode;
    
    @Column(name = "country")
    private String country;
    
    // Core User: Preferences
    @Column(name = "preferred_language")
    private String preferredLanguage;
    
    @Column(name = "locale")
    private String locale;
    
    @Column(name = "timezone")
    private String timezone;
    
    // Core User: Status
    @Column(nullable = false)
    private boolean active = true;
    
    // Enterprise User Extension
    @Column(name = "employee_number")
    private String employeeNumber;
    
    @Column(name = "cost_center")
    private String costCenter;
    
    @Column(name = "organization")
    private String organization;
    
    @Column(name = "division")
    private String division;
    
    @Column(name = "department")
    private String department;
    
    @Column(name = "title")
    private String title;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private ScimUserEntity manager;
    
    // Multi-valued: Emails
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ScimUserEmailEntity> emails = new HashSet<>();
    
    // Multi-valued: Phone Numbers
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<ScimUserPhoneEntity> phones = new HashSet<>();
    
    // Metadata
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    @Version
    private Long version;
    
    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = Instant.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
```

```java
@Entity
@Table(name = "scim_user_emails")
public class ScimUserEmailEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private ScimUserEntity user;
    
    @Column(nullable = false)
    private String value;
    
    private String type;  // work, home, other
    
    @Column(name = "is_primary")
    private boolean primary;
}
```

---

## 10. 版本矩陣與參考來源

### 10.1 版本矩陣

| 元件 | 版本 | 發布日期 | 狀態 |
|------|------|---------|------|
| Java | 25 | 2025 年 | GA |
| Spring Boot | 4.0.1 | 2025 年 11 月 | GA |
| Spring Framework | 7.0.x | 2025 年 11 月 | GA |
| Spring Cloud | 2025.1.0 | 2025 年 11 月 | GA |
| Spring Cloud GCP | 7.4.2 | 2025 年 12 月 | GA |
| UnboundID SCIM 2 SDK | 5.0.0 | 2025 年 12 月 15 日 | GA |
| Jackson 2 (for SCIM) | 2.18.2 | 2024 年 | GA |
| CloudEvents Spec | 1.0.2 | 2022 年 2 月 | GA |

### 10.2 官方文件連結

**SCIM 標準**：
- RFC 7643 (Core Schema): https://www.rfc-editor.org/rfc/rfc7643
- RFC 7644 (Protocol): https://datatracker.ietf.org/doc/html/rfc7644

**UnboundID SCIM 2 SDK**：
- GitHub: https://github.com/pingidentity/scim2
- Releases: https://github.com/pingidentity/scim2/releases

**Microsoft Entra ID**：
- SCIM Tutorial: https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups
- How Provisioning Works: https://learn.microsoft.com/en-us/entra/identity/app-provisioning/how-provisioning-works
- Compatibility: https://learn.microsoft.com/en-us/entra/identity/app-provisioning/application-provisioning-config-problem-scim-compatibility

**CloudEvents**：
- Specification: https://github.com/cloudevents/spec

**Spring**：
- Spring Boot 4: https://spring.io/projects/spring-boot
- Spring Cloud Function (CloudEvents): https://docs.spring.io/spring-cloud-function/reference/spring-cloud-function/cloud-events.html
- Spring Cloud Stream: https://spring.io/projects/spring-cloud-stream
- Spring Cloud GCP: https://spring.io/projects/spring-cloud-gcp

**GCP**：
- Pub/Sub: https://cloud.google.com/pubsub/docs
- Cloud Run: https://cloud.google.com/run/docs

---

## 11. 開發里程碑

| 版本 | 內容 | 預估時間 |
|------|------|---------|
| **0.1.0** | 核心 SCIM 端點 (Users CRUD + Filter)、Jackson 2 整合 | 2 週 |
| **0.2.0** | Entra ID 相容性、Bearer Token 認證、錯誤處理 | 1 週 |
| **0.3.0** | CloudEvents + Pub/Sub 事件發布 | 1 週 |
| **0.4.0** | Audit Logging、Discovery 端點 | 1 週 |
| **0.5.0** | Docker Compose、GCP 部署配置 | 1 週 |
| **1.0.0** | 文件完善、GitHub Actions CI/CD、生產就緒 | 1 週 |

---

## 12. 未來擴展規劃

### 12.1 Group 資源支援（v2.0）

| 功能 | 說明 |
|------|------|
| **Group CRUD** | `/scim/v2/Groups` 端點完整支援 |
| **Members 管理** | 群組成員新增/移除 |
| **Event Types** | `io.github.samzhu.scim.group.created.v1` 等 |

**注意**：Entra ID 不支援巢狀群組，僅同步直接成員。

### 12.2 Custom Extension 支援（v2.x）

支援自訂 Schema Extension：

```
urn:ietf:params:scim:schemas:extension:{CompanyName}:2.0:User:{AttributeName}
```

**限制**：
- 複雜/多值自訂屬性僅 Gallery Apps 支援
- Non-gallery apps 僅支援簡單類型（String, Boolean, Integer）

### 12.3 進階功能（v3.0）

| 功能 | 說明 |
|------|------|
| **Bulk Operations** | `/scim/v2/Bulk` 端點（Entra ID 計劃未來支援） |
| **Password Sync** | 密碼同步（需評估安全性） |
| **Roles Provisioning** | 應用程式角色佈建 |

---

## 13. 結論

本 PRD 定義了 spring-scim-server 的完整技術規格。三個關鍵設計決策將確保專案成功：

**第一，Jackson 雙版本共存**。透過自訂 MessageConverter 為 SCIM 端點配置獨立的 Jackson 2 ObjectMapper，與 Spring Boot 4 預設的 Jackson 3 隔離，確保 UnboundID SDK 正常運作。

**第二，CloudEvents + Spring Cloud Stream 實現 MQ 無關性**。使用 Spring Cloud Function 內建的 `CloudEventMessageBuilder` 建立符合 CloudEvents 1.0.2 規範的事件，透過 Spring Cloud Stream 抽象底層 Message Broker。目前配置使用 GCP Pub/Sub，未來可輕鬆切換至 Kafka、RabbitMQ 等其他 MQ。

**第三，專注 User 資源，未來擴展 Group**。v1.0 專注實作 User 和 Enterprise User Extension 資源，Group 資源列入未來版本規劃。需注意 Entra ID 不支援巢狀群組，僅能同步直接成員。

本專案將填補 Spring 生態系中缺乏生產級 SCIM Server 的空白，為企業提供開箱即用的身份同步解決方案。